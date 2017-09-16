package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.PublicKey


object Scripts { me =>
  type ScriptEltSeq = Seq[ScriptElt]

  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): ScriptEltSeq =
    LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin) match {
      case false => Script.createMultiSigMofN(m = 2, pubkey2 :: pubkey1 :: Nil)
      case true => Script.createMultiSigMofN(m = 2, pubkey1 :: pubkey2 :: Nil)
    }

  def witness2of2(sig1: BinaryData, sig2: BinaryData, pubkey1: PublicKey, pubkey2: PublicKey) =
    LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin) -> multiSig2of2(pubkey1, pubkey2) match {
      case (false, multisig) => ScriptWitness(BinaryData.empty :: sig2 :: sig1 :: Script.write(multisig) :: Nil)
      case (true, multisig) => ScriptWitness(BinaryData.empty :: sig1 :: sig2 :: Script.write(multisig) :: Nil)
    }

  def csvTimeout(tx: Transaction): Long =
    if (tx.version < 2) 0L else tx.txIn.map { in =>
      val isCsvDisabled = (in.sequence & TxIn.SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0
      if (isCsvDisabled) 0L else in.sequence & TxIn.SEQUENCE_LOCKTIME_MASK
    }.max
}