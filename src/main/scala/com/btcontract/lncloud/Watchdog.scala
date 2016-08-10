package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import com.btcontract.lncloud.JsonHttpUtils._
import org.bitcoinj.core.{Sha256Hash, Transaction}
import rx.lang.scala.{Observable => Obs}
import scala.util.{Failure, Try}

import collection.JavaConverters.asScalaBufferConverter
import com.btcontract.lncloud.database.Database
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import com.btcontract.lncloud.crypto.AES
import org.bitcoinj.core.Utils.HEX
import java.net.ConnectException


class Watchdog(db: Database) { me =>
  def connectError = "Can't connect to Bitcoin node"
  def delayError = "Can't process delay tx because can't connect to Bitcoin node"
  def breachError = s"Can't process breach tx, last processed block is ${db.getLastBlockHeight}"
  def decodePunish(key: Bytes, watch: WatchdogTx) = AES.dec(HEX decode watch.txEnc, key, HEX decode watch.ivHex)
  def getSigsHash(tx: Transaction) = Sha256Hash hash tx.getInputs.asScala.flatMap(_.getScriptBytes).sorted.toArray
  def blocksRange = bitcoin.getBlockCount match { case lst => db.getLastBlockHeight.getOrElse(lst - 720) - 5 to lst }

  def processBreach(watch: WatchdogTx) = Try {
    val rawParentTx = HEX.decode(bitcoin getRawTransactionHex watch.parentTxId)
    val punishTx = decodePunish(me getSigsHash new Transaction(params, rawParentTx), watch)
    bitcoin sendRawTransaction HEX.encode(punishTx)
  } match {
    case Failure(_: ConnectException) => logger error breachError
    case _ => db setWatchdogTxSpent watch.parentTxId
  }

  def processDelayTx(transactionHex: String) =
    Try(bitcoin sendRawTransaction transactionHex) match {
      case Failure(_: ConnectException) => logger error delayError
      case _ => db setDelayTxSpent transactionHex
    }

  def resolveError(exception: Throwable): Unit = exception match {
    case _: ConnectException => values.emailParams notifyError connectError
    case _ => logger error exception.getMessage
  }

  // For n last blocks, take all the txid from each block and try to find matching breaches in a database
  Obs.interval(10.seconds).zip(obsOn(blocksRange, IOScheduler.apply) flatMap Obs.just).map(bitcoin getBlock _._2)
    .map(db getWatchdogTxs _.tx.asScala).flatMap(Obs.just).doOnCompleted(db putLastBlockHeight bitcoin.getBlockCount)
    .doOnCompleted(logger info "Breach watch done").doOnError(resolveError).repeatWhen(_ delay 10.minutes)
    .retryWhen(_ delay 10.minutes).subscribe(processBreach _)

  // For each new block, take all unspent transactions with lesser height and broadcast them
  Obs.interval(5.millis).zip(obsOn(db.getDelayTxs(bitcoin.getBlockCount).toList, IOScheduler.apply) flatMap Obs.just)
    .map(_._2).doOnError(resolveError).doOnCompleted(logger info "Height watch done").repeatWhen(_ delay 10.minutes)
    .retryWhen(_ delay 10.minutes).subscribe(processDelayTx _)
}
