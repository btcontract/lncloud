package com.lightning.olympus.database

import com.mongodb.casbah.Imports._
import fr.acinq.bitcoin.{BinaryData, Transaction}
import com.lightning.olympus.{BlindData, ChanInfo, TxidAndSats}
import com.lightning.walletapp.ln.Scripts.cltvBlocks
import com.lightning.walletapp.ln.wire.AESZygote
import com.lightning.walletapp.ln.Tools.Bytes
import com.lightning.olympus.Utils.StringVec
import language.implicitConversions
import java.math.BigInteger
import java.util.Date


abstract class Database {
  // Recording of format [spends from] -> spender
  def putSpender(txids: Seq[String], prefix: String): Unit
  def getSpenders(txids: StringVec): StringVec

  // Scheduling txs to spend
  def putScheduled(tx: Transaction): Unit
  def getScheduled(depth: Int): Seq[String]

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String): Unit
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String): Unit

  // Storing arbitrary data in database
  def putData(key: String, data: String): Unit
  def getData(key: String): List[String]

  // Chan information
  def getChanInfo(shortChanId: Long): Option[TxidAndSats]
  def addChanInfo(info: ChanInfo): Unit

  // Registering revoked transactions to be watched
  def putWatched(params: AESZygote, halfTxId: String): Unit
  def getWatched(halfTxIds: StringVec): Map[String, AESZygote]
}

class MongoDatabase extends Database {
  implicit def obj2String(source: Object): String = source.toString
  val blindSignatures: MongoDB = MongoClient("localhost")("btc-blindSignatures")
  val watchedTxs: MongoDB = MongoClient("localhost")("btc-watchedTxs")
  val olympus: MongoDB = MongoClient("localhost")("btc-olympus")
  final val createdAt = "createdAt"

  def getSpenders(txids: StringVec) =
    olympus("spentTxs").find("txids" $in txids)
      .map(_ as[String] "prefix").toVector

  def putSpender(txids: Seq[String], prefix: String) = olympus("spentTxs").update("prefix" $eq prefix,
    $set("prefix" -> prefix, "txids" -> txids, createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getScheduled(depth: Int) =
    olympus("scheduledTxs").find("cltv" $lt depth)
      .map(_ as[String] "tx").toList

  def putScheduled(tx: Transaction) =
    olympus("scheduledTxs") insert MongoDBObject("cltv" -> cltvBlocks(tx),
      "tx" -> Transaction.write(tx).toString, createdAt -> new Date)

  // Storing arbitrary data, typically channel backups
  def putData(key: String, data: String) = olympus("userData") insert MongoDBObject("key" -> key, "data" -> data, createdAt -> new Date)
  def getData(key: String): List[String] = olympus("userData").find("key" $eq key).sort(DBObject(createdAt -> -1) take 8).map(_ as[String] "data").toList

  // Chan information
  def getChanInfo(shortChanId: Long) = for {
    result <- olympus("chanInfo").findOne("shortChanId" $eq shortChanId)
  } yield TxidAndSats(result as[String] "txid", result as[Long] "capacity")

  def addChanInfo(info: ChanInfo) = olympus("chanInfo").update("shortChanId" $eq info.ca.shortChannelId,
    $set("txid" -> info.txid, "capacity" -> info.capacity, "shortChanId" -> info.ca.shortChannelId,
      createdAt -> new Date), upsert = true, multi = false, WriteConcern.Unacknowledged)

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) = blindSignatures("blindTokens").update("seskey" $eq seskey,
    $set("seskey" -> seskey, "paymentHash" -> data.paymentHash.toString, "id" -> data.id, "k" -> data.k.toString,
      "tokens" -> data.tokens, createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String) = for {
    res <- blindSignatures("blindTokens").findOne("seskey" $eq seskey)
    tokens = res.get("tokens").asInstanceOf[BasicDBList].map(token => token.toString).toVector
  } yield BlindData(BinaryData(res get "paymentHash"), res get "id", new BigInteger(res get "k"), tokens)

  // Clear token is transferred as BigInteger so has 0-9 in it's head
  // Many collections to store clear tokens because we have to keep every token
  def putClearToken(ct: String) = blindSignatures("clearTokens" + ct.head).insert("token" $eq ct)
  def isClearTokenUsed(ct: String) = blindSignatures("clearTokens" + ct.head).findOne("token" $eq ct).isDefined

  // Store and retrieve watched revoked transactions
  def putWatched(aesz: AESZygote, halfTxId: String) =
    watchedTxs("watchedTxs" + halfTxId.head).update("halfTxId" $eq halfTxId,
      $set("v" -> aesz.v, "iv" -> aesz.iv.toArray, "ciphertext" -> aesz.ciphertext.toArray,
        "halfTxId" -> halfTxId, createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getWatched(halfTxids: StringVec) = for {
    Tuple2(headPrefix, txidsHex) <- halfTxids.groupBy(_ take 1)
    record <- watchedTxs("watchedTxs" + headPrefix).find("halfTxId" $in txidsHex)
    easz = AESZygote(record as[Int] "v", record as[Bytes] "iv", record as[Bytes] "ciphertext")
  } yield obj2String(record get "halfTxId") -> easz
}