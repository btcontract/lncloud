package com.lightning.walletapp.ln

import fr.acinq.bitcoin.Crypto._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.Scripts._
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import com.lightning.walletapp.ln.CommitmentSpec.{HtlcAndFail, HtlcAndFulfill}
import com.lightning.walletapp.ln.wire.LightningMessageCodecs.{LNMessageVector, RedeemScriptAndSig}
import com.lightning.walletapp.ln.crypto.ShaHashesWithIndex
import fr.acinq.eclair.UInt64


sealed trait CommitPublished
case class RevokedCommitPublished(claimMain: Seq[ClaimP2WPKHOutputTx], claimTheirMainPenalty: Seq[MainPenaltyTx],
                                  htlcPenalty: Seq[HtlcPenaltyTx], commitTx: Transaction) extends CommitPublished

case class RevocationInfo(redeemScriptsToSigs: List[RedeemScriptAndSig], claimMainTxSig: Option[BinaryData],
                          claimPenaltyTxSig: Option[BinaryData], feeRate: Long, dustLimit: Long, finalScriptPubKey: BinaryData,
                          toSelfDelay: Int, localPubKey: PublicKey, remoteRevocationPubkey: PublicKey, remoteDelayedPaymentKey: PublicKey) {

  lazy val dustLim = Satoshi(dustLimit)
  def makeClaimP2WPKHOutput(tx: Transaction) = Scripts.makeClaimP2WPKHOutputTx(tx, localPubKey, finalScriptPubKey, feeRate, dustLim)
  def makeHtlcPenalty(finder: PubKeyScriptIndexFinder)(rs: BinaryData) = Scripts.makeHtlcPenaltyTx(finder, rs, finalScriptPubKey, feeRate, dustLim)
  def makeMainPenalty(tx: Transaction) = Scripts.makeMainPenaltyTx(tx, remoteRevocationPubkey, finalScriptPubKey, toSelfDelay, remoteDelayedPaymentKey,
    feeRate, dustLim)
}


// COMMITMENTS

case class Htlc(incoming: Boolean, add: UpdateAddHtlc)
case class CommitmentSpec(feeratePerKw: Long, toLocalMsat: Long, toRemoteMsat: Long,
                          htlcs: Set[Htlc] = Set.empty, fulfilled: Set[HtlcAndFulfill] = Set.empty,
                          failed: Set[HtlcAndFail] = Set.empty, malformed: Set[Htlc] = Set.empty)

object CommitmentSpec {
  def findHtlcById(cs: CommitmentSpec, id: Long, isIncoming: Boolean): Option[Htlc] =
    cs.htlcs.find(htlc => htlc.add.id == id && htlc.incoming == isIncoming)

  type HtlcAndFulfill = (Htlc, UpdateFulfillHtlc)
  def fulfill(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFulfillHtlc) = findHtlcById(cs, m.id, isIncoming) match {
    case Some(h) if h.incoming => cs.copy(toLocalMsat = cs.toLocalMsat + h.add.amountMsat, fulfilled = cs.fulfilled + Tuple2(h, m), htlcs = cs.htlcs - h)
    case Some(h) => cs.copy(toRemoteMsat = cs.toRemoteMsat + h.add.amountMsat, fulfilled = cs.fulfilled + Tuple2(h, m), htlcs = cs.htlcs - h)
    case None => cs
  }

  type HtlcAndFail = (Htlc, UpdateFailHtlc)
  def fail(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFailHtlc) = findHtlcById(cs, m.id, isIncoming) match {
    case Some(h) if h.incoming => cs.copy(toRemoteMsat = cs.toRemoteMsat + h.add.amountMsat, failed = cs.failed + Tuple2(h, m), htlcs = cs.htlcs - h)
    case Some(h) => cs.copy(toLocalMsat = cs.toLocalMsat + h.add.amountMsat, failed = cs.failed + Tuple2(h, m), htlcs = cs.htlcs - h)
    case None => cs
  }

  def failMalformed(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFailMalformedHtlc) = findHtlcById(cs, m.id, isIncoming) match {
    case Some(h) if h.incoming => cs.copy(toRemoteMsat = cs.toRemoteMsat + h.add.amountMsat, malformed = cs.malformed + h, htlcs = cs.htlcs - h)
    case Some(h) => cs.copy(toLocalMsat = cs.toLocalMsat + h.add.amountMsat, malformed = cs.malformed + h, htlcs = cs.htlcs - h)
    case None => cs
  }

  def plusOutgoing(data: UpdateAddHtlc, cs: CommitmentSpec) =
    cs.copy(htlcs = cs.htlcs + Htlc(incoming = false, add = data),
      toLocalMsat = cs.toLocalMsat - data.amountMsat)

