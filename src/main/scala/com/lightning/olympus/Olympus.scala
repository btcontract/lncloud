package com.lightning.olympus

import cats.data._
import spray.json._
import cats.effect._
import fr.acinq.bitcoin._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze._
import com.lightning.walletapp.ln._
import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._

import org.http4s.{HttpRoutes, Response}
import rx.lang.scala.{Observable => Obs}
import akka.actor.{ActorSystem, Props}

import org.http4s.server.SSLKeyStoreSupport.StoreInfo
import com.lightning.walletapp.lnutils.InRoutesPlus
import javax.crypto.Cipher.getMaxAllowedKeyLength
import org.http4s.server.middleware.UrlFormLifter
import com.lightning.walletapp.ln.Tools.random
import com.lightning.olympus.zmq.ZMQSupervisor
import com.lightning.olympus.JsonHttpUtils.to
import scala.concurrent.duration.DurationInt
import fr.acinq.bitcoin.Crypto.PublicKey
import language.implicitConversions
import scala.language.higherKinds
import org.http4s.dsl.impl.Root
import org.bitcoinj.core.ECKey
import scodec.bits.ByteVector
import java.math.BigInteger
import java.nio.file.Paths
import cats.effect.IOApp


object Olympus extends IOApp {
  type ProgramArguments = List[String]

  def run(args: ProgramArguments) = {
    // Check that we can decrypt punishment blobs
    require(getMaxAllowedKeyLength("AES") >= 256)
    LNParams.setup(random getBytes 32)

    args match {
      case List("testrun") =>
        val description = "Storage tokens for backup Olympus server at 127.0.0.1"
        val eclairProvider = EclairProvider(500000L, 50, description, "http://127.0.0.1:8080", "watermel0n")
        values = Vals(privKey = "33337641954423495759821968886025053266790003625264088739786982511471995762588",
          btcApi = "http://foo:bar@127.0.0.1:8332", zmqApi = "tcp://127.0.0.1:29000", eclairSockIp = "127.0.0.1",
          eclairSockPort = 9735, eclairNodeId = "020874b2bdd24dd6dcafa35b2b64695d950f95cb2c87bcc1fadced5d568295cc52",
          rewindRange = 2, ip = "127.0.0.1", port = 9103, eclairProvider, minCapacity = 250000L,
          sslFile = "/home/anton/Desktop/olympus/keystore.jks", sslPass = "pass123")

      case List("production", rawVals) =>
        values = to[Vals](rawVals)
    }

    val lifter = OptionT.liftK[IO]
    val httpLNCloudServer = new Responder

    BlazeServerBuilder[IO]
      .bindHttp(values.port, values.ip)
      .withSSL(StoreInfo(Paths.get(values.sslFile).toAbsolutePath.toString, values.sslPass), values.sslPass)
      .withHttpApp(UrlFormLifter(lifter)(httpLNCloudServer.http).orNotFound).serve.compile.drain.map(_ => ExitCode.Success)
  }
}

class Responder { me =>
  type IOResponse = Response[IO]
  type HttpResponse = IO[IOResponse]
  type HttpParams = Map[String, String]

  implicit def js2Task(js: JsValue): HttpResponse = Ok(js.toString)
  private val (bODY, oK, eRROR) = Tuple3("body", "ok", "error")
  private val exchangeRates = new ExchangeRates
  private val blindTokens = new BlindTokens
  private val feeRates = new FeeRates

  val system = ActorSystem("zmq-system")
  // Start watching Bitcoin blocks and transactions via ZMQ interface
  val supervisor = system actorOf Props.create(classOf[ZMQSupervisor], db)
  LNConnector.connect // Start filling routing message queue
  Router.rescheduleQueue // Start processing routing messages

  val http = HttpRoutes.of[IO] {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> Root / "blindtokens" / "info" => new ECKey(random) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      val res = (blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.paymentProvider.quantity)
      Tuple2(oK, res).toJson
    }

    case req @ POST -> Root / "blindtokens" / "buy" =>
      // Record tokens and send a payment request if we still have data in cache
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")

      blindTokens.cache get sesKey map { item =>
        val pruned = hex2String andThen to[StringVec] apply tokens take values.paymentProvider.quantity
        val Charge(hash, id, serializedPaymentRequest, false) = values.paymentProvider.generateInvoice
        db.putPendingTokens(BlindData(hash, id, item.data, pruned), sesKey)
        js2Task(Tuple2(oK, serializedPaymentRequest).toJson)
      } getOrElse js2Task(Tuple2(eRROR, "notfound").toJson)

    // Provide signed blind tokens
    case req @ POST -> Root / "blindtokens" / "redeem" =>
      val tokens = db.getPendingTokens(req params "seskey")
      val isPaid = tokens map values.paymentProvider.isPaid

      isPaid -> tokens match {
        case Some(true) \ Some(data) => Tuple2(oK, blindTokens sign data).toJson
        case Some(false) \ _ => Tuple2(eRROR, "notfulfilled").toJson
        case _ => Tuple2(eRROR, "notfound").toJson
      }

    // ROUTER

    case req @ POST -> Root / "router" / "routesplus" =>
      val InRoutesPlus(sat, nodes, chans, from, dest) = req.params andThen hex2String andThen to[InRoutesPlus] apply "params"
      val paths = Router.finder.findPaths(nodes take 240, chans take 240, from take 16, dest, sat = (sat * 1.2).toLong)
      Tuple2(oK, paths).toJson

