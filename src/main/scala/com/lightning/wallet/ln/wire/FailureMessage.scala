package com.lightning.wallet.ln.wire

import scodec.codecs._
import LightningMessageCodecs._
import fr.acinq.bitcoin.BinaryData


sealed trait FailureMessage
sealed trait Perm extends FailureMessage
sealed trait Node extends FailureMessage
sealed trait Update extends FailureMessage { def update: ChannelUpdate }
sealed trait BadOnion extends FailureMessage { def onionHash: BinaryData }

case object TemporaryChannelFailure extends FailureMessage
case object RequiredNodeFeatureMissing extends Perm with Node
case object TemporaryNodeFailure extends Node

case class InvalidOnionVersion(onionHash: BinaryData) extends BadOnion with Perm
case class InvalidOnionHmac(onionHash: BinaryData) extends BadOnion with Perm
case class InvalidOnionKey(onionHash: BinaryData) extends BadOnion with Perm

case object PermanentChannelFailure extends Perm
case object IncorrectPaymentAmount extends Perm
case object PermanentNodeFailure extends Perm
case object UnknownPaymentHash extends Perm
case object FinalExpiryTooSoon extends Perm
case object UnknownNextPeer extends Perm
case object InvalidRealm extends Perm

case class AmountBelowMinimum(amountMsat: Long, update: ChannelUpdate) extends Update
case class InsufficientFee(amountMsat: Long, update: ChannelUpdate) extends Update
case class IncorrectCltvExpiry(expiry: Long, update: ChannelUpdate) extends Update
case class ExpiryTooSoon(update: ChannelUpdate) extends Update

object FailureMessageCodecs {
  private val amountBelowMinimum =
    (uint32 withContext "amountMsat") ::
      (channelUpdateCodec withContext "channelUpdate")

  private val insufficientFee =
    (uint32 withContext "amountMsat") ::
      (channelUpdateCodec withContext "channelUpdate")

  private val incorrectCltvExpiry =
    (uint32 withContext "expiry") ::
      (channelUpdateCodec withContext "channelUpdate")

  private val channelupdate = channelUpdateCodec withContext "channelUpdate"
  private val sha256Codec = binarydata(32) withContext "sha256Codec"

  val BADONION = 0x8000
  val UPDATE = 0x1000
  val PERM = 0x4000
  val NODE = 0x2000

  val failureMessageCodec =
    discriminated[FailureMessage].by(uint16)
      .typecase(cr = provide(InvalidRealm), tag = PERM | 1)
      .typecase(cr = provide(TemporaryNodeFailure), tag = NODE | 2)
      .typecase(cr = provide(PermanentNodeFailure), tag = PERM | 2)
      .typecase(cr = provide(RequiredNodeFeatureMissing), tag = PERM | NODE | 3)
      .typecase(cr = sha256Codec.as[InvalidOnionVersion], tag = BADONION | PERM | 4)
      .typecase(cr = sha256Codec.as[InvalidOnionHmac], tag = BADONION | PERM | 5)
      .typecase(cr = sha256Codec.as[InvalidOnionKey], tag = BADONION | PERM | 6)
      .typecase(cr = provide(TemporaryChannelFailure), tag = 7)
      .typecase(cr = provide(PermanentChannelFailure), tag = PERM | 8)
      .typecase(cr = provide(UnknownNextPeer), tag = PERM | 10)
      .typecase(cr = amountBelowMinimum.as[AmountBelowMinimum], tag = UPDATE | 11)
      .typecase(cr = insufficientFee.as[InsufficientFee], tag = UPDATE | 12)
      .typecase(cr = incorrectCltvExpiry.as[IncorrectCltvExpiry], tag = UPDATE | 13)
      .typecase(cr = channelupdate.as[ExpiryTooSoon], tag = UPDATE | 14)
      .typecase(cr = provide(UnknownPaymentHash), tag = PERM | 15)
      .typecase(cr = provide(IncorrectPaymentAmount), tag = PERM | 16)
      .typecase(cr = provide(FinalExpiryTooSoon), tag = PERM | 17)
}
