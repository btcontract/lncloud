package com.btcontract.lncloud

import com.lightning.wallet.ln._
import collection.JavaConverters._
import com.btcontract.lncloud.Utils._
import org.json4s.jackson.JsonMethods._
import rx.lang.scala.{Observable => Obs}

import com.btcontract.lncloud.crypto.ECBlindSign
import com.github.kevinsawicki.http.HttpRequest
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import org.json4s.jackson.Serialization
import org.spongycastle.math.ec.ECPoint
import fr.acinq.bitcoin.MilliSatoshi
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import java.math.BigInteger


class BlindTokens { me =>
  type SesKeyCacheItem = CacheItem[BigInteger]
  val signer = new ECBlindSign(values.privKey.bigInteger)
  val cache = new ConcurrentHashMap[String, SesKeyCacheItem].asScala

  // Periodically remove used and outdated requests
  Obs.interval(2.hours).map(_ => System.currentTimeMillis) foreach { now =>
    for (hex \ item <- cache if item.stamp < now - 20.minutes.toMillis) cache remove hex
  }

  def generateInvoice(price: MilliSatoshi): PaymentRequest = {
    val params = Map("params" -> List(price.amount, "Storage tokens"), "method" -> "receive")
    val request = HttpRequest.post(values.eclairApi).connectTimeout(5000).contentType("application/json")
    val raw = parse(request.send(Serialization write params).body) \ "result"
    PaymentRequest read raw.values.toString
  }

  def decodeECPoint(raw: String): ECPoint =
    ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
}