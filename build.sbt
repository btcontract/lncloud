name := "lncloud"

version := "1.0"

scalaVersion := "2.11.8"

// Network
libraryDependencies += "org.http4s" % "http4s-dsl_2.11" % "0.12.0"
libraryDependencies += "org.http4s" % "http4s-blaze-server_2.11" % "0.12.0"
libraryDependencies += "wf.bitcoin" % "JavaBitcoindRpcClient" % "0.9.9"
libraryDependencies += "io.backchat.hookup" % "hookup_2.11" % "0.4.2"
libraryDependencies += "me.lessis" %% "courier" % "0.1.3"

// Mongo with logging
libraryDependencies += "org.mongodb" % "casbah_2.11" % "3.1.1"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.12"
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.12"

// Misc
libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.1.1"
libraryDependencies += "org.bitcoinj" % "bitcoinj-core" % "0.14.2"
libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.26.0"
libraryDependencies += "com.google.zxing" % "core" % "3.2.1"