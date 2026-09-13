package com.messenger.app.security

import java.security.MessageDigest

/**
 * Who a piece of MLS state belongs to.
 *
 * MLS identity is per ACCOUNT and per DEVICE, not per chat. A leaf carries the
 * credential "userId|deviceId", and the codebase already relies on several
 * devices of one account being separate leaves - so a chat id, a group id or a
 * KeyPackage reference hash says nothing about ownership. Two accounts on one
 * phone can legitimately belong to the same group and still need entirely
 * separate leaf and signature keys.
 *
 * Gate 26 proved what indexing by chat id alone costs: account B observed the
 * group and epoch account A had established, and would have committed to it
 * under account A's credential.
 *
 * Both components are required and non-blank. There is deliberately no default,
 * no nullable owner and no "current user" fallback: an operation that cannot
 * name its owner has no business touching MLS state.
 */
data class MlsOwner(
    val accountId: String,
    val deviceId: String
) {
    init {
        require(accountId.isNotBlank()) { "MLS owner needs an account id" }
        require(deviceId.isNotBlank()) { "MLS owner needs a device id" }
    }

    /**
     * The namespace component for this owner.
     *
     * The account id is length-prefixed before hashing, the same trick the
     * session-cache AAD uses: a plain concatenation would let ("ab","c") and
     * ("a","bc") hash alike, and one owner could then address another's slots
     * by choosing its ids.
     */
    val tag: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val material = "${accountId.length}:$accountId:$deviceId"
        MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Null when either half is missing, so callers fail closed. */
        fun of(accountId: String?, deviceId: String?): MlsOwner? {
            val a = accountId?.trim().orEmpty()
            val d = deviceId?.trim().orEmpty()
            return if (a.isEmpty() || d.isEmpty()) null else MlsOwner(a, d)
        }
    }
}
