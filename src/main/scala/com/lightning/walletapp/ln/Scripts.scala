package com.lightning.walletapp.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin.Protocol._
import com.softwaremill.quicklens._

import com.lightning.walletapp.ln.wire.UpdateAddHtlc
import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
import scala.language.postfixOps
import scodec.bits.ByteVector
import java.nio.ByteOrder
import scala.util.Try


object Scripts { me =>
  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey) =
    LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin) match {
      case false => Script.createMultiSigMofN(m = 2, pubkey2 :: pubkey1 :: Nil)
      case true => Script.createMultiSigMofN(m = 2, pubkey1 :: pubkey2 :: Nil)
    }

  def witness2of2(sig1: ByteVector, sig2: ByteVector, pubkey1: PublicKey, pubkey2: PublicKey) =
    LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin) -> multiSig2of2(pubkey1, pubkey2) match {
      case (false, multisig) => ScriptWitness(ByteVector.empty :: sig2 :: sig1 :: Script.write(multisig) :: Nil)
      case (true, multisig) => ScriptWitness(ByteVector.empty :: sig1 :: sig2 :: Script.write(multisig) :: Nil)
    }

  def pubKeyScript(pub1: PublicKey, pub2: PublicKey) = {
    val multisigScript = Scripts.multiSig2of2(pub1, pub2)
    Script.write(Script pay2wsh multisigScript)
  }

  def cltvBlocks(tx: Transaction): Long =
    if (tx.lockTime <= LockTimeThreshold) tx.lockTime else 0L

  def csvTimeout(tx: Transaction): Long =
    if (tx.version < 2) 0L else tx.txIn map { in =>
      val isCsvDisabled = (in.sequence & TxIn.SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0L
      if (isCsvDisabled) 0L else in.sequence & TxIn.SEQUENCE_LOCKTIME_MASK
    } max

  def encodeNumber(number: Long): ScriptElt = number match {
    case n if n < -1 | n > 16 => OP_PUSHDATA(Script encodeNumber n)

    case x if x >= 1 && x <= 16 =>
      val code = ScriptElt elt2code OP_1
      val result = (code + x - 1).toInt
      ScriptElt code2elt result

    case -1 => OP_1NEGATE
    case 0 => OP_0
  }

  // LN SCRIPTS

  def toLocalDelayed(revocationPubkey: PublicKey, toSelfDelay: Int,
                     localDelayedPaymentPubkey: PublicKey) =

    OP_IF ::
      OP_PUSHDATA(revocationPubkey) ::
      OP_ELSE ::
      encodeNumber(toSelfDelay) ::
      OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedPaymentPubkey) ::
      OP_ENDIF ::
      OP_CHECKSIG :: Nil

  // This witness script spends a [[toLocalDelayed]] output using a revocation key as a punishment
  def witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector, toLocalScript: ByteVector) =
  ScriptWitness(revocationSig :: ByteVector(1) :: toLocalScript :: Nil)

  def htlcOffered(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey,
                  revocationPubKey: PublicKey, payHash160: ByteVector) =

    OP_DUP :: OP_HASH160 ::
      OP_PUSHDATA(revocationPubKey.hash160) ::
      OP_EQUAL ::
      OP_IF ::
      // To you with revocation key
      OP_CHECKSIG ::
      OP_ELSE ::
      OP_PUSHDATA(remoteHtlcPubkey) :: OP_SWAP ::
      OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
      OP_NOTIF ::
      // To me via timelocked HTLC-timeout transaction
      OP_DROP :: OP_2 :: OP_SWAP ::
      OP_PUSHDATA(localHtlcPubkey) ::
      OP_2 :: OP_CHECKMULTISIG ::
      OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(payHash160) ::
      OP_EQUALVERIFY :: OP_CHECKSIG ::
      OP_ENDIF ::
      OP_ENDIF :: Nil

  def htlcReceived(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey,
                   revocationPubKey: PublicKey, payHash160: ByteVector, lockTime: Long) =

    OP_DUP :: OP_HASH160 ::
      OP_PUSHDATA(revocationPubKey.hash160) ::
      OP_EQUAL ::
      OP_IF ::
      // To you with revocation key
      OP_CHECKSIG ::
      OP_ELSE ::
      OP_PUSHDATA(remoteHtlcPubkey) :: OP_SWAP ::
      OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
      OP_IF ::
      // To me via HTLC-success transaction
      OP_HASH160 :: OP_PUSHDATA(payHash160) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localHtlcPubkey) ::
      OP_2 :: OP_CHECKMULTISIG ::
      OP_ELSE ::
      // To you after timeout
      OP_DROP :: encodeNumber(lockTime) ::
      OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
      OP_CHECKSIG ::
      OP_ENDIF ::
      OP_ENDIF :: Nil

  // TRANSACTION TEMPLATES

  trait TransactionWithInputInfo {
    // Input of current tx subtracted from output of next tx outputs reveals a final fee
    def --(that: TransactionWithInputInfo) = input.txOut.amount - that.tx.allOutputsAmount
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

  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: ByteVector)
  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo

  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, add: UpdateAddHtlc) extends TransactionWithInputInfo
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction, add: UpdateAddHtlc) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(addOpt: Option[UpdateAddHtlc], input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo

  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  def weight2fee(perKw: Long, weight: Int) = Satoshi(perKw * weight / 1000L)

  val htlcWeight = 172
  val commitWeight = 724
  val htlcTimeoutWeight = 663
  val htlcSuccessWeight = 703
  val claimP2WPKHOutputWeight = 437
  val claimHtlcDelayedWeight = 482
  val claimHtlcSuccessWeight = 570
  val claimHtlcTimeoutWeight = 544
  val mainPenaltyWeight = 483
  val htlcPenaltyWeight = 577

  private def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec) = {
    val htlcTimeoutFee = weight2fee(spec.feeratePerKw, htlcTimeoutWeight) + dustLimit
    spec.htlcs.collect { case Htlc(false, add) if add.amount >= htlcTimeoutFee => add }.toSeq
  }

  private def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec) = {
    val htlcSuccessFee = weight2fee(spec.feeratePerKw, htlcSuccessWeight) + dustLimit
    spec.htlcs.collect { case Htlc(true, add) if add.amount >= htlcSuccessFee => add }.toSeq
  }

  def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec) = {
    val trimmedOfferedHtlcs = htlcWeight * trimOfferedHtlcs(dustLimit, spec).size
    val trimmedReceivedHtlcs = htlcWeight * trimReceivedHtlcs(dustLimit, spec).size
    weight2fee(spec.feeratePerKw, commitWeight + trimmedOfferedHtlcs + trimmedReceivedHtlcs)
  }

  // Commit tx with obscured tx number
  // SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
  def obscuredCommitTxNumber(number: Long, isFunder: Boolean, local: Point, remote: Point) = {
    val (paymentBasepoint1, paymentBasepoint2) = if (isFunder) (local, remote) else (remote, local)
    val combined = paymentBasepoint1.toBin(compressed = true) ++ paymentBasepoint2.toBin(compressed = true)
    val blind = (Crypto sha256 combined takeRight 6).reverse ++ ByteVector.fromValidHex("0000")
    number ^ Protocol.uint64(blind.toArray, ByteOrder.LITTLE_ENDIAN)
  }

  def getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, local: Point, remote: Point): Long =
    decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime) ^ obscuredCommitTxNumber(0, isFunder, local, remote)

  // This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
  def encodeTxNumber(txnumber: Long) = (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  def decodeTxNumber(sequence: Long, locktime: Long) = (sequence & 0xffffffL).<<(24) + (locktime & 0xffffffL)

  def addSigs(commit: CommitTx, localKey: PublicKey, remoteKey: PublicKey, localSig: ByteVector, remoteSig: ByteVector): CommitTx =
    commit.modify(_.tx).using(_ updateWitnesses witness2of2(localSig, remoteSig, localKey, remoteKey) :: Nil)

  def addSigs(closing: ClosingTx, localFunding: PublicKey, remoteFunding: PublicKey, localSig: ByteVector, remoteSig: ByteVector): ClosingTx =
    closing.modify(_.tx).using(_ updateWitnesses witness2of2(localSig, remoteSig, localFunding, remoteFunding) :: Nil)

  def addSigs(mainPenaltyTx: MainPenaltyTx, revocationSig: ByteVector): MainPenaltyTx =
    mainPenaltyTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(revocationSig :: ByteVector(1) ::
      mainPenaltyTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(htlcPenaltyTx: HtlcPenaltyTx, revocationSig: ByteVector, revocationPubkey: PublicKey): HtlcPenaltyTx =
    htlcPenaltyTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(revocationSig :: revocationPubkey.toBin ::
      htlcPenaltyTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: ByteVector, remoteSig: ByteVector, paymentPreimage: ByteVector): HtlcSuccessTx =
    htlcSuccessTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(ByteVector.empty :: remoteSig :: localSig :: paymentPreimage ::
      htlcSuccessTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: ByteVector, remoteSig: ByteVector): HtlcTimeoutTx =
    htlcTimeoutTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(ByteVector.empty :: remoteSig ::
      localSig :: ByteVector.empty :: htlcTimeoutTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: ByteVector, paymentPreimage: ByteVector): ClaimHtlcSuccessTx =
    claimHtlcSuccessTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: paymentPreimage ::
      claimHtlcSuccessTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: ByteVector): ClaimHtlcTimeoutTx =
    claimHtlcTimeoutTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: ByteVector.empty ::
      claimHtlcTimeoutTx.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localSig: ByteVector, localPaymentPubkey: ByteVector): ClaimP2WPKHOutputTx =
    claimP2WPKHOutputTx.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: localPaymentPubkey :: Nil) :: Nil)

  def addSigs(claimHtlcDelayed: ClaimDelayedOutputTx, localSig: ByteVector): ClaimDelayedOutputTx =
    claimHtlcDelayed.modify(_.tx).using(_ updateWitnesses ScriptWitness(localSig :: ByteVector.empty ::
      claimHtlcDelayed.input.redeemScript :: Nil) :: Nil)

  def addSigs(claimHtlcDelayedPenalty: ClaimDelayedOutputPenaltyTx, revocationSig: ByteVector): ClaimDelayedOutputPenaltyTx =
    claimHtlcDelayedPenalty.modify(_.tx).using(_ updateWitnesses Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig,
      claimHtlcDelayedPenalty.input.redeemScript) :: Nil)

  def sign(tx: Transaction, inputIndex: Int, redeemScript: ByteVector, amount: Satoshi, key: PrivateKey): ByteVector =
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, key)

  def sign(key: PrivateKey)(txinfo: TransactionWithInputInfo): ByteVector =
    sign(txinfo.tx, 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)

  def checkValid[T <: TransactionWithInputInfo](txWithInputInfo: => T) = Try {
    if (txWithInputInfo.tx.txOut.isEmpty) throw new Exception("Empty transaction found")
    val check = Map(txWithInputInfo.tx.txIn.head.outPoint -> txWithInputInfo.input.txOut)
    Transaction.correctlySpends(txWithInputInfo.tx, check, STANDARD_SCRIPT_VERIFY_FLAGS)
    txWithInputInfo
  }

  def checkSig(txinfo: TransactionWithInputInfo, sig: ByteVector, pubKey: PublicKey): Boolean =
    Crypto.verifySignature(Transaction.hashForSigning(txinfo.tx, 0, txinfo.input.redeemScript,
      SIGHASH_ALL, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0), sig, pubKey)

  def makeCommitTx(commitTxInput: InputInfo, commitTxNumber: Long, localPaymentBasePoint: Point, remotePaymentBasePoint: Point,
                   localIsFunder: Boolean, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int,
                   localDelayedPaymentPubkey: PublicKey, remotePaymentPubkey: PublicKey, localHtlcPubkey: PublicKey,
                   remoteHtlcPubkey: PublicKey, spec: CommitmentSpec): CommitTx = {

    val commitFee = commitTxFee(localDustLimit, spec)
    val toRemote = Satoshi(spec.toRemoteMsat / 1000L)
    val toLocal = Satoshi(spec.toLocalMsat / 1000L)

    val redeem = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
    val localSat \ remoteSat = if (localIsFunder) Tuple2(toLocal - commitFee, toRemote) else Tuple2(toLocal, toRemote - commitFee)
    val toRemoteOutput = if (remoteSat < localDustLimit) Nil else TxOut(remoteSat, Script pay2wpkh remotePaymentPubkey) :: Nil
    val toLocalDelayedOutput = if (localSat < localDustLimit) Nil else TxOut(localSat, Script pay2wsh redeem) :: Nil

    val htlcOfferedOutputs = trimOfferedHtlcs(dustLimit = localDustLimit, spec) map { add =>
      val offered = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, add.hash160)
      TxOut(add.amount, Script pay2wsh offered)
    }

    val htlcReceivedOutputs = trimReceivedHtlcs(dustLimit = localDustLimit, spec) map { add =>
      val received = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, add.hash160, add.expiry)
      TxOut(add.amount, Script pay2wsh received)
    }

    val sequence \ locktime =
      me encodeTxNumber obscuredCommitTxNumber(commitTxNumber,
        localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)

    val outs = toLocalDelayedOutput ++ toRemoteOutput ++ htlcOfferedOutputs ++ htlcReceivedOutputs
    val in = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence) :: Nil
    val tx = Transaction(version = 2, in, outs, lockTime = locktime)
    CommitTx(commitTxInput, LexicographicalOrdering sort tx)
  }

  // Concrete templates

  def makeHtlcTxs(commitTx: Transaction, dustLimit: Satoshi, localRevPubkey: PublicKey,
                  toLocalDelay: Int, localDelayedPaymentPubkey: PublicKey, localHtlcPubkey: PublicKey,
                  remoteHtlcPubkey: PublicKey, spec: CommitmentSpec) = {

    val finder = new PubKeyScriptIndexFinder(commitTx)
    def makeHtlcTx(redeem: Seq[ScriptElt], pubKeyScript: Seq[ScriptElt], amount: Satoshi, fee: Satoshi, expiry: Long) = {
      val index = finder.findPubKeyScriptIndex(pubkeyScript = Script.write(Script pay2wsh redeem), Option apply amount)
      val inputInfo = InputInfo(OutPoint(commitTx, index), commitTx.txOut(index), Script write redeem)
      val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0x00000000L) :: Nil
      val txOut = TxOut(amount - fee, pubKeyScript) :: Nil
      val tx = Transaction(2, txIn, txOut, expiry)
      inputInfo -> tx
    }

    val htlcTimeoutTxs = trimOfferedHtlcs(dustLimit, spec) map { add =>
      val offered = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevPubkey, add.hash160)
      val pubKeyScript = Script pay2wsh toLocalDelayed(localRevPubkey, toLocalDelay, localDelayedPaymentPubkey)
      val info \ tx = makeHtlcTx(offered, pubKeyScript, add.amount, weight2fee(spec.feeratePerKw, htlcTimeoutWeight), add.expiry)
      HtlcTimeoutTx(info, tx, add)
    }

    val htlcSuccessTxs = trimReceivedHtlcs(dustLimit, spec) map { add =>
      val received = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevPubkey, add.hash160, add.expiry)
      val pubKeyScript = Script pay2wsh toLocalDelayed(localRevPubkey, toLocalDelay, localDelayedPaymentPubkey)
      val info \ tx = makeHtlcTx(received, pubKeyScript, add.amount, weight2fee(spec.feeratePerKw, htlcSuccessWeight), 0L)
      HtlcSuccessTx(info, tx, add)
    }

    // Dusty HTLCs are filtered to fees
    htlcTimeoutTxs -> htlcSuccessTxs
  }

  def makeClaimHtlcTimeoutTx(finder: PubKeyScriptIndexFinder, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, add: UpdateAddHtlc,
                             feeratePerKw: Long, dustLimit: Satoshi): Try[ClaimHtlcTimeoutTx] = Try {

    val redeem = htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, add.hash160, add.expiry)
    val index = finder.findPubKeyScriptIndex(Script.write(Script pay2wsh redeem), Option apply add.amount)
    val inputInfo = InputInfo(OutPoint(finder.tx, index), finder.tx.txOut(index), Script write redeem)
    val finalAmount = inputInfo.txOut.amount - weight2fee(feeratePerKw, claimHtlcTimeoutWeight)
    if (finalAmount < dustLimit) throw new LightningException("ClaimTx amount below dust")
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0x00000000L) :: Nil
    val txOut = TxOut(finalAmount, localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = add.expiry)
    ClaimHtlcTimeoutTx(Some(add), inputInfo, tx)
  }

  def makeClaimHtlcSuccessTx(finder: PubKeyScriptIndexFinder, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, add: UpdateAddHtlc,
                             feeratePerKw: Long, dustLimit: Satoshi): Try[ClaimHtlcSuccessTx] = Try {

    val redeem = htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, add.hash160)
    val index = finder.findPubKeyScriptIndex(Script.write(Script pay2wsh redeem), Option apply add.amount)
    val inputInfo = InputInfo(OutPoint(finder.tx, index), finder.tx.txOut(index), Script write redeem)
    val finalAmount = inputInfo.txOut.amount - weight2fee(feeratePerKw, claimHtlcSuccessWeight)
    if (finalAmount < dustLimit) throw new LightningException("ClaimTx amount below dust")
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0xffffffffL) :: Nil
    val txOut = TxOut(finalAmount, localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = 0L)
    ClaimHtlcSuccessTx(inputInfo, tx)
  }

  def makeClaimP2WPKHOutputTx(delayedOutputTx: Transaction, localPaymentPubkey: PublicKey,
                              localFinalScriptPubKey: ByteVector, feeratePerKw: Long,
                              dustLimit: Satoshi): Try[ClaimP2WPKHOutputTx] = Try {

    val redeem = Script pay2pkh localPaymentPubkey
    val finder = new PubKeyScriptIndexFinder(delayedOutputTx)
    val index = finder.findPubKeyScriptIndex(Script.write(Script pay2wpkh localPaymentPubkey), None)
    val inputInfo = InputInfo(OutPoint(delayedOutputTx, index), delayedOutputTx.txOut(index), Script write redeem)
    val finalAmount = inputInfo.txOut.amount - weight2fee(feeratePerKw, claimP2WPKHOutputWeight)
    if (finalAmount < dustLimit) throw new LightningException("ClaimTx amount below dust")
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0x00000000L) :: Nil
    val txOut = TxOut(finalAmount, localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = 0L)
    ClaimP2WPKHOutputTx(inputInfo, tx)
  }

  def makeClaimDelayedOutputTx(delayedOutputTx: Transaction,
                               localRevocationPubkey: PublicKey, toLocalDelay: Int,
                               remoteDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector,
                               feeratePerKw: Long, dustLimit: Satoshi): Try[ClaimDelayedOutputTx] = Try {

    val finder = new PubKeyScriptIndexFinder(delayedOutputTx)
    val redeem = toLocalDelayed(localRevocationPubkey, toLocalDelay, remoteDelayedPaymentPubkey)
    val index = finder.findPubKeyScriptIndex(pubkeyScript = Script.write(Script pay2wsh redeem), None)
    val inputInfo = InputInfo(OutPoint(delayedOutputTx, index), delayedOutputTx.txOut(index), Script write redeem)
    val finalAmount = inputInfo.txOut.amount - weight2fee(feeratePerKw, claimHtlcDelayedWeight)
    if (finalAmount < dustLimit) throw new LightningException("ClaimTx amount below dust")
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, toLocalDelay) :: Nil
    val txOut = TxOut(finalAmount, localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = 0L)
    ClaimDelayedOutputTx(inputInfo, tx)
  }

  def makeMainPenaltyTx(commitTx: Transaction,
                        remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector,
                        toRemoteDelay: Int, remoteDelayedPaymentPubkey: PublicKey, feeratePerKw: Long,
                        dustLimit: Satoshi): Try[MainPenaltyTx] = Try {

    val finder = new PubKeyScriptIndexFinder(commitTx)
    val redeem = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
    val index = finder.findPubKeyScriptIndex(pubkeyScript = Script.write(Script pay2wsh redeem), None)
    val inputInfo = InputInfo(OutPoint(commitTx, index), commitTx.txOut(index), Script write redeem)
    val finalAmount = inputInfo.txOut.amount - weight2fee(feeratePerKw, mainPenaltyWeight)
    if (finalAmount < dustLimit) throw new LightningException("ClaimTx amount below dust")
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0xffffffffL) :: Nil
    val txOut = TxOut(finalAmount, localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = 0L)
    MainPenaltyTx(inputInfo, tx)
  }

  def makeHtlcPenaltyTx(finder: PubKeyScriptIndexFinder, redeem: ByteVector,
                        localFinalScriptPubKey: ByteVector, feeratePerKw: Long,
                        dustLimit: Satoshi): Try[HtlcPenaltyTx] = Try {

    val index = finder.findPubKeyScriptIndex(pubkeyScript = Script.write(Script pay2wsh redeem), None)
    val inputInfo = InputInfo(outPoint = OutPoint(finder.tx, index), finder.tx.txOut(index), redeem)
    val finalAmount = inputInfo.txOut.amount - weight2fee(feeratePerKw, htlcPenaltyWeight)
    if (finalAmount < dustLimit) throw new LightningException("ClaimTx amount below dust")
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0xffffffffL) :: Nil
    val txOut = TxOut(finalAmount, localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = 0L)
    HtlcPenaltyTx(inputInfo, tx)
  }

  def makeClaimDelayedOutputPenaltyTx(delayedOutputTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int,
                                      localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: Long,
                                      dustLimit: Satoshi): Try[ClaimDelayedOutputPenaltyTx] = Try {

    val finder = new PubKeyScriptIndexFinder(delayedOutputTx)
    val redeem = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
    val index = finder.findPubKeyScriptIndex(pubkeyScript = Script.write(Script pay2wsh redeem), None)
    val inputInfo = InputInfo(OutPoint(delayedOutputTx, index), delayedOutputTx.txOut(index), Script write redeem)

    // Make an unsigned dummy transaction
    val txIn = TxIn(inputInfo.outPoint, ByteVector.empty, 0xffffffffL) :: Nil
    val txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil
    val tx = Transaction(2, txIn, txOut, lockTime = 0)

    // 73 bytes signature which is the largest one can get
    val claim = ClaimDelayedOutputPenaltyTx(inputInfo, tx)
    val dummySig = ByteVector.fill(73)(0)

    val approx = Scripts.addSigs(claim, dummySig).tx.weight(PROTOCOL_VERSION)
    val finalAmount = inputInfo.txOut.amount - weight2fee(perKw = feeratePerKw, weight = approx)
    if (finalAmount < dustLimit) throw new LightningException("HtlcPunishTx amount below dust")
    val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = finalAmount) :: Nil)
    ClaimDelayedOutputPenaltyTx(inputInfo, tx1)
  }
}

class PubKeyScriptIndexFinder(val tx: Transaction) {
  private[this] var indexesAlreadyUsed = Set.empty[Long]
  private[this] val indexedOutputs = tx.txOut.zipWithIndex

  def findPubKeyScriptIndex(pubkeyScript: ByteVector, amountOpt: Option[Satoshi] = None): Int = {
    // It is never enough to resolve on pubkeyScript alone because we may have duplicate HTLC payments
    // hence we collect an already used output indexes and make sure payment sums are matched in some cases

    val index = indexedOutputs indexWhere { case out \ idx =>
      val isOutputUsedAlready = indexesAlreadyUsed contains idx
      val amountMatches = amountOpt.forall(_ == out.amount)
      val scriptOk = out.publicKeyScript == pubkeyScript
      !isOutputUsedAlready & amountMatches & scriptOk
    }

    if (index < 0) throw new LightningException
    indexesAlreadyUsed += index
    index
  }
}