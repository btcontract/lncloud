package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._
import com.btcontract.lncloud.Utils.StringSeq
import com.mongodb.casbah.MongoCollection
import language.implicitConversions
import fr.acinq.bitcoin.BinaryData
import java.math.BigInteger
import java.util.Date


abstract class Database {
  // For signature-based auth
  def keyExists(key: String): Boolean

  // Recording all transactions
  def putTx(txids: StringSeq, hex: String)
  def getTxs(txids: StringSeq): StringSeq

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData): Boolean

  // Storing arbitrary data
  def putData(key: String, data: String)
  def getDatas(key: String): List[String]
}

class MongoDatabase extends Database {
  val mongo: MongoDB = MongoClient("localhost")("lncloud")
  val clearTokensMongo: MongoDB = MongoClient("localhost")("clearTokens")
  val eclair: MongoCollection = MongoClient("localhost")("eclair")("paymentRequest")

  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString

  // For signature-based auth users need to save their keys in this collection
  def keyExists(key: String) = mongo("authKeys").findOne("key" $eq key).isDefined

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) =
    mongo("blindTokens").update("seskey" $eq seskey, $set("seskey" -> seskey, "k" -> data.k.toString,
      "paymentHash" -> data.paymentHash.toString, "tokens" -> data.tokens, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String) =
    mongo("blindTokens").findOne("seskey" $eq seskey) map { result =>
      val tokens = result.get("tokens").asInstanceOf[BasicDBList].map(_.toString).toList
      BlindData(BinaryData(result get "paymentHash"), new BigInteger(result get "k"), tokens)
    }

  // Many collections in total to store clear tokens because we have to keep every token
  def putClearToken(clear: String) = clearTokensMongo(clear take 1).insert("clearToken" $eq clear)
  def isClearTokenUsed(clear: String) = clearTokensMongo(clear take 1).findOne("clearToken" $eq clear).isDefined

  // Recording all txs
  def getTxs(txids: StringSeq) =
    mongo("allTxs").find("txids" $in txids)
      .map(_ as[String] "hex").toList

  def putTx(txids: StringSeq, hex: String) =
    mongo("allTxs").update("hex" $eq hex, $set("txids" -> txids, "hex" -> hex,
      "date" -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData) = {
    val result = eclair.findOne("hash" $eq hash.toString)
    result.map(_ as[Boolean] "isFulfilled") exists identity
  }

  // Storing arbitrary data
  def putData(key: String, data: String) = {
    val record = DBObject("key" -> key, "data" -> data, "date" -> new Date)
    mongo("userData").insert(doc = record, WriteConcern.Safe)
  }

  def getDatas(key: String) = {
    val desc = DBObject("date" -> -1)
    val allResults = mongo("userData").find("key" $eq key)
    allResults.sort(desc).take(5).map(_ as[String] "data").toList
  }
}