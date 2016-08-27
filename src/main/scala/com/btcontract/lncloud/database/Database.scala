package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._
import com.btcontract.lncloud.Utils.SeqString
import org.bitcoinj.core.Utils.HEX
import java.util.Date


abstract class Database {
  // Mapping from email to public key
  def putSignedMail(container: ServerSignedMail)
  def getSignedMail(something: String): Option[ServerSignedMail]

  // sesPubKey is R which we get from k, rval is Lightning r-value
  def getPendingTokens(rVal: String, sesPubKey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, sesPubKey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Messages
  def getAllWraps: List[Wrap]
  def putWrap(wrap: Wrap)

  // Watchdog encrypted txs
  def putWatchdogTx(watch: WatchdogTx)
  def setWatchdogTxSpent(parentTxId: String)
  def getWatchdogTxs(txIds: SeqString): List[WatchdogTx]

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

  // Many collections to store clear tokens because we have to keep them all forever
  def isClearTokenUsed(clear: String) = getClearTokenCol(clear).findOne("clearToken" $eq clear).isDefined
  def putClearToken(clear: String) = getClearTokenCol(clear).insert("clearToken" $eq clear)
  def getClearTokenCol(name: String) = clearTokensMongo("col" + name.head)

  // Messages
  def putWrap(wrap: Wrap) = mongo("wraps").insert(MongoDBObject("stamp" -> wrap.stamp,
    "pubKey" -> HEX.encode(wrap.data.pubKey), "content" -> HEX.encode(wrap.data.content),
    "date" -> new Date), WriteConcern.Safe)

  def getAllWraps = mongo("wraps").find.map { res =>
    val contents = HEX.decode(res get "content")
    val pubKey = HEX.decode(res get "pubKey")
    val msg = Message(pubKey, contents)
    Wrap(msg, res get "stamp")
  }.toList

  // Mapping from email to public key
  def putSignedMail(ssm: ServerSignedMail) =
    mongo("keymail").update("email" $eq ssm.client.email, $set("serverSignature" -> ssm.signature,
      "email" -> ssm.client.email, "pubKey" -> ssm.client.pubKey, "signature" -> ssm.client.signature),
      upsert = true, multi = false, WriteConcern.Safe)

  def getSignedMail(something: String) =
    mongo("keymail") findOne $or("email" $eq something, "pubKey" $eq something) map { res =>
      val signedMail = SignedMail(res get "email", res get "pubKey", res get "signature")
      ServerSignedMail(signedMail, res get "serverSignature")
    }

  // Watchdog encrypted txs
  def putWatchdogTx(watch: WatchdogTx) =
    mongo("watchTxs").update("prefix" $eq watch.prefix, $set("prefix" -> watch.prefix,
      "txEnc" -> watch.txEnc, "iv" -> watch.iv, "spent" -> false, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def setWatchdogTxSpent(prefix: String) =
    mongo("watchTxs").update("prefix" $eq prefix, $set("spent" -> true),
      upsert = true, multi = false, WriteConcern.Unacknowledged)

  def getWatchdogTxs(prefixes: SeqString) = {
    def toWatchTx(res: DBObject) = WatchdogTx(res get "prefix", res get "txEnc", res get "iv")
    val iterator = mongo("watchTxs") find $and("prefix" $in prefixes, "spent" $eq false) map toWatchTx
    iterator.toList
  }

  // Watchdog height memo
  def putLastBlockHeight(height: Int) = mongo("blockHeight").insert(MongoDBObject("height" -> height), WriteConcern.Safe)
  def getLastBlockHeight = mongo("blockHeight").findOne("height" $gt 0, MongoDBObject.empty, "height" $eq 1).map(_ as[Int] "height")
}