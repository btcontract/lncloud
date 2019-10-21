package com.lightning.olympus

import spray.json._
import scala.concurrent.duration._
import com.lightning.olympus.Utils._
import com.lightning.olympus.JsonHttpUtils._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import scala.collection.JavaConverters.mapAsJavaMapConverter
import com.lightning.olympus.database.MongoDatabase
import com.lightning.walletapp.ln.PaymentRequest
import com.github.kevinsawicki.http.HttpRequest
import rx.lang.scala.schedulers.IOScheduler
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import wf.bitcoin.javabitcoindrpcclient
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger
import java.net.URLEncoder
import scala.util.Try


object Utils {
  var values: Vals = _
  type StringSet = Set[String]
  type StringVec = Vector[String]

  val db = new MongoDatabase
  val blockchain = new Blockchain(db)
  val hex2String: String => String = raw => new String(HEX decode raw, "UTF-8")
  lazy val bitcoin = new javabitcoindrpcclient.BitcoinJSONRPCClient(values.btcApi)

  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
}

object JsonHttpUtils {
  import rx.lang.scala.{Observable => Obs}
  def initDelay[T](next: Obs[T], startMillis: Long, timeoutMillis: Long) = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 0L) 0L else adjustedTimeout
    Obs.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def obsOnIO = Obs just null subscribeOn IOScheduler.apply
  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range) =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)

  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  def pickInc(error: Throwable, next: Int) = next.seconds
}

// k is session private key, a source for signerR, tokens is a list of unsigned blind BigInts
case class BlindData(paymentHash: String, id: String, k: BigInteger, tokens: StringVec)

case class CacheItem[T](data: T, stamp: Long)
case class Vals(privKey: String, btcApi: String, zmqApi: String, eclairSockIp: String, eclairSockPort: Int,
                eclairNodeId: String, rewindRange: Int, ip: String, port: Int, paymentProvider: PaymentProvider,
                minCapacity: Long, sslFile: String, sslPass: String) {

  lazy val eclairNodePubKey = PublicKey fromValidHex eclairNodeId
  lazy val bigIntegerPrivKey = new BigInteger(privKey)
}

trait PaymentProvider {
  def isPaid(data: BlindData): Boolean
  def generateInvoice: Charge

  val quantity: Int
  val priceMsat: Long
  val description: String
  val url: String
}

case class Charge(paymentHash: String, id: String, paymentRequest: String, paid: Boolean)
case class StrikeProvider(priceMsat: Long, quantity: Int, description: String, url: String, privKey: String) extends PaymentProvider {
  def isPaid(bd: BlindData) = to[Charge](HttpRequest.get(url + "/" + bd.id).basic(privKey, "").userAgent("curl/7.47.0").connectTimeout(10000).body).paid
  def generateInvoice = to[Charge](HttpRequest.post(url).basic(privKey, "").userAgent("curl/7.47.0").form(parameters).connectTimeout(10000).body)
  val parameters = Map("amount" -> (priceMsat / 1000L).toString, "currency" -> "btc", "description" -> description).asJava
}

case class EclairProvider(priceMsat: Long, quantity: Int, description: String, url: String, pass: String) extends PaymentProvider {
  def request(way: String) = HttpRequest.post(s"$url/$way").basic("eclair-cli", pass).connectTimeout(5000)

  def generateInvoice: Charge = {
    val encodedDescription = URLEncoder.encode(description, "UTF-8")
    val req = request("createinvoice").send(s"description=$encodedDescription&amountMsat=$priceMsat")
    val serializedPaymentRequest = req.body.parseJson.asJsObject.fields("serialized").convertTo[String]
    val payHash = PaymentRequest.read(serializedPaymentRequest).paymentHash.toHex
    Charge(payHash, payHash, serializedPaymentRequest, paid = false)
  }

  def isPaid(data: BlindData): Boolean = Try {
    val req = request("getreceivedinfo").send(s"paymentHash=${data.paymentHash}")
    req.body.parseJson.asJsObject.fields("status").asJsObject.fields("receivedAt").convertTo[Long] > 0L
  }.isSuccess
}