package com.lightning.wallet.ln

import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, hash256, verifySignature}
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering}
import shapeless.HNil


object Announcements { me =>
  private def hashTwice[T](attempt: BitVectorAttempt) = hash256(serializationResult(attempt).data)
  private def channelAnnouncementWitnessEncode(shortChannelId: Long, nodeId1: BinaryData, nodeId2: BinaryData, bitcoinKey1: BinaryData, bitcoinKey2: BinaryData, features: BinaryData) =
    me hashTwice LightningMessageCodecs.channelAnnouncementWitness.encode(shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: features :: HNil)

  private def nodeAnnouncementWitnessEncode(timestamp: Long, nodeId: BinaryData, rgbColor: RGB, alias: String, features: BinaryData, addresses: InetSocketAddressList) =
    me hashTwice LightningMessageCodecs.nodeAnnouncementWitness.encode(timestamp :: nodeId :: rgbColor :: alias :: features :: addresses :: HNil)

  private def channelUpdateWitnessEncode(shortChannelId: Long, timestamp: Long, flags: BinaryData, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long) =
    me hashTwice LightningMessageCodecs.channelUpdateWitness.encode(shortChannelId :: timestamp :: flags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: HNil)

  def signChannelAnnouncement(shortChannelId: Long, localNodeSecret: PrivateKey, remoteNodeId: PublicKey,
                              localFundingPrivKey: PrivateKey, remoteFundingKey: PublicKey,
                              features: BinaryData): (BinaryData, BinaryData) = {

    val witness = LexicographicalOrdering.isLessThan(localNodeSecret.publicKey.toBin, remoteNodeId.toBin) match {
      case true => channelAnnouncementWitnessEncode(shortChannelId, localNodeSecret.publicKey, remoteNodeId, localFundingPrivKey.publicKey, remoteFundingKey, features)
      case false => channelAnnouncementWitnessEncode(shortChannelId, remoteNodeId, localNodeSecret.publicKey, remoteFundingKey, localFundingPrivKey.publicKey, features)
    }

    val nodeSig = Crypto encodeSignature Crypto.sign(witness, localNodeSecret)
    val bitcoinSig = Crypto encodeSignature Crypto.sign(witness, localFundingPrivKey)
    (nodeSig :+ 1.toByte, bitcoinSig :+ 1.toByte)
  }

  def makeChannelAnnouncement(shortChannelId: Long, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey,
                              remoteFundingKey: PublicKey, localNodeSignature: BinaryData, remoteNodeSignature: BinaryData,
                              localBitcoinSignature: BinaryData, remoteBitcoinSignature: BinaryData): ChannelAnnouncement =

    LexicographicalOrdering.isLessThan(localNodeId.toBin, remoteNodeId.toBin) match {
      case true => ChannelAnnouncement(nodeSignature1 = localNodeSignature, nodeSignature2 = remoteNodeSignature,
        bitcoinSignature1 = localBitcoinSignature, bitcoinSignature2 = remoteBitcoinSignature, shortChannelId = shortChannelId,
        nodeId1 = localNodeId, nodeId2 = remoteNodeId, bitcoinKey1 = localFundingKey, bitcoinKey2 = remoteFundingKey, features = "")

      case false => ChannelAnnouncement(nodeSignature1 = remoteNodeSignature, nodeSignature2 = localNodeSignature,
        bitcoinSignature1 = remoteBitcoinSignature, bitcoinSignature2 = localBitcoinSignature, shortChannelId = shortChannelId,
        nodeId1 = remoteNodeId, nodeId2 = localNodeId, bitcoinKey1 = remoteFundingKey, bitcoinKey2 = localFundingKey, features = "")
    }

  def makeNodeAnnouncement(nodeSecret: PrivateKey, alias: String, color: RGB, addresses: InetSocketAddressList, timestamp: Long): NodeAnnouncement = {
    val sig = Crypto encodeSignature Crypto.sign(nodeAnnouncementWitnessEncode(timestamp, nodeSecret.publicKey, color, alias, "", addresses), nodeSecret)
    NodeAnnouncement(signature = sig :+ 1.toByte, timestamp = timestamp, nodeId = nodeSecret.publicKey, rgbColor = color, alias = alias take 32,
      features = "", addresses = addresses)
  }

  def makeChannelUpdate(nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: Long, cltvExpiryDelta: Int, htlcMinimumMsat: Long,
                        feeBaseMsat: Long, feeProportionalMillionths: Long, timestamp: Long): ChannelUpdate = {

    val flags = LexicographicalOrdering.isLessThan(nodeSecret.publicKey.toBin, remoteNodeId.toBin) match { case true => "0000" case false => "0001" }
    val sig = Crypto encodeSignature Crypto.sign(channelUpdateWitnessEncode(shortChannelId, timestamp, flags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths), nodeSecret)
    ChannelUpdate(signature = sig :+ 1.toByte, shortChannelId = shortChannelId, timestamp = timestamp, flags = flags, cltvExpiryDelta = cltvExpiryDelta, htlcMinimumMsat = htlcMinimumMsat,
      feeBaseMsat = feeBaseMsat, feeProportionalMillionths = feeProportionalMillionths)
  }

  def checkSigs(ann: ChannelAnnouncement): Boolean = {
    val witness = channelAnnouncementWitnessEncode(ann.shortChannelId,
      ann.nodeId1, ann.nodeId2, ann.bitcoinKey1, ann.bitcoinKey2, ann.features)

    verifySignature(witness, ann.nodeSignature1, PublicKey apply ann.nodeId1) &&
      verifySignature(witness, ann.nodeSignature2, PublicKey apply ann.nodeId2) &&
      verifySignature(witness, ann.bitcoinSignature1, PublicKey apply ann.bitcoinKey1) &&
      verifySignature(witness, ann.bitcoinSignature2, PublicKey apply ann.bitcoinKey2)
  }

  def checkSig(ann: NodeAnnouncement): Boolean =
    verifySignature(nodeAnnouncementWitnessEncode(ann.timestamp,
      ann.nodeId, ann.rgbColor, ann.alias, ann.features, ann.addresses),
      ann.signature, PublicKey apply ann.nodeId)

  def checkSig(ann: ChannelUpdate, nodeId: BinaryData): Boolean =
    verifySignature(channelUpdateWitnessEncode(ann.shortChannelId, ann.timestamp, ann.flags,
      ann.cltvExpiryDelta, ann.htlcMinimumMsat, ann.feeBaseMsat, ann.feeProportionalMillionths),
      ann.signature, PublicKey apply nodeId)
}