package com.lightning.wallet.ln

import scala.concurrent.duration._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.Features._

import rx.lang.scala.{Observable => Obs}
import com.lightning.wallet.ln.Tools.{Bytes, none}
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.wallet.ln.LNParams.nodePrivateKey
import com.lightning.wallet.ln.crypto.Noise.KeyPair
import fr.acinq.bitcoin.BinaryData
import scala.collection.mutable
import scala.concurrent.Future
import java.net.Socket


object ConnectionManager {
  val pair = KeyPair(nodePrivateKey.publicKey, nodePrivateKey.toBin)
  val ourInit = Init(LNParams.globalFeatures, LNParams.localFeatures)
  val connections = mutable.Map.empty[NodeAnnouncement, Worker]
  val listeners = mutable.Set.empty[ConnectionListener]

  protected[this] val events = new ConnectionListener {
    override def onDisconnect(ann: NodeAnnouncement) = for (lst <- listeners) lst.onDisconnect(ann)
    override def onMessage(message: LightningMessage) = for (lst <- listeners) lst.onMessage(message)
    override def onTerminalError(ann: NodeAnnouncement) = for (lst <- listeners) lst.onTerminalError(ann)
    override def onOperational(ann: NodeAnnouncement, their: Init) =
      for (lst <- listeners) lst.onOperational(ann, their)
  }

  def connectTo(a: NodeAnnouncement) = connections get a match {
    case Some(existingWorker) if !existingWorker.work.isCompleted =>
      if (null == existingWorker.savedInit) existingWorker.disconnect
      else events.onOperational(a, existingWorker.savedInit)

    // Either disconnected or no worker at all
    case _ => connections(a) = new Worker(a)
  }

  class Worker(ann: NodeAnnouncement) {
    val handler: TransportHandler = new TransportHandler(pair, ann.nodeId) {
      def handleDecryptedIncomingData(data: BinaryData) = intercept(LightningMessageCodecs deserialize data)
      def handleEncryptedOutgoingData(data: BinaryData) = try socket.getOutputStream write data catch none
      def handleError = { case _ => events onTerminalError ann }
      def handleEnterOperationalState = process(ourInit)
    }

    val socket: Socket = new Socket
    var lastPing = System.currentTimeMillis
    var savedInit: Init = _

    val work = Future {
      val buffer = new Bytes(1024)
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

    // Some messages need a special handling so we intercept
    def intercept(message: LightningMessage) = message match {
      case their: Init if areSupported(their.localFeatures) =>
        events.onOperational(ann, their)
        savedInit = their

      case ping: Ping if ping.pongLength > 0 =>
        handler process Pong("00" * ping.pongLength)
        lastPing = System.currentTimeMillis

      case _: Init => events onTerminalError ann
      case _ => events onMessage message
    }
  }

  Obs interval 90.seconds foreach { _ =>
    val outdated = System.currentTimeMillis - 1000 * 90
    for (work <- connections.values if work.lastPing < outdated)
      work.disconnect
  }
}

class ConnectionListener {
  def onOperational(ann: NodeAnnouncement, their: Init): Unit = none
  def onTerminalError(ann: NodeAnnouncement): Unit = none
  def onDisconnect(ann: NodeAnnouncement): Unit = none
  def onMessage(msg: LightningMessage): Unit = none
}