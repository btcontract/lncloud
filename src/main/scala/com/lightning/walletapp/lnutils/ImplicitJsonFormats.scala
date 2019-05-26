package com.lightning.walletapp.lnutils

import spray.json._
import fr.acinq.bitcoin._
import com.lightning.olympus._
import com.lightning.olympus.ExchangeRates._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._
import com.lightning.olympus.Router.PubKeySet
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.bits.BitVector
import java.math.BigInteger
import scodec.Codec


object ImplicitJsonFormats extends DefaultJsonProtocol { me =>
  def json2BitVec(json: JsValue): Option[BitVector] = BitVector fromHex json2String(json)
  def sCodecJsonFmt[T](codec: Codec[T] /* Json <-> sCodec bridge */) = new JsonFormat[T] {
    def read(serialized: JsValue) = codec.decode(json2BitVec(serialized).get).require.value
    def write(internal: T) = codec.encode(internal).require.toHex.toJson
  }

  val json2String = (_: JsValue).convertTo[String]
  def taggedJsonFmt[T](base: JsonFormat[T], tag: String) =
    // Adds an external tag which can be later used to discern
    // different children of the same super class
    new JsonFormat[T] {
      def read(serialized: JsValue) =
        base read serialized

      def write(internal: T) = {
        val extension = "tag" -> JsString(tag)
        val core = base.write(internal).asJsObject
        JsObject(core.fields + extension)
      }
    }

  implicit object BigIntegerFmt extends JsonFormat[BigInteger] {
    def read(json: JsValue): BigInteger = new BigInteger(me json2String json)
    def write(internal: BigInteger): JsValue = internal.toString.toJson
  }

  implicit object TransactionFmt extends JsonFormat[Transaction] {
    def read(json: JsValue): Transaction = Transaction.read(me json2String json)
    def write(internal: Transaction): JsValue = internal.bin.toHex.toJson
  }

  implicit object PublicKeyFmt extends JsonFormat[PublicKey] {
    def read(json: JsValue): PublicKey = PublicKey.fromValidHex(me json2String json)
    def write(internal: PublicKey): JsValue = internal.toBin.toHex.toJson
  }

  implicit val lightningMessageFmt = sCodecJsonFmt(lightningMessageCodec)
  implicit val nodeAnnouncementFmt = sCodecJsonFmt(nodeAnnouncementCodec)
  implicit val updateFailHtlcFmt = sCodecJsonFmt(updateFailHtlcCodec)
  implicit val acceptChannelFmt = sCodecJsonFmt(acceptChannelCodec)
  implicit val closingSignedFmt = sCodecJsonFmt(closingSignedCodec)
  implicit val fundingLockedFmt = sCodecJsonFmt(fundingLockedCodec)
  implicit val channelUpdateFmt = sCodecJsonFmt(channelUpdateCodec)
  implicit val byteVectorFmt = sCodecJsonFmt(scodec.codecs.bytes)
  implicit val commitSigFmt = sCodecJsonFmt(commitSigCodec)
  implicit val shutdownFmt = sCodecJsonFmt(shutdownCodec)
  implicit val uint64exFmt = sCodecJsonFmt(uint64ex)
  implicit val hopFmt = sCodecJsonFmt(hopCodec)
  implicit val pointFmt = sCodecJsonFmt(point)

  implicit val satoshiFmt = jsonFormat[Long, Satoshi](Satoshi.apply, "amount")
  implicit val milliSatoshiFmt = jsonFormat[Long, MilliSatoshi](MilliSatoshi.apply, "amount")
  implicit val chargeFmt = jsonFormat[String, String, String, Boolean, Charge](Charge.apply, "payment_hash", "id", "payment_request", "paid")

  implicit val strikeProviderFmt =
    taggedJsonFmt(jsonFormat[Long, Int, String, String, String,
      StrikeProvider](StrikeProvider.apply, "priceMsat", "quantity",
      "description", "url", "privKey"), tag = "StrikeProvider")

  implicit val eclairProvider =
    taggedJsonFmt(jsonFormat[Long, Int, String, String, String,
      EclairProvider](EclairProvider.apply, "priceMsat", "quantity",
      "description", "url", "pass"), tag = "EclairProvider")

  implicit object PaymentProviderFmt
    extends JsonFormat[PaymentProvider] {

    def read(json: JsValue) = json.asJsObject fields "tag" match {
      case JsString("StrikeProvider") => json.convertTo[StrikeProvider]
      case JsString("EclairProvider") => json.convertTo[EclairProvider]
      case _ => throw new RuntimeException
    }

    def write(internal: PaymentProvider) = internal match {
      case paymentProvider: StrikeProvider => paymentProvider.toJson
      case paymentProvider: EclairProvider => paymentProvider.toJson
      case _ => throw new RuntimeException
    }
  }

  implicit val valsFmt =
    jsonFormat[String, String, String, String, Int,
      String, Int, String, Int, PaymentProvider, Long, String, String,
      Vals](Vals.apply, "privKey", "btcApi", "zmqApi", "eclairSockIp",
      "eclairSockPort", "eclairNodeId", "rewindRange", "ip", "port",
      "paymentProvider", "minCapacity", "sslFile", "sslPass")

  implicit val inRoutesPlusFmt = jsonFormat[Long, PubKeySet, Set[Long], PubKeySet, PublicKey,
    InRoutesPlus](InRoutesPlus.apply, "sat", "badNodes", "badChans", "from", "to")

  implicit val bitpayItemFmt = jsonFormat[String, Double, BitpayItem](BitpayItem.apply, "code", "rate")
  implicit val coinGeckoItemFmt = jsonFormat[String, Double, CoinGeckoItem](CoinGeckoItem.apply, "unit", "value")
  implicit val coinGeckoFmt = jsonFormat[CoinGeckoItemMap, CoinGecko](CoinGecko.apply, "rates")
  implicit val bitpayFmt = jsonFormat[BitpayItemList, Bitpay](Bitpay.apply, "data")
}

case class InRoutesPlus(sat: Long,
                        badNodes: PubKeySet, badChans: Set[Long],
                        from: PubKeySet, to: PublicKey)