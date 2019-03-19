package com.lightning.olympus.zmq

import akka.actor.SupervisorStrategy.Resume
import scala.concurrent.duration.DurationInt
import com.lightning.olympus.database.Database
import akka.actor.{Actor, OneForOneStrategy, Props}


class ZMQSupervisor(db: Database) extends Actor {
  val zmqActor = context actorOf Props.create(classOf[ZMQActor], db)
  def receive: Receive = { case some => zmqActor ! some }

  override def supervisorStrategy = OneForOneStrategy(-1, 5.seconds) {
    // ZMQ connection may be lost or an exception may be thrown while processing data
    // so we always wait for 5 seconds and try to reconnect again if that happens
    case processingError: Throwable =>
      processingError.printStackTrace
      Thread sleep 5000L
      Resume
  }
}
