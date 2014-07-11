package com.coinffeine.client.peer.orders

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout

import com.coinffeine.client.peer.CoinffeinePeerActor.{RetrievedOpenOrders, RetrieveOpenOrders}
import com.coinffeine.client.peer.orders.SubmissionSupervisor.{KeepSubmitting, StopSubmitting}
import com.coinffeine.common.{FiatAmount, FiatCurrency, Order, OrderId}
import com.coinffeine.common.exchange.PeerId
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.messages.brokerage.Market

class SubmissionSupervisor(protocolConstants: ProtocolConstants) extends Actor with ActorLogging{

  import context.dispatcher
  private implicit val timeout = Timeout(1.second)

  override def receive: Receive = {
    case init: SubmissionSupervisor.Initialize =>
      new InitializedSubmissionSupervisor(init).start()
  }

  private class InitializedSubmissionSupervisor(init: SubmissionSupervisor.Initialize) {
    import init._

    private var delegatesByMarket = Map.empty[Market[FiatCurrency], ActorRef]

    def start(): Unit = {
      context.become(waitingForOrders)
    }

    private val waitingForOrders: Receive = {

      case message @ KeepSubmitting(order) =>
        getOrCreateDelegate(marketOf(order)) forward message

      case message @ StopSubmitting(order) =>
      delegatesByMarket.values.foreach(_ forward message)
    }

    private def marketOf(order: Order[FiatAmount]) = Market(currency = order.price.currency)

    private def getOrCreateDelegate(market: Market[FiatCurrency]): ActorRef =
    delegatesByMarket.getOrElse(market, createDelegate(market))

    private def createDelegate(market: Market[FiatCurrency]): ActorRef = {
      log.info(s"Start submitting to $market")
      val newDelegate = context.actorOf(MarketSubmissionActor.props(protocolConstants))
      newDelegate ! MarketSubmissionActor.Initialize(market, eventChannel, gateway, brokerId)
      delegatesByMarket += market -> newDelegate
      newDelegate
    }
  }
}

object SubmissionSupervisor {

  case class Initialize(ownId: PeerId,
                        brokerId: PeerId,
                        eventChannel: ActorRef,
                        gateway: ActorRef)

  case class KeepSubmitting(order: Order[FiatAmount])

  case class StopSubmitting(orderId: OrderId)

  trait Component { this: ProtocolConstants.Component =>
    lazy val ordersActorProps = Props(new SubmissionSupervisor(protocolConstants))
  }
}
