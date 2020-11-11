package fr.acinq.chainswap.app

import fr.acinq.eclair._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props}
import fr.acinq.eclair.io.{PeerConnected, PeerDisconnected, UnknownMessageReceived}
import fr.acinq.chainswap.app.processor.{IncomingChainTxProcessor, SwapInProcessor, SwapOutProcessor, ZMQActor}
import fr.acinq.eclair.channel.Channel.OutgoingMessage
import fr.acinq.chainswap.app.ChainSwap.messageTags
import akka.actor.SupervisorStrategy.Resume
import fr.acinq.chainswap.app.wire.Codecs
import slick.jdbc.PostgresProfile
import scala.collection.mutable
import grizzled.slf4j.Logging
import scodec.Attempt


class Worker(db: PostgresProfile.backend.Database, vals: Vals, kit: Kit) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)
  val account2Connection = mutable.Map.empty[String, PeerAndConnection]

  val swapInProcessor: ActorRef = context actorOf Props(classOf[SwapInProcessor], vals, kit, db)
  val zmqActor: ActorRef = context actorOf Props(classOf[ZMQActor], vals.bitcoinAPI, vals.btcZMQApi, vals.rewindBlocks)
  val swapOutProcessor: ActorRef = context actorOf Props(classOf[SwapOutProcessor], vals, kit, (accountId: String) => randomBytes32)
  val incomingChainTxProcessor: ActorRef = context actorOf Props(classOf[IncomingChainTxProcessor], vals, swapInProcessor, zmqActor, db)

  override def receive: Receive = {
    case peerMessage: PeerDisconnected =>
      account2Connection.remove(peerMessage.nodeId.toString)

    case PeerConnected(peer, remoteNodeId, info) if info.remoteInit.features.hasPluginFeature(ChainSwapFeature.plugin) =>
      account2Connection(remoteNodeId.toString) = PeerAndConnection(peer, info.peerConnection)
      swapOutProcessor ! SwapOutProcessor.ChainFeeratesFrom(remoteNodeId.toString)
      swapInProcessor ! SwapInProcessor.AccountStatusFrom(remoteNodeId.toString)

    case SwapOutProcessor.ChainFeeratesTo(swapOutFeerates, accountId) =>
      account2Connection.get(accountId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage swapOutFeerates, connection)
      }

    case SwapInProcessor.AccountStatusTo(swapInState, accountId) =>
      // May not be sent back by `swapInProcessor` if account is empty and nothing is happening
      account2Connection.get(accountId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage swapInState, connection)
      }

    case peerMessage: UnknownMessageReceived if messageTags.contains(peerMessage.message.tag) =>
      // Filter unknown messages related to ChainSwap since there may be other messaging-enabled plugins

      Codecs.decode(peerMessage.message) match {
        case Attempt.Successful(SwapInRequest) => swapInProcessor ! SwapInProcessor.SwapInRequestFrom(peerMessage.nodeId.toString)
        case Attempt.Successful(msg: SwapInWithdrawRequest) => swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(msg, peerMessage.nodeId.toString)
        case Attempt.Successful(msg: SwapOutRequest) => swapOutProcessor ! SwapOutProcessor.SwapOutRequestFrom(msg, peerMessage.nodeId.toString)
        case _: Attempt.Failure => logger.info(s"PLGN ChainSwap, parsing fail, tag=${peerMessage.message.tag}")
        case _ => // Do nothing
      }

    case SwapInProcessor.SwapInResponseTo(swapInResponse, accountId) =>
      // Always works, each account is guaranteed to have at least one chain address
      account2Connection.get(accountId) foreach { case PeerAndConnection(peer, connection) =>
        incomingChainTxProcessor ! AccountAndAddress(accountId, swapInResponse.btcAddress)
        peer ! OutgoingMessage(Codecs toUnknownMessage swapInResponse, connection)
      }

    // Either deny right away or silently attempt to fulfill a payment request
    case SwapInProcessor.SwapInWithdrawRequestDeniedTo(paymentRequest, reason, accountId) =>
      account2Connection.get(accountId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage SwapInWithdrawDenied(paymentRequest, reason), connection)
      }

    case SwapOutProcessor.SwapOutResponseTo(swapOutResponse, accountId) =>
      // May not be sent back if `paymentInitiator` fails somehow, client should timeout
      account2Connection.get(accountId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage swapOutResponse, connection)
      }

    case SwapOutProcessor.SwapOutDeniedTo(btcAddress, reason, accountId) =>
      // Either deny right away or silently send a chain transaction on LN payment
      account2Connection.get(accountId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage SwapOutDenied(btcAddress, reason), connection)
      }
  }

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy(-1, 5.seconds) {
    // ZMQ connection may be lost or an exception may be thrown while processing data
    case _: Throwable => Resume
  }
}
