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

  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey) =
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

  def encodeNumber(number: Long): ScriptElt = number match {
    case n if n < -1 | n > 16 => OP_PUSHDATA(Script encodeNumber n)
    case n if n >= 1 & n <= 16 => ScriptElt code2elt number.toInt
    case -1L => OP_1NEGATE
    case 0L => OP_0
  }

  // LN SCRIPTS
  // @formatter:off

  def toLocalDelayed(revocationPubkey: PublicKey, toSelfDelay: Int,
                     localDelayedPubkey: PublicKey): ScriptEltSeq =

    OP_IF ::
      OP_PUSHDATA(revocationPubkey) ::
    OP_ELSE ::
      encodeNumber(toSelfDelay) ::
      OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedPubkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil

  def htlcOffered(localPubkey: PublicKey, remotePubkey: PublicKey,
                  revocationPubKey: PublicKey, paymentHash: BinaryData) =

    OP_DUP :: OP_HASH160 ::
    OP_PUSHDATA(revocationPubKey.hash160) ::
    OP_EQUAL ::
    OP_IF ::
      // To you with revocation key
      OP_CHECKSIG ::
    OP_ELSE ::
      OP_PUSHDATA(remotePubkey) :: OP_SWAP ::
      OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
      OP_NOTIF ::
        // To me via timelocked HTLC-timeout transaction
        OP_DROP :: OP_2 :: OP_SWAP ::
        OP_PUSHDATA(localPubkey) :: OP_2 ::
        OP_CHECKMULTISIG ::
      OP_ELSE ::
        OP_HASH160 :: OP_PUSHDATA(paymentHash) ::
        OP_EQUALVERIFY :: OP_CHECKSIG ::
      OP_ENDIF ::
    OP_ENDIF :: Nil

  def htlcReceived(localKey: PublicKey, remotePubkey: PublicKey, revocationPubKey: PublicKey,
                   paymentHash: BinaryData, lockTime: Long): ScriptEltSeq =

    OP_DUP :: OP_HASH160 ::
    OP_PUSHDATA(revocationPubKey.hash160) ::
    OP_EQUAL ::
    OP_IF ::
      // To you with revocation key
      OP_CHECKSIG ::
    OP_ELSE ::
      OP_PUSHDATA(remotePubkey) :: OP_SWAP ::
      OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
      OP_IF ::
        // To me via HTLC-success transaction
        OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
        OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 ::
        OP_CHECKMULTISIG ::
      OP_ELSE ::
        // To you after timeout
        OP_DROP :: encodeNumber(lockTime) ::
        OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
        OP_CHECKSIG ::
      OP_ENDIF ::
    OP_ENDIF :: Nil

  // @formatter:on

  // TRANSACTION TEMPLATES

  trait TransactionWithInputInfo {
    def input: InputInfo
    def tx: Transaction
  }

  /**
    * When *local* *current* [[CommitTx]] is published:
    *   - [[ClaimDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
    *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    *     - [[ClaimDelayedOutputTx]] spends [[HtlcSuccessTx]] after a delay
    *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *     - [[ClaimDelayedOutputTx]] spends [[HtlcTimeoutTx]] after a delay
    *
    * When *remote* *current* [[CommitTx]] is published:
    *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
    *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *
    * When *remote* *revoked* [[CommitTx]] is published:
    *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
    *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
    *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
    *     - [[HtlcPenaltyTx]] spends [[HtlcSuccessTx]] using the per-commitment secret
    *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by local or remote)
    *     - [[HtlcPenaltyTx]] spends [[HtlcTimeoutTx]] using the per-commitment secret
    */

  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: BinaryData)
  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: BinaryData)
    extends TransactionWithInputInfo

  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo

  val commitWeight = 724
  val htlcTimeoutWeight = 663
  val htlcSuccessWeight = 703
  val claimP2WPKHOutputWeight = 437
  val claimHtlcDelayedWeight = 482
  val claimHtlcSuccessWeight = 570
  val claimHtlcTimeoutWeight = 544
  val mainPenaltyWeight = 483

  // Fee calculation
  def weight2fee(feeratePerKw: Long, weight: Int) =
    Satoshi(feeratePerKw * weight / 1000)

  private def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec) = {
    val htlcTimeoutFee: MilliSatoshi = weight2fee(spec.feeratePerKw, htlcTimeoutWeight) + dustLimit
    spec.htlcs collect { case x if !x.incoming & x.add.amountMsat >= htlcTimeoutFee.amount => x.add }
  }.toSeq

  private def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec) = {
    val htlcSuccessFee: MilliSatoshi = weight2fee(spec.feeratePerKw, htlcSuccessWeight) + dustLimit
    spec.htlcs collect { case x if x.incoming & x.add.amountMsat >= htlcSuccessFee.amount => x.add }
  }.toSeq

  def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = {
    val trimmedOfferedHtlcs = 172 * trimOfferedHtlcs(dustLimit, spec).size
    val trimmedReceivedHtlcs = 172 * trimReceivedHtlcs(dustLimit, spec).size
    val weight = commitWeight + trimmedOfferedHtlcs + trimmedReceivedHtlcs
    weight2fee(spec.feeratePerKw, weight)
  }

  // Commit tx with obscured tx number
  // SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
  def obscuredCommitTxNumber(number: Long, isFunder: Boolean, local: Point, remote: Point) = {
    val (paymentBasepoint1, paymentBasepoint2) = if (isFunder) (local, remote) else (remote, local)
    val combined = paymentBasepoint1.toBin(compressed = true) ++ paymentBasepoint2.toBin(compressed = true)
    val blind = Crypto.sha256(combined).takeRight(6).reverse ++ BinaryData("0x0000")
    number ^ Protocol.uint64(blind, ByteOrder.LITTLE_ENDIAN)
  }

  def getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, local: Point, remote: Point): Long =
    decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime) ^ obscuredCommitTxNumber(0, isFunder, local, remote)

  // This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
  def encodeTxNumber(txnumber: Long) = (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  def decodeTxNumber(sequence: Long, locktime: Long) = (sequence & 0xffffffL).<<(24) + (locktime & 0xffffffL)

  // Tx siging and checking
  def addSigs(commit: CommitTx, localFunding: PublicKey, remoteFunding: PublicKey, localSig: BinaryData, remoteSig: BinaryData): CommitTx =
  commit.modify(_.tx).using(_ updateWitnesses witness2of2(localSig, remoteSig, localFunding, remoteFunding) :: Nil)

  def addSigs(closing: ClosingTx, localFunding: PublicKey, remoteFunding: PublicKey, localSig: BinaryData, remoteSig: BinaryData): ClosingTx =
    closing.modify(_.tx).using(_ updateWitnesses witness2of2(localSig, remoteSig, localFunding, remoteFunding) :: Nil)

  def addSigs(claimMainDelayedRevokedTx: MainPenaltyTx, revocationSig: BinaryData): MainPenaltyTx =
    claimMainDelayedRevokedTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(revocationSig :: BinaryData("01") ::
      claimMainDelayedRevokedTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: BinaryData, remoteSig: BinaryData, paymentPreimage: BinaryData): HtlcSuccessTx =
    htlcSuccessTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: paymentPreimage ::
      htlcSuccessTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: BinaryData, remoteSig: BinaryData): HtlcTimeoutTx =
    htlcTimeoutTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(BinaryData.empty :: remoteSig ::
      localSig :: BinaryData.empty :: htlcTimeoutTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: BinaryData, paymentPreimage: BinaryData): ClaimHtlcSuccessTx =
    claimHtlcSuccessTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: paymentPreimage ::
      claimHtlcSuccessTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: BinaryData): ClaimHtlcTimeoutTx =
    claimHtlcTimeoutTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: BinaryData.empty ::
      claimHtlcTimeoutTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPubkey: BinaryData, localSig: BinaryData): ClaimP2WPKHOutputTx =
    claimP2WPKHOutputTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: localPubkey :: Nil) :: Nil)

  def addSigs(claimHtlcDelayed: ClaimDelayedOutputTx, localSig: BinaryData): ClaimDelayedOutputTx =
    claimHtlcDelayed.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: BinaryData.empty ::
      claimHtlcDelayed.input.redeemScript :: Nil) :: Nil)

  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, amount: Satoshi, key: PrivateKey): BinaryData =
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, key)

  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, key: PrivateKey): BinaryData =
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, Satoshi(0), SIGVERSION_BASE, key)

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey): BinaryData =
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)

  def checkSpendable(txinfo: TransactionWithInputInfo) = Try {
    val check = Map(txinfo.tx.txIn.head.outPoint -> txinfo.input.txOut)
    Transaction.correctlySpends(txinfo.tx, check, STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  def checkSig(txinfo: TransactionWithInputInfo, sig: BinaryData, pubKey: PublicKey): Boolean =
    Crypto.verifySignature(Transaction.hashForSigning(txinfo.tx, 0, txinfo.input.redeemScript,
      SIGHASH_ALL, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0), sig, pubKey)

  def makeCommitTx(commitTxInput: InputInfo, commitTxNumber: Long, localPaymentBasePoint: Point,
                   remotePaymentBasePoint: Point, localIsFunder: Boolean, localDustLimit: Satoshi,
                   localPubKey: PublicKey, localRevocationPubkey: PublicKey, toLocalDelay: Int,
                   localDelayedPubkey: PublicKey, remotePubkey: PublicKey,
                   spec: CommitmentSpec): CommitTx = {

    val commitFee: Satoshi = commitTxFee(localDustLimit, spec)
    val toRemote: Satoshi = MilliSatoshi(spec.toRemoteMsat)
    val toLocal: Satoshi = MilliSatoshi(spec.toLocalMsat)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) =
      if (localIsFunder) (toLocal - commitFee, toRemote)
      else (toLocal, toRemote - commitFee)

    val toLocalDelayedOutput = if (toLocalAmount >= localDustLimit) {
      val pubKeyScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)
      TxOut(toLocalAmount, publicKeyScript = Script pay2wsh pubKeyScript) :: Nil
    } else Nil

    val toRemoteOutput = if (toRemoteAmount >= localDustLimit) {
      TxOut(toRemoteAmount, publicKeyScript = Script pay2wpkh remotePubkey) :: Nil
    } else Nil

    val htlcOfferedOutputs = trimOfferedHtlcs(localDustLimit, spec) map { add =>
      val offered = htlcOffered(localPubKey, remotePubkey, localRevocationPubkey, Crypto ripemd160 add.paymentHash)
      TxOut(amount = MilliSatoshi(add.amountMsat), publicKeyScript = Script pay2wsh offered)
    }

    val htlcReceivedOutputs = trimReceivedHtlcs(localDustLimit, spec) map { add =>
      val received = htlcReceived(localPubKey, remotePubkey, localRevocationPubkey, Crypto ripemd160 add.paymentHash, add.expiry)
      TxOut(amount = MilliSatoshi(add.amountMsat), publicKeyScript = Script pay2wsh received)
    }

    // Make an obscured tx number as defined in BOLT #3 (a 48 bits integer) which we can use in case of contract breach
    val txNumber = obscuredCommitTxNumber(commitTxNumber, localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, locktime) = encodeTxNumber(txNumber)

    val outs = toLocalDelayedOutput ++ toRemoteOutput ++ htlcOfferedOutputs ++ htlcReceivedOutputs
    val in = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = sequence) :: Nil
    val tx = Transaction(version = 2, in, outs, lockTime = locktime)
    CommitTx(commitTxInput, LexicographicalOrdering sort tx)
  }

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData,
                    remoteScriptPubKey: BinaryData, localIsFunder: Boolean,
                    dustLimit: Satoshi, closingFee: Satoshi,
                    spec: CommitmentSpec): ClosingTx = {

    require(spec.htlcs.isEmpty, "Should not be pending htlcs")
    val toRemote: Satoshi = MilliSatoshi(spec.toRemoteMsat)
    val toLocal: Satoshi = MilliSatoshi(spec.toLocalMsat)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) =
      if (localIsFunder) (toLocal - closingFee, toRemote)
      else (toLocal, toRemote - closingFee)

    val toLocalOutput = if (toLocalAmount >= dustLimit) TxOut(toLocalAmount, localScriptPubKey) :: Nil else Nil
    val toRemoteOutput = if (toRemoteAmount >= dustLimit) TxOut(toRemoteAmount, remoteScriptPubKey) :: Nil else Nil
    val in = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = 0xffffffffL) :: Nil
    val tx = Transaction(version = 2, in, toLocalOutput ++ toRemoteOutput, lockTime = 0)
    ClosingTx(commitTxInput, LexicographicalOrdering sort tx)
  }

  // General templates

  def findPubKeyScriptIndex(tx: Transaction, script: BinaryData): Int =
    tx.txOut.indexWhere(_.publicKeyScript == script)

  def makeHtlcTx(parent: Transaction, redeemScript: ScriptEltSeq,
                 pubKeyScript: ScriptEltSeq, amountWithFee: MilliSatoshi,
                 expiry: Long, sequence: Long): (InputInfo, Transaction) = {

    val pay2wsh = Script pay2wsh redeemScript
    val index = findPubKeyScriptIndex(parent, Script write pay2wsh)
    val input = InputInfo(OutPoint(parent, index), parent.txOut(index),
      Script write redeemScript)

    input -> Transaction(version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, sequence) :: Nil,
      txOut = TxOut(amountWithFee, pubKeyScript) :: Nil, lockTime = expiry)
  }

  def makeClaimHtlcTx(parent: Transaction, redeemScript: ScriptEltSeq, pubKeyScript: ScriptEltSeq,
                      localFinalScriptPubKey: BinaryData, fee: Satoshi, expiry: Long,
                      sequence: Long): (InputInfo, Transaction) = {

    val index = findPubKeyScriptIndex(parent, Script write pubKeyScript)
    val input = InputInfo(OutPoint(parent, index), parent.txOut(index),
      Script write redeemScript)

    input -> Transaction(version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, sequence) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = expiry)
  }

  // Concrete templates

  def makeHtlcTxs(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey,
                  toLocalDelay: Int, localPubkey: PublicKey, localDelayedPubkey: PublicKey,
                  remotePubkey: PublicKey, spec: CommitmentSpec) = {

    val htlcTimeoutFee = weight2fee(spec.feeratePerKw, htlcTimeoutWeight)
    val htlcSuccessFee = weight2fee(spec.feeratePerKw, htlcSuccessWeight)

    def makeHtlcTimeoutTx(add: UpdateAddHtlc) = {
      val paymentHash160 = Crypto ripemd160 add.paymentHash
      val amountWithFee = MilliSatoshi(add.amountMsat) - htlcTimeoutFee
      val pubKeyScript = Script pay2wsh toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)
      val (input, tx) = makeHtlcTx(commitTx, htlcOffered(localPubkey, remotePubkey, localRevocationPubkey,
        paymentHash160), pubKeyScript, amountWithFee, add.expiry, 0x00000000L)

      HtlcTimeoutTx(input, tx)
    }

    def makeHtlcSuccessTx(add: UpdateAddHtlc) = {
      val paymentHash160 = Crypto ripemd160 add.paymentHash
      val amountWithFee = MilliSatoshi(add.amountMsat) - htlcSuccessFee
      val pubKeyScript = Script pay2wsh toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)
      val (input, tx) = makeHtlcTx(commitTx, htlcReceived(localPubkey, remotePubkey, localRevocationPubkey,
        paymentHash160, add.expiry), pubKeyScript, amountWithFee, 0L, 0x00000000L)

      HtlcSuccessTx(input, tx, add.paymentHash)
    }

    // Dusty HTLCs are filtered out and thus go to fees
    val htlcTimeoutTxs = trimOfferedHtlcs(localDustLimit, spec) map makeHtlcTimeoutTx
    val htlcSuccessTxs = trimReceivedHtlcs(localDustLimit, spec) map makeHtlcSuccessTx
    htlcTimeoutTxs -> htlcSuccessTxs
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction, localPubkey: PublicKey, remotePubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData,
                             add: UpdateAddHtlc, feeratePerKw: Long): ClaimHtlcTimeoutTx = {

    val hash = Crypto ripemd160 add.paymentHash
    val redeem = htlcReceived(remotePubkey, localPubkey, remoteRevocationPubkey, hash, add.expiry)
    ClaimHtlcTimeoutTx tupled makeClaimHtlcTx(commitTx, redeem, pubKeyScript = Script pay2wsh redeem,
      localFinalScriptPubKey, weight2fee(feeratePerKw, claimHtlcTimeoutWeight), add.expiry, 0x00000000L)
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction, localPubkey: PublicKey, remotePubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData,
                             add: UpdateAddHtlc, feeratePerKw: Long): ClaimHtlcSuccessTx = {

    val hash = Crypto ripemd160 add.paymentHash
    val redeem = htlcOffered(remotePubkey, localPubkey, remoteRevocationPubkey, hash)
    ClaimHtlcSuccessTx tupled makeClaimHtlcTx(commitTx, redeem, pubKeyScript = Script pay2wsh redeem,
      localFinalScriptPubKey, weight2fee(feeratePerKw, claimHtlcSuccessWeight), 0L, 0xffffffffL)
  }

  def makeClaimP2WPKHOutputTx(delayedOutputTx: Transaction, localPubkey: PublicKey,
                              localFinalScriptPubKey: BinaryData, feeratePerKw: Long) =

    ClaimP2WPKHOutputTx tupled makeClaimHtlcTx(parent = delayedOutputTx,
      redeemScript = Script pay2pkh localPubkey, pubKeyScript = Script pay2wpkh localPubkey,
      localFinalScriptPubKey, weight2fee(feeratePerKw, claimP2WPKHOutputWeight), 0L, 0x00000000L)

  def makeClaimDelayedOutputTx(delayedOutputTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int,
                               localDelayedPubkey: PublicKey, localFinalScriptPubKey: BinaryData,
                               feeratePerKw: Long): ClaimDelayedOutputTx = {

    val redeem = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)
    ClaimDelayedOutputTx tupled makeClaimHtlcTx(delayedOutputTx, redeem, pubKeyScript = Script pay2wsh redeem,
      localFinalScriptPubKey, weight2fee(feeratePerKw, claimHtlcDelayedWeight), 0L, sequence = toLocalDelay)
  }

  def makeMainPenaltyTx(commitTx: Transaction, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData,
                        toRemoteDelay: Int, remoteDelayedPubkey: PublicKey, feeratePerKw: Long): MainPenaltyTx = {

    val redeem = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPubkey)
    MainPenaltyTx tupled makeClaimHtlcTx(commitTx, redeem, pubKeyScript = Script pay2wsh redeem,
      localFinalScriptPubKey, weight2fee(feeratePerKw, mainPenaltyWeight), expiry = 0L, 0xffffffffL)
  }
}