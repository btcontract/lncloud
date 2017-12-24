package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.PrivateKey


object LNParams { me =>
  val localFeatures = "0a"
  val globalFeatures = ""

  var nodePrivateKey: PrivateKey = _
  var extendedNodeKey: ExtendedPrivateKey = _

  def setup(seed: BinaryData): Unit = generate(seed) match { case master =>
    extendedNodeKey = derivePrivateKey(master, hardened(46) :: hardened(0) :: Nil)
    nodePrivateKey = extendedNodeKey.privateKey
  }
}