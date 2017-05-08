package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import org.json4s.jackson.JsonMethods._
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import collection.JavaConverters.mapAsScalaConcurrentMapConverter
import com.btcontract.lncloud.crypto.ECBlindSign
import com.github.kevinsawicki.http.HttpRequest
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import org.json4s.jackson.Serialization
import org.spongycastle.math.ec.ECPoint
import com.lightning.wallet.ln.Invoice
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import java.math.BigInteger


class BlindTokens { me =>
  type StampOpt = Option[Long]
  type SesKeyCacheItem = CacheItem[BigInteger]
  val signer: ECBlindSign = new ECBlindSign(values.privKey.bigInteger)
  val cache: collection.concurrent.Map[String, SesKeyCacheItem] = new ConcurrentHashMap[String, SesKeyCacheItem].asScala
  private def rpcRequest = HttpRequest.post(values.eclairUrl).connectTimeout(5000).contentType("application/json")

  // Periodically remove used and outdated requests
  Obs.interval(2.hours).map(_ => System.currentTimeMillis) foreach { now =>
    for (Tuple2(hex, item) <- cache if item.stamp < now - twoHours) cache remove hex
  }

  def generateInvoice(price: MilliSatoshi): Invoice = {
    val params = Map("params" -> List(price.amount), "method" -> "receive")
    val raw = parse(rpcRequest.send(Serialization write params).body) \ "result"
    Invoice parse raw.values.toString
  }

  def isFulfilled(paymentHash: BinaryData): Boolean = {
    val params = Map("paymentHash" -> List(paymentHash.toString), "method" -> "status")
    val raw = parse(rpcRequest.send(Serialization write params).body) \ "result"
    raw.extract[StampOpt].isDefined
  }

  def decodeECPoint(raw: String): ECPoint =
    ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
}