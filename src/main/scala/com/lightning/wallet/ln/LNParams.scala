package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.PrivateKey


object LNParams { me =>
  val maxReserveToFundingRatio = 0.05 // %
  val updateFeeMinDiffRatio = 0.25 // %
  val reserveToFundingRatio = 0.01 // %
  val localFeatures = "08"
  val globalFeatures = ""
  val minDepth = 2

  val htlcMinimumMsat = 500
  val maxHtlcValue = MilliSatoshi(4000000000L)
  val maxChannelCapacity = MilliSatoshi(16777216000L)
  val chainHash = Block.RegtestGenesisBlock.blockId

  var nodePrivateKey: PrivateKey = _
  var cloudPrivateKey: PrivateKey = _
  var extendedNodeKey: ExtendedPrivateKey = _

  def setup(seed: BinaryData): Unit = generate(seed) match { case master =>
    cloudPrivateKey = derivePrivateKey(master, hardened(92) :: hardened(0) :: Nil).privateKey
    extendedNodeKey = derivePrivateKey(master, hardened(46) :: hardened(0) :: Nil)
    nodePrivateKey = extendedNodeKey.privateKey
  }
}