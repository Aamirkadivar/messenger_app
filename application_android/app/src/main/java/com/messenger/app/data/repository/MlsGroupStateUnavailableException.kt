package com.messenger.app.data.repository

/**
 * Raised when a group is MLS-governed but this device holds no MLS state for
 * it, so the message cannot be encrypted for the group.
 *
 * The send is refused rather than downgraded. The previous behaviour fell
 * through to Sender Keys and emitted `encryption_version = 1` into an MLS
 * group: the message reached the server and was delivered, but no member could
 * read it, and nothing anywhere said the protocol had changed underneath.
 *
 * This is recoverable, not fatal - the device needs re-admission to the group,
 * which is a deliberate act rather than something a send path should attempt on
 * its own.
 */
class MlsGroupStateUnavailableException(
    val chatId: String
) : Exception(
    "This device has lost its encryption membership for this group, so the " +
        "message was not sent. It needs to rejoin the group before it can send here."
)
