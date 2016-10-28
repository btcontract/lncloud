package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._
import com.btcontract.lncloud.Utils.ListStr
import java.util.Date


abstract class Database {
  // sesPubKey is R which we get from k, rval is Lightning r-value
  def getPendingTokens(rVal: String, sesPubKey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, sesPubKey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Watchdog encrypted txs
  def putWatchdogTx(watch: WatchdogTx)
  def setWatchdogTxSpent(parentTxId: String)
  def getWatchdogTxs(txIds: ListStr): List[WatchdogTx]

  // Watchdog height memo
  def putLastBlockHeight(height: Int)
  def getLastBlockHeight: Option[Int]
}

class MongoDatabase extends Database {
  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString
  val clearTokensMongo = MongoClient("localhost")("clearTokens")
  val mongo = MongoClient("localhost")("lncloud")

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, sesPubKey: String) =
    mongo("blindTokens").update("sesPubKey" $eq sesPubKey, $set("sesPubKey" -> sesPubKey,
      "tokens" -> data.tokens, "rval" -> data.rval, "k" -> data.k, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(rVal: String, sesPubKey: String) =
    mongo("blindTokens") findOne $and("sesPubKey" $eq sesPubKey, "rval" $eq rVal) map { res =>
      BlindData(res.get("tokens").asInstanceOf[BasicDBList].map(_.toString), res get "rval", res get "k")
    }

  // 35 collections in total to store clear tokens because we have to keep every token forever
  def isClearTokenUsed(clear: String) = clearTokensMongo(clear take 1).findOne("clearToken" $eq clear).isDefined
  def putClearToken(clear: String) = clearTokensMongo(clear take 1).insert("clearToken" $eq clear)

  // Watchdog encrypted txs
  def putWatchdogTx(watch: WatchdogTx) =
    mongo("watchTxs").update("prefix" $eq watch.prefix, $set("prefix" -> watch.prefix,
      "txEnc" -> watch.txEnc, "iv" -> watch.iv, "spent" -> false, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def setWatchdogTxSpent(prefix: String) =
    mongo("watchTxs").update("prefix" $eq prefix, $set("spent" -> true),
      upsert = true, multi = false, WriteConcern.Unacknowledged)

  def getWatchdogTxs(prefixes: ListStr) = {
    def toWatchTx(res: DBObject) = WatchdogTx(res get "prefix", res get "txEnc", res get "iv")
    val iterator = mongo("watchTxs") find $and("prefix" $in prefixes, "spent" $eq false) map toWatchTx
    iterator.toList
  }

  // Watchdog height memo
  def putLastBlockHeight(height: Int) = mongo("blockHeight").insert(MongoDBObject("height" -> height), WriteConcern.Safe)
  def getLastBlockHeight = mongo("blockHeight").findOne("height" $gt 0, MongoDBObject.empty, "height" $eq 1).map(_ as[Int] "height")
}