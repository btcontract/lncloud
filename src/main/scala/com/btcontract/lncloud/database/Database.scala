package com.btcontract.lncloud.database

import com.mongodb.casbah.Imports._
import com.btcontract.lncloud.{BlindData, ServerSignedMail, WatchdogTx, Wrap}
import com.btcontract.lncloud.Utils.SeqString
import com.mongodb.WriteResult
import java.util.Date


abstract class Database {
  // Mapping from email to public key
  def putSignedMail(container: ServerSignedMail): WriteResult
  def getSignedMail(something: String): Option[ServerSignedMail]

  // sesPubKey is R which we get from k, rval is Lightning r-value
  def getPendingTokens(rval: String, sesPubKey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, sesPubKey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Messages
  def getAllWraps: Seq[Wrap]
  def putWrap(wrap: Wrap)

  // Delayed txs for broken channels
  def getDelayTxs(height: Int): SeqString
  def putDelayTx(txHex: String, height: Int)
  def setDelayTxSpent(txHex: String)

  // Watchdog encrypted txs
  def putWatchdogTx(watch: WatchdogTx)
  def setWatchdogTxSpent(parentTxId: String)
  def getWatchdogTxs(txIds: SeqString): Seq[WatchdogTx]

  // Watchdog height memo
  def putLastBlockHeight(height: Int)
  def getLastBlockHeight: Option[Int]
}

abstract class MongoDatabase extends Database {
  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString
  val mongo = MongoClient("localhost")("lncloud")

  // Delayed transactions for broken channels
  def putDelayTx(txHex: String, height: Int) =
    mongo("delayTxs").update("txHex" $eq txHex, $set("txHex" -> txHex,
      "height" -> height, "spent" -> false, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getDelayTxs(height: Int) = {
    val query = $and("height" $lt height, "spent" $eq false)
    mongo("delayTxs").find(query).map(_ as[String] "txHex").toList
  }

  def setDelayTxSpent(txHex: String) =
    mongo("delayTxs").update("txHex" $eq txHex, $set("spent" -> true),
      upsert = true, multi = false, WriteConcern.Unacknowledged)

  // Watchdog encrypted txs
  def putWatchdogTx(watch: WatchdogTx) =
    mongo("watchTxs").update("parentTxId" $eq watch.parentTxId, $set("parentTxId" -> watch.parentTxId,
      "txEnc" -> watch.txEnc, "ivHex" -> watch.ivHex, "spent" -> false, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def setWatchdogTxSpent(parentTxId: String) =
    mongo("watchTxs").update("parentTxId" $eq parentTxId, $set("spent" -> true),
      upsert = true, multi = false, WriteConcern.Unacknowledged)

  def getWatchdogTxs(txIds: SeqString) = {
    def toWatchTx(res: DBObject) = WatchdogTx(res get "parentTxId", res get "txEnc", res get "ivHex")
    val iterator = mongo("watchTxs") find $and("parentTxId" $in txIds, "spent" $eq false) map toWatchTx
    iterator.toList
  }

  // Watchdog height memo
  def putLastBlockHeight(height: Int) = mongo("blockHeight").insert(MongoDBObject("height" -> height), WriteConcern.Safe)
  def getLastBlockHeight = mongo("blockHeight").findOne("height" $gt 0, MongoDBObject.empty, "height" $eq 1).map(_ as[Int] "height")
}