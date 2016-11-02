package com.btcontract.lncloud

import Utils._
import org.http4s.dsl._
import org.http4s.{HttpService, Response}

import com.btcontract.lncloud.database.MongoDatabase
import concurrent.ExecutionContext.Implicits.global
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.jackson.Serialization
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import java.math.BigInteger


object LNCloud extends App {
  // Config should be provided via console call,
  // don't forget to add space before command
  // to disable history

  args match {
    case Array("generateConfig") =>
      val testKeys = new ECKey(rand).getPublicKeyAsHex :: Nil
      val btcRpc = "http://bitcoinrpc:4T2C2oDSMiuQvYHhyRNjU5japkyYrYTASBbJpyY38FSZ@127.0.0.1:8332"
      val config = Vals(privKey = new ECKey(rand).getPrivKey, testKeys, MSat(50000), quantity = 100, btcRpc)

      // Print out an example
      println(Serialization write config)

    case /*Array(config)*/ _ =>
      val testKey = "022717bbe78bf577c516ac27ab15f85d1ba189725beb4181ddb3049ad5c5837251"
      val btcRpc = "http://bitcoinrpc:4T2C2oDSMiuQvYHhyRNjU5japkyYrYTASBbJpyY38FSZ@127.0.0.1:8332"
      val config = Vals(privKey = new ECKey(rand).getPrivKey, List(testKey), MSat(50000), quantity = 100, btcRpc)

      values = config /*toClass[Vals](config)*/
      val socketAndHttpLnCloudServer = new Server
      val postLift = UrlFormLifter(socketAndHttpLnCloudServer.http)
      BlazeBuilder.bindHttp(9002).mountService(postLift).run.awaitShutdown
  }
}

class Server {
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  val db = new MongoDatabase
  private val txSigChecker = new TxSigChecker
  private val blindTokens = new BlindTokens(db)
  private val watchdog = new Watchdog(db)
  watchdog.run

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case req @ POST -> Root / "blindtokens" / "info" => new ECKey(rand) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
    }

    // Record tokens to be signed and send a Charge
    case req @ POST -> Root / "blindtokens" / "buy" =>
      val Seq(lang, sesKey, tokens) = extract(req.params, identity, "lang", "seskey", "tokens")
      val maybeInvoice = blindTokens.getCharge(toClass[ListStr](hex2Json apply tokens), lang, sesKey)

      maybeInvoice match {
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

    // BREACH TXS

    // If they try to supply way too much data
    case req @ POST -> Root / "tx" / "breach" / _
      if req.params("watch").length > 2048 =>
      Ok apply error("toobig")

    // Record a transaction to be broadcasted in case of channel breach
    case req @ POST -> Root / "token" / "tx" / "breach" => ifToken(req.params) {
      db putWatchdogTx toClass[WatchdogTx](req.params andThen hex2Json apply "data")
      Ok apply okSingle("done")
    }

    // Same as above but without blind sigs
    case req @ POST -> Root / "sig" / "tx" / "breach" => ifSig(req.params) {
      db putWatchdogTx toClass[WatchdogTx](req.params andThen hex2Json apply "data")
      Ok apply okSingle("done")
    }

    // Checking if signature based authentication works
    case req @ POST -> Root / "sig" / "check" => ifSig(req.params) {
      // Will be used once user adds a server address in wallet, just ok
      Ok apply okSingle("done")
    }

    // DATA STORAGE

    // Short keys are reserved
    case req @ POST -> Root / "data" / "put"
      if req.params("key").length < 20 =>
      Ok apply error("tooshort")

    case req @ POST -> Root / "data" / "put" => ifToken(req.params) {
      // Reqrites user's channel data, can be used for general purposes
      db.putGeneralData(req params "key", req params "data")
      Ok apply okSingle("done")
    }

    case req @ POST -> Root / "data" / "get" =>
      db.getGeneralData(key = req params "key") match {
        case Some(realData) => Ok apply okSingle(realData)
        case None => Ok apply error("notfound")
      }
  }

  // Checking clear token validity before proceeding
  def ifToken(params: HttpParams)(next: => TaskResponse) =  {
    val Seq(point, sig, token) = extract(params, identity, "point", "clearsig", "cleartoken")
    val sigIsFine = blindTokens.signer.verifyClearSig(clearMessage = new BigInteger(token),
      clearSignature = new BigInteger(sig), point = blindTokens decodeECPoint point)

    if (db isClearTokenUsed token) Ok apply error("tokenused")
    else if (!sigIsFine) Ok apply error("tokeninvalid")
    else try next finally db putClearToken token
  }

  // Checking signature validity before proceeding
  def ifSig(params: HttpParams)(next: => TaskResponse) = {
    val Seq(data, sig, prefix) = extract(params, identity, "data", "sig", "prefix")
    val isValidSignature = txSigChecker.check(msg = data, sig, prefix)
    if (isValidSignature) next else Ok apply error("errsig")
  }

  // HTTP answer as JSON array
  def okSingle(data: Any) = ok(data)
  def ok(data: Any*) = Serialization write "ok" +: data
  def error(data: Any*) = Serialization write "error" +: data
}