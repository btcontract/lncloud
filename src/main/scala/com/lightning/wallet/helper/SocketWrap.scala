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
    override def onData(chunk: Bytes) = for (lst <- listeners) lst onData chunk
  }

  class Worker {
    val socket = new Socket
    private val BUFFER_SIZE = 1024
    private val buffer = new Bytes(BUFFER_SIZE)

    Future {
      socket.connect(new InetSocketAddress(ip, port), 10000)
      Tools.log(s"Sock has connected to $ip:$port")
      try events.onConnect catch none

      while (true) {
        val read = socket.getInputStream.read(buffer, 0, BUFFER_SIZE)
        if (read > 0) try events.onData(buffer take read) catch none
        else if (read < 0) throw new RuntimeException("Sock closed")
      }
    } onComplete { _ =>
      Tools.log("Sock off")
      events.onDisconnect
    }
  }
}

class SocketListener {
  def onConnect: Unit = none
  def onDisconnect: Unit = none
  def onData(chunk: Bytes): Unit = none
}