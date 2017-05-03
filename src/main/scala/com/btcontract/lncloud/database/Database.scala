package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._

import com.btcontract.lncloud.Utils.TokenSeq
import com.lightning.wallet.ln.Invoice
import language.implicitConversions
import java.math.BigInteger
import java.util.Date


abstract class Database {
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Channel recovery info and misc
  def getGeneralData(key: String): Option[String]
  def putGeneralData(key: String, value: String)
  def deleteGeneralData(key: String)
}

class MongoDatabase extends Database {
  val mongo: MongoDB = MongoClient("localhost")("lncloud")
  val clearTokensMongo: MongoDB = MongoClient("localhost")("clearTokens")
  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String): Unit =
    mongo("blindTokens").update("seskey" $eq seskey, $set("seskey" -> seskey, "k" -> data.k.toString,
      "invoice" -> Invoice.serialize(data.invoice), "tokens" -> data.tokens, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String): Option[BlindData] =
    mongo("blindTokens").findOne("seskey" $eq seskey) map { result =>
      val tokens: TokenSeq = result.get("tokens").asInstanceOf[BasicDBList].map(_.toString)
      BlindData(Invoice.parse(result get "invoice"), new BigInteger(result get "k"), tokens.toList)
    }

  // Many collections in total to store clear tokens because we have to keep every token
  def isClearTokenUsed(clear: String): Boolean = clearTokensMongo(clear take 1).findOne("clearToken" $eq clear).isDefined
  def putClearToken(clear: String): Unit = clearTokensMongo(clear take 1).insert("clearToken" $eq clear)

  // Channel closing info and misc
  def deleteGeneralData(key: String) = mongo("generalData").remove("key" $eq key)
  def getGeneralData(key: String): Option[String] = mongo("generalData").findOne("key" $eq key).map(_ as[String] "value")
  def putGeneralData(key: String, value: String) = mongo("generalData").update("key" $eq key, $set("key" -> key, "value" -> value),
    upsert = true, multi = false, concern = WriteConcern.Safe)
}