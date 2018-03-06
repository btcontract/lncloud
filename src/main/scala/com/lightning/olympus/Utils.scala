package com.lightning.olympus

import spray.json._
import scala.concurrent.duration._
import com.lightning.olympus.Utils._
import rx.lang.scala.{Scheduler, Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}

import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import wf.bitcoin.javabitcoindrpcclient
import com.lightning.wallet.ln.Tools
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  var values: Vals = _
  type StringSet = Set[String]
  type StringVec = Vector[String]

  val hex2Ascii: String => String = raw => new String(HEX decode raw, "UTF-8")
  lazy val bitcoin = new javabitcoindrpcclient.BitcoinJSONRPCClient(values.btcApi)
  implicit def string2PublicKey(raw: String): PublicKey = PublicKey(BinaryData apply raw)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def errLog: PartialFunction[Throwable, Unit] = { case err => Tools log err.getMessage }
}

object JsonHttpUtils {
  def initDelay[T](next: Obs[T], startMillis: Long, timeoutMillis: Long) = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 0) 0L else adjustedTimeout
    Obs.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def obsOn[T](provider: => T, scheduler: Scheduler) =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)

  def pickInc(err: Throwable, next: Int) = next.seconds
  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range) =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client

case class CacheItem[T](data: T, stamp: Long)
case class BlindData(paymentHash: BinaryData, k: BigInteger, tokens: StringVec)
case class Vals(privKey: String, price: MilliSatoshi, quantity: Int, btcApi: String,
                zmqApi: String, eclairApi: String, eclairSockIp: String, eclairSockPort: Int,
                eclairNodeId: String, eclairPass: String, rewindRange: Int, ip: String) {

  lazy val bigIntegerPrivKey = new BigInteger(privKey)
  lazy val eclairNodePubKey = PublicKey(eclairNodeId)
}