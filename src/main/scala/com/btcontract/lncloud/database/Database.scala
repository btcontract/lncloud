package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._
import com.btcontract.lncloud.Utils.OptString

import language.implicitConversions
import java.util.Date

import fr.acinq.bitcoin.BinaryData


abstract class Database {
  // sesPubKey is R which we get from k, preimage is Lightning payment preimage
  def getPendingTokens(paymentHash: String): Option[BlindData]
  def putPendingTokens(data: BlindData, paymentHash: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Channel recovery info and misc
  def getGeneralData(key: String): OptString
  def putGeneralData(key: String, value: String)
  def deleteGeneralData(key: String)
}

class MongoDatabase extends Database {
  val mongo: MongoDB = MongoClient("localhost")("lncloud")
  val clearTokensMongo: MongoDB = MongoClient("localhost")("clearTokens")
  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, paymentHash: String): Unit = {
    val set = $set("paymentHash" -> paymentHash, "tokens" -> data.tokens, "k" -> data.k, "date" -> new Date)
    mongo("blindTokens").update("paymentHash" $eq paymentHash, set, upsert = true, multi = false, WriteConcern.Safe)
  }

  def getPendingTokens(paymentHash: String): Option[BlindData] =
    mongo("blindTokens").findOne("paymentHash" $eq paymentHash) map { res =>
      val tokens = res.get("tokens").asInstanceOf[BasicDBList].map(_.toString)
      BlindData(tokens.toList, res get "k")
    }

  // Many collections in total to store clear tokens because we have to keep every token
  def isClearTokenUsed(clear: String): Boolean = clearTokensMongo(clear take 1).findOne("clearToken" $eq clear).isDefined
  def putClearToken(clear: String): Unit = clearTokensMongo(clear take 1).insert("clearToken" $eq clear)

  // Channel closing info and misc
  def deleteGeneralData(key: String): Unit = mongo("generalData").remove("key" $eq key)
  def getGeneralData(key: String): OptString = mongo("generalData").findOne("key" $eq key).map(_ as[String] "value")
  def putGeneralData(key: String, value: String): Unit = mongo("generalData").update("key" $eq key,
    $set("key" -> key, "value" -> value), upsert = true, multi = false, WriteConcern.Safe)
}