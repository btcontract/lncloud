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
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import scala.concurrent.Future
import java.math.BigInteger


class BlindTokens(db: Database) {
  type PaymentData = (Bytes, Bytes)
  type SesKeyCacheItem = CacheItem[BigInteger]

  val signer = new ECBlindSign(values.privKey.bigInteger)
  val cache = new ConcurrentHashMap[String, SesKeyCacheItem].asScala
  Obs.interval(30.seconds).map(_ => System.currentTimeMillis) foreach { now =>
    for (Tuple2(hex, item) <- cache if item.stamp < now - oneHour) cache remove hex
  }

  lazy val languages = Map(
    "esp" -> s"Compra de ${values.quantity} créditos LN. Ver detalles en www.lightning-wallet.com/credits",
    "eng" -> s"Purchase of ${values.quantity} LN credits. See details at www.lightning-wallet.com/credits",
    "rus" -> s"Покупка ${values.quantity} LN-кредитов. Подробности смотрите на www.lightning-wallet.com/credits",
    "ukr" -> s"Купівля ${values.quantity} LN-кредитів. Подробиці дивіться на www.lightning-wallet.com/credits",
    "cny" -> s"${values.quantity} LN购买学分. www.lightning-wallet.com/credits で詳細を参照してください。",
    "jpn" -> s"${values.quantity} LNクレジットの購入. 查看详情在 www.lightning-wallet.com/credits"
  )

  def getHTLCData(price: MilliSatoshi): Future[PaymentData] = Future {
    ("nodeId" getBytes "UTF-8", "preimage" getBytes "UTF-8")
  }

  def getCharge(tokens: ListStr, lang: String, sesKey: String) =
    for (CacheItem(privKey, stamp) <- cache get sesKey) yield getHTLCData(values.price).map { case (nodeId, preimage) =>
      db.putPendingTokens(data = BlindData(tokens, rval = HEX encode preimage, k = privKey.toString), sesPubKey = sesKey)
      Invoice(languages.get(lang) orElse languages.get("eng"), values.price, nodeId, Crypto sha256 preimage)
    }

  def decodeECPoint(raw: String) = ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
  def redeemTokens(rVal: String, key: String) = db.getPendingTokens(rVal, key) map { bd =>
    for (token <- bd.tokens) yield signer.blindSign(new BigInteger(token), bd.kBigInt).toString
  }
}