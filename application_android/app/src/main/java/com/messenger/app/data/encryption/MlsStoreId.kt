package com.messenger.app.data.encryption

import java.security.MessageDigest

/**
 * The opaque tag naming one incarnation of this device's local MLS store.
 *
 * A device keeps its device id forever, but its MLS store does not: a recovery
 * reset, a reinstall, or a change of client implementation all build a brand-new
 * store with a brand-new identity. Everything the previous store published stays
 * on the Delivery Service, and the private init keys that would open those
 * KeyPackages died with it — so a Welcome built from one is undecryptable. The
 * server needs some way to tell the incarnations apart, and this is it.
 *
 * The tag is `SHA-256(signature public key)` of the credential the store signs
 * with. That key is stable for the life of a store — it survives snapshot and
 * restore — and changes exactly when [MlsClient.create] mints a new identity,
 * which is precisely the boundary we need to name. Deriving it from the store's
 * own key material rather than from a separate counter is deliberate: the two can
 * never drift apart, and drift is the whole failure mode being fixed.
 *
 * PUBLIC MATERIAL ONLY. The signature *public* key is already published inside
 * every KeyPackage, and hashing it adds nothing a claimer could not compute. No
 * private key is read, and the tag is not a secret — it is an equality token.
 *
 * This reads the RFC 9420 framing of a KeyPackage far enough to reach that
 * field, and does no cryptography: no validation, no signature check, no
 * interpretation. Crypto stays in mls-core.
 */
internal object MlsStoreId {

    /**
     * The store tag for whichever store produced [keyPackage], or null if the
     * bytes cannot be read. Null must fail the caller closed — publishing a
     * package under a tag we could not derive would recreate the exact
     * ambiguity this exists to remove.
     */
    fun of(keyPackage: ByteArray): String? {
        val signatureKey = signatureKey(keyPackage) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(signatureKey)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * KeyPackage framing, read positionally:
     *
     *     protocol_version : u16
     *     cipher_suite     : u16
     *     init_key         : opaque<V>
     *     leaf_node.encryption_key : opaque<V>
     *     leaf_node.signature_key  : opaque<V>   <- what we want
     *
     * Only the first three vectors are walked; everything after is ignored.
     */
    private fun signatureKey(kp: ByteArray): ByteArray? {
        val afterInit = skipVector(kp, 4) ?: return null
        val afterEncryption = skipVector(kp, afterInit) ?: return null
        val (signatureKey, _) = readVector(kp, afterEncryption) ?: return null
        return signatureKey.takeIf { it.isNotEmpty() }
    }

    private fun skipVector(b: ByteArray, offset: Int): Int? =
        readVector(b, offset)?.second

    private fun readVector(b: ByteArray, offset: Int): Pair<ByteArray, Int>? {
        val (length, start) = readVarint(b, offset) ?: return null
        val end = start + length
        if (length < 0 || end > b.size || end < start) return null
        return b.copyOfRange(start, end) to end
    }

    /**
     * RFC 9420 §2.1.2 variable-length integer: the top two bits of the first
     * byte give the encoding width. The 0b11 prefix is reserved and rejected
     * rather than guessed at.
     */
    private fun readVarint(b: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset < 0 || offset >= b.size) return null
        val first = b[offset].toInt() and 0xFF
        return when (first ushr 6) {
            0 -> (first and 0x3F) to (offset + 1)
            1 -> if (offset + 1 >= b.size) null else
                (((first and 0x3F) shl 8) or (b[offset + 1].toInt() and 0xFF)) to (offset + 2)
            2 -> if (offset + 3 >= b.size) null else
                (((first and 0x3F) shl 24) or
                    ((b[offset + 1].toInt() and 0xFF) shl 16) or
                    ((b[offset + 2].toInt() and 0xFF) shl 8) or
                    (b[offset + 3].toInt() and 0xFF)) to (offset + 4)
            else -> null
        }
    }
}
