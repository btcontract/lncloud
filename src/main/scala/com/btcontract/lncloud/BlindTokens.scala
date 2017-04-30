package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}

import collection.JavaConverters.mapAsScalaConcurrentMapConverter
import concurrent.ExecutionContext.Implicits.global
import com.btcontract.lncloud.crypto.ECBlindSign
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import fr.acinq.bitcoin.Crypto.PublicKey
import org.spongycastle.math.ec.ECPoint
import com.lightning.wallet.ln.Invoice
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import scala.concurrent.Future
import java.math.BigInteger


class BlindTokens { me =>
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
    Invoice(None, PublicKey("nodeId" getBytes "UTF-8"), price, "hash" getBytes "UTF-8")
  }

  def getBlind(tokens: TokenSeq, k: BigInteger): Future[BlindData] =
    for (invoice: Invoice <- me generateInvoice values.price)
      yield BlindData(invoice, k, tokens)

  def signTokens(bd: BlindData): TokenSeq = for (token <- bd.tokens)
    yield signer.blindSign(new BigInteger(token), bd.k).toString

  def decodeECPoint(raw: String): ECPoint =
    ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
}