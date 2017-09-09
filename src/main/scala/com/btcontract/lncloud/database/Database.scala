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
  def getTxs(txid: String): StringSeq

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData): Boolean

  // Storing arbitrary data
  def putData(key: String, data: String)
  def getData(key: String): Option[String]
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

  // Recording all transactions because clients may need to know which HTLC txs has been spent
  def getTxs(txid: String): StringSeq = mongo("allTxs").find("txids" $eq txid).map(_ as[String] "hex").toList

  def putTx(txids: StringSeq, hex: String) =
    mongo("allTxs").update("hex" $eq hex, $set("txids" -> txids,
      "hex" -> hex), upsert = true, multi = false, WriteConcern.Safe)

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData) = {
    val result = eclair.findOne("hash" $eq hash.toString)
    result.map(_ as[Boolean] "isFulfilled") exists identity
  }

  // Storing arbitrary data (like static channel parameters to restore a lost channel)
  def getData(key: String) = mongo("anyData").findOne("key" $eq key).map(_ as[String] "data")

  def putData(key: String, data: String) =
    mongo("anyData").update("key" $eq key, $set("key" -> key,
      "data" -> data), upsert = true, multi = false, WriteConcern.Safe)
}