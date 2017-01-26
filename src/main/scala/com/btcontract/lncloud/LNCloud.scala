package com.btcontract.lncloud

import Utils._
import org.http4s.dsl._
import org.http4s.{HttpService, Response}
import com.btcontract.lncloud.database.MongoDatabase
import concurrent.ExecutionContext.Implicits.global
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.jackson.Serialization
import fr.acinq.bitcoin.MilliSatoshi
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
      val config = Vals(new ECKey(rand).getPrivKey, testKeys, MilliSatoshi(500000), quantity = 100, btcRpc)

      // Print out an example
      println(Serialization write config)

    case /*Array(config)*/ _ =>
      val testKey = "0213feda60268053e5fd8aff92f9f9934a51264d0caaf3d883b9d633770fa1b2d9"
      val btcRpc = "http://bitcoinrpc:4T2C2oDSMiuQvYHhyRNjU5japkyYrYTASBbJpyY38FSZ@127.0.0.1:8332"
      val config = Vals(new ECKey(rand).getPrivKey, List(testKey), MilliSatoshi(500000), quantity = 200, btcRpc)

      values = config /*toClass[Vals](config)*/
      val socketAndHttpLnCloudServer = new Server
      val postLift = UrlFormLifter(socketAndHttpLnCloudServer.http)
      BlazeBuilder.bindHttp(9002).mountService(postLift).run.awaitShutdown
  }
}

class Server {
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  private val db = new MongoDatabase
  private val txSigChecker = new TxSigChecker
  private val blindTokens = new BlindTokens(db)

  val http = HttpService {
    // Checking if signature based authentication works
    case req @ POST -> Root / "sig" / "check" => ifSig(req.params) {
      // Will be used once user adds a server address in wallet, just ok
      Ok apply okSingle("done")
    }

    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> Root / "blindtokens" / "info" => new ECKey(rand) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
    }

    // Record tokens to be signed and send an Invoice
    case req @ POST -> Root / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val maybeInvoice = blindTokens.getInvoice(toClass[ListStr](hex2Json apply tokens), sesKey)

      maybeInvoice match {
        case Some(future) => Ok(future map okSingle)
        case _ => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> Root / "blindtokens" / "redeem" =>
      val Seq(sesKey, preImage) = extract(req.params, identity, "seskey", "preimage")
      val maybeBlindTokens = blindTokens.redeemTokens(preImage, sesKey)

      maybeBlindTokens match {
        case Some(tokens) => Ok apply ok(tokens:_*)
        case _ => Ok apply error("notfound")
      }

    // BREACH TXS

    // If they try to supply way too much data
    case req @ POST -> Root / _ / "tx" / "breach"
      if req.params("watch").length > 2048 =>
      Ok apply error("toobig")

    // Record a transaction to be broadcasted in case of channel breach
    case req @ POST -> Root / "token" / "tx" / "breach" => ifToken(req.params) {
      Ok apply okSingle("done")
    }

    // DATA STORAGE

    // If they try to supply way too much data
    case req @ POST -> Root / _ / "data" / "put"
      if req.params("data").length > 2048 =>
      Ok apply error("toobig")

    case req @ POST -> Root / "token" / "data" / "put" => ifToken(req.params) {
      // Rewrites user's channel data, but also can be used for general purposes
      db.putGeneralData(req params "key", req params "data")
      Ok apply okSingle("done")
    }

    case req @ POST -> Root / "sig" / "data" / "put" => ifSig(req.params) {
      // Rewrites user's channel data, but also can be used for general purposes
      db.putGeneralData(req params "key", req params "data")
      Ok apply okSingle("done")
    }

    case req @ POST -> Root / "data" / "get" =>
      db.getGeneralData(key = req params "key") match {
        case Some(realData) => Ok apply okSingle(realData)
        case _ => Ok apply error("notfound")
      }

    case req @ POST -> Root / "data" / "delete" =>
      db.deleteGeneralData(req params "key")
      Ok apply okSingle("done")
  }

  // Checking clear token validity before proceeding
  def ifToken(params: HttpParams)(next: => TaskResponse): TaskResponse =  {
    val Seq(point, sig, token) = extract(params, identity, "point", "clearsig", "cleartoken")
    val sigIsFine = blindTokens.signer.verifyClearSig(token, sig, blindTokens decodeECPoint point)

    if (db isClearTokenUsed token) Ok apply error("tokenused")
    else if (!sigIsFine) Ok apply error("tokeninvalid")
    else try next finally db putClearToken token
  }

  // Checking signature validity before proceeding
  def ifSig(params: HttpParams)(next: => TaskResponse): TaskResponse = {
    val Seq(data, sig, prefix) = extract(params, identity, "data", "sig", "prefix")
    val isValidOpt = txSigChecker.check(HEX decode data, HEX decode sig, prefix)

    isValidOpt match {
      case Some(true) => next
      case Some(false) => Ok apply error("errsig")
      case _ => Ok apply error("errkey")
    }
  }

  // HTTP answer as JSON array
  def okSingle(data: Any): String = ok(data)
  def ok(data: Any*): String = Serialization write "ok" +: data
  def error(data: Any*): String = Serialization write "error" +: data
}