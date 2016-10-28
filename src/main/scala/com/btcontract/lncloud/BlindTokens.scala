package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import rx.lang.scala.{Observable => Obs}

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
import okio.ByteString


class BlindTokens(db: Database) {
  type SesKeyCacheItem = CacheItem[BigInteger]
  val signer = new ECBlindSign(masterPriv = values.privKey)
  val cache = new ConcurrentHashMap[String, SesKeyCacheItem].asScala

  // Periodically remove stale info requests
  Obs.interval(30.seconds).map(_ => System.currentTimeMillis) foreach { now =>
    for (Tuple2(hex, item) <- cache if item.stamp < now - oneHour) cache remove hex
  }

  lazy val languages = Map (
    "esp" -> s"Compra de ${values.quantity} créditos LN. Ver detalles en www.lncloud.com/lncredits",
    "eng" -> s"Purchase of ${values.quantity} LN credits. See details at www.lncloud.com/lncredits",
    "rus" -> s"Покупка ${values.quantity} LN-кредитов. Подробности смотрите на www.lncloud.com/lncredits",
    "ukr" -> s"Купівля ${values.quantity} LN-кредитів. Подробиці дивіться на www.lncloud.com/lncredits",
    "cny" -> s"${values.quantity} LN购买学分. www.lncloud.com/lncredits で詳細を参照してください。",
    "jpn" -> s"${values.quantity} LNクレジットの購入. 查看详情在 www.lncloud.com/lncredits"
  )

  def getHTLCData: Future[proto.payment_data] = {
    val rVal = new proto.rval(200L, 200L, 200L, 200L)
    val hash = new proto.sha256_hash(100L, 100L, 100L, 100L)
    val lock = new proto.locktime(null, 100)

    val route = ByteString.of("test" getBytes "UTF-8":_*)
    val routing = new proto.routing(route)

    val htlc = new proto.update_add_htlc(1L, 40000000, hash, lock, routing)
    Future apply new proto.payment_data(htlc, rVal)
  }

  def getCharge(tokens: ListStr, lang: String, sesKey: String) =
    for (CacheItem(privKey, stamp) <- cache get sesKey) yield getHTLCData.map { payData =>
      db.putPendingTokens(BlindData(tokens, HEX encode payData.r.encode, privKey.toString), sesKey)
      Invoice(languages.get(lang) orElse languages.get("eng"), values.price, "nodeId", "rHash" getBytes "UTF-8")
    }

  def decodeECPoint(raw: String) = ECKey.CURVE.getCurve.decodePoint(HEX decode raw)
  def redeemTokens(rVal: String, key: String) = db.getPendingTokens(rVal, key) map { bd =>
    for (token <- bd.tokens) yield signer.blindSign(new BigInteger(token), bd.kBigInt).toString
  }
}