package com.btcontract.lncloud

import Utils._
import org.http4s.dsl._
import io.backchat.hookup._

import rx.lang.scala.{Observable => Obs}
import org.http4s.{HttpService, Response}
import org.bitcoinj.core.{BloomFilter, ECKey}
import org.json4s.{JArray, JInt, JString => JS}

import collection.JavaConverters.mapAsScalaConcurrentMapConverter
import com.btcontract.lncloud.database.MongoDatabase
import concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.jackson.Serialization
import org.bitcoinj.core.Utils.HEX
import scalaz.concurrent.Task
import java.math.BigInteger


object LNCloud extends App {
  // Config should be provided via console call,
  // don't forget to add space before command
  // to disable history

  args match {
    case Array("generateConfig") =>
      val emailPrivKey = new ECKey(rand).getPrivKey
      val emailParams = EmailParams("smtp.google.com", "from@gmail.com", "password")
      val blindInfo = BlindParams(new ECKey(rand).getPrivKey, quantity = 500, price = 50000)
      val config = Vals(emailParams, emailPrivKey, blindInfo, storagePeriod = 7, sockIpLimit = 1000,
        maxMessageSize = 5000, "http://bitcoinrpc:4T2C2oDSMiuQvYHhyRNjU5japkyYrYTASBbJpyY38FSZ@127.0.0.1:8332")

      // Print configuration to console
      println(Serialization write config)

    case Array(config, "email") =>
      values = toClass[Vals](raw = config)
      values.emailParams.notifyError("It works")

    case Array(config) =>
      values = toClass[Vals](config)
      val socketAndHttpLnCloudServer = new Server
      HookupServer(9001)(new socketAndHttpLnCloudServer.Client).start
      BlazeBuilder.bindHttp(9002).mountService(socketAndHttpLnCloudServer.http).run
  }
}

class Server {
  type Hooks = Set[Client]
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  val db = new MongoDatabase
  private val wraps = new Wraps(db)
  private val emails = new Emails(db)
  private val watchdog = new Watchdog(db)
  private val blindTokens = new BlindTokens(db)

  // Track connected websockets by their ip addresses
  private val connects = new ConcurrentHashMap[String, Hooks]
    .asScala withDefaultValue Set.empty

  Obs.interval(30.seconds).map(increment => System.currentTimeMillis) foreach { now =>
    for (Tuple2(hex, item) <- blindTokens.cache) if (item.stamp < now - oneDay / 6) blindTokens.cache remove hex
    for (Tuple2(secret, item) <- emails.cache) if (item.stamp < now - oneDay) emails.cache remove secret
    for (Tuple2(ip, hooks) <- connects if hooks.isEmpty) connects remove ip
    wraps clean System.currentTimeMillis
    logger info "Cleaned caches"
  }

  // Checking clear token validity before proceeding
  def check(params: HttpParams)(next: => TaskResponse) = {
    val Seq(token, sig, key) = extract(params, identity, "cleartoken", "clearsig", "key")
    val signatureOk = blindTokens.verifyClearSig(new BigInteger(token), new BigInteger(sig), HEX decode key)

    if (db isClearTokenUsed token) Ok apply error("tokenused")
    else if (!signatureOk) Ok apply error("tokeninvalid")
    else try next finally db putClearToken token
  }

  // Send only to those online clients who have a matching bloom filter
  def onlineBroadcast(wrap: Wrap) = for { hook <- connects.values.flatten
    filter <- hook.bloomFilter if filter contains wrap.data.pubKey
  } hook send okSingle(wrap)

  // HTTP answer as JSON array
  def okSingle(data: Any) = ok(data)
  def ok(data: Any*) = Serialization write "ok" +: data
  def error(data: Any*) = Serialization write "error" +: data

