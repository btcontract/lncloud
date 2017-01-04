package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._
import language.implicitConversions
import java.util.Date


abstract class Database {
  // sesPubKey is R which we get from k, rval is Lightning r-value
  def getPendingTokens(rVal: String, sesPubKey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, sesPubKey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Channel recovery info and misc
  def getGeneralData(key: String): Option[String]
  def putGeneralData(key: String, value: String)
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

  // Channel recovery info and misc
  def getGeneralData(key: String) = mongo("generalData").findOne("key" $eq key).map(_ as[String] "value")
  def putGeneralData(key: String, value: String): Unit = mongo("generalData").update("key" $eq key,
    $set("key" -> key, "value" -> value), upsert = true, multi = false, WriteConcern.Safe)
}