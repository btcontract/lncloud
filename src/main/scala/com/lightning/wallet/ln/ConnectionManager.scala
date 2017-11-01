package com.lightning.wallet.ln

import com.lightning.wallet.ln.wire._
import java.net.{InetSocketAddress, Socket}
import com.lightning.wallet.ln.Tools.{Bytes, none}

import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.wallet.ln.LNParams.nodePrivateKey
import com.lightning.wallet.ln.Features.binData2BitSet
import com.lightning.wallet.ln.crypto.Noise.KeyPair
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.BinaryData
import scala.collection.mutable
import scala.concurrent.Future


object ConnectionManager {
  val pair = KeyPair(nodePrivateKey.publicKey, nodePrivateKey.toBin)
  val ourInit = Init(LNParams.globalFeatures, LNParams.localFeatures)
  val connections = mutable.Map.empty[PublicKey, Worker]
  val listeners = mutable.Set.empty[ConnectionListener]

  protected[this] val events = new ConnectionListener {
    override def onDisconnect(id: PublicKey) = for (lst <- listeners) lst.onDisconnect(id)
    override def onTerminalError(id: PublicKey) = for (lst <- listeners) lst.onTerminalError(id)
    override def onMessage(message: LightningMessage) = for (lst <- listeners) lst.onMessage(message)
    override def onOperational(id: PublicKey, their: Init) = for (lst <- listeners) lst.onOperational(id, their)
  }

  def requestConnection(announce: NodeAnnouncement) = connections get announce.nodeId match {
    case Some(work) if !work.work.isCompleted && work.savedInit == null => Tools log "Awaiting for their Init"
    case Some(work) if !work.work.isCompleted => events.onOperational(announce.nodeId, work.savedInit)
    case _ => connections(announce.nodeId) = new Worker(announce.nodeId, announce.addresses.head)
  }

  class Worker(nodeId: PublicKey, location: InetSocketAddress) {
    val handler: TransportHandler = new TransportHandler(pair, nodeId) {
      def handleDecryptedIncomingData(data: BinaryData) = intercept(LightningMessageCodecs deserialize data)
      def handleEncryptedOutgoingData(data: BinaryData) = try socket.getOutputStream write data catch none
      def handleError(err: Throwable) = events onTerminalError nodeId
      def handleEnterOperationalState = process(ourInit)
    }

    var savedInit: Init = _
    val BUFFER_SIZE: Int = 1024
    val socket: Socket = new Socket

    val work = Future {
      val buffer = new Bytes(BUFFER_SIZE)
      socket.connect(location, 7500)
      handler.init

      while (true) {
        val length = socket.getInputStream.read(buffer, 0, BUFFER_SIZE)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler process BinaryData(buffer take length)
      }
    }

    work onComplete { _ =>
      Tools log s"Disconnected"
      events onDisconnect nodeId
    }

    def intercept(message: LightningMessage) = message match {
      case their: Init if Features areSupported their.localFeatures =>
        events.onOperational(nodeId, their)
        savedInit = their

      case error: Error =>
        val decoded = new String(error.data.toArray)
        Tools log s"Got remote Error: $decoded"
        events onTerminalError nodeId

      case unsupportedFeaturesInit: Init => events onTerminalError nodeId
      case Ping(len, _) if len > 0 => handler process Pong("00" * len)
      case theirMessage => events onMessage theirMessage
    }
  }
}

class ConnectionListener {
  def onDisconnect(id: PublicKey): Unit = none
  def onTerminalError(id: PublicKey): Unit = none
  def onOperational(id: PublicKey, their: Init): Unit = none
  def onMessage(msg: LightningMessage): Unit = none
}