  def plusIncoming(data: UpdateAddHtlc, cs: CommitmentSpec) =
    cs.copy(htlcs = cs.htlcs + Htlc(incoming = true, add = data),
      toRemoteMsat = cs.toRemoteMsat - data.amountMsat)

  def reduce(cs: CommitmentSpec, local: LNMessageVector, remote: LNMessageVector) = {
    val spec1 = cs.copy(fulfilled = Set.empty, failed = Set.empty, malformed = Set.empty)
    val spec2 = (spec1 /: local) { case (s, add: UpdateAddHtlc) => plusOutgoing(add, s) case s \ _ => s }
    val spec3 = (spec2 /: remote) { case (s, add: UpdateAddHtlc) => plusIncoming(add, s) case s \ _ => s }

    val spec4 = (spec3 /: local) {
      case (s, msg: UpdateFee) => s.copy(feeratePerKw = msg.feeratePerKw)
      case (s, msg: UpdateFulfillHtlc) => fulfill(s, isIncoming = true, msg)
      case (s, msg: UpdateFailMalformedHtlc) => failMalformed(s, isIncoming = true, msg)
      case (s, msg: UpdateFailHtlc) => fail(s, isIncoming = true, msg)
      case s \ _ => s
    }

    (spec4 /: remote) {
      case (s, msg: UpdateFee) => s.copy(feeratePerKw = msg.feeratePerKw)
      case (s, msg: UpdateFulfillHtlc) => fulfill(s, isIncoming = false, msg)
      case (s, msg: UpdateFailMalformedHtlc) => failMalformed(s, isIncoming = false, msg)
      case (s, msg: UpdateFailHtlc) => fail(s, isIncoming = false, msg)
      case s \ _ => s
    }
  }
}

case class LocalParams(maxHtlcValueInFlightMsat: UInt64, channelReserveSat: Long, toSelfDelay: Int,
                       maxAcceptedHtlcs: Int, fundingPrivKey: PrivateKey, revocationSecret: Scalar,
                       paymentKey: Scalar, delayedPaymentKey: Scalar, htlcKey: Scalar,
                       defaultFinalScriptPubKey: BinaryData, dustLimit: Satoshi,
                       shaSeed: BinaryData, isFunder: Boolean) {

  lazy val delayedPaymentBasepoint = delayedPaymentKey.toPoint
  lazy val revocationBasepoint = revocationSecret.toPoint
  lazy val paymentBasepoint = paymentKey.toPoint
  lazy val htlcBasepoint = htlcKey.toPoint
}

case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, localCommitIndexSnapshot: Long)
case class LocalCommit(index: Long, spec: CommitmentSpec, htlcTxsAndSigs: Seq[HtlcTxAndSigs], commitTx: CommitTx)
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, txOpt: Option[Transaction], remotePerCommitmentPoint: Point) // TODO: remove later
case class HtlcTxAndSigs(txinfo: TransactionWithInputInfo, localSig: BinaryData, remoteSig: BinaryData)
case class Changes(proposed: LNMessageVector, signed: LNMessageVector, acked: LNMessageVector)

case class ReducedState(htlcs: Set[Htlc], canSendMsat: Long, canReceiveMsat: Long, myFeeSat: Long, theirFeeSat: Long)
case class Commitments(localParams: LocalParams, remoteParams: AcceptChannel, localCommit: LocalCommit, remoteCommit: RemoteCommit, localChanges: Changes,
                       remoteChanges: Changes, localNextHtlcId: Long, remoteNextHtlcId: Long, remoteNextCommitInfo: Either[WaitingForRevocation, Point],
                       commitInput: InputInfo, remotePerCommitmentSecrets: ShaHashesWithIndex, channelId: BinaryData, extraHop: Option[Hop] = None,
                       startedAt: Long = System.currentTimeMillis) { me =>

  lazy val reducedRemoteState = {
    val reduced = CommitmentSpec.reduce(Commitments.latestRemoteCommit(me).spec, remoteChanges.acked, localChanges.proposed)
    val commitFeeSat = Scripts.commitTxFee(dustLimit = remoteParams.dustLimitSat, spec = reduced).amount
    val myFeeSat \ theirFeeSat = if (localParams.isFunder) commitFeeSat -> 0L else 0L -> commitFeeSat

    val canSendMsat = reduced.toRemoteMsat - (myFeeSat + remoteParams.channelReserveSatoshis) * 1000L
    val canReceiveMsat = localCommit.spec.toRemoteMsat - (theirFeeSat + localParams.channelReserveSat) * 1000L
    ReducedState(reduced.htlcs, canSendMsat, canReceiveMsat, myFeeSat, theirFeeSat)
  }
}

object Commitments {
  def fundingTxid(c: Commitments) = BinaryData(c.commitInput.outPoint.hash.reverse)
  def latestRemoteCommit(c: Commitments) = c.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit) getOrElse c.remoteCommit
}