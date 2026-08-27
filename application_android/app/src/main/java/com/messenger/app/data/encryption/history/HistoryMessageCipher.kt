package com.messenger.app.data.encryption.history

import com.messenger.app.data.encryption.VaultCrypto

/**
 * The AEAD edge of the history hierarchy.
 *
 * Isolated in its own file because it is the ONLY part of Layer B's crypto core that touches
 * [VaultCrypto], and therefore the only part that needs libsodium at runtime. Tests for this file
 * must live under `androidTest`; everything in [HistoryCrypto] and [HistoryKeyring] stays on the JVM.
 *
 * No new cipher is introduced here. Sealing is delegated to `VaultCrypto.sealXChaCha`, which already
 * generates and prepends a fresh 24-byte nonce, so this layer never invents nonce material.
 */
object HistoryMessageCipher {

    /**
     * Seals one archived message.
     *
     * Output is exactly what `VaultCrypto.openXChaCha` needs later: `nonce(24) || ciphertext || tag(16)`.
     * Returns null if the primitive refuses (it never returns partial or unauthenticated output).
     */
    fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray): ByteArray? {
        val key = HistoryCrypto.deriveMessageKey(historyRoot, ctx.messageId, ctx.rootVersion)
        return try {
            VaultCrypto.sealXChaCha(key, plaintext, HistoryCrypto.aad(ctx))
        } finally {
            HistoryCrypto.bestEffortWipe(key)
        }
    }

    /**
     * Opens one archived message.
     *
     * Returns null on ANY failure - wrong root, wrong root version, wrong message id, altered
     * context, tampered ciphertext, tag, or nonce. There is deliberately no fallback path that
     * returns the input, a partial decryption, or an unauthenticated plaintext: authentication
     * failure is the only possible outcome other than the exact original plaintext.
     */
    fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? {
        val key = HistoryCrypto.deriveMessageKey(historyRoot, ctx.messageId, ctx.rootVersion)
        return try {
            VaultCrypto.openXChaCha(key, sealed, HistoryCrypto.aad(ctx))
        } finally {
            HistoryCrypto.bestEffortWipe(key)
        }
    }
}
