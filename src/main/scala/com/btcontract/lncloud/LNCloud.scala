package com.btcontract.lncloud

import Utils._
import org.http4s.dsl._
import collection.JavaConverters._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._

import org.http4s.{HttpService, Response}
import org.http4s.server.{Server, ServerApp}
import fr.acinq.bitcoin.{MilliSatoshi, string2binaryData}
import com.lightning.wallet.ln.wire.NodeAnnouncement
import concurrent.ExecutionContext.Implicits.global
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import com.btcontract.lncloud.router.Router
import org.json4s.jackson.Serialization
import com.lightning.wallet.ln.Invoice
import scodec.Attempt.Successful
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import database.MongoDatabase
import java.math.BigInteger


object LNCloud extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments): Task[Server] = {
    val config = Vals(new ECKey(random).getPrivKey, MilliSatoshi(500000), 100,
      "http://user:password@127.0.0.1:8332", "tcp://127.0.0.1:28332", 144)

    values = config /*toClass[Vals](config)*/
    val socketAndHttpLnCloudServer = new Responder
    val postLift = UrlFormLifter(socketAndHttpLnCloudServer.http)
    BlazeBuilder.bindHttp(9002).mountService(postLift).start
  }
}

class Responder {
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  private val db: MongoDatabase = new MongoDatabase
  private val blindTokens = new BlindTokens
  private val V1 = Root / "v1"

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> V1 / "blindtokens" / "info" => new ECKey(random) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
    }

    // Record tokens and send an Invoice
    case req @ POST -> V1 / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val prunedTokens = toClass[TokenSeq](hex2Json apply tokens) take values.quantity

      // Only if we have a seskey in a cache
      blindTokens.cache get sesKey map { privateKey =>
        val blindFuture = blindTokens.getBlind(prunedTokens, k = privateKey.data)
        for (blindData <- blindFuture) db.putPendingTokens(data = blindData, sesKey)
        for (bd <- blindFuture) yield okSingle(Invoice serialize bd.invoice)
      } match {
        case Some(future) => Ok apply future
        case None => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> V1 / "blindtokens" / "redeem" =>
      // Only if we have a saved BlindData which is paid for
      db.getPendingTokens(req params "seskey") map { blindData =>
        blindTokens isFulfilled blindData.invoice.paymentHash map {
          case true => ok(data = blindTokens signTokens blindData:_*)
          case false => error("notpaid")
        }
      } match {
        case Some(future) => Ok apply future
        case None => Ok apply error("notfound")
      }

    // BREACH TXS

    // If they try to supply way too much data
    case req @ POST -> V1 / "token" / "tx" / "breach"
      if req.params("watch").length > 4096 =>
      Ok apply error("toobig")

    // Record a transaction to be broadcasted in case of channel breach
    case req @ POST -> V1 / "token" / "tx" / "breach" => ifToken(req.params) {
      Ok apply okSingle("done")
    }

    // DATA STORAGE

    // If they try to supply way too much data
    case req @ POST -> V1 / "token" / "data" / "put"
      if req.params("data").length > 4096 =>
      Ok apply error("toobig")

    case req @ POST -> V1 / "token" / "data" / "put" => ifToken(req.params) {
      // Rewrites user's channel data, but also can be used for general purposes
      db.putGeneralData(req params "key", req params "data")
      Ok apply okSingle("done")
    }

    case req @ POST -> V1 / "data" / "get" =>
      db.getGeneralData(key = req params "key") match {
        case Some(realData) => Ok apply okSingle(realData)
        case _ => Ok apply error("notfound")
      }

    case req @ POST -> V1 / "data" / "delete" =>
      db.deleteGeneralData(req params "key")
      Ok apply okSingle("done")

    // ROUTER DATA

    case req @ POST -> V1 / "router" / "routes"
      // GUARD: counterparty has been blacklisted
      if Router.black.contains(req params "from") =>
      Ok apply error("fromblacklisted")

    case req @ POST -> V1 / "router" / "routes"
      // GUARD: destination has been blacklisted or removed
      if Router.channels.nodeId2Chans(req params "to").isEmpty =>
      Ok apply error("tolost")

    case req @ POST -> V1 / "router" / "routes" =>
      val routes: Seq[PaymentRoute] = Router.finder.findRoutes(req params "from", req params "to")
      val data = routes take 5 map hopsCodec.encode collect { case Successful(bv) => bv.toHex }
      Ok apply ok(data:_*)

    case req @ POST -> V1 / "router" / "nodes" / "find" =>
      val query = req.params("query").trim.take(50).toLowerCase
      val nodes: Seq[NodeAnnouncement] = Router.nodes.searchTree.getValuesForKeysStartingWith(query).asScala.toList
      val data = nodes take 20 map nodeAnnouncementCodec.encode collect { case Successful(bv) => bv.toHex }
      Ok apply ok(data:_*)

    // NEW VERSION WARNING

    case POST -> Root / "v2" / _ =>
      Ok apply error("mustupdate")
  }

  // Checking clear token validity before proceeding
  def ifToken(params: HttpParams)(next: => TaskResponse): TaskResponse =  {
    val Seq(point, clearsig, cleartoken) = extract(params, identity, "point", "clearsig", "cleartoken")
    val signatureIsFine = blindTokens.signer.verifyClearSig(new BigInteger(cleartoken), new BigInteger(clearsig),
      blindTokens decodeECPoint point)

    if (db isClearTokenUsed cleartoken) Ok apply error("tokenused")
    else if (!signatureIsFine) Ok apply error("tokeninvalid")
    else try next finally db putClearToken cleartoken
  }

  // HTTP answer as JSON array
  def okSingle(data: Any): String = ok(data)
  def ok(data: Any*): String = Serialization write "ok" +: data
  def error(data: Any*): String = Serialization write "error" +: data
}