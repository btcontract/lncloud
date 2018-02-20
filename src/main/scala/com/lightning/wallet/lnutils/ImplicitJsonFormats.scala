package com.lightning.wallet.lnutils

import spray.json._
import com.lightning.wallet.ln._
import spray.json.DefaultJsonProtocol._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi, Transaction}
import com.lightning.wallet.ln.Tools.Bytes
import com.lightning.olympus.Vals
import scodec.bits.BitVector
import java.math.BigInteger
import scodec.Codec


object ImplicitJsonFormats { me =>
  val json2String = (_: JsValue).convertTo[String]
  def json2BitVec(json: JsValue): Option[BitVector] = BitVector fromHex json2String(json)
  def sCodecJsonFmt[T](codec: Codec[T] /* Json <-> sCodec bridge */) = new JsonFormat[T] {
    def read(serialized: JsValue) = codec.decode(json2BitVec(serialized).get).require.value
    def write(internal: T) = codec.encode(internal).require.toHex.toJson
  }

  implicit object BigIntegerFmt extends JsonFormat[BigInteger] {
    def read(json: JsValue): BigInteger = new BigInteger(me json2String json)
    def write(internal: BigInteger): JsValue = internal.toString.toJson
  }

  implicit object BinaryDataFmt extends JsonFormat[BinaryData] {
    def read(json: JsValue): BinaryData = BinaryData(me json2String json)
    def write(internal: BinaryData): JsValue = internal.toString.toJson
  }

  implicit object TransactionFmt extends JsonFormat[Transaction] {
    def read(json: JsValue): Transaction = Transaction.read(me json2String json)
    def write(internal: Transaction): JsValue = Transaction.write(internal).toString.toJson
  }

  implicit val lightningMessageFmt = sCodecJsonFmt(lightningMessageCodec)
  implicit val nodeAnnouncementFmt = sCodecJsonFmt(nodeAnnouncementCodec)
  implicit val updateFailHtlcFmt = sCodecJsonFmt(updateFailHtlcCodec)
  implicit val acceptChannelFmt = sCodecJsonFmt(acceptChannelCodec)
  implicit val updateAddHtlcFmt = sCodecJsonFmt(updateAddHtlcCodec)
  implicit val closingSignedFmt = sCodecJsonFmt(closingSignedCodec)
  implicit val fundingLockedFmt = sCodecJsonFmt(fundingLockedCodec)
  implicit val channelUpdateFmt = sCodecJsonFmt(channelUpdateCodec)
  implicit val commitSigFmt = sCodecJsonFmt(commitSigCodec)
  implicit val shutdownFmt = sCodecJsonFmt(shutdownCodec)
  implicit val uint64exFmt = sCodecJsonFmt(uint64ex)
  implicit val hopFmt = sCodecJsonFmt(hopCodec)
  implicit val pointFmt = sCodecJsonFmt(point)

  implicit val scalarFmt = jsonFormat[BigInteger, Scalar](Scalar.apply, "value")
  implicit val privateKeyFmt = jsonFormat[Scalar, Boolean, PrivateKey](PrivateKey.apply, "value", "compressed")
  implicit val publicKeyFmt = jsonFormat[Point, Boolean, PublicKey](PublicKey.apply, "value", "compressed")
  implicit val milliSatoshiFmt = jsonFormat[Long, MilliSatoshi](MilliSatoshi.apply, "amount")
  implicit val satoshiFmt = jsonFormat[Long, Satoshi](Satoshi.apply, "amount")

  implicit val valsFmt = jsonFormat[String, MilliSatoshi, Int,
    String, String, String, String, Int, String, String, Int, String, Boolean,
    Vals](Vals.apply, "privKey", "price", "quantity", "btcApi", "zmqApi", "eclairApi",
    "eclairSockIp", "eclairSockPort", "eclairNodeId", "eclairPass", "rewindRange",
    "ip", "checkByToken")
}