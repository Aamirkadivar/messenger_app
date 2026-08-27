package com.messenger.app.data.encryption.history

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Layer B history-key hierarchy - PURE core.
 *
 *     per-chat history root -> HKDF -> per-message archive key -> XChaCha20-Poly1305
 *
 * This file deliberately contains NO reference to [com.messenger.app.data.encryption.VaultCrypto].
 * VaultCrypto is a Kotlin `object` whose initialiser constructs `LazySodiumAndroid(SodiumAndroid())`,
 * so touching any of its members loads a native library and cannot run in a JVM unit test. Keeping
 * the AEAD call in a separate file ([HistoryMessageCipher]) makes that boundary structural rather
 * than incidental: everything here is testable off-device, by construction.
 *
 * History keys are NOT MLS keys. Nothing in this hierarchy is derived from, or may be seeded with,
 * an MLS group secret, epoch secret, sender key, ratchet secret, or MLS snapshot.
 */
object HistoryCrypto {

    /** Bound into the AAD; bump only on a wire-format change. */
    const val PROTOCOL_VERSION = 1

    const val ROOT_BYTES = 32
    const val MESSAGE_KEY_BYTES = 32

    private const val AAD_LABEL = "messenger/history-aad/v1"
    private const val MESSAGE_KEY_LABEL = "messenger/history-message-key/v1"
    private const val KDF_SALT_LABEL = "messenger/history-kdf-salt/v1"

    /**
     * Platform CSPRNG. `VaultCrypto.randomBytes` (libsodium) is the repository's other option, but
     * it is unreachable from JVM tests, and root generation is required to stay JVM-testable.
     * `SecureRandom` is the platform-approved CSPRNG on Android and is not a new crypto library.
     */
    private val rng = SecureRandom()

    /**
     * A fresh 32-byte per-chat history root.
     *
     * Never deterministic, never derived from an MLS secret, never logged, never uploaded.
     */
    fun newHistoryRoot(): ByteArray = ByteArray(ROOT_BYTES).also(rng::nextBytes)

    /**
     * Canonical AAD encoding - the exact byte layout is:
     *
     *     lp("messenger/history-aad/v1")
     *     u32be(protocolVersion)
     *     lp(utf8(userId))
     *     lp(utf8(chatId))
     *     lp(utf8(messageId))
     *     u32be(rootVersion)
     *
     * where `lp(x) = u32be(x.size) || x` and all integers are fixed-width big-endian.
     *
     * Length prefixes rather than delimiters: a separator-based encoding lets a value containing the
     * separator impersonate a different field split, so `chatId="a|b", messageId="c"` and
     * `chatId="a", messageId="b|c"` would produce identical AAD. With explicit lengths, no field
     * value can forge a different parse. UTF-8 and big-endian are stated explicitly so the encoding
     * is neither locale- nor platform-dependent.
     */
    fun aad(ctx: HistoryContext): ByteArray =
        lp(AAD_LABEL) +
            u32be(ctx.protocolVersion) +
            lp(ctx.userId) +
            lp(ctx.chatId) +
            lp(ctx.messageId) +
            u32be(ctx.rootVersion)

    /**
     * Derives the 32-byte per-message archive key.
     *
     * HKDF-SHA256, not a raw hash: the root is the IKM, and the message identity plus root version
     * are bound through `info`, so the same message under a rotated root yields an unrelated key.
     */
    fun deriveMessageKey(historyRoot: ByteArray, messageId: String, rootVersion: Int): ByteArray {
        require(historyRoot.size == ROOT_BYTES) {
            "history root must be exactly " + ROOT_BYTES + " bytes, was " + historyRoot.size
        }
        val info = lp(MESSAGE_KEY_LABEL) + lp(messageId) + u32be(rootVersion)
        return hkdfSha256(
            ikm = historyRoot,
            salt = KDF_SALT_LABEL.toByteArray(Charsets.UTF_8),
            info = info,
            length = MESSAGE_KEY_BYTES
        )
    }

    /**
     * Overwrites a short-lived derived key.
     *
     * This is hygiene, NOT a guarantee. On the JVM the GC may already have copied the array, and
     * nothing here erases those copies or any paged-out image. Do not treat it as memory erasure.
     */
    fun bestEffortWipe(secret: ByteArray) = secret.fill(0)

    /**
     * RFC 5869 HKDF-SHA256.
     *
     * Duplicated rather than reused: `VaultCrypto.HkdfSha256` is `private` and Gate 2 forbids
     * modifying VaultCrypto. This is byte-identical to it - HMAC-SHA256 from the JDK, not a new
     * cryptographic library.
     */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun u32be(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun lp(b: ByteArray): ByteArray = u32be(b.size) + b

    private fun lp(s: String): ByteArray = lp(s.toByteArray(Charsets.UTF_8))
}

/**
 * Everything bound into the AAD of one archived message.
 *
 * Changing any field changes the AAD, so a ciphertext cannot be replayed under a different user,
 * chat, message id, root version, or protocol version.
 */
data class HistoryContext(
    val userId: String,
    val chatId: String,
    val messageId: String,
    val rootVersion: Int,
    val protocolVersion: Int = HistoryCrypto.PROTOCOL_VERSION,
)
