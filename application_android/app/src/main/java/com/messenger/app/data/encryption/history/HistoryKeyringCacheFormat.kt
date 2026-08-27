package com.messenger.app.data.encryption.history

/**
 * The account binding on the Keystore-only keyring cache.
 *
 * WHY THIS EXISTS. The authoritative keyring is sealed under the vault master key
 * with `historyKeyringAad(userId, …)`, so it is cryptographically bound to one
 * account and a second account simply cannot open it. The cold-start cache had no
 * such binding: it was the bare keyring encoding under a single global
 * preferences key. Since logout clears tokens and `mls1_` state but not this
 * cache, a second account signing in on the same device would read the cache
 * first and silently adopt the first account's history roots.
 *
 * The fix is structural rather than cryptographic, deliberately. The bytes are
 * already Keystore-wrapped, so forging a binding requires the Keystore key -
 * at which point the roots are exposed regardless. What the binding must prevent
 * is ACCIDENTAL reuse across accounts, and an explicit owner field does that
 * unambiguously without introducing a second crypto mechanism.
 *
 * Wire format (all integers fixed-width big-endian):
 *
 *     u8    cacheFormatVersion = 3
 *     u32be userIdLen
 *     bytes userId (UTF-8, non-empty)
 *     u64be generation
 *     bytes keyring encoding   (itself beginning with HistoryKeyring.FORMAT_VERSION = 1)
 *
 * Legacy detection is exact and needs no heuristics: an unbound cache is a raw
 * `HistoryKeyring.encode()`, whose first byte is 1; the account-bound but
 * unstamped format leads with 2; this one leads with 3. None can be confused,
 * and anything that is not the current version decodes as [Decoded.Unusable] -
 * ownership and freshness are both unknown, which is not the same as absent.
 *
 * THE GENERATION FIELD. The cache and the authoritative keyring are two separate
 * durable writes. This field records which authoritative state the cache was
 * built from, so a reader can tell - without MK, and therefore on a cold start -
 * whether the cache is known to match. See [HistoryKeyringStore] for the write
 * ordering that gives the comparison its meaning.
 */
object HistoryKeyringCacheFormat {

    const val CACHE_FORMAT_VERSION = 3

    /** What a stored cache turned out to be. */
    sealed interface Decoded {
        /**
         * Bound to the expected account and structurally sound.
         *
         * [generation] still has to be checked against the store's marker before
         * the payload may be used: "sound and ours" is not "current".
         */
        data class Owned(val keyringBytes: ByteArray, val generation: Long) : Decoded

        /**
         * Bound to a DIFFERENT account.
         *
         * Definitive proof the material is not ours, which makes it safe to treat
         * exactly like an absent cache: there is no history of ours to orphan by
         * starting fresh.
         */
        data object ForAnotherUser : Decoded

        /**
         * Legacy unbound material, corrupt bytes, or a binding that cannot be read.
         *
         * Ownership is UNKNOWN, which is different from "not ours". Minting a
         * replacement keyring here could orphan archives this very account
         * already wrote, so the caller must refuse rather than start fresh.
         */
        data object Unusable : Decoded
    }

    fun encode(userId: String, generation: Long, keyringBytes: ByteArray): ByteArray {
        require(userId.isNotBlank()) { "cache binding requires a user id" }
        require(generation >= 0) { "generation must not be negative" }
        val id = userId.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(CACHE_FORMAT_VERSION)
        out.write(HistoryCrypto.u32be(id.size))
        out.write(id)
        out.write(u64be(generation))
        out.write(keyringBytes)
        return out.toByteArray()
    }

    private fun u64be(v: Long) = ByteArray(8) { i -> ((v ushr (56 - 8 * i)) and 0xFF).toByte() }

    /**
     * @param expectedUserId the signed-in account. Blank or null means there is no
     *   identity to check against, so nothing can be accepted.
     */
    fun decode(bytes: ByteArray?, expectedUserId: String?): Decoded {
        if (bytes == null || bytes.isEmpty()) return Decoded.Unusable
        if (expectedUserId.isNullOrBlank()) return Decoded.Unusable

        val version = bytes[0].toInt() and 0xFF
        // A legacy (version 1) cache is the bare keyring encoding. It may well
        // belong to this account, but nothing in it says so, and guessing is
        // exactly what this format exists to stop.
        if (version != CACHE_FORMAT_VERSION) return Decoded.Unusable
        if (bytes.size < 5) return Decoded.Unusable

        val idLen = ((bytes[1].toInt() and 0xFF) shl 24) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 8) or
            (bytes[4].toInt() and 0xFF)
        if (idLen <= 0 || 5 + idLen > bytes.size) return Decoded.Unusable

        val owner = String(bytes, 5, idLen, Charsets.UTF_8)
        // Ownership is decided before anything else is parsed, so a truncated or
        // damaged generation field can never turn another account's cache into
        // this account's - it stays ForAnotherUser exactly as before.
        if (owner != expectedUserId) return Decoded.ForAnotherUser

        val genAt = 5 + idLen
        if (genAt + 8 > bytes.size) return Decoded.Unusable
        var generation = 0L
        for (i in 0 until 8) {
            generation = (generation shl 8) or (bytes[genAt + i].toLong() and 0xFF)
        }
        if (generation < 0) return Decoded.Unusable

        val keyring = bytes.copyOfRange(genAt + 8, bytes.size)
        if (keyring.isEmpty()) return Decoded.Unusable
        return Decoded.Owned(keyring, generation)
    }
}
