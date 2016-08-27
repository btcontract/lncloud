package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import collection.JavaConverters.mapAsScalaConcurrentMapConverter
import concurrent.ExecutionContext.Implicits.global
import com.btcontract.lncloud.crypto.ECBlindSign
import com.btcontract.lncloud.database.Database
import java.util.concurrent.ConcurrentHashMap
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import scala.concurrent.Future
import java.math.BigInteger


class BlindTokens(db: Database) {
  val cache = new ConcurrentHashMap[String, SessionKeyCacheItem].asScala
  val signer = new ECBlindSign(values.blindParams.privKey)
  type SessionKeyCacheItem = CacheItem[BigInteger]

  val languages = Map.empty updated
    ("eng", "Blind signatures purchase") updated
    ("rus", "Покупка слепых подписей")

  def getHTLCData: Future[proto.payment_data] = ???

  def getCharge(tokens: SeqString, lang: String, sesKey: String) =
    for (CacheItem(privKey, stamp) <- cache get sesKey) yield getHTLCData map { payData =>
      db.putPendingTokens(BlindData(tokens, HEX encode payData.r.encode, privKey.toString), sesKey)
      val purposeDescription = languages.getOrElse(key = lang, default = languages apply "eng")
      val request = Request(None, values.blindParams.price, purposeDescription, uid)
      Charge(request, payData.htlc.encode)
    }

  def redeemTokens(rVal: String, key: String) = db.getPendingTokens(rVal, key) map { bd =>
    for (blindToken <- bd.tokens) yield signer.blindSign(new BigInteger(blindToken), bd.kBigInt)
  }

  def verifyClearSig(token: BigInteger, sig: BigInteger, keyPoint: Bytes) =
    signer.verifyClearSignature(token, sig, ECKey.CURVE.getCurve decodePoint keyPoint)
}
