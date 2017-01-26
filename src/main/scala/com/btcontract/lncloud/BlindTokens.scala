package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{Crypto, MilliSatoshi}

import collection.JavaConverters.mapAsScalaConcurrentMapConverter
import concurrent.ExecutionContext.Implicits.global
import com.btcontract.lncloud.crypto.ECBlindSign
import com.btcontract.lncloud.database.Database
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import org.spongycastle.math.ec.ECPoint
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import scala.concurrent.Future
import java.math.BigInteger


class BlindTokens(db: Database) {
  type PaymentData = (Bytes, Bytes)
  type FutureInvoice = Future[Invoice]
  type SesKeyCacheItem = CacheItem[BigInteger]
  val signer = new ECBlindSign(values.privKey.bigInteger)
  val cache: collection.concurrent.Map[String, SesKeyCacheItem] =
    new ConcurrentHashMap[String, SesKeyCacheItem].asScala

  // Preiodically remove used and outdated requests
  Obs.interval(1.minute).map(_ => System.currentTimeMillis) foreach { now =>
    for (Tuple2(hex, item) <- cache if item.stamp < now - twoHours) cache remove hex
  }

  def getHTLCData(price: MilliSatoshi): Future[PaymentData] = Future {
    ("nodeId" getBytes "UTF-8", "preimage" getBytes "UTF-8")
  }

  // If item is found, ask ln server for a payment data
  def getInvoice(tokens: ListStr, sesKey: String): Option[FutureInvoice] =
    for (item <- cache get sesKey) yield getHTLCData(values.price).map { case (nodeId, preimage) =>
      db.putPendingTokens(data = BlindData(tokens, HEX encode preimage, item.data.toString), sesKey)
      Invoice(None, values.price, nodeId, Crypto sha256 preimage)
    }

  def decodeECPoint(raw: String): ECPoint =
    ECKey.CURVE.getCurve.decodePoint(HEX decode raw)

  // If secret matches, sign client's blind tokens
  def redeemTokens(secret: String, key: String): Option[ListStr] =
    db.getPendingTokens(preimage = secret, sesPubKey = key) map { bd =>
      for (token <- bd.tokens) yield signer.blindSign(token, bd.k).toString
    }
}