    case req @ POST -> Root / "router" / "nodes" =>
      val announces = req.params("query").trim.take(32).toLowerCase match {
        case query if query.nonEmpty => Router.searchTrie.getValuesForKeysStartingWith(query).asScala
        case _ => Router.nodeId2Chans.scoredNodeSuggestions take 48 flatMap Router.nodeId2Announce.get
      }

      val encoded = announces.take(24).map(ann => nodeAnnouncementCodec.encode(ann).require.toHex)
      val sizes = announces.take(24).map(ann => Router.nodeId2Chans.node2Ids(ann.nodeId).size)
      Tuple2(oK, encoded zip sizes).toJson

    // TRANSACTIONS AND SHORT ID

    case req @ POST -> Root / "shortid" / "get" =>
      val txInfo = bitcoin.getRawTransaction(req params "txid")
      val fundingTxParentBlock = bitcoin.getBlock(txInfo.blockHash)
      val fundingTxOrderIndex = fundingTxParentBlock.tx.asScala.indexOf(txInfo.txId)
      if (fundingTxParentBlock.confirmations < 1) Tuple2(eRROR, "orphanBlock").toJson
      else if (fundingTxOrderIndex < 1) Tuple2(eRROR, "incorrectFundTxIndex").toJson
      else Tuple2(oK, fundingTxParentBlock.height -> fundingTxOrderIndex).toJson

    case req @ POST -> Root / "txs" / "get" =>
      // Given a list of parent tx ids, fetch all child txs which spend their outputs
      val txIds = req.params andThen hex2String andThen to[StringVec] apply "txids" take 24
      val spenderTxs = db.getSpenders(txIds).map(blockchain.getRawTxData).flatMap(_.toOption)
      Tuple2(oK, spenderTxs).toJson

    case req @ POST -> Root / "txs" / "schedule" => verify(req.params) {
      val txBitVecFromHex = ByteVector.fromValidHex(req params bODY).toBitVector
      val prunedTxByteVec = txvec.decode(txBitVecFromHex).require.value take 16
      for (bv <- prunedTxByteVec) db.putScheduled(Transaction read bv.toArray)
      Tuple2(oK, "done").toJson
    }

    // ARBITRARY DATA

    case req @ POST -> Root / "data" / "put" => verify(req.params) {
      val Seq(key, userDataHex) = extract(req.params, identity, "key", bODY)
      db.putData(key, userDataHex)
      Tuple2(oK, "done").toJson
    }

    case req @ POST -> Root / "data" / "get" =>
      val results = db.getData(req params "key")
      Tuple2(oK, results).toJson

    // WATCHDOG

    case req @ POST -> Root / "cerberus" / "watch" => verify(req.params) {
      val cerberusPayloadBitVecFromHex = ByteVector.fromValidHex(req params bODY).toBitVector
      val cerberusPayloadDecoded = cerberusPayloadCodec decode cerberusPayloadBitVecFromHex
      val CerberusPayload(aesZygotes, halfTxIds) = cerberusPayloadDecoded.require.value
      for (aesz \ half <- aesZygotes zip halfTxIds take 20) db.putWatched(aesz, half)
      Tuple2(oK, "done").toJson
    }

    // FEERATE AND EXCHANGE RATES

    case POST -> Root / "rates" / "get" =>
      val feesPerBlock = for (k \ v <- feeRates.rates) yield (k.toString, v getOrElse 0D)
      val response = Tuple2(feesPerBlock.toMap, exchangeRates.cache)
      Tuple2(oK, response).toJson

    case GET -> Root / "rates" / "state" =>
      val bitcoinPart = s"${feeRates.rates.toString}\r\n===\r\n"
      val fiatPart = s"${exchangeRates.cache}\r\n===\r\n${exchangeRates.updated}"
      Ok(s"$bitcoinPart$fiatPart")
  }

  def verify(params: HttpParams)(next: => HttpResponse): HttpResponse = {
    val Seq(point, cleartoken, clearsig) = extract(params, identity, "point", "cleartoken", "clearsig")
    lazy val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
      clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

    if (params(bODY).length > 250000) Tuple2(eRROR, "bodytoolarge").toJson
    else if (db isClearTokenUsed cleartoken) Tuple2(eRROR, "tokenused").toJson
    else if (!signatureIsFine) Tuple2(eRROR, "tokeninvalid").toJson
    else try next finally db putClearToken cleartoken
  }
}

object LNConnector {
  def connect = ConnectionManager.connectTo(announce, notify = true)
  val nodeAddress = NodeAddress.fromParts(values.eclairSockIp, values.eclairSockPort)
  val announce = NodeAnnouncement(null, null, 0, values.eclairNodePubKey, null, "Routing", nodeAddress :: Nil)

  ConnectionManager.listeners += new ConnectionListener {
    override def onMessage(nodeId: PublicKey, msg: LightningMessage) = Router.unprocessedMessages offer msg
    override def onTerminalError(nodeId: PublicKey) = ConnectionManager.connections.get(nodeId).foreach(_.socket.close)
    override def onOperational(nodeId: PublicKey, isCompat: Boolean) = Tools log s"Eclair socket is operational, is compat: $isCompat"
    override def onDisconnect(nodeId: PublicKey) = Obs.just(Tools log "Restarting").delay(5.seconds).foreach(_ => connect, Tools.errlog)
    Obs.interval(6.hours).foreach(_ => for (worker <- ConnectionManager.connections.values) worker.disconnect, Tools.errlog)
  }
}