package com.lightning.wallet.ln

import fr.acinq.bitcoin.Crypto._
import com.softwaremill.quicklens._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.Scripts._
import com.lightning.wallet.ln.Exceptions._

import com.lightning.wallet.ln.crypto.{Generators, ShaChain, ShaHashesWithIndex}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Transaction, TxOut}
import com.lightning.wallet.ln.Tools.LightningMessages
import com.lightning.wallet.ln.MSat.satFactor


sealed trait Command
case object CMDShutdown extends Command
case object CMDCommitSig extends Command
case object CMDClosingFinished extends Command

case class CMDOpenChannel(temporaryChannelId: BinaryData, fundingSatoshis: Long, pushMsat: Long,
                          initialFeeratePerKw: Long, localParams: LocalParams, remoteInit: Init) extends Command

trait CMDAddHtlc extends Command { val spec: OutgoingPaymentSpec }
case class PlainAddHtlc(spec: OutgoingPaymentSpec) extends CMDAddHtlc
case class SilentAddHtlc(spec: OutgoingPaymentSpec) extends CMDAddHtlc

case class CMDFailMalformedHtlc(id: Long, onionHash: BinaryData, code: Int) extends Command
case class CMDFulfillHtlc(id: Long, preimage: BinaryData) extends Command
case class CMDFailHtlc(id: Long, reason: BinaryData) extends Command
case class CMDFundingSpent(tx: Transaction) extends Command
case class CMDFeerate(rate: Long) extends Command
case class CMDDepth(depth: Int) extends Command

// STORABLE CHANNEL DATA

sealed trait ChannelData { val announce: NodeAnnouncement }
sealed trait HasCommitments { val commitments: Commitments }

case class InitData(announce: NodeAnnouncement) extends ChannelData
case class WaitAcceptData(announce: NodeAnnouncement, cmd: CMDOpenChannel,
                          openChannelMessage: OpenChannel) extends ChannelData

case class WaitFundingTxData(announce: NodeAnnouncement, cmd: CMDOpenChannel, firstRemotePoint: Point,
                             remoteParams: RemoteParams) extends ChannelData

case class WaitFundingSignedData(announce: NodeAnnouncement, channelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams,
                                 fundingTx: Transaction, localSpec: CommitmentSpec, localCommitTx: CommitTx, remoteCommit: RemoteCommit,
                                 fundingCreatedMessage: FundingCreated) extends ChannelData

case class WaitFundingConfirmedData(announce: NodeAnnouncement, our: Option[FundingLocked], their: Option[FundingLocked],
                                    commitments: Commitments) extends ChannelData with HasCommitments

case class NormalData(announce: NodeAnnouncement, commitments: Commitments,
                      announced: Boolean, localShutdown: Option[Shutdown], remoteShutdown: Option[Shutdown],
                      afterCommit: Option[Any] = None) extends ChannelData with HasCommitments

case class NegotiationsData(announce: NodeAnnouncement, commitments: Commitments, localClosingSigned: ClosingSigned,
                            localShutdown: Shutdown, remoteShutdown: Shutdown) extends ChannelData with HasCommitments

case class ClosingData(announce: NodeAnnouncement, commitments: Commitments,
                       mutualClose: Seq[Transaction] = Nil, localCommit: Seq[LocalCommitPublished] = Nil,
                       remoteCommit: Seq[RemoteCommitPublished] = Nil, nextRemoteCommit: Seq[RemoteCommitPublished] = Nil,
                       revokedCommits: Seq[RevokedCommitPublished] = Nil) extends ChannelData with HasCommitments

case class BroadcastStatus(relativeDelay: Option[Long], publishable: Boolean, tx: Transaction)
case class LocalCommitPublished(claimMainDelayedOutputTx: Seq[Transaction], htlcSuccessTxs: Seq[Transaction],
                                htlcTimeoutTxs: Seq[Transaction], claimHtlcSuccessTxs: Seq[Transaction],
                                claimHtlcTimeoutTxs: Seq[Transaction], commitTx: Transaction)

case class RemoteCommitPublished(claimMainOutputTx: Seq[Transaction], claimHtlcSuccessTxs: Seq[Transaction],
                                 claimHtlcTimeoutTxs: Seq[Transaction], commitTx: Transaction)

case class RevokedCommitPublished(claimMainOutputTx: Seq[Transaction], mainPenaltyTx: Seq[Transaction],
                                  claimHtlcTimeoutTxs: Seq[Transaction], htlcTimeoutTxs: Seq[Transaction],
                                  htlcPenaltyTxs: Seq[Transaction], commitTx: Transaction)

object ClosingData {
  private def extractTxs(bag: LocalCommitPublished): Seq[Transaction] =
    bag.claimMainDelayedOutputTx ++ bag.htlcSuccessTxs ++ bag.htlcTimeoutTxs ++
      bag.claimHtlcSuccessTxs ++ bag.claimHtlcTimeoutTxs

  private def extractTxs(bag: RemoteCommitPublished): Seq[Transaction] =
    bag.claimMainOutputTx ++ bag.claimHtlcSuccessTxs ++ bag.claimHtlcTimeoutTxs

  private def extractTxs(bag: RevokedCommitPublished): Seq[Transaction] =
    bag.claimMainOutputTx ++ bag.mainPenaltyTx ++ bag.claimHtlcTimeoutTxs ++
      bag.htlcTimeoutTxs ++ bag.htlcPenaltyTxs

  // Order of txs broadcasting matters here!
  def extractTxs(cd: ClosingData): Seq[Transaction] =
    cd.mutualClose ++ cd.localCommit.flatMap(extractTxs) ++ cd.remoteCommit.flatMap(extractTxs) ++
      cd.nextRemoteCommit.flatMap(extractTxs) ++ cd.revokedCommits.flatMap(extractTxs)

  def extractWatchScripts(closing: ClosingData): Seq[TxOut] = {
    // On channel breaking we need to watch all commit outputs for preimages
    val remoteNext = closing.nextRemoteCommit.flatMap(_.commitTx.txOut)
    val remote = closing.remoteCommit.flatMap(_.commitTx.txOut)
    val local = closing.localCommit.flatMap(_.commitTx.txOut)
    remoteNext ++ remote ++ local
  }
}

// COMMITMENTS

case class LocalParams(chainHash: BinaryData, dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: Long,
                       channelReserveSatoshis: Long, htlcMinimumMsat: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int,
                       fundingPrivKey: PrivateKey, revocationSecret: Scalar, paymentKey: PrivateKey, delayedPaymentKey: Scalar,
                       defaultFinalScriptPubKey: BinaryData, shaSeed: BinaryData, isFunder: Boolean,
                       globalFeatures: BinaryData, localFeatures: BinaryData)

case class RemoteParams(dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: Long, channelReserveSatoshis: Long,
                        htlcMinimumMsat: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int, fundingPubKey: PublicKey,
                        revocationBasepoint: Point, paymentBasepoint: Point, delayedPaymentBasepoint: Point,
                        globalFeatures: BinaryData, localFeatures: BinaryData)

case class Htlc(incoming: Boolean, add: UpdateAddHtlc)
case class CommitmentSpec(feeratePerKw: Long, toLocalMsat: Long,
                          toRemoteMsat: Long, htlcs: Set[Htlc] = Set.empty)

object CommitmentSpec {
  def findHtlcById(cs: CommitmentSpec, id: Long, isIncoming: Boolean): Option[Htlc] =
    cs.htlcs.find(htlc => htlc.add.id == id && htlc.incoming == isIncoming)

  private def fulfill(cs: CommitmentSpec, in: Boolean, update: UpdateFulfillHtlc) = findHtlcById(cs, update.id, in) match {
    case Some(htlc) if htlc.incoming => cs.copy(toLocalMsat = cs.toLocalMsat + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case Some(htlc) => cs.copy(toRemoteMsat = cs.toRemoteMsat + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  private def fail(cs: CommitmentSpec, in: Boolean, update: UpdateFailHtlc) = findHtlcById(cs, update.id, in) match {
    case Some(htlc) if htlc.incoming => cs.copy(toRemoteMsat = cs.toRemoteMsat + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case Some(htlc) => cs.copy(toLocalMsat = cs.toLocalMsat + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  private def plus(htlc: Htlc, cs: CommitmentSpec) =
    if (htlc.incoming) cs.copy(htlcs = cs.htlcs + htlc, toRemoteMsat = cs.toRemoteMsat - htlc.add.amountMsat)
    else cs.copy(htlcs = cs.htlcs + htlc, toLocalMsat = cs.toLocalMsat - htlc.add.amountMsat)

  def reduce(cs: CommitmentSpec, localChanges: LightningMessages, remoteChanges: LightningMessages) = {
    val spec1 = (cs /: localChanges) { case (spec, add: UpdateAddHtlc) => plus(Htlc(incoming = false, add), spec) case (spec, _) => spec }
    val spec2 = (spec1 /: remoteChanges) { case (spec, add: UpdateAddHtlc) => plus(Htlc(incoming = true, add), spec) case (spec, _) => spec }

    val spec3 = (spec2 /: localChanges) {
      case (spec, msg: UpdateFailHtlc) => fail(spec, in = true, msg)
      case (spec, msg: UpdateFulfillHtlc) => fulfill(spec, in = true, msg)
      case (spec, u: UpdateFee) => spec.copy(feeratePerKw = u.feeratePerKw)
      case (spec, _) => spec
    }

    (spec3 /: remoteChanges) {
      case (spec, msg: UpdateFailHtlc) => fail(spec, in = false, msg)
      case (spec, msg: UpdateFulfillHtlc) => fulfill(spec, in = false, msg)
      case (spec, u: UpdateFee) => spec.copy(feeratePerKw = u.feeratePerKw)
      case (spec, _) => spec
    }
  }
}

case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig)
case class PublishableTxs(htlcTxsAndSigs: Seq[HtlcTxAndSigs], commitTx: CommitTx)
case class HtlcTxAndSigs(txinfo: TransactionWithInputInfo, localSig: BinaryData, remoteSig: BinaryData)
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs, commit: CommitSig)
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, remotePerCommitmentPoint: Point)
case class Changes(proposed: LightningMessages, signed: LightningMessages, acked: LightningMessages)
object Changes { def all(c: Changes): LightningMessages = c.proposed ++ c.signed ++ c.acked }

case class Commitments(localParams: LocalParams, remoteParams: RemoteParams,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: Changes, remoteChanges: Changes,
                       localNextHtlcId: Long, remoteNextHtlcId: Long,
                       remoteNextCommitInfo: Either[WaitingForRevocation, Point],
                       unackedMessages: LightningMessages, commitInput: InputInfo,
                       remotePerCommitmentSecrets: ShaHashesWithIndex,
                       channelId: BinaryData)

object Commitments {
  def hasNoPendingHtlcs(c: Commitments): Boolean = c.remoteNextCommitInfo match {
    case Right(_) => c.localCommit.spec.htlcs.isEmpty && c.remoteCommit.spec.htlcs.isEmpty
    case Left(wait) => c.localCommit.spec.htlcs.isEmpty && wait.nextRemoteCommit.spec.htlcs.isEmpty
  }

  def hasTimedoutOutgoingHtlcs(c: Commitments, blockHeight: Long): Boolean =
    c.localCommit.spec.htlcs.exists(htlc => !htlc.incoming && blockHeight >= htlc.add.expiry) ||
      c.remoteCommit.spec.htlcs.exists(htlc => htlc.incoming && blockHeight >= htlc.add.expiry)

  def addRemoteProposal(c: Commitments, proposal: LightningMessage) =
    c.modify(_.remoteChanges.proposed).using(_ :+ proposal)

  def addLocalProposal(c: Commitments, proposal: LightningMessage): Commitments =
    c.modifyAll(_.localChanges.proposed, _.unackedMessages).using(_ :+ proposal)

  def addUnacked(c: Commitments, proposal: LightningMessage) =
    c.modify(_.unackedMessages).using(_ :+ proposal)

  def replaceRevoke(ms: LightningMessages, r: RevokeAndAck) = ms.filter { case _: RevokeAndAck => false case _ => true } :+ r
  def cutAcked(ms: LightningMessages) = ms.drop(ms.indexWhere { case _: CommitSig => true case _ => false } + 1)

  def localHasChanges(c: Commitments): Boolean = c.remoteChanges.acked.nonEmpty || c.localChanges.proposed.nonEmpty
  def remoteHasChanges(c: Commitments): Boolean = c.localChanges.acked.nonEmpty || c.remoteChanges.proposed.nonEmpty

  def getHtlcCrossSigned(commitments: Commitments, incomingRelativeToLocal: Boolean, htlcId: Long) = {
    val remoteSigned = CommitmentSpec.findHtlcById(commitments.localCommit.spec, htlcId, incomingRelativeToLocal)

    val localSigned = commitments.remoteNextCommitInfo match {
      case Left(wait) => CommitmentSpec.findHtlcById(wait.nextRemoteCommit.spec, htlcId, !incomingRelativeToLocal)
      case _ => CommitmentSpec.findHtlcById(commitments.remoteCommit.spec, htlcId, !incomingRelativeToLocal)
    }

    for {
      htlcOut <- remoteSigned
      htlcIn <- localSigned
    } yield htlcIn.add
  }

  def sendAdd(c: Commitments, cmd: CMDAddHtlc, blockCount: Int) =
    if (cmd.spec.expiry <= blockCount) throw DetailedException(HTLC_EXPIRY_TOO_SOON, cmd)
    else if (cmd.spec.invoice.sum > LNParams.maxHtlcValue) throw DetailedException(HTLC_VALUE_TOO_LARGE, cmd)
    else if (cmd.spec.amountWithFee < c.remoteParams.htlcMinimumMsat) throw DetailedException(HTLC_VALUE_TOO_SMALL, cmd)
    else {

      // Let's compute the current commitment *as seen by them* with this change taken into account
      val add = UpdateAddHtlc(c.channelId, c.localNextHtlcId, cmd.spec.amountWithFee, cmd.spec.expiry,
        cmd.spec.invoice.paymentHash, cmd.spec.onion.packet.serialize)

      val c1 = addLocalProposal(c, add).modify(_.localNextHtlcId).using(_ + 1)
      val reduced = CommitmentSpec.reduce(c1.remoteCommit.spec, c1.remoteChanges.acked, c1.localChanges.proposed)
      val fees = if (c1.localParams.isFunder) Scripts.commitTxFee(Satoshi(c1.remoteParams.dustLimitSatoshis), reduced).amount else 0
      val htlcValueInFlightOverflow = reduced.htlcs.map(_.add.amountMsat).sum > c1.remoteParams.maxHtlcValueInFlightMsat
      val feesOverflow = reduced.toRemoteMsat / satFactor - c1.remoteParams.channelReserveSatoshis - fees < 0
      val acceptedHtlcsOverflow = reduced.htlcs.count(_.incoming) > c1.remoteParams.maxAcceptedHtlcs

      // The rest of the guards
      if (htlcValueInFlightOverflow) throw DetailedException(HTLC_TOO_MUCH_VALUE_IN_FLIGHT, cmd)
      else if (acceptedHtlcsOverflow) throw DetailedException(HTLC_TOO_MANY_ACCEPTED, cmd)
      else if (feesOverflow) throw DetailedException(HTLC_MISSING_FEES, cmd)
      else c1
    }

  // Instead of forgetting their htlc proposal
  // we check if it's an old one before proceeding
  def receiveAdd(c: Commitments, add: UpdateAddHtlc, blockCount: Int) =
    if (add.id < c.remoteNextHtlcId) c else doReceiveAdd(c, add, blockCount)

  private def doReceiveAdd(c: Commitments, add: UpdateAddHtlc, blockCount: Int) =
    if (add.amountMsat < c.localParams.htlcMinimumMsat) throw ChannelException(HTLC_VALUE_TOO_SMALL)
    else if (add.expiry < blockCount + LNParams.minExpiryBlocks) throw ChannelException(HTLC_EXPIRY_TOO_SOON)
    else if (add.id != c.remoteNextHtlcId) throw ChannelException(HTLC_UNEXPECTED_ID)
    else {

      // Let's compute the current commitment *as seen by us* including this change
      val c1 = addRemoteProposal(c, add).copy(remoteNextHtlcId = c.remoteNextHtlcId + 1)
      val reduced = CommitmentSpec.reduce(c1.localCommit.spec, c1.localChanges.acked, c1.remoteChanges.proposed)
      val fees = if (c1.localParams.isFunder) Scripts.commitTxFee(Satoshi(c1.localParams.dustLimitSatoshis), reduced).amount else 0
      val htlcValueInFlightOverflow = reduced.htlcs.map(_.add.amountMsat).sum > c1.localParams.maxHtlcValueInFlightMsat
      val feesOverflow = reduced.toRemoteMsat / satFactor - c1.localParams.channelReserveSatoshis - fees < 0
      val acceptedHtlcsOverflow = reduced.htlcs.count(_.incoming) > c1.localParams.maxAcceptedHtlcs

      // The rest of the guards
      if (htlcValueInFlightOverflow) throw ChannelException(HTLC_TOO_MUCH_VALUE_IN_FLIGHT)
      else if (acceptedHtlcsOverflow) throw ChannelException(HTLC_TOO_MANY_ACCEPTED)
      else if (feesOverflow) throw ChannelException(HTLC_MISSING_FEES)
      else c1
    }

  def sendFulfill(c: Commitments, cmd: CMDFulfillHtlc) = {
    val ok = UpdateFulfillHtlc(c.channelId, cmd.id, cmd.preimage)
    getHtlcCrossSigned(c, incomingRelativeToLocal = true, htlcId = cmd.id) match {
      case Some(add) if ok.paymentHash == add.paymentHash => addLocalProposal(c, ok)
      case Some(add) => throw ChannelException(HTLC_INVALID_PREIMAGE)
      case _ => throw ChannelException(HTLC_UNKNOWN_PREIMAGE)
    }
  }

  // Instead of forgetting their fulfill proposal
  // we check if it's an old one before proceeding
  def receiveFulfill(c: Commitments, fulfill: UpdateFulfillHtlc) =
    if (Changes all c.remoteChanges contains fulfill) c
    else doReceiveFulfill(c, fulfill)

  private def doReceiveFulfill(c: Commitments, ok: UpdateFulfillHtlc) =
    getHtlcCrossSigned(c, incomingRelativeToLocal = false, htlcId = ok.id) match {
      case Some(add) if ok.paymentHash == add.paymentHash => addRemoteProposal(c, ok)
      case Some(add) => throw ChannelException(HTLC_INVALID_PREIMAGE)
      case _ => throw ChannelException(HTLC_UNKNOWN_PREIMAGE)
    }

  def sendFail(c: Commitments, cmd: CMDFailHtlc) = {
    val fail = UpdateFailHtlc(c.channelId, cmd.id, cmd.reason)
    val found = getHtlcCrossSigned(c, incomingRelativeToLocal = true, cmd.id)
    if (found.isEmpty) throw ChannelException(HTLC_UNKNOWN_PREIMAGE)
    else addLocalProposal(c, fail)
  }

  def sendFailMalformed(c: Commitments, cmd: CMDFailMalformedHtlc) = {
    val fail = UpdateFailMalformedHtlc(c.channelId, cmd.id, cmd.onionHash, cmd.code)
    val found = getHtlcCrossSigned(c, incomingRelativeToLocal = true, cmd.id)
    if (found.isEmpty) throw ChannelException(HTLC_UNEXPECTED_ID)
    else addLocalProposal(c, fail)
  }

  // Instead of forgetting their fail proposal
  // we check if it's an old one before proceeding
  def receiveFail(c: Commitments, fail: FailHtlc) =
    if (Changes all c.remoteChanges contains fail) c
    else doReceiveFail(c, fail)

  private def doReceiveFail(c: Commitments, fail: FailHtlc) = {
    val found = getHtlcCrossSigned(c, incomingRelativeToLocal = false, fail.id)
    if (found.isEmpty) throw ChannelException(HTLC_UNEXPECTED_ID)
    else addRemoteProposal(c, fail)
  }

  def sendFee(c: Commitments, rate: Long) = {
    val updateFee = UpdateFee(c.channelId, rate)
    val c1 = addLocalProposal(c, updateFee)

    val reduced = CommitmentSpec.reduce(c1.remoteCommit.spec, c1.remoteChanges.acked, c1.localChanges.proposed)
    val fees = Scripts.commitTxFee(dustLimit = Satoshi(c1.remoteParams.dustLimitSatoshis), spec = reduced).amount
    val feesOverflow = reduced.toRemoteMsat / satFactor - c1.remoteParams.channelReserveSatoshis - fees < 0
    if (feesOverflow) throw ChannelException(FEE_FUNDEE_CAN_NOT_PAY) else c1
  }

  def sendCommit(c: Commitments, remoteNextPerCommitmentPoint: Point) = {
    // Remote commitment will include all local proposed changes as well as remote acked changes
    val spec = CommitmentSpec.reduce(c.remoteCommit.spec, c.remoteChanges.acked, c.localChanges.proposed)
    val paymentKey = Generators.derivePrivKey(c.localParams.paymentKey, remoteNextPerCommitmentPoint)

    val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) =
      Helpers.makeRemoteTxs(c.remoteCommit.index + 1, c.localParams,
        c.remoteParams, c.commitInput, remoteNextPerCommitmentPoint, spec)

    // Generate signatures
    val sortedHtlcTxs = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    val htlcSigs = for (info <- sortedHtlcTxs) yield Scripts.sign(info, paymentKey)

    // Update commitment data
    val commitSig = CommitSig(c.channelId, Scripts.sign(remoteCommitTx, c.localParams.fundingPrivKey), htlcSigs.toList)
    val remote1 = RemoteCommit(c.remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint)
    val localChanges1 = c.localChanges.copy(proposed = Vector.empty, signed = c.localChanges.proposed)
    val remoteChanges1 = c.remoteChanges.copy(acked = Vector.empty, signed = c.remoteChanges.acked)
    val remoteNextCommitInfo1 = Left apply WaitingForRevocation(remote1, commitSig)

    addUnacked(c.copy(remoteNextCommitInfo = remoteNextCommitInfo1,
      localChanges = localChanges1, remoteChanges = remoteChanges1),
      commitSig)
  }

  // Instead of forgetting their commit sig
  // we check if it's an old one before proceeding
  def receiveCommit(c: Commitments, commit: CommitSig) =
    c.localCommit.commit.hashCode == commit.hashCode match {
      case false if remoteHasChanges(c) => doReceiveCommit(c, commit)
      case false => throw ChannelException(COMMIT_RECEIVE_ATTEMPT_NO_CHANGES)
      case true => c
    }

  private def doReceiveCommit(c: Commitments, commit: CommitSig) = {
    val spec = CommitmentSpec.reduce(c.localCommit.spec, c.localChanges.acked, c.remoteChanges.proposed)
    val localPerCommitmentSecret = Generators.perCommitSecret(c.localParams.shaSeed, c.localCommit.index)
    val localPerCommitmentPoint = Generators.perCommitPoint(c.localParams.shaSeed, c.localCommit.index + 1)
    val localNextPerCommitmentPoint = Generators.perCommitPoint(c.localParams.shaSeed, c.localCommit.index + 2)
    val remotePaymentPubkey = Generators.derivePubKey(c.remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val localPaymentKey = Generators.derivePrivKey(c.localParams.paymentKey, localPerCommitmentPoint)
    val revocation = RevokeAndAck(c.channelId, localPerCommitmentSecret, localNextPerCommitmentPoint)

    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) =
      Helpers.makeLocalTxs(c.localCommit.index + 1, c.localParams,
        c.remoteParams, c.commitInput, localPerCommitmentPoint, spec)

    val sortedHtlcTxs = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    val signedCommitTx = Scripts.addSigs(localCommitTx, c.localParams.fundingPrivKey.publicKey,
      c.remoteParams.fundingPubKey, Scripts.sign(localCommitTx, c.localParams.fundingPrivKey),
      remoteSig = commit.signature)

    if (Scripts.checkSpendable(signedCommitTx).isFailure) throw ChannelException(COMMIT_RECEIVE_INVALID_SIGNATURE)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size) throw ChannelException(COMMIT_SIG_COUNT_MISMATCH)

    val htlcSigs = for (info <- sortedHtlcTxs) yield Scripts.sign(info, localPaymentKey)
    val combined = (sortedHtlcTxs, htlcSigs, commit.htlcSignatures).zipped.toList

    val htlcTxsAndSigs = combined.collect {
      case (htlcTx: HtlcTimeoutTx, localSig, remoteSig) =>
        val check = Scripts checkSpendable Scripts.addSigs(htlcTx, localSig, remoteSig)
        if (check.isFailure) throw ChannelException(COMMIT_RECEIVE_INVALID_SIGNATURE)
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)

      case (htlcTx: HtlcSuccessTx, localSig, remoteSig) =>
        val isSigValid = Scripts.checkSig(htlcTx, remoteSig, remotePaymentPubkey)
        if (!isSigValid) throw ChannelException(COMMIT_RECEIVE_INVALID_SIGNATURE)
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)
    }

    val localChanges1 = c.localChanges.copy(acked = Vector.empty)
    c.copy(remoteChanges = c.remoteChanges.copy(proposed = Vector.empty, acked = c.remoteChanges.acked ++ c.remoteChanges.proposed),
      localCommit = LocalCommit(c.localCommit.index + 1, spec, PublishableTxs(htlcTxsAndSigs, signedCommitTx), commit),
      unackedMessages = replaceRevoke(c.unackedMessages, revocation), localChanges = localChanges1)
  }

  def receiveRevocation(c: Commitments, rev: RevokeAndAck) = c.remoteNextCommitInfo match {
    case Left(wait) if wait.nextRemoteCommit.remotePerCommitmentPoint == rev.nextPerCommitmentPoint => c
    case Left(_) if c.remoteCommit.remotePerCommitmentPoint != rev.perCommitmentSecret.toPoint =>
      throw ChannelException(REVOCATION_INVALID_PREIMAGE)

    case Left(wait: WaitingForRevocation) =>
      val nextIndex = ShaChain.largestTxIndex - c.remoteCommit.index
      val secrets1 = ShaChain.addHash(c.remotePerCommitmentSecrets, rev.perCommitmentSecret.toBin, nextIndex)
      val localChanges1 = c.localChanges.copy(signed = Vector.empty, acked = c.localChanges.acked ++ c.localChanges.signed)

      c.copy(localChanges = localChanges1, remoteChanges = c.remoteChanges.copy(signed = Vector.empty),
        remoteCommit = wait.nextRemoteCommit, remoteNextCommitInfo = Right apply rev.nextPerCommitmentPoint,
        unackedMessages = cutAcked(c.unackedMessages), remotePerCommitmentSecrets = secrets1)

    case Right(point) if point == rev.nextPerCommitmentPoint => c
    case _ => throw ChannelException(REVOCATION_UNEXPECTED)
  }
}