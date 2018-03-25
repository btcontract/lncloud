package com.lightning.olympus.zmq

import scala.concurrent.duration.DurationInt
import akka.actor.SupervisorStrategy.Restart
import com.lightning.olympus.database.Database
import akka.actor.{Actor, OneForOneStrategy, Props}


class ZMQSupervisor(db: Database) extends Actor {
  val zmqActor = context actorOf Props.create(classOf[ZMQActor], db)
  def receive: Receive = { case some => zmqActor ! some }

  override def supervisorStrategy = OneForOneStrategy(Int.MaxValue, 5.seconds) {
    // ZMQ connection may be lost or an exception may be thrown while processing data
    // wait for 5 seconds and try to reconnect if that happens
    case _: Throwable =>
      Thread sleep 5000
      Restart
  }
}
