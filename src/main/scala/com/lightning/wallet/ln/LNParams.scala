package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}


object LNParams { me =>
  val localFeatures = "0a"
  val globalFeatures = ""

  var extendedNodeKey: ExtendedPrivateKey = _
  lazy val nodePublicKey: PublicKey = nodePrivateKey.publicKey
  lazy val nodePrivateKey: PrivateKey = extendedNodeKey.privateKey

  def setup(seed: BinaryData): Unit = generate(seed) match { case m =>
    extendedNodeKey = derivePrivateKey(m, hardened(46) :: hardened(0) :: Nil)
  }
}