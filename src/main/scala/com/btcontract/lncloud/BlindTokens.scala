package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}

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
  type FutureCompactInvoice = Future[String]
  type SesKeyCacheItem = CacheItem[BigInteger]
  val signer = new ECBlindSign(values.privKey.bigInteger)
  val cache: collection.concurrent.Map[String, SesKeyCacheItem] =
    new ConcurrentHashMap[String, SesKeyCacheItem].asScala

  // Preiodically remove used and outdated requests
  Obs.interval(1.minute).map(_ => System.currentTimeMillis) foreach { now =>
    for (Tuple2(hex, item) <- cache if item.stamp < now - twoHours) cache remove hex
  }

  def isFulfilled(paymentHash: BinaryData): Future[Boolean] = Future {
    true
  }
  private def generateInvoice(price: MilliSatoshi) = Future {
    Invoice(None, "nodeId" getBytes "UTF-8", price, "hash" getBytes "UTF-8")
  }

  // If item is found, ask an ln node for a payment data
  def getInvoice(tokens: ListStr, sesKey: String): Option[FutureCompactInvoice] =
    for (item <- cache get sesKey) yield generateInvoice(values.price).map { invoice =>
      db.putPendingTokens(BlindData(tokens, item.data.toString), invoice.paymentHash.toString)
      Invoice serialize invoice
    }

  def signTokens(hash: BinaryData): Option[ListStr] =
    db getPendingTokens hash.toString map { case BlindData(tokens, k) =>
      for (token <- tokens) yield signer.blindSign(token, k).toString
    }

  def decodeECPoint(raw: String): ECPoint =
    ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
}