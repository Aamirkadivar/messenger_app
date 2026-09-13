package com.messenger.app.data.local

import java.security.MessageDigest

/**
 * Maps an authenticated account to the name of its local cache database.
 *
 * The physical database IS the account boundary in this design, so this
 * function is the whole of the security-relevant identity mapping.
 *
 * Canonical input
 *   The account id exactly as stored under `current_user_id`, with surrounding
 *   whitespace removed and nothing else altered.
 *
 * Normalisation
 *   Trim only. Case is deliberately NOT folded. Folding could map two
 *   genuinely distinct account ids onto one namespace, which is a
 *   confidentiality failure; failing to fold can at worst give one account two
 *   namespaces, which is a history-visibility annoyance. Server ids are stable
 *   canonical UUID strings, so in practice neither occurs - but when the two
 *   failure modes are unequal, the safe default is the one that never merges.
 *
 * Collision resistance
 *   SHA-256 over the UTF-8 bytes, hex-encoded, truncated to 32 hex characters
 *   (128 bits). Finding two account ids that share a namespace requires a
 *   128-bit collision.
 *
 * Filesystem safety
 *   The output is `[0-9a-f]{32}` behind a fixed prefix, so no account id -
 *   however malformed, however hostile - can produce a path separator, a
 *   traversal sequence, a reserved device name, or a case-insensitive
 *   collision on any filesystem.
 *
 * Stability
 *   Pure function of the account id, so the same account resolves to the same
 *   database across logout, account switching and process death.
 */
object AccountCacheNamespace {

    /** Prefix kept distinct from the pre-Gate-19 `messenger_database`. */
    const val PREFIX = "messenger_cache_"

    /**
     * The legacy, account-blind database. Quarantined: nothing in production
     * opens a database by this name any more. Named here only so the constant
     * is greppable and so tests can assert it is never opened.
     */
    const val LEGACY_QUARANTINED_DB = "messenger_database"

    fun databaseNameFor(accountId: String): String {
        val canonical = accountId.trim()
        require(canonical.isNotEmpty()) { "cannot derive a cache namespace from a blank account id" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (i in 0 until 16) {
            hex.append("0123456789abcdef"[(digest[i].toInt() shr 4) and 0xF])
            hex.append("0123456789abcdef"[digest[i].toInt() and 0xF])
        }
        return PREFIX + hex
    }
}
