package com.btcontract.lncloud

import org.json4s.jackson.Serialization


object LNCloud extends App {

}

class Server {
  // HTTP answer as JSON array
  def ok(data: Any*) = Serialization write "ok" +: data
  def error(data: Any*) = Serialization write "error" +: data
}