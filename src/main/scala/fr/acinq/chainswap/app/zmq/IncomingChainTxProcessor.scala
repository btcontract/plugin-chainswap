package fr.acinq.chainswap.app.zmq

import fr.acinq.bitcoin._
import slick.jdbc.PostgresProfile.api._
import scala.jdk.CollectionConverters._
import scala.collection.parallel.CollectionConverters._

import fr.acinq.chainswap.app.{BTCDeposit, ChainDepositReceived, Tools, UserIdAndAddress, Vals}
import fr.acinq.chainswap.app.dbo.{BTCDeposits, Blocking, Users}
import akka.actor.{Actor, ActorRef}

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import fr.acinq.chainswap.app.Tools.DuplicateInsertMatcher
import com.google.common.cache.Cache
import slick.jdbc.PostgresProfile
import grizzled.slf4j.Logging
import scodec.bits.ByteVector
import scala.util.Try


class IncomingChainTxProcessor(vals: Vals, swapInProcessor: ActorRef, zmq: ActorRef, db: PostgresProfile.backend.Database) extends Actor with Logging { me =>
  val processedBlocks: Cache[java.lang.Integer, java.lang.Long] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(100000).build[java.lang.Integer, java.lang.Long]
  val recentRequests: Cache[ByteVector, UserIdAndAddress] = Tools.makeExpireAfterAccessCache(1440).maximumSize(5000000).build[ByteVector, UserIdAndAddress]

  val onAnyDatabaseError: DuplicateInsertMatcher[Unit] = new DuplicateInsertMatcher[Unit] {
    def onOtherError(error: Throwable): Unit = logger.info(s"PLGN ChainSwap, DB, error=$error")
    def onDuplicateError: Unit = ()
  }

  val processor: ZMQListener = new ZMQListener {
    override def onNewTx(tx: Transaction): Unit = for {
      Tuple2(TxOut(amount, pubKeyScript), outIdx) <- tx.txOut.zipWithIndex
      UserIdAndAddress(userId, btcAddress) <- Option(recentRequests getIfPresent pubKeyScript)
      if amount.toLong >= vals.minChainDepositSat

      txid = tx.txid.toHex
      _ = Blocking.txWrite(BTCDeposits.insert(btcAddress, outIdx.toLong, txid, amount.toLong, 0L), db)
    } swapInProcessor ! ChainDepositReceived(userId, amount, txid, depth = 0L)

    override def onNewBlock(block: Block): Unit =
      if (Option(processedBlocks getIfPresent block.height).isEmpty)
        try processBlock(block) catch onAnyDatabaseError.matcher

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

        txid = transaction.txid.toHex
        btcAddress = Base58Check.encode(vals.addressPrefix, hash)
      } yield BTCDeposits.insert(btcAddress, outIdx.toLong, txid, amount.toLong, 1L)

      // Insert new records, ignore duplicates
      Blocking.txWrite(DBIO.sequence(refreshes.toVector), db)
      // Remove inserts which do not match any user address
      Blocking.txWrite(BTCDeposits.clearUp, db)

      val lookBackPeriod = System.currentTimeMillis - vals.lookBackPeriodMsecs
      // Select specifically PENDING txs, importantly NOT the ones which exceed our depth threshold
      val query = BTCDeposits.findAllWaitingCompiled(vals.depthThreshold, lookBackPeriod)

      for {
        btcDeposit <- Blocking.txRead(query.result, db).map(BTCDeposit.tupled).par
        // Relies on deposit still pending in our db, but having enough confs in bitcoind
        depth <- getConfs(btcDeposit.txid, btcDeposit.outIndex) if depth >= vals.depthThreshold
        userId <- Blocking.txRead(Users.findByBtcAddressCompiled(btcDeposit.btcAddress).result, db)
        _ = Blocking.txWrite(BTCDeposits.findAllDepthUpdatableCompiled(btcDeposit.id).update(depth), db)
      } swapInProcessor ! ChainDepositReceived(userId, Satoshi(btcDeposit.amount), btcDeposit.txid, depth)

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
  def parse(pubKeyScript: ByteVector): Option[List[ScriptElt]] = Try(Script parse pubKeyScript).toOption

  def receive: Receive = {
    // Map pubKeyScript because it requires less computations when comparing against tx stream
    case user: UserIdAndAddress => recentRequests.put(stringAddressToP2PKH(user.btcAddress), user)
    case Symbol("processor") => sender ! processor
  }
}
