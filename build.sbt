name := "lncloud"

version := "1.0"

scalaVersion := "2.11.8"

// Network
libraryDependencies += "org.http4s" % "http4s-dsl_2.11" % "0.15.5"
libraryDependencies += "org.http4s" % "http4s-blaze-server_2.11" % "0.15.5"
libraryDependencies += "com.github.kevinsawicki" % "http-request" % "6.0"
libraryDependencies += "org.json4s" % "json4s-jackson_2.11" % "3.4.1"
libraryDependencies += "com.mdialog" % "scala-zeromq_2.11" % "1.1.1"
libraryDependencies += "org.zeromq" % "jeromq" % "0.3.6"

// Mongo with logging
libraryDependencies += "org.mongodb" % "casbah_2.11" % "3.1.1"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.12"
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.12"

// Misc
libraryDependencies += "com.googlecode.concurrent-trees" % "concurrent-trees" % "2.6.0"
libraryDependencies += "com.softwaremill.quicklens" % "quicklens_2.11" % "1.4.8"

libraryDependencies += "org.scodec" % "scodec-core_2.11" % "1.10.3"
libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.26.5"
libraryDependencies += "org.jgrapht" % "jgrapht-core" % "1.0.1"
libraryDependencies += "org.jgrapht" % "jgrapht-ext" % "1.0.1"

// Exchanges
libraryDependencies += "org.knowm.xchange" % "xchange-core" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-okcoin" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-gdax" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-kraken" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-bitfinex" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-paymium" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-bitstamp" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-bter" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-btcchina" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-btc38" % "4.1.0"
libraryDependencies += "org.knowm.xchange" % "xchange-chbtc" % "4.1.0"