package com.btcontract.lncloud

import java.awt.image.BufferedImage._
import org.json4s.jackson.JsonMethods._

import com.btcontract.lncloud.crypto.{AES, RandomGenerator}
import com.google.zxing.{BarcodeFormat, EncodeHintType}
import rx.lang.scala.{Scheduler, Observable => Obs}
import java.net.{InetSocketAddress, SocketAddress}
import org.bitcoinj.core.{ECKey, Sha256Hash}
import courier.{Envelope, Mailer, Text}

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import concurrent.ExecutionContext.Implicits.global
import org.bitcoinj.core.ECKey.ECDSASignature
import com.google.zxing.qrcode.QRCodeWriter
import javax.mail.internet.InternetAddress
import com.btcontract.lncloud.Utils.Bytes
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import org.bitcoinj.core.Utils.HEX
import org.slf4j.LoggerFactory
import javax.imageio.ImageIO
import java.math.BigInteger


object Utils {
  type Bytes = Array[Byte]
  type SeqString = Seq[String]

  var values: Vals = _
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  lazy val params = org.bitcoinj.params.MainNetParams.get

  val hex2Json = (raw: String) => new String(HEX decode raw, "UTF-8")
  val logger = LoggerFactory getLogger "LNCloud"
  val rand = new RandomGenerator
  val oneHour = 3600000

  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*) = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*) = args.map(src andThen fn)
  def getIp(sock: SocketAddress) = sock.asInstanceOf[InetSocketAddress].getAddress.getHostAddress
  def toClass[T : Manifest](raw: String) = parse(raw, useBigDecimalForDouble = true).extract[T]
  def anyway[T, V](execute: => V): PartialFunction[T, V] = { case _ => execute }
  def uid = HEX.encode(rand getBytes 64)
}

object JsonHttpUtils {
  def obsOn[T](provider: => T, scheduler: Scheduler) =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)
}

object QRGen {
  val writer = new QRCodeWriter
  val hints = new java.util.Hashtable[EncodeHintType, Any]
  hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
  hints.put(EncodeHintType.MARGIN, 1)

  def get(txt: String, size: Int) = {
    val bitMatrix = writer.encode(txt, BarcodeFormat.QR_CODE, size, size, hints)
    val (wid, height) = (bitMatrix.getWidth, bitMatrix.getHeight)
    val pixels = new Array[Int](wid * height)

    for (y <- 0 until height) for (x <- 0 until wid)
      pixels(y * wid + x) = bitMatrix.get(x, y) match {
        case true => 0xFF000000 case false => 0xFFFFFFFF
      }

    val outStream = new ByteArrayOutputStream
    val bufImg = new BufferedImage(wid, height, TYPE_BYTE_GRAY)
    bufImg.setRGB(0, 0, wid, height, pixels, 0, wid)
    ImageIO.write(bufImg, "png", outStream)
    outStream.toByteArray
  }
}

case class BlindData(tokens: Seq[String], rval: String, k: String) {
  // tokens is a list of yet unsigned blind BigInts provided from client
  // k is session private key, a source for signerR
  val kBigInt = new BigInteger(k)
}

// A "response-to" ephemeral key, it's private part should be stored in a database
// because my bloom filter has it, it's optional because Charge may come locally via NFC
case class Ask(ephemeral: Option[Bytes], mSatAmount: Long, message: String, id: String)
case class Charge(ask: Ask, lnPaymentData: Bytes)

// Clients send a message and server adds a timestamp
case class Message(pubKey: Bytes, content: Bytes)
case class Wrap(data: Message, stamp: Long)

// Client and server signed email to key mappings
case class ServerSignedMail(client: SignedMail, signature: String)
case class SignedMail(email: String, pubKey: String, signature: String) {
  def totalHash = Sha256Hash.of(email + pubKey + signature getBytes "UTF-8")
  def identityPubECKey = ECKey.fromPublicOnly(HEX decode pubKey)
  def emailHash = Sha256Hash.of(email getBytes "UTF-8")

  def checkSig = HEX decode signature match { case sig =>
    identityPubECKey.verify(emailHash, ECDSASignature decodeFromDER sig)
  }
}

// Utility classes
case class CacheItem[T](data: T, stamp: Long)
case class BlindAsk(privKey: BigInteger, quantity: Int, price: Long)

// Prefix is first 16 bytes of txId, key is last 16 bytes
case class WatchdogTx(prefix: String, txEnc: String, iv: String) {
  def decodeTx(key: Bytes) = AES.dec(HEX decode txEnc, key, HEX decode iv)
}

// Sending emails
case class EmailParams(server: String, account: String, password: String) {
  def mailer = Mailer(server, 587).auth(true).as(account, password).startTtls(true).apply
  def to(adr: String) = Envelope from new InternetAddress(account, "LN cloud") to new InternetAddress(adr)
  def notifyError(message: String) = mailer(to(account) content Text(message) subject "Malfunction")
}

// Server secrets and parameters, MUST NOT be stored in config file
case class Vals(emailParams: EmailParams, emailPrivKey: BigInteger, blindAsk: BlindAsk,
                storagePeriod: Int, sockIpLimit: Int, maxMessageSize: Int, rpcUrl: String)