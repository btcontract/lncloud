package com.lightning.olympus

import com.lightning.wallet.ln._
import collection.JavaConverters._
import rx.lang.scala.{Observable => Obs}
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
  Obs.interval(1.hour).map(_ => System.currentTimeMillis) foreach { now =>
    for (hex \ item <- cache if item.stamp < now - 1.hour.toMillis) cache remove hex
  }
}