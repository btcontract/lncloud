package com.lightning.wallet.ln

import scala.concurrent._
import scala.concurrent.duration._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.LNParams._
import com.lightning.wallet.ln.Features._

import rx.lang.scala.{Observable => Obs}
import com.lightning.wallet.ln.Tools.{Bytes, none}
import com.lightning.wallet.ln.crypto.Noise.KeyPair
import java.util.concurrent.Executors
import fr.acinq.bitcoin.BinaryData
import java.net.Socket


object ConnectionManager {
  var connections = Map.empty[NodeAnnouncement, Worker]
  var listeners = Set.empty[ConnectionListener]

  protected[this] val events = new ConnectionListener {
    override def onMessage(ann: NodeAnnouncement, msg: LightningMessage) = for (lst <- listeners) lst.onMessage(ann, msg)
    override def onOperational(ann: NodeAnnouncement, their: Init) = for (lst <- listeners) lst.onOperational(ann, their)
    override def onTerminalError(ann: NodeAnnouncement) = for (lst <- listeners) lst.onTerminalError(ann)
    override def onDisconnect(ann: NodeAnnouncement) = for (lst <- listeners) lst.onDisconnect(ann)
  }

  def disconnectOrCallback(w: Worker, ann: NodeAnnouncement) =
    // If it is still connected but has no Init we assume failure
    if (null != w.savedInit) events.onOperational(ann, w.savedInit)
    else w.disconnect

  def connectTo(ann: NodeAnnouncement) = connections get ann match {
    case Some(worker) if !worker.work.isCompleted => disconnectOrCallback(worker, ann)
    case _ => connections = connections.updated(value = new Worker(ann), key = ann)
  }

  class Worker(ann: NodeAnnouncement, val buffer: Bytes = new Bytes(1024), val socket: Socket = new Socket) {
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
    val handler: TransportHandler = new TransportHandler(KeyPair(nodePublicKey.toBin, nodePrivateKey.toBin), ann.nodeId) {
      def handleEnterOperationalState = handler process Init(globalFeatures = LNParams.globalFeatures, LNParams.localFeatures)
      def handleDecryptedIncomingData(data: BinaryData) = intercept(LightningMessageCodecs deserialize data)
      def handleEncryptedOutgoingData(data: BinaryData) = try socket.getOutputStream write data catch none
      def handleError = { case _ => events onTerminalError ann }
    }

    // Used to remove disconnected nodes
    var lastMsg = System.currentTimeMillis
    var savedInit: Init = _

    val work = Future {
      // First blocking connect, then send data
      socket.connect(ann.addresses.head, 7500)
      handler.init

      while (true) {
        val length = socket.getInputStream.read(buffer, 0, buffer.length)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler process BinaryData(buffer take length)
      }
    }

    // Listener may trigger a reconnect
    work onComplete { _ => events onDisconnect ann }
    def disconnect = try socket.close catch none

    def intercept(message: LightningMessage) = {
      // Some incoming messages need special handling
      lastMsg = System.currentTimeMillis

      message match {
        case their: Init if areSupported(their.localFeatures) =>
          // We need to save their Init for subsequent requests
          events.onOperational(ann, their)
          savedInit = their

        case ping: Ping if ping.pongLength > 0 =>
          val response = Pong("00" * ping.pongLength)
          handler process response

        // This node has incompatible features
        case _: Init => events.onTerminalError(ann)
        case _ => events.onMessage(ann, message)
      }
    }
  }

  Obs interval 60.seconds foreach { _ =>
    val outdated = System.currentTimeMillis - 1000 * 120
    for (work <- connections.values if work.lastMsg < outdated)
      work.disconnect
  }
}

class ConnectionListener {
  def onMessage(ann: NodeAnnouncement, msg: LightningMessage): Unit = none
  def onOperational(ann: NodeAnnouncement, their: Init): Unit = none
  def onTerminalError(ann: NodeAnnouncement): Unit = none
  def onDisconnect(ann: NodeAnnouncement): Unit = none
}