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
  val lncloud: MongoDB = MongoClient("localhost")("lncloud")
  val clearTokens: MongoDB = MongoClient("localhost")("clearTokens")
  val eclair: MongoCollection = MongoClient("localhost")("eclair")("paymentRequest")

  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString

  // For signature-based auth users need to save their keys in this collection
  def keyExists(key: String) = lncloud("authKeys").findOne("key" $eq key).isDefined

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) =
    lncloud("blindTokens").update("seskey" $eq seskey, $set("seskey" -> seskey, "k" -> data.k.toString,
      "paymentHash" -> data.paymentHash.toString, "tokens" -> data.tokens, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String) =
    lncloud("blindTokens").findOne("seskey" $eq seskey) map { result =>
      val tokens = result.get("tokens").asInstanceOf[BasicDBList].map(_.toString).toList
      BlindData(BinaryData(result get "paymentHash"), new BigInteger(result get "k"), tokens)
    }

  // Many collections in total to store clear tokens because we have to keep every token
  def putClearToken(clear: String) = clearTokens(clear take 1).insert("clearToken" $eq clear)
  def isClearTokenUsed(clear: String) = clearTokens(clear take 1).findOne("clearToken" $eq clear).isDefined

  // Recording all txs
  def getTxs(txids: StringSeq) =
    lncloud("allTxs").find("txids" $in txids)
      .map(_ as[String] "hex").toList

  def putTx(txids: StringSeq, hex: String) =
    lncloud("allTxs").update("hex" $eq hex, $set("txids" -> txids, "hex" -> hex,
      "date" -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData) = {
    val result = eclair.findOne("hash" $eq hash.toString)
    result.map(_ as[Boolean] "isFulfilled") exists identity
  }

  // Storing arbitrary data
  def putData(key: String, data: String) =
    lncloud("userData").update("data" $eq data, $set("key" -> key, "data" -> data,
      "date" -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getDatas(key: String) = {
    val desc = DBObject("date" -> -1)
    val allResults = lncloud("userData").find("key" $eq key)
    allResults.sort(desc).take(5).map(_ as[String] "data").toList
  }
}