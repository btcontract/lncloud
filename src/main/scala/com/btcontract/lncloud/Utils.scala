package com.btcontract.lncloud

import java.awt.image.BufferedImage._

import com.btcontract.lncloud.crypto.RandomGenerator

import scala.concurrent.duration.{Duration, DurationInt}
import com.google.zxing.{BarcodeFormat, EncodeHintType}
import rx.lang.scala.{Scheduler, Observable => Obs}
import org.bitcoinj.core.{ECKey, Sha256Hash}
import concurrent.ExecutionContext.Implicits.global
import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import com.btcontract.lncloud.Utils.Bytes
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage

import org.bitcoinj.core.Utils.HEX
import org.slf4j.LoggerFactory
import javax.imageio.ImageIO
import java.math.BigInteger
import javax.mail.internet.InternetAddress

import courier.{Envelope, Mailer, Text}


object Utils {
  type Bytes = Array[Byte]
  type SeqString = Seq[String]

  var values: Vals = null
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  lazy val params = org.bitcoinj.params.MainNetParams.get
  val logger = LoggerFactory getLogger "LNCloud"
  val rand = new RandomGenerator
  val oneDay = 86400000
}

object JsonHttpUtils {
  def pickInc(err: Throwable, next: Int) = next.second

  def obsOn[T](provider: => T, scheduler: Scheduler) =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)

  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range) =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)
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

trait Cleanable {
  def clean(stamp: Long)
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client
case class BlindData(tokens: Seq[String], rval: String, k: String) {
  def tokensBigInts = for (token <- tokens) yield new BigInteger(token)
  def kBigInt = new BigInteger(k)
}

// A "response-to" ephemeral key, it's private part should be stored in a database
// because my bloom filter has it, it's optional because Charge may come locally via NFC
case class Request(ephemeral: Option[Bytes], mSatAmount: Long, message: String, id: String)
case class Charge(request: Request, lnPaymentData: Bytes)

// Clients send a message and server adds a timestamp
case class Message(pubKey: Bytes, content: Bytes)
case class Wrap(data: Message, stamp: Long)

// Client and server signed email to key mappings
case class ServerSignedMail(client: SignedMail, signature: String)
case class SignedMail(email: String, pubKey: String, signature: String) {
  def totalHash = Sha256Hash.of(email + pubKey + signature getBytes "UTF-8")
  def identityPubECKey = ECKey.fromPublicOnly(HEX decode pubKey)
  def emailHash = Sha256Hash.of(email getBytes "UTF-8")
}

// Utility classes
case class CacheItem[T](data: T, stamp: Long)
case class BlindParams(privKey: BigInteger, quantity: Int, price: Long)
case class WatchdogTx(parentTxId: String, txEnc: String, ivHex: String)
case class EmailParams(server: String, account: String, password: String) {
  def mailer = Mailer(server, 587).auth(true).as(account, password).startTtls(true).apply
  def to(address: String) = Envelope from new InternetAddress(account) to new InternetAddress(address)
  def notifyError(message: String) = mailer(to(account) content Text(message) subject "Malfunction")
}

// Server secrets and parameters, MUST NOT be stored in config file
case class Vals(emailParams: EmailParams, emailPrivKey: BigInteger, blindParams: BlindParams,
                storagePeriod: Int, sockIpLimit: Int, maxMessageSize: Int, rpcUrl: String)