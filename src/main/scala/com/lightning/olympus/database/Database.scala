package com.lightning.olympus.database

import com.mongodb.casbah.Imports._
import fr.acinq.bitcoin.{BinaryData, Transaction}
import com.lightning.walletapp.ln.Scripts.cltvBlocks
import com.lightning.olympus.Utils.StringVec
import com.lightning.olympus.BlindData
import language.implicitConversions
import java.math.BigInteger
import java.util.Date


abstract class Database {
  // Recording on-chain transactions
  def putTx(txids: Seq[String], prefix: String, hex: String)
  def getTxs(txids: StringVec): StringVec

  // Scheduling txs to spend
  def putScheduled(tx: Transaction): Unit
  def getScheduled(depth: Int): Seq[String]

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Storing arbitrary data in database
  def putData(key: String, data: String)
  def getData(key: String): List[String]
}

class MongoDatabase extends Database {
  implicit def obj2String(stringSource: Object): String = stringSource.toString
  val blindSignatures: MongoDB = MongoClient("localhost")("btc-blindSignatures")
  val olympus: MongoDB = MongoClient("localhost")("btc-olympus")
  val createdAt = "createdAt"

  def getTxs(txids: StringVec) = olympus("spentTxs").find("txids" $in txids).map(_ as[String] "hex").toVector
  def putTx(txids: Seq[String], prefix: String, hex: String) = olympus("spentTxs").update("prefix" $eq prefix,
    $set("prefix" -> prefix, "txids" -> txids, "hex" -> hex, createdAt -> new Date), upsert = true,
    multi = false, WriteConcern.Safe)

  def getScheduled(depth: Int) = olympus("scheduledTxs").find("cltv" $lt depth).map(_ as[String] "tx").toList
  def putScheduled(tx: Transaction) = olympus("scheduledTxs") insert MongoDBObject("cltv" -> cltvBlocks(tx),
    "tx" -> Transaction.write(tx).toString, createdAt -> new Date)

  // Storing arbitrary data, typically channel backups
  def putData(key: String, data: String) = olympus("userData") insert MongoDBObject("key" -> key, "data" -> data, createdAt -> new Date)
  def getData(key: String) = olympus("userData").find("key" $eq key).sort(DBObject(createdAt -> -1) take 8).map(_ as[String] "data").toList

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) = blindSignatures("blindTokens").update("seskey" $eq seskey,
    $set("seskey" -> seskey, "paymentHash" -> data.paymentHash.toString, "id" -> data.id, "k" -> data.k.toString,
      "tokens" -> data.tokens, createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String) = for {
    result <- blindSignatures("blindTokens").findOne("seskey" $eq seskey)
    tokens = result.get("tokens").asInstanceOf[BasicDBList].map(token => token.toString).toVector
  } yield BlindData(result as[String] "paymentHash", result get "id", new BigInteger(result get "k"), tokens)

  // Many collections to store clear tokens because we have to keep every token
  def putClearToken(ct: String) = blindSignatures("clearTokens" + ct.head).insert("token" $eq ct)
  def isClearTokenUsed(ct: String) = blindSignatures("clearTokens" + ct.head).findOne("token" $eq ct).isDefined
}