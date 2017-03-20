package com.btcontract.lncloud.router

import com.btcontract.lncloud.ln.wire._
import com.btcontract.lncloud.ln.wire.Codecs.{BitVectorAttempt, InetSocketAddressList, RGB, serializationResult}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256, verifySignature}
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering}
import shapeless.HNil


object Announcements { me =>
  private def hashTwice[T](attempt: BitVectorAttempt) = sha256(sha256(serializationResult(attempt).data).data)
  private def channelAnnouncementWitnessEncode(shortChannelId: Long, nodeId1: BinaryData, nodeId2: BinaryData, bitcoinKey1: BinaryData, bitcoinKey2: BinaryData, features: BinaryData) =
    me hashTwice Codecs.channelAnnouncementWitness.encode(shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: features :: HNil)

  private def nodeAnnouncementWitnessEncode(timestamp: Long, nodeId: BinaryData, rgbColor: RGB, alias: String, features: BinaryData, addresses: InetSocketAddressList) =
    me hashTwice Codecs.nodeAnnouncementWitness.encode(timestamp :: nodeId :: rgbColor :: alias :: features :: addresses :: HNil)

  private def channelUpdateWitnessEncode(shortChannelId: Long, timestamp: Long, flags: BinaryData, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long) =
    me hashTwice Codecs.channelUpdateWitness.encode(shortChannelId :: timestamp :: flags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: HNil)

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