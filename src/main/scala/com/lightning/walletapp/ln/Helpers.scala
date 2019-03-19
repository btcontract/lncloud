package com.lightning.walletapp.ln

import fr.acinq.bitcoin._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.Scripts._
import com.lightning.walletapp.ln.crypto.ShaChain._
import com.lightning.walletapp.ln.crypto.Generators._
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import scala.util.{Success, Try}
import scodec.bits.ByteVector


object Helpers {
  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams,
                   remoteParams: AcceptChannel, commitmentInput: InputInfo,
                   localPerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localHtlcPubkey = derivePubKey(localParams.htlcBasepoint, localPerCommitmentPoint)
    val localDelayedPaymentPubkey = derivePubKey(localParams.delayedPaymentBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val remotePaymentPubkey = derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val remoteHtlcPubkey = derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)

    val commitTx =
      Scripts.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentBasepoint, remoteParams.paymentBasepoint,
        localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey,
        remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)

    val htlcTimeoutTxs \ htlcSuccessTxs =
      Scripts.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey,
        remoteParams.toSelfDelay, localDelayedPaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams,
                    remoteParams: AcceptChannel, commitmentInput: InputInfo,
                    remotePerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localHtlcPubkey = derivePubKey(localParams.htlcBasepoint, remotePerCommitmentPoint)
    val localPaymentPubkey = derivePubKey(localParams.paymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = revocationPubKey(localParams.revocationBasepoint, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)

    val commitTx =
      Scripts.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localParams.paymentBasepoint,
        !localParams.isFunder, remoteParams.dustLimitSat, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey,
        localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, spec)

    val htlcTimeoutTxs \ htlcSuccessTxs =
      Scripts.makeHtlcTxs(commitTx.tx, remoteParams.dustLimitSat, remoteRevocationPubkey,
        localParams.toSelfDelay, remoteDelayedPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs,
      remoteHtlcPubkey, remoteRevocationPubkey)
  }

  object Closing {
    type SuccessAndClaim = (HtlcSuccessTx, ClaimDelayedOutputTx)
    type TimeoutAndClaim = (HtlcTimeoutTx, ClaimDelayedOutputTx)

    def isValidFinalScriptPubkey(raw: ByteVector) = Try(Script parse raw) match {
      case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pkh, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) => pkh.size == 20
      case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) => scriptHash.size == 20
      case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.length == 20 => true
      case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.length == 32 => true
      case _ => false
    }

    def makeClosing(commitments: Commitments, closingFee: Satoshi, local: ByteVector, remote: ByteVector) = {
      val theirDustIsHigherThanOurs: Boolean = commitments.localParams.dustLimit < commitments.remoteParams.dustLimitSat
      val dustLimit = if (theirDustIsHigherThanOurs) commitments.remoteParams.dustLimitSat else commitments.localParams.dustLimit

      val closing: ClosingTx =
        makeClosingTx(commitments.commitInput, local, remote, dustLimit,
          closingFee, commitments.localCommit.spec, commitments.localParams.isFunder)

      val localClosingSig = Scripts.sign(commitments.localParams.fundingPrivKey)(closing)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      require(isValidFinalScriptPubkey(remote), "Invalid remoteScriptPubkey")
      require(isValidFinalScriptPubkey(local), "Invalid localScriptPubkey")
      ClosingTxProposed(closing, closingSigned)
    }

    def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector,
                      dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec, localIsFunder: Boolean) = {

      require(spec.htlcs.isEmpty, "No pending HTLCs allowed")
      val toRemoteAmount: Satoshi = if (localIsFunder) MilliSatoshi(spec.toRemoteMsat) else MilliSatoshi(spec.toRemoteMsat) - closingFee
      val toLocalAmount: Satoshi = if (localIsFunder) MilliSatoshi(spec.toLocalMsat) - closingFee else MilliSatoshi(spec.toLocalMsat)
      val toRemoteOutput = if (toRemoteAmount < dustLimit) Nil else TxOut(toRemoteAmount, remoteScriptPubKey) :: Nil
      val toLocalOutput = if (toLocalAmount < dustLimit) Nil else TxOut(toLocalAmount, localScriptPubKey) :: Nil
      val input = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = 0xffffffffL) :: Nil
      val tx = Transaction(2, input, toLocalOutput ++ toRemoteOutput, lockTime = 0)
      ClosingTx(commitTxInput, LexicographicalOrdering sort tx)
    }

    // CONTRACT BREACH HANDLING

    def makeRevocationInfo(commitments: Commitments, tx: Transaction,
                           perCommitSecret: Scalar, feeRate: Long) = {

      val remotePerCommitmentPoint = perCommitSecret.toPoint
      val localPrivkey = derivePrivKey(commitments.localParams.paymentKey, remotePerCommitmentPoint)
      val remoteRevocationPrivkey = revocationPrivKey(commitments.localParams.revocationSecret, perCommitSecret)
      val remoteDelayedPaymentKey = derivePubKey(commitments.remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)

      val ri = RevocationInfo(redeemScriptsToSigs = Nil, claimMainTxSig = None, claimPenaltyTxSig = None,
        feeRate, commitments.localParams.dustLimit.amount, commitments.localParams.defaultFinalScriptPubKey,
        commitments.localParams.toSelfDelay, localPrivkey.publicKey, remoteRevocationPrivkey.publicKey,
        remoteDelayedPaymentKey)

      val finder = new PubKeyScriptIndexFinder(tx)
      val htlcs = commitments.remoteCommit.spec.htlcs
      val localHtlcKey = derivePubKey(commitments.localParams.htlcBasepoint, remotePerCommitmentPoint)
      val remoteHtlcKey = derivePubKey(commitments.remoteParams.htlcBasepoint, remotePerCommitmentPoint)
      val remoteRevKey = revocationPubKey(commitments.localParams.revocationBasepoint, remotePerCommitmentPoint)
      val offered = for (Htlc(false, add) <- htlcs) yield Scripts.htlcOffered(remoteHtlcKey, localHtlcKey, remoteRevKey, add.hash160)
      val received = for (Htlc(true, add) <- htlcs) yield Scripts.htlcReceived(remoteHtlcKey, localHtlcKey, remoteRevKey, add.hash160, add.expiry)
      val redeemScripts = for (redeem <- offered ++ received) yield Tuple2(Script.write(Script pay2wsh redeem), Script write redeem)
      val redeemScriptsMap = redeemScripts.toMap

      val htlcPenaltySigs = for {
        TxOut(_, publicKeyScript) <- tx.txOut
        // Small HTLCs won't make it into tx outputs
        redeemScript <- redeemScriptsMap get publicKeyScript
        penaltyTx <- ri.makeHtlcPenalty(finder)(redeemScript).toOption
        htlcSig = Scripts.sign(remoteRevocationPrivkey)(penaltyTx)
      } yield (redeemScript, htlcSig)

      ri.copy(redeemScriptsToSigs = htlcPenaltySigs.toList,
        claimPenaltyTxSig = ri.makeMainPenalty(tx).map(Scripts sign remoteRevocationPrivkey).toOption,
        claimMainTxSig = ri.makeClaimP2WPKHOutput(tx).map(Scripts sign localPrivkey).toOption)
    }

    // Here we have an existing RevocationInfo with updated fee rate
    def reMakeRevocationInfo(ri: RevocationInfo, commitments: Commitments,
                             tx: Transaction, perCommitSecret: Scalar) = {

      val finder = new PubKeyScriptIndexFinder(tx)
      val remotePerCommitmentPoint = perCommitSecret.toPoint
      val localPrivkey = derivePrivKey(commitments.localParams.paymentKey, remotePerCommitmentPoint)
      val remoteRevocationPrivkey = revocationPrivKey(commitments.localParams.revocationSecret, perCommitSecret)

      val htlcPenaltySigs = for {
        redeemScript \ _ <- ri.redeemScriptsToSigs
        // Small HTLCs won't make it into tx outputs
        penaltyTx <- ri.makeHtlcPenalty(finder)(redeemScript).toOption
        htlcSig = Scripts.sign(remoteRevocationPrivkey)(penaltyTx)
      } yield (redeemScript, htlcSig)

      ri.copy(redeemScriptsToSigs = htlcPenaltySigs,
        claimPenaltyTxSig = ri.makeMainPenalty(tx).map(Scripts sign remoteRevocationPrivkey).toOption,
        claimMainTxSig = ri.makeClaimP2WPKHOutput(tx).map(Scripts sign localPrivkey).toOption)
    }

    def claimRevokedRemoteCommitTxOutputs(ri: RevocationInfo, tx: Transaction) = {
      // We only save scripts and signatures in RevocationInfo to save storage space
      // once required we regenerate full transactions here on demand
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

    def extractCommitmentSecret(commitments: Commitments, commitTx: Transaction) = {
      val txNumber = Scripts.obscuredCommitTxNumber(Scripts.decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime),
        !commitments.localParams.isFunder, commitments.remoteParams.paymentBasepoint, commitments.localParams.paymentBasepoint)

      val index = moves(largestTxIndex - txNumber)
      val hashes = commitments.remotePerCommitmentSecrets.hashes
      getHash(hashes)(index).map(ByteVector.view).map(Scalar.apply)
    }
  }

  object Funding {
    def makeFundingInputInfo(fundingTxid: ByteVector, fundingTxOutputIndex: Int,
                             fundingSatoshis: Satoshi, fundingPubkey1: PublicKey,
                             fundingPubkey2: PublicKey): InputInfo = {

      val multisig = Scripts.multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, Script pay2wsh multisig)
      val outPoint = OutPoint(fundingTxid, fundingTxOutputIndex)
      InputInfo(outPoint, fundingTxOut, Script write multisig)
    }

    def makeFirstCommitTxs(localParams: LocalParams, fundingSat: Long, pushMsat: Long, initialFeeratePerKw: Long,
                           remoteParams: AcceptChannel, fundingTxid: ByteVector, fundingTxOutputIndex: Int,
                           remoteFirstPoint: Point) = {

      val toLocalMsat = if (localParams.isFunder) fundingSat * 1000L - pushMsat else pushMsat
      val toRemoteMsat = if (localParams.isFunder) pushMsat else fundingSat * 1000L - pushMsat

      val localSpec = CommitmentSpec(initialFeeratePerKw, toLocalMsat, toRemoteMsat)
      val remoteSpec = CommitmentSpec(initialFeeratePerKw, toRemoteMsat, toLocalMsat)

      if (!localParams.isFunder) {
        val fees = Scripts.commitTxFee(remoteParams.dustLimitSat, remoteSpec).amount
        val missing = remoteSpec.toLocalMsat / 1000L - localParams.channelReserveSat - fees
        if (missing < 0) throw new LightningException("They are funder and can not afford fees")
      }

      val localPerCommitmentPoint = perCommitPoint(localParams.shaSeed, 0L)
      val commitmentInput = makeFundingInputInfo(fundingTxid, fundingTxOutputIndex,
        Satoshi(fundingSat), localParams.fundingPrivKey.publicKey, remoteParams.fundingPubkey)

      val (localCommitTx, _, _) = makeLocalTxs(0L, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _, _, _) = makeRemoteTxs(0L, localParams, remoteParams, commitmentInput, remoteFirstPoint, remoteSpec)
      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }
  }
}