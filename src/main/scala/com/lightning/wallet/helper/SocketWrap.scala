package com.lightning.wallet.helper

import com.lightning.wallet.ln.Tools.{Bytes, none}
import java.net.{InetAddress, InetSocketAddress, Socket}
import concurrent.ExecutionContext.Implicits.global
import com.lightning.wallet.ln.Transport
import fr.acinq.bitcoin.BinaryData
import scala.concurrent.Future


abstract class SocketWrap(ip: InetAddress, port: Int) extends Transport {
  def send(data: BinaryData): Unit = worker.socket.getOutputStream write data
  def shutdown: Unit = try worker.socket.close catch none
  def start: Unit = worker = new Worker

  private var worker: Worker = _
  var listeners = Set.empty[SocketListener]
  val events: SocketListener = new SocketListener {
    override def onConnect = for (lst <- listeners) lst.onConnect
    override def onDisconnect = for (lst <- listeners) lst.onDisconnect
  }

  class Worker {
    val socket = new Socket
    private val BUFFER_SIZE = 1024
    private val buffer = new Bytes(BUFFER_SIZE)
    private val where = new InetSocketAddress(ip, port)

    Future {
      socket.connect(where, 10000)
      events.onConnect

      while (true) {
        val read = socket.getInputStream.read(buffer, 0, BUFFER_SIZE)
        if (read < 0) throw new RuntimeException("Socket closed")
        else onReceive(buffer take read)
      }
    } onComplete { _ =>
      events.onDisconnect
    }
  }
}

class SocketListener {
  def onConnect: Unit = none
  def onDisconnect: Unit = none
}