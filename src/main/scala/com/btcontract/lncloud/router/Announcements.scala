package com.btcontract.lncloud.router

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._
import com.btcontract.lncloud.ln.wire._

import org.bitcoinj.core.Sha256Hash
import Codecs.BitVectorAttempt
import scodec.Attempt
import shapeless.HNil


object Announcements { me =>
  def hashTwice[T](attempt: BitVectorAttempt): BinaryData = attempt match {
    case Attempt.Failure(cause) => throw new Exception(s"Serialization error: $cause")
    case Attempt.Successful(bin) => Sha256Hash hashTwice bin.toByteArray
  }

  def checkSigs(ann: ChannelAnnouncement): Boolean = {
    val list = ann.shortChannelId :: ann.nodeId1 :: ann.nodeId2 ::
      ann.bitcoinKey1 :: ann.bitcoinKey2 :: HNil

    val witness = hashTwice(Codecs.channelAnnouncementWitness encode list)
    verifySignature(witness, ann.nodeSignature1, PublicKey apply ann.nodeId1) &&
      verifySignature(witness, ann.nodeSignature2, PublicKey apply ann.nodeId2) &&
      verifySignature(witness, ann.bitcoinSignature1, PublicKey apply ann.bitcoinKey1) &&
      verifySignature(witness, ann.bitcoinSignature2, PublicKey apply ann.bitcoinKey2)
  }

  def checkSig(ann: NodeAnnouncement): Boolean = {
    val list = ann.timestamp :: ann.nodeId :: ann.rgbColor :: ann.alias :: ann.features :: ann.addresses :: HNil
    verifySignature(me hashTwice Codecs.nodeAnnouncementWitness.encode(list), ann.signature, PublicKey apply ann.nodeId)
  }

  def checkSig(ann: ChannelUpdate, nodeId: BinaryData): Boolean =
    verifySignature(me hashTwice Codecs.channelUpdateWitness.encode(ann.shortChannelId ::
      ann.timestamp :: ann.flags :: ann.cltvExpiryDelta :: ann.htlcMinimumMsat ::
      ann.feeBaseMsat :: ann.feeProportionalMillionths :: HNil),
      ann.signature, PublicKey apply nodeId)

  def makeChannelAnnouncement(shortChannelId: Long, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey,
                              remoteFundingKey: PublicKey, localNodeSignature: BinaryData, remoteNodeSignature: BinaryData,
                              localBitcoinSignature: BinaryData, remoteBitcoinSignature: BinaryData): ChannelAnnouncement =

    LexicographicalOrdering.isLessThan(localNodeId.toBin, remoteNodeId.toBin) match {
      case true => ChannelAnnouncement(localNodeSignature, remoteNodeSignature, localBitcoinSignature,
        remoteBitcoinSignature, shortChannelId, localNodeId, remoteNodeId, localFundingKey, remoteFundingKey)

      case false => ChannelAnnouncement(remoteNodeSignature, localNodeSignature, remoteBitcoinSignature,
        localBitcoinSignature, shortChannelId, remoteNodeId, localNodeId, remoteFundingKey, localFundingKey)
    }
}
