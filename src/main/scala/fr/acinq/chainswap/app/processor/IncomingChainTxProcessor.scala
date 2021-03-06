package fr.acinq.chainswap.app.processor

import fr.acinq.bitcoin._
import slick.jdbc.PostgresProfile.api._

import scala.jdk.CollectionConverters._
import scala.collection.parallel.CollectionConverters._
import akka.actor.{Actor, ActorRef}
import fr.acinq.chainswap.app.db.{Addresses, BTCDeposits, Blocking}
import fr.acinq.chainswap.app.{AccountAndAddress, ChainDeposit, ChainDepositReceived, Tools, Vals}
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.google.common.cache.Cache
import slick.jdbc.PostgresProfile
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.util.Try


class IncomingChainTxProcessor(vals: Vals, swapInProcessor: ActorRef, zmq: ActorRef, db: PostgresProfile.backend.Database) extends Actor with Logging {
  val processedBlocks: Cache[java.lang.Integer, java.lang.Long] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(1000000).build[java.lang.Integer, java.lang.Long]
  val recentRequests: Cache[ByteVector, AccountAndAddress] = Tools.makeExpireAfterAccessCache(1440).maximumSize(5000000).build[ByteVector, AccountAndAddress]

  val processor: ZMQListener = new ZMQListener {
    override def onNewTx(tx: Transaction): Unit = for {
      Tuple2(TxOut(amount, pubKeyScript), outIdx) <- tx.txOut.zipWithIndex
      AccountAndAddress(accountId, btcAddress) <- Option(recentRequests getIfPresent pubKeyScript)
      if amount.toLong >= vals.minChainDepositSat

      txid = tx.txid.toHex
      // We only insert an unconfirmed tx into db if we can find an accountId which has requested it recently
      _ = Blocking.txWrite(BTCDeposits.insert(btcAddress, outIdx.toLong, txid, amount.toLong, 0L), db)
    } swapInProcessor ! ChainDepositReceived(accountId, txid, amount.toLong, depth = 0L)

    override def onNewBlock(block: Block): Unit = {
      val alreadyProcessed = Option(processedBlocks getIfPresent block.height)
      if (alreadyProcessed.isEmpty) processBlock(block)
    }

    private def processBlock(block: Block): Unit = {
      // 1. Obtain address/outIndex/txid tuples from each tx
      // 2. Store all relevant tuples from this block with depth = 1
      // 3. While storing, ignore existing index matches (could be unconfirmed, already confirmed if we do resync!)
      // 4. Obtain all PENDING txs, get their current bitcoind-provided depth, tx-update depth in db, too
      // 5. For all txs with good depth: inform users

      val refreshes = for {
        transaction <- block.tx.asScala.flatMap(getTx).par
        Tuple2(TxOut(amount, pubKeyScript), outIdx) <- transaction.txOut.zipWithIndex
        List(OP_DUP, OP_HASH160, OP_PUSHDATA(hash, _), OP_EQUALVERIFY, OP_CHECKSIG) <- parse(pubKeyScript)
        if amount.toLong >= vals.minChainDepositSat && 20 == hash.size

        btcAddress = Base58Check.encode(vals.addressPrefix, hash)
      } yield BTCDeposits.insert(btcAddress, outIdx.toLong, transaction.txid.toHex, amount.toLong, 1L)

      // Insert new records, ignore duplicates
      Blocking.txWrite(DBIO.sequence(refreshes.toVector), db)
      // Remove inserts which do not match any user address
      Blocking.txWrite(BTCDeposits.clearUp, db)

      // Give up on waiting if it stays in mempool for this long
      val lookBackPeriod = System.currentTimeMillis - vals.lookBackPeriodMsecs
      // Select specifically PENDING txs, importantly NOT the ones which exceed our depth threshold
      val pending = BTCDeposits.findAllWaitingCompiled(vals.depthThreshold, lookBackPeriod)

      for {
        chainDeposit <- Blocking.txRead(pending.result, db).map(ChainDeposit.tupled).par
        // Relies on deposit still pending in our db, but having enough confs in bitcoind
        depth <- getConfs(chainDeposit.txid, chainDeposit.outIndex) if depth >= vals.depthThreshold
        _ = Blocking.txWrite(BTCDeposits.findDepthUpdatableByIdCompiled(chainDeposit.id).update(depth), db)
        accountId <- Blocking.txRead(Addresses.findByBtcAddressCompiled(chainDeposit.btcAddress).result.headOption, db)
        // To increase user privacy we add a new Bitcoin address on each successful deposit, old addresses stay valid
        _ = Blocking.txWrite(Addresses.insertCompiled += (vals.bitcoinAPI.getNewAddress, accountId), db)
        reply = ChainDepositReceived(accountId, chainDeposit.txid, chainDeposit.amountSat, depth)
      } swapInProcessor ! reply

      // Prevent this block from being processed twice
      processedBlocks.put(block.height, System.currentTimeMillis)
    }
  }

  zmq ! processor

  def stringAddressToP2PKH(address: String): ByteVector = {
    val (_, keyHash) = Base58Check.decode(address)
    Script.write(Script pay2pkh keyHash)
  }

  def getTx(txid: String): Option[Transaction] = Try(vals.bitcoinAPI getRawTransactionHex txid).map(Transaction.read).toOption
  def getConfs(txid: String, idx: Long): Option[Long] = Try(vals.bitcoinAPI.getTxOut(txid, idx, false).confirmations).toOption
  private def parse(pubKeyScript: ByteVector) = Try(Script parse pubKeyScript).toOption

  def receive: Receive = {
    // Map pubKeyScript because it requires less computations when comparing against tx stream
    case message: AccountAndAddress => recentRequests.put(stringAddressToP2PKH(message.btcAddress), message)
    case Symbol("processor") => sender ! processor
  }
}
