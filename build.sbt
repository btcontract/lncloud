name := "olympus"

version := "1.0"

scalaVersion := "2.11.11"

// Network
libraryDependencies += "org.http4s" % "http4s-dsl_2.11" % "0.15.5"
libraryDependencies += "org.http4s" % "http4s-blaze-server_2.11" % "0.15.5"
libraryDependencies += "com.github.kevinsawicki" % "http-request" % "6.0"
libraryDependencies += "io.spray" % "spray-json_2.11" % "1.3.4"
libraryDependencies += "org.zeromq" % "jeromq" % "0.4.3"

// Mongo with logging
libraryDependencies += "org.mongodb" % "casbah_2.11" % "3.1.1"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.12"
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.12"

// Misc
libraryDependencies += "com.googlecode.concurrent-trees" % "concurrent-trees" % "2.6.0"
libraryDependencies += "com.typesafe.akka" % "akka-actor_2.11" % "2.5.11"
libraryDependencies += "org.scodec" % "scodec-core_2.11" % "1.10.3"
libraryDependencies += "org.bitcoinj" % "bitcoinj-core" % "0.14.5"
libraryDependencies += "fr.acinq" % "bitcoin-lib_2.11" % "0.9.13"
libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.26.5"
libraryDependencies += "org.jgrapht" % "jgrapht-core" % "1.1.0"
libraryDependencies += "org.jgrapht" % "jgrapht-ext" % "1.1.0"

// Exchanges
libraryDependencies += "org.knowm.xchange" % "xchange-bitcoinaverage" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-gatecoin" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-bitfinex" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-bitstamp" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-paymium" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-quoine" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-kraken" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-okcoin" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-core" % "4.3.1"
libraryDependencies += "org.knowm.xchange" % "xchange-gdax" % "4.3.1"

val workaround = {
  sys.props += "packaging.type" -> "jar"
  ()
}

mergeStrategy in assembly := {
  case n if n.startsWith("META-INF") => MergeStrategy.discard
  case _ => MergeStrategy.first
}