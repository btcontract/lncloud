package com.lightning.olympus.database

import com.mongodb.casbah.Imports._
import com.lightning.olympus.{BlindData, ChanInfo, TxidAndSats}
import com.lightning.walletapp.ln.Scripts.cltvBlocks
import com.lightning.walletapp.ln.wire.AESZygote
import com.lightning.walletapp.ln.Tools.Bytes
import com.lightning.olympus.Utils.StringVec
import fr.acinq.bitcoin.Transaction

import language.implicitConversions
import scodec.bits.ByteVector
import java.math.BigInteger
import java.util.Date

import com.lightning.walletapp.ln.Tools


abstract class Database {
  // Recording of format [spends from] -> spender
  def putSpender(txids: Seq[String], prefix: String): Unit
  def getSpenders(txids: StringVec): StringVec

  // Scheduling txs to spend
  def putScheduled(tx: Transaction): Unit
  def getScheduled(depth: Int): StringVec

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String): Unit
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String): Unit

  // Storing arbitrary data in database
  def putData(key: String, data: String): Unit
  def getData(key: String): StringVec

  // Chan information
  def getChanInfo(shortChanId: Long): Option[TxidAndSats]
  def addChanInfo(info: ChanInfo): Unit

  // Registering revoked transactions to be watched
  def putWatched(params: AESZygote, halfTxId: String): Unit
  def getWatched(halfTxIds: StringVec): Map[String, AESZygote]
}

class MongoDatabase extends Database {
  val blindSignatures: MongoDB = MongoClient("localhost")("btc-blindSignatures")
  val watchedTxs: MongoDB = MongoClient("localhost")("btc-watchedTxs")
  val olympus: MongoDB = MongoClient("localhost")("btc-olympus")
  final val createdAt = "createdAt"

  def getSpenders(txids: StringVec): StringVec = olympus("spentTxs").find("txids" $in txids).map(_ as[String] "prefix").toVector

  def putSpender(txids: Seq[String], prefix: String) = olympus("spentTxs").update("prefix" $eq prefix,
    $set("prefix" -> prefix, "txids" -> txids, createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getScheduled(depth: Int): StringVec = olympus("scheduledTxs").find("cltv" $lt depth).map(_ as[String] "tx").toVector

  def putScheduled(tx: Transaction) = olympus("scheduledTxs") insert MongoDBObject("cltv" -> cltvBlocks(tx), "tx" -> tx.bin.toHex, createdAt -> new Date)

  // Storing arbitrary data, typically channel backups
  def putData(key: String, data: String) = olympus("userData") insert MongoDBObject("key" -> key, "data" -> data, createdAt -> new Date)

  def getData(key: String): StringVec = olympus("userData").find("key" $eq key).sort(DBObject(createdAt -> -1) take 16).map(_ as[String] "data").toVector

  // Chan information
  def getChanInfo(shortChanId: Long) = for {
    result <- olympus("chanInfo").findOne("shortChanId" $eq shortChanId)
  } yield TxidAndSats(result as[String] "txid", result as[Long] "capacity")

  def addChanInfo(info: ChanInfo) = olympus("chanInfo").update("shortChanId" $eq info.ca.shortChannelId,
    $set("txid" -> info.txid, "capacity" -> info.capacity, "shortChanId" -> info.ca.shortChannelId,
      createdAt -> new Date), upsert = true, multi = false, WriteConcern.Unacknowledged)

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) = blindSignatures("blindTokens").update("seskey" $eq seskey,
    $set("seskey" -> seskey, "paymentHash" -> data.paymentHash, "id" -> data.id, "k" -> data.k.toString, "tokens" -> data.tokens,
      createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String) = for {
    res <- blindSignatures("blindTokens").findOne("seskey" $eq seskey)
    blindTokens = res.get("tokens").asInstanceOf[BasicDBList].map(blindToken => blindToken.toString).toVector
  } yield BlindData(res as[String] "paymentHash", res as[String] "id", new BigInteger(res as[String] "k", 10), blindTokens)

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

    ivVec = ByteVector.view(record as[Bytes] "iv")
    ciphertextVec = ByteVector.view(record as[Bytes] "ciphertext")
    aesz = AESZygote(record as[Int] "v", ivVec, ciphertextVec)
  } yield Tuple2(record as[String] "halfTxId", aesz)
}