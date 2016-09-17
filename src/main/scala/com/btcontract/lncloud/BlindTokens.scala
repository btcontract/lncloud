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
import okio.ByteString


class BlindTokens(db: Database) {
  val cache = new ConcurrentHashMap[String, SessionKeyCacheItem].asScala
  val signer = new ECBlindSign(values.blindAsk.privKey)
  type SessionKeyCacheItem = CacheItem[BigInteger]

  lazy val languages = Map.empty updated
    ("eng", s"Purchase of ${values.blindAsk.quantity} LN credits") updated
    ("rus", s"Покупка ${values.blindAsk.quantity} LN-кредитов")

  def getHTLCData: Future[proto.payment_data] = {
    val rVal = new proto.rval(200L, 200L, 200L, 200L)
    val hash = new proto.sha256_hash(100L, 100L, 100L, 100L)
    val lock = new proto.locktime(null, 100)

    val route = ByteString.of("test" getBytes "UTF-8":_*)
    val routing = new proto.routing(route)

    val htlc = new proto.update_add_htlc(1L, 40000000, hash, lock, routing)
    Future apply new proto.payment_data(htlc, rVal)
  }

  def getCharge(tokens: SeqString, lang: String, sesKey: String) =
    for (CacheItem(privKey, stamp) <- cache get sesKey) yield getHTLCData map { payData =>
      db.putPendingTokens(BlindData(tokens, HEX encode payData.r.encode, privKey.toString), sesKey)
      val ask = Ask(None, values.blindAsk.price, languages.getOrElse(lang, languages apply "eng"), uid)
      Charge(ask, payData.htlc.encode)
    }

  def redeemTokens(rVal: String, key: String) = db.getPendingTokens(rVal, key) map { bd =>
    for (blindToken <- bd.tokens) yield signer.blindSign(new BigInteger(blindToken), bd.kBigInt).toString
  }

  def verifyClearSig(token: BigInteger, sig: BigInteger, keyPoint: Bytes) =
    signer.verifyClearSignature(token, sig, ECKey.CURVE.getCurve decodePoint keyPoint)
}
