package com.lightning.walletapp.ln

import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import com.lightning.walletapp.ln.Tools.Bytes
import scodec.bits.ByteVector


object LNParams { me =>
  val localFeatures = ByteVector.fromValidHex("0a")
  val globalFeatures = ByteVector.fromValidHex("")

  var extendedNodeKey: ExtendedPrivateKey = _
  lazy val nodePublicKey: PublicKey = nodePrivateKey.publicKey
  lazy val nodePrivateKey: PrivateKey = extendedNodeKey.privateKey

  def setup(seed: Bytes): Unit = generate(ByteVector view seed) match { case m =>
    extendedNodeKey = derivePrivateKey(m, hardened(46) :: hardened(0) :: Nil)
  }
}