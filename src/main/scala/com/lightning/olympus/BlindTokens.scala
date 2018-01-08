package com.lightning.olympus

import spray.json._
import com.lightning.wallet.ln._
import collection.JavaConverters._
import spray.json.DefaultJsonProtocol._
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import com.github.kevinsawicki.http.HttpRequest
import com.lightning.olympus.crypto.ECBlindSign
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import org.spongycastle.math.ec.ECPoint
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import java.math.BigInteger
import Utils.values


class BlindTokens { me =>
  type SesKeyCacheItem = CacheItem[BigInteger]
  val signer = new ECBlindSign(values.bigIntegerPrivKey)
  val cache = new ConcurrentHashMap[String, SesKeyCacheItem].asScala
  def decodeECPoint(raw: String): ECPoint = ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
  def sign(data: BlindData) = for (tn <- data.tokens) yield signer.blindSign(new BigInteger(tn), data.k).toString

  // Periodically remove used and outdated requests
  Obs.interval(2.hours).map(_ => System.currentTimeMillis) foreach { now =>
    for (hex \ item <- cache if item.stamp < now - 20.minutes.toMillis) cache remove hex
  }

  def generateInvoice(price: MilliSatoshi): PaymentRequest = {
    val content = s"""{ "params": [${price.amount}, "Storage tokens"], "method": "receive" }"""
    val request = HttpRequest.post(values.eclairApi).connectTimeout(5000).contentType("application/json")
    PaymentRequest read request.send(content).body.parseJson.asJsObject.fields("result").convertTo[String]
  }

  def isFulfilled(hash: BinaryData): Boolean = {
    val content = s"""{ "params": ["${hash.toString}"], "method": "checkpayment" }"""
    val request = HttpRequest.post(values.eclairApi).connectTimeout(5000).contentType("application/json")
    request.send(content).body.parseJson.asJsObject.fields("result").convertTo[Boolean]
  }
}