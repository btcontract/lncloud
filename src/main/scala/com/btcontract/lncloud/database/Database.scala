package com.btcontract.lncloud.database

import com.btcontract.lncloud._
import com.mongodb.casbah.Imports._

import com.btcontract.lncloud.Utils.StringSeq
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
}

class MongoDatabase extends Database {
  val mongo: MongoDB = MongoClient("localhost")("lncloud")
  val clearTokensMongo: MongoDB = MongoClient("localhost")("clearTokens")
  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String): Unit =
    mongo("blindTokens").update("seskey" $eq seskey, $set("seskey" -> seskey, "k" -> data.k.toString,
      "paymentHash" -> data.paymentHash.toString, "tokens" -> data.tokens, "date" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String): Option[BlindData] =
    mongo("blindTokens").findOne("seskey" $eq seskey) map { result =>
      val tokens: StringSeq = result.get("tokens").asInstanceOf[BasicDBList].map(_.toString)
      BlindData(BinaryData(result get "paymentHash"), new BigInteger(result get "k"), tokens.toList)
    }

  // Many collections in total to store clear tokens because we have to keep every token
  def putClearToken(clear: String): Unit = clearTokensMongo(clear take 1).insert("clearToken" $eq clear)
  def isClearTokenUsed(clear: String) = clearTokensMongo(clear take 1).findOne("clearToken" $eq clear).isDefined

  // Channel closing info, keys and and misc
  def keyExists(key: String): Boolean = mongo("authKeys").findOne("key" $eq key).isDefined

  // Recording all transactions because clients may need to know which txs has been spent by whom
  def getTxs(txid: String): StringSeq = mongo("txs").find("txids" $eq txid).map(_ as[String] "hex").toList
  def putTx(txids: StringSeq, hex: String) = mongo("txs").update("hex" $eq hex, $set("txids" -> txids, "hex" -> hex),
    upsert = true, multi = false, WriteConcern.Safe)
}