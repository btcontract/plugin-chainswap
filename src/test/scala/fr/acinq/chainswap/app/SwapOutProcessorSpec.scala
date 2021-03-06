package fr.acinq.chainswap.app

import fr.acinq.eclair._
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.chainswap.app.processor.SwapOutProcessor
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRequest}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector


class SwapOutProcessorSpec extends AnyFunSuite {

  // This requires a running bitcoind testnet with balance of ~0.1 BTC

  test("Deposit off-chain, withdraw on-chain") {
    ChainSwapTestUtils.resetEntireDatabase()
    implicit val system: ActorSystem = ActorSystem("test-actor-system")
    val eventListener = TestProbe()(system)
    val oneMilSat = "lntb10m1p0czexapp5xzhg6h2wc4lm2xd7wn29gm8xla3x805x0k72t0dzvqspzgavcvesdqqxqrrsscqp79qy9qsqsp5tgftdg2r" +
      "8sgh26tt8l4e89auxddln6utfr7xl42sy4zep3cv52fqnj7l37zgckrtg7yvk7czmx8j8p4hkt5fs5r7na2vec5cap8urz4snw97q7huulp9mprcpzrx403hnxhldax2x86h3p8saccxyrual8spe8vyxv"
    val pr = PaymentRequest.read(oneMilSat)
    val kit = ChainSwapTestUtils.testKit(eventListener.ref, pr)(system)
    val preimage = ByteVector32(ByteVector.fromValidHex("7f4877db7c440358928719f703fd54b409e735624a532fd8af8501dca1f058c6"))
    val swapOutProcessor = eventListener childActorOf Props(classOf[SwapOutProcessor], Config.vals, kit, (userId: String) => preimage)
    val userId = "user-id-1"
    synchronized(wait(100))

    // Client sends SwapOutRequest
    swapOutProcessor ! SwapOutProcessor.ChainInfoRequestFrom(userId)
    val info = eventListener.expectMsgType[SwapOutProcessor.ChainInfoResponseTo].info
    val feerateKey = info.feerates.feerateKey
    println(info.providerCanHandle)

    swapOutProcessor ! SwapOutProcessor.SwapOutRequestFrom(SwapOutTransactionRequest(100000L.sat, btcAddress = "wrong-address", blockTarget = 36, feerateKey), userId)
    eventListener.expectMsgType[SwapOutProcessor.SwapOutDeniedTo] // Wrong address
    swapOutProcessor ! SwapOutProcessor.SwapOutRequestFrom(SwapOutTransactionRequest(10L.sat, btcAddress = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ", blockTarget = 36, feerateKey), userId)
    eventListener.expectMsgType[SwapOutProcessor.SwapOutDeniedTo] // Too small withdraw amount
    swapOutProcessor ! SwapOutProcessor.SwapOutRequestFrom(SwapOutTransactionRequest(2500000.sat, btcAddress = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ", blockTarget = 36, feerateKey), userId)
    eventListener.expectMsgType[SwapOutProcessor.SwapOutDeniedTo] // We don't have enough reserve in chain wallet
    swapOutProcessor ! SwapOutProcessor.SwapOutRequestFrom(SwapOutTransactionRequest(250000.sat, btcAddress = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ", blockTarget = 36, randomBytes32), userId)
    eventListener.expectMsgType[SwapOutProcessor.SwapOutDeniedTo] // Unknown feerate key

    swapOutProcessor ! SwapOutProcessor.SwapOutRequestFrom(SwapOutTransactionRequest(1000000.sat, btcAddress = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ", blockTarget = 36, feerateKey), userId)
    eventListener.expectMsgType[SwapOutProcessor.SwapOutResponseTo] // pending swap-out is found in cache

    swapOutProcessor ! PaymentReceived(pr.paymentHash, PartialPayment(100000000L.msat, ByteVector32.Zeroes, timestamp = 0L) :: Nil)
    eventListener.expectNoMessage // Payment is not complete
    swapOutProcessor ! PaymentReceived(pr.paymentHash, PartialPayment(100000000L.msat, ByteVector32.Zeroes, timestamp = 0L) :: PartialPayment(910000000L.msat, ByteVector32.Zeroes, timestamp = 0L) :: Nil)
    eventListener.expectNoMessage // Got enough, tx should be initiated, otherwise an error will be logged
  }
}
