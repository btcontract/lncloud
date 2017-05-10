package com.lightning.wallet.helper

import com.lightning.wallet.ln.Tools.{Bytes, none}
import com.lightning.wallet.ln.{DataTransport, Tools}
import java.net.{InetAddress, InetSocketAddress, Socket}
import concurrent.ExecutionContext.Implicits.global
import fr.acinq.bitcoin.BinaryData
import scala.concurrent.Future


class SocketWrap(ip: InetAddress, port: Int) extends DataTransport {
  def send(data: BinaryData) = worker.socket.getOutputStream write data
  def shutdown = try worker.socket.close catch none
  def start = worker = new Worker

  private var worker: Worker = _
  var listeners = Set.empty[SocketListener]
  private val events = new SocketListener {
    override def onConnect = for (lst <- listeners) lst.onConnect
    override def onDisconnect = for (lst <- listeners) lst.onDisconnect
    override def onData(chunk: BinaryData) = for (lst <- listeners) lst onData chunk
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
        else events onData BinaryData(buffer take read)
      }
    } onComplete { _ =>
      events.onDisconnect
    }
  }
}

class SocketListener {
  def onConnect: Unit = none
  def onDisconnect: Unit = none
  def onData(chunk: BinaryData): Unit = none
}