package com.lightning.walletapp.ln

import fr.acinq.bitcoin._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.Scripts._
import com.lightning.walletapp.ln.crypto.Generators._
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import scala.util.{Success, Try}


object Helpers {
  object Closing {
    def claimRevokedRemoteCommitTxOutputs(ri: RevocationInfo, tx: Transaction) = {
      // For v2 channels we store a single RevocationInfo object per each commit tx
      val finder = new PubKeyScriptIndexFinder(tx)

      val claimMainTx = for {
        sig <- ri.claimMainTxSig
        makeClaimP2WPKH <- ri.makeClaimP2WPKHOutput(tx).toOption
        signed = Scripts.addSigs(makeClaimP2WPKH, sig, ri.localPubKey)
        main <- Scripts.checkValid(signed).toOption
      } yield main

      val claimPenaltyTx = for {
        sig <- ri.claimPenaltyTxSig
        theirMainPenalty <- ri.makeMainPenalty(tx).toOption
        signed = Scripts.addSigs(theirMainPenalty, sig)
        their <- Scripts.checkValid(signed).toOption
      } yield their

      val htlcPenaltyTxs = for {
        redeemScript \ sig <- ri.redeemScriptsToSigs
        htlcPenaltyTx <- ri.makeHtlcPenalty(finder)(redeemScript).toOption
        signed = Scripts.addSigs(htlcPenaltyTx, sig, ri.remoteRevocationPubkey)
        penalty <- Scripts.checkValid(signed).toOption
      } yield penalty

      RevokedCommitPublished(claimMainTx.toSeq,
        claimPenaltyTx.toSeq, htlcPenaltyTxs, tx)
    }
  }
}