  val http = HttpService {

    // IDENTIY <-> EMAIL MAPPER

    // Request for a new email <-> key mapping
    case req @ POST -> Root / "keymail" / "put" => check(req.params) {
      val Seq(lang, data) = extract(req.params, identity, "lang", "data")
      val signed = hex2Json andThen toClass[SignedMail] apply data

      if (!signed.checkSig) Ok apply error("mismatch") else uid match { case secret =>
        emails.cache(secret) = CacheItem(data = signed, stamp = System.currentTimeMillis)
        emails.sendEmail(secret, lang, address = signed.email)
        Ok apply okSingle("done")
      }
    }

    // Save ServerSignedMail if secret matches
    case req @ POST -> Root / "keymail" / "confirm" =>
      req.params andThen emails.confirmEmail apply "secret" match {
        case Some(servSignedMail) => Ok apply okSingle(servSignedMail.client)
        case None => Ok apply error("notfound")
      }

    // Get ServerSignedMail by key or email
    case req @ POST -> Root / "keymail" / "get" =>
      req.params andThen db.getSignedMail apply "value" match {
        case Some(ssm) if emails servSigOk ssm => Ok apply okSingle(ssm)
        case Some(sigMismatch) => Ok apply error("notfound")
        case None => Ok apply error("notfound")
      }

    // BLIND TOKENS

    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case req @ POST -> Root / "blindtokens" / "info" => new ECKey(rand) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(data = ses.getPrivKey, stamp = System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.blindParams.quantity, values.blindParams.price)
    }

    // Record tokens to be signed and send a Charge
    case req @ POST -> Root / "blindtokens" / "buy" =>
      val Seq(lang, sesKey, tokens) = extract(req.params, identity, "lang", "seskey", "tokens")
      val maybeCharge = blindTokens.getCharge(hex2Json andThen toClass[SeqString] apply tokens, lang, sesKey)

      maybeCharge match {
        case Some(future) => Ok(future map okSingle)
        case None => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> Root / "blindtokens" / "redeem" =>
      val Seq(rVal, sesKey) = extract(req.params, identity, "rval", "seskey")
      val maybeBlindTokens = blindTokens.redeemTokens(rVal, sesKey)

      maybeBlindTokens match {
        case Some(tokens) => Ok apply ok(tokens:_*)
        case None => Ok apply error("notfound")
      }

    // BREACH AND UNICLOSE TXS

    // Record a tx to be broadcasted at a given height
    case req @ POST -> Root / "tx" / "putat" => check(req.params) {
      val Seq(tx, height) = extract(req.params, identity, "tx", "height")
      db.putDelayTx(txHex = tx, height.toInt)
      Ok apply okSingle("done")
    }

    // Record a tx to be broadcasted on channel breach
    case req @ POST -> Root / "tx" / "putbreach" => check(req.params) {
      db.putWatchdogTx(req.params andThen toClass[WatchdogTx] apply "tx")
      Ok apply okSingle("done")
    }

    // REQUESTS AND CHARGES

    // Reject a message if it is too large
    case req @ POST -> Root / "message" / "put" if
    req.params("data").length > values.maxMessageSize =>
      Ok apply error("toolarge")

    // Send an encrypted message to some public key's mask
    case req @ POST -> Root / "message" / "put" => check(req.params) {
      val message = req.params andThen hex2Json andThen toClass[Message] apply "msg"
      val msgWrap = Wrap(data = message, stamp = System.currentTimeMillis)
      msgWrap >> (onlineBroadcast, wraps.putWrap, db.putWrap)
      Ok apply okSingle(msgWrap)
    }
  }

  class Client extends HookupServerClient {
    def parseJson(data: JArray) = data.children match {
      case JS("syncFilter") :: JS(filter) :: JInt(time) :: Nil =>
        val newBloomFilter = new BloomFilter(params, HEX decode filter)
        this send ok(wraps.get(newBloomFilter, time.toLong):_*)
        bloomFilter = Some apply newBloomFilter

      // Got array with wrong contents
      case _ => this send error("wrongformat")
    }

    def receive = {
      case Connected if connects(ip).size > values.sockIpLimit =>
        this send error("limit") onComplete anyway(disconnect)

      case Connected => connects(ip) += this
      case JsonMessage(data: JArray) => parseJson(data)
      case Disconnected(why) => connects(ip) -= this
    }

    // None of these is available at start
    var bloomFilter = Option.empty[BloomFilter]
    lazy val ip = getIp(remoteAddress)
  }
}