package com.lightning.wallet.ln


object Exceptions {
  case class ChannelException(why: String) extends RuntimeException(why)
  case class DetailedException[T](why: String, details: T) extends RuntimeException(why)

  val CHACHA_INVALID_MAC = "ChaCha20Poly1305: Invalid mac"
  val CHACHA_INVALID_DATA_LENGTH = "Invalid data length"

  val HTLC_TOO_MANY_ACCEPTED = "Too many accepted HTLCs"
  val HTLC_MISSING_FEES = "Missing fees after accepting an HTLC"
  val HTLC_TOO_MUCH_VALUE_IN_FLIGHT = "In-flight htlcs hold too much value"
  val HTLC_WRONG_CHANNEL_STATE = "Can't accept HTLC in a current channel state"
  val HTLC_INVALID_PAYMENT_HASH = "Invalid payment hash"
  val HTLC_EXPIRY_TOO_SOON = "HTLC expiry is too soon"
  val HTLC_VALUE_TOO_LARGE = "HTLC value is too large"
  val HTLC_VALUE_TOO_SMALL = "HTLC value is too small"
  val HTLC_INVALID_PREIMAGE = "Invalid HTLC preimage"
  val HTLC_UNKNOWN_PREIMAGE = "Unknown HTLC preimage"
  val HTLC_UNEXPECTED_ID = "Unexpected HTLC id"

  val FEE_FUNDEE_CAN_NOT_PAY = "Fundee won't be able to pay a fee after an update"

  val COMMIT_RECEIVE_INVALID_SIGNATURE = "Signature in a received commit is invalid"
  val COMMIT_RECEIVE_ATTEMPT_NO_CHANGES = "Cannot receive a commit when there are no changes"
  val COMMIT_ATTEMPT_NO_REVOCATION = "Cannot sign until next revocation hash is received"
  val COMMIT_SEND_ATTEMPT_NO_CHANGES = "Cannot send a commit when there are no changes"
  val COMMIT_SIG_COUNT_MISMATCH = "HTLC sig count mismatch when receiving a commit"

  val REVOCATION_INVALID_PREIMAGE = "Invalid preimage in RevokeAndAck"
  val REVOCATION_UNEXPECTED = "Received an unexpected RevokeAndAck message"

  val SHA_CHAIN_HASH_CHECK_FAILED = "Hash recomputation check failed"
  val SHA_CHAIN_WRONG_INDEX = "New index is not one less than the last known"

  val LONG_ID_INDEX_TOO_BIG = "fundingOutputIndex must not be greater than FFFF"
  val LONG_ID_HASH_WRONG_SIZE = "fundingTxHash must be of length 32B"

  val SERIALIZATION_ERROR = "Serialization error"
  val DESERIALIZATION_ERROR = "Deserialization error"

  val SPHINX_ERR_PACKET_WRONG_LENGTH = "Error packet is of incorrect length"

  val CHANNEL_CLOSE_PENDING_CHANGES = "Cannot close when there are pending changes"
  val CHANNEL_SHUTDOWN_IN_PROGRESS = "Cannot proceed because channel shutdown is in progress"
  val CHANNEL_CLOSE_SIG_FAIL = "Cannot verify their close signature"
  val CHANNEL_TIMEDOUT_HTLC = "One or more htlcs has timed out"
  val CHANNEL_INFO_LEACK = "Channel info leak happened"
}

