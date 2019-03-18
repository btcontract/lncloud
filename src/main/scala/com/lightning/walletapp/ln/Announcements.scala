package com.lightning.walletapp.ln

import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, hash256, verifySignature}
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering}
import scodec.bits.BitVector
import shapeless.HNil


object Announcements { me =>
  private def hashTwice(attempt: BitVectorAttempt) = hash256(serialize(attempt).data)
  private def channelAnnouncementWitnessEncode(chainHash: BinaryData, shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, bitcoinKey1: PublicKey, bitcoinKey2: PublicKey, features: BinaryData) =
    me hashTwice LightningMessageCodecs.channelAnnouncementWitness.encode(features :: chainHash :: shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: HNil)

  private def nodeAnnouncementWitnessEncode(timestamp: Long, nodeId: PublicKey, rgbColor: RGB, alias: String, features: BinaryData, addresses: NodeAddressList) =
    me hashTwice LightningMessageCodecs.nodeAnnouncementWitness.encode(features :: timestamp :: nodeId :: rgbColor :: alias :: addresses :: HNil)

  private def channelUpdateWitnessEncode(chainHash: BinaryData, shortChannelId: Long, timestamp: Long, messageFlags: Byte, channelFlags: Byte, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long, htlcMaximumMsat: Option[Long] = None) =
    me hashTwice LightningMessageCodecs.channelUpdateWitness.encode(chainHash :: shortChannelId :: timestamp :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: HNil)

  def signChannelAnnouncement(chainHash: BinaryData, shortChannelId: Long, localNodeSecret: PrivateKey, remoteNodeId: PublicKey,
                              localFundingPrivKey: PrivateKey, remoteFundingKey: PublicKey, features: BinaryData): (BinaryData, BinaryData) = {

    val witness = isNode1(localNodeSecret.publicKey.toBin, remoteNodeId.toBin) match {
      case true => channelAnnouncementWitnessEncode(chainHash, shortChannelId, localNodeSecret.publicKey, remoteNodeId, localFundingPrivKey.publicKey, remoteFundingKey, features)
      case false => channelAnnouncementWitnessEncode(chainHash, shortChannelId, remoteNodeId, localNodeSecret.publicKey, remoteFundingKey, localFundingPrivKey.publicKey, features)
    }

    val nodeSig = Crypto encodeSignature Crypto.sign(witness, localNodeSecret)
    val bitcoinSig = Crypto encodeSignature Crypto.sign(witness, localFundingPrivKey)
    (nodeSig :+ 1.toByte, bitcoinSig :+ 1.toByte)
  }

  def makeChannelAnnouncement(chainHash: BinaryData, shortChannelId: Long, localNodeId: PublicKey, remoteNodeId: PublicKey,
                              localFundingKey: PublicKey, remoteFundingKey: PublicKey, localNodeSignature: BinaryData,
                              remoteNodeSignature: BinaryData, localBitcoinSignature: BinaryData,
                              remoteBitcoinSignature: BinaryData): ChannelAnnouncement =

    isNode1(localNodeId.toBin, remoteNodeId.toBin) match {
      case true => ChannelAnnouncement(localNodeSignature, remoteNodeSignature, localBitcoinSignature, remoteBitcoinSignature,
        features = "", chainHash, shortChannelId, nodeId1 = localNodeId, nodeId2 = remoteNodeId, localFundingKey, remoteFundingKey)

      case false => ChannelAnnouncement(remoteNodeSignature, localNodeSignature, remoteBitcoinSignature, localBitcoinSignature,
        features = "", chainHash, shortChannelId, nodeId1 = remoteNodeId, nodeId2 = localNodeId, remoteFundingKey, localFundingKey)
    }

  // The creating node MUST set node-id-1 and node-id-2 to the public keys of the
  // two nodes who are operating the channel, such that node-id-1 is the numerically-lesser
  // of the two DER encoded keys sorted in ascending numerical order

  def isNode1(channelFlags: Byte) = (channelFlags & 1) == 0
  def isEnabled(channelFlags: Byte) = (channelFlags & 2) == 0
  def isNode1(localNodeId: BinaryData, remoteNodeId: BinaryData) = LexicographicalOrdering.isLessThan(localNodeId, remoteNodeId)
  def makeMessageFlags(hasOptionChannelHtlcMax: Boolean) = BitVector.bits(hasOptionChannelHtlcMax :: Nil).padLeft(8).toByte(true)
  def makeChannelFlags(isNode1: Boolean, enable: Boolean) = BitVector.bits(!enable :: !isNode1 :: Nil).padLeft(8).toByte(true)

  def makeChannelUpdate(chainHash: BinaryData, nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: Long, cltvExpiryDelta: Int,
                        htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long, htlcMaximumMsat: Long,
                        enable: Boolean = true, timestamp: Long = System.currentTimeMillis / 1000) = {

    val htlcMaximumMsatOpt = Some(htlcMaximumMsat)
    val messageFlags = makeMessageFlags(hasOptionChannelHtlcMax = true)
    val channelFlags = makeChannelFlags(isNode1 = isNode1(nodeSecret.publicKey.toBin, remoteNodeId.toBin), enable = enable)
    val witness = channelUpdateWitnessEncode(chainHash, shortChannelId, timestamp, messageFlags, channelFlags, cltvExpiryDelta,
      htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsatOpt)

    val sig = Crypto.sign(witness, nodeSecret)
    ChannelUpdate(signature = Crypto.encodeSignature(sig) :+ 1.toByte, chainHash = chainHash, shortChannelId = shortChannelId, timestamp = timestamp,
      messageFlags = messageFlags, channelFlags = channelFlags, cltvExpiryDelta = cltvExpiryDelta, htlcMinimumMsat = htlcMinimumMsat,
      feeBaseMsat = feeBaseMsat, feeProportionalMillionths = feeProportionalMillionths, htlcMaximumMsat = htlcMaximumMsatOpt)
  }

  def checkSigs(ann: ChannelAnnouncement): Boolean = {
    val witness = channelAnnouncementWitnessEncode(ann.chainHash, ann.shortChannelId,
      ann.nodeId1, ann.nodeId2, ann.bitcoinKey1, ann.bitcoinKey2, ann.features)

    verifySignature(witness, ann.nodeSignature1, PublicKey apply ann.nodeId1) &&
      verifySignature(witness, ann.nodeSignature2, PublicKey apply ann.nodeId2) &&
      verifySignature(witness, ann.bitcoinSignature1, PublicKey apply ann.bitcoinKey1) &&
      verifySignature(witness, ann.bitcoinSignature2, PublicKey apply ann.bitcoinKey2)
  }

  def checkSig(ann: NodeAnnouncement): Boolean =
    verifySignature(nodeAnnouncementWitnessEncode(ann.timestamp, ann.nodeId, ann.rgbColor,
      ann.alias, ann.features, ann.addresses), ann.signature, PublicKey apply ann.nodeId)

  def checkSig(upd: ChannelUpdate, nodeId: PublicKey): Boolean =
    verifySignature(channelUpdateWitnessEncode(upd.chainHash, upd.shortChannelId, upd.timestamp, upd.messageFlags,
      upd.channelFlags, upd.cltvExpiryDelta, upd.htlcMinimumMsat, upd.feeBaseMsat, upd.feeProportionalMillionths,
      upd.htlcMaximumMsat), upd.signature, nodeId)
}