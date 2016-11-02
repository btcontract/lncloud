package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import com.btcontract.lncloud.JsonHttpUtils._

import scala.util.{Failure, Try}
import rx.lang.scala.{Observable => Obs}

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import collection.JavaConverters.asScalaBufferConverter
import com.btcontract.lncloud.database.Database
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import org.bitcoinj.core.Utils.HEX
import java.net.ConnectException


class Watchdog(db: Database) { me =>
  def getLastBlockHeight = db.getGeneralData("lastBlockHeight").map(_.toInt)
  def putLastBlockHeight(height: Int) = db.putGeneralData("lastBlockHeight", height.toString)
  def blocks = bitcoin.getBlockCount match { case lst => me.getLastBlockHeight.getOrElse(lst - 720) - 6 to lst }
  def block2PrefixKey(block: Block) = for (txId <- block.tx.asScala) yield txId.take(16) -> HEX.decode(txId drop 16)
  def breachError = s"Can't process breach tx, last processed block is ${me.getLastBlockHeight}"
  def connectError = "Can't connect to Bitcoin node"

  def publishTxs(block: Block) = {
    val prefixKey = block2PrefixKey(block).toMap
    for (watch <- db getWatchdogTxs prefixKey.keys.toList) Try {
      val punishTransaction = watch decodeTx prefixKey(watch.prefix)
      bitcoin.sendRawTransaction(HEX encode punishTransaction)
    } match {
      // Tx already spent, tx could not be decoded, malformed tx
      case Failure(_: ConnectException) => logger error breachError
      case _ => db setWatchdogTxSpent watch.prefix
    }
  }

  // For n last blocks, take all the txid from each block and try to find matching breaches in a database
  def run = Obs.interval(10.seconds).zip(obsOn(blocks, IOScheduler.apply) flatMap Obs.just).map(bitcoin getBlock _._2)
    .doOnCompleted(me putLastBlockHeight bitcoin.getBlockCount).doOnCompleted(logger info "Breach watch DONE")
    .doOnError(err => logger info s"Breach watch FAILED: $err").repeatWhen(_ delay 5.minutes)
    .retryWhen(_ delay 5.minutes).subscribe(publishTxs _)
}