package com.lightning.olympus

import spray.json._
import com.lightning.wallet.ln._
import collection.JavaConverters._
import spray.json.DefaultJsonProtocol._
import rx.lang.scala.{Observable => Obs}
import com.github.kevinsawicki.http.HttpRequest
import com.lightning.olympus.crypto.ECBlindSign
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import org.spongycastle.math.ec.ECPoint
import fr.acinq.bitcoin.MilliSatoshi
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

  def request = HttpRequest.post(values.eclairApi)
    .basic("eclair-cli", values.eclairPass)
    .contentType("application/json")
    .connectTimeout(5000)

  def generateInvoice(price: MilliSatoshi): PaymentRequest = {
    val content = s"""{ "params": [${price.amount}, "Storage tokens"], "method": "receive" }"""
    PaymentRequest read request.send(content).body.parseJson.asJsObject.fields("result").convertTo[String]
  }

  def isFulfilled(data: BlindData): Boolean = {
    val content = s"""{ "params": ["${data.paymentHash.toString}"], "method": "checkpayment" }"""
    request.send(content).body.parseJson.asJsObject.fields("result").convertTo[Boolean]
  }
}