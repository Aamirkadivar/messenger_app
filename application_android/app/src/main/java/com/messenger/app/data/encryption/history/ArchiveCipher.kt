package com.messenger.app.data.encryption.history

/**
 * The AEAD step of archiving, behind a port.
 *
 * [HistoryMessageCipher] is the production implementation and reaches VaultCrypto, which loads
 * libsodium and therefore cannot run in a JVM unit test. Routing archiving through this interface
 * keeps the surrounding policy - which root, which context, what happens on failure - testable
 * off-device, while production still uses the real XChaCha20-Poly1305 verified in Gate 2.
 *
 * Implementations must never return the input on failure. Null means "did not decrypt", and callers
 * must treat it as failure rather than as plaintext.
 */
interface ArchiveCipher {
    fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray): ByteArray?
    fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray?
}

/** Production binding: the real cipher, unchanged. */
object RealArchiveCipher : ArchiveCipher {
    override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray): ByteArray? =
        HistoryMessageCipher.seal(historyRoot, ctx, plaintext)

    override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? =
        HistoryMessageCipher.open(historyRoot, ctx, sealed)
}
