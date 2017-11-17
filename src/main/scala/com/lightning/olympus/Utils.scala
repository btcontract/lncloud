package com.lightning.olympus

import scala.concurrent.duration._
import com.lightning.olympus.Utils._
import org.json4s.jackson.JsonMethods._
import rx.lang.scala.{Scheduler, Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}

import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import wf.bitcoin.javabitcoindrpcclient
import com.lightning.wallet.ln.Tools
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger
import java.net.InetAddress


object Utils {
  var values: Vals = _
  type StringSeq = Seq[String]

  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new javabitcoindrpcclient.BitcoinJSONRPCClient(values.btcApi)
  val hex2Ascii: String => String = raw => new String(HEX decode raw, "UTF-8")

  implicit def string2PublicKey(raw: String): PublicKey = PublicKey(BinaryData apply raw)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def toClass[T : Manifest](raw: String): T = parse(raw, useBigDecimalForDouble = true).extract[T]
  def errLog: PartialFunction[Throwable, Unit] = { case err => Tools log err.getMessage }
}

object JsonHttpUtils {
  def initDelay[T](next: Obs[T], startMillis: Long, timeoutMillis: Long) = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 0) 0 else adjustedTimeout
    Obs.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def obsOn[T](provider: => T, scheduler: Scheduler): Obs[T] =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)

  def pickInc(err: Throwable, next: Int) = next.seconds
  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range): Obs[T] =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client

case class CacheItem[T](data: T, stamp: Long)
case class BlindData(paymentHash: BinaryData, k: BigInteger, tokens: StringSeq)
case class Vals(privKey: String, price: MilliSatoshi, quantity: Int, btcApi: String,
                zmqApi: String, eclairApi: String, eclairSockIp: String, eclairSockPort: Int,
                eclairNodeId: String, rewindRange: Int, ip: String, checkByToken: Boolean) {

  lazy val allowedIp = InetAddress.getByName(ip)
  lazy val bigIntegerPrivKey = new BigInteger(privKey)
  lazy val eclairNodePubKey = PublicKey(eclairNodeId)
}