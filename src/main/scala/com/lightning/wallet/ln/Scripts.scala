package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import com.softwaremill.quicklens._

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey}
import com.lightning.wallet.ln.wire.UpdateAddHtlc
import java.nio.ByteOrder
import scala.util.Try

import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import fr.acinq.bitcoin.SigVersion.SIGVERSION_BASE
import ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS


object Scripts { me =>
  type ScriptEltSeq = Seq[ScriptElt]

  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): ScriptEltSeq =
    LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin) match {
      case false => Script.createMultiSigMofN(m = 2, pubkey2 :: pubkey1 :: Nil)
      case true => Script.createMultiSigMofN(m = 2, pubkey1 :: pubkey2 :: Nil)
    }
}