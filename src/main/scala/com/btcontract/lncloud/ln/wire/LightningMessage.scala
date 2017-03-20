package com.btcontract.lncloud.ln.wire

import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import com.btcontract.lncloud.Utils.{BinaryDataList, fromShortId}
import com.btcontract.lncloud.ln.wire.Codecs.{InetSocketAddressList, RGB}
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.TxOut
import fr.acinq.bitcoin.BinaryData


trait LightningMessage
trait SetupMessage extends LightningMessage
trait RoutingMessage extends LightningMessage
trait ChannelMessage extends LightningMessage

trait HasHtlcId extends ChannelMessage { val id: Long }

case class Init(globalFeatures: BinaryData, localFeatures: BinaryData) extends SetupMessage
case class Error(channelId: BinaryData, data: BinaryData) extends SetupMessage

case class OpenChannel(temporaryChannelId: BinaryData,
                       fundingSatoshis: Long, pushMsat: Long, dustLimitSatoshis: Long,
                       maxHtlcValueInFlightMsat: Long, channelReserveSatoshis: Long, htlcMinimumMsat: Long,
                       feeratePerKw: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int, fundingPubkey: PublicKey,
                       revocationBasepoint: Point, paymentBasepoint: Point, delayedPaymentBasepoint: Point,
                       firstPerCommitmentPoint: Point) extends ChannelMessage

case class AcceptChannel(temporaryChannelId: BinaryData,
                         dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: Long,
                         channelReserveSatoshis: Long, minimumDepth: Long, htlcMinimumMsat: Long, toSelfDelay: Int,
                         maxAcceptedHtlcs: Int, fundingPubkey: PublicKey, revocationBasepoint: Point, paymentBasepoint: Point,
                         delayedPaymentBasepoint: Point, firstPerCommitmentPoint: Point) extends ChannelMessage

case class FundingCreated(temporaryChannelId: BinaryData,
                          fundingTxid: BinaryData, fundingOutputIndex: Int,
                          signature: BinaryData) extends ChannelMessage

case class FundingSigned(channelId: BinaryData, signature: BinaryData) extends ChannelMessage
case class FundingLocked(channelId: BinaryData, nextPerCommitmentPoint: Point) extends ChannelMessage
case class ClosingSigned(channelId: BinaryData, feeSatoshis: Long, signature: BinaryData) extends ChannelMessage
case class Shutdown(channelId: BinaryData, scriptPubKey: BinaryData) extends ChannelMessage

case class UpdateAddHtlc(channelId: BinaryData,
                         id: Long, amountMsat: Long, expiry: Long, paymentHash: BinaryData,
                         onionRoutingPacket: BinaryData) extends ChannelMessage

case class UpdateFailHtlc(channelId: BinaryData, id: Long, reason: BinaryData) extends HasHtlcId
case class UpdateFailMalformedHtlc(channelId: BinaryData, id: Long, onionHash: BinaryData, failureCode: Int) extends HasHtlcId
case class UpdateFulfillHtlc(channelId: BinaryData, id: Long, paymentPreimage: BinaryData) extends HasHtlcId


case class CommitSig(channelId: BinaryData, signature: BinaryData, htlcSignatures: BinaryDataList) extends ChannelMessage
case class RevokeAndAck(channelId: BinaryData, perCommitmentSecret: Scalar, nextPerCommitmentPoint: Point) extends ChannelMessage
case class UpdateFee(channelId: BinaryData, feeratePerKw: Long) extends ChannelMessage

case class AnnouncementSignatures(channelId: BinaryData,
                                  shortChannelId: Long, nodeSignature: BinaryData,
                                  bitcoinSignature: BinaryData) extends RoutingMessage

case class ChannelAnnouncement(nodeSignature1: BinaryData, nodeSignature2: BinaryData, bitcoinSignature1: BinaryData,
                               bitcoinSignature2: BinaryData, shortChannelId: Long, nodeId1: BinaryData, nodeId2: BinaryData,
                               bitcoinKey1: BinaryData, bitcoinKey2: BinaryData, features: BinaryData) extends RoutingMessage {

  val (blockHeight, txIndex, outputIndex) = fromShortId(shortChannelId)
}

case class NodeAnnouncement(signature: BinaryData, timestamp: Long, nodeId: BinaryData, rgbColor: RGB, alias: String,
                            features: BinaryData, addresses: InetSocketAddressList) extends RoutingMessage {

  val identifier: String = s"$alias${nodeId.toString}".toLowerCase
}

case class ChannelUpdate(signature: BinaryData, shortChannelId: Long, timestamp: Long, flags: BinaryData,
                         cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long,
                         feeProportionalMillionths: Long) extends RoutingMessage {

  val lastSeen: Long = System.currentTimeMillis
}

// Internal
case class ChanInfo(txid: String, txo: TxOut, ca: ChannelAnnouncement)
case class ChanDirection(channelId: Long, from: BinaryData, to: BinaryData)
case class Hop(lastUpdate: ChannelUpdate, nodeId: BinaryData, nextNodeId: BinaryData)
case class PerHopPayload(amt_to_forward: Long, outgoing_cltv_value: Int)