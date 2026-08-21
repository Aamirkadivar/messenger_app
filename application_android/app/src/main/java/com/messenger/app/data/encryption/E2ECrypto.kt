package com.messenger.app.data.encryption

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.SecretBox
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Real end-to-end encryption using NaCl crypto_box (X25519 + XSalsa20-Poly1305)
 * via libsodium. Byte-for-byte compatible with the Windows client (libsodium)
 * and Go backend (nacl/box).
 *
 * Wire format (shared with the Windows client): the ciphertext string is
 * hex(nonce[24] || crypto_box_easy_output). Keys are 32-byte hex strings.
 *
 * For a direct chat, the "other" key is the other participant's public key
 * regardless of who sent the message (box's shared secret is symmetric), so
 * one key per chat both encrypts our outgoing messages and decrypts the thread.
 */
object E2ECrypto {

    private const val TAG = "E2ECrypto"
    private val sodium = LazySodiumAndroid(SodiumAndroid())

    data class KeyPairHex(val publicHex: String, val privateHex: String)

    fun generateKeyPair(): KeyPairHex? {
        return try {
            val pk = ByteArray(Box.PUBLICKEYBYTES)
            val sk = ByteArray(Box.SECRETKEYBYTES)
            if (!sodium.cryptoBoxKeypair(pk, sk)) {
                Log.e(TAG, "cryptoBoxKeypair failed")
                return null
            }
            KeyPairHex(toHex(pk), toHex(sk))
        } catch (e: Exception) {
            Log.e(TAG, "generateKeyPair error", e)
            null
        }
    }

    /** Returns hex(nonce||ciphertext), or null on failure. */
    fun encrypt(message: String, recipientPublicHex: String, senderPrivateHex: String): String? {
        return try {
            val pk = fromHex(recipientPublicHex) ?: return null
            val sk = fromHex(senderPrivateHex) ?: return null
            if (pk.size != Box.PUBLICKEYBYTES || sk.size != Box.SECRETKEYBYTES) return null

            val msg = message.toByteArray(Charsets.UTF_8)
            val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
            val cipher = ByteArray(msg.size + Box.MACBYTES)
            if (!sodium.cryptoBoxEasy(cipher, msg, msg.size.toLong(), nonce, pk, sk)) {
                Log.e(TAG, "cryptoBoxEasy failed")
                return null
            }
            toHex(nonce + cipher)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt error", e)
            null
        }
    }

    /**
     * Protocol v2 direct encrypt: ephemeral sender keypair, discard sk after seal.
     * Wire: hex(eph_pk[32] || nonce[24] || ct). Recipient opens with identity sk.
     */
    fun encryptEphemeral(message: String, recipientPublicHex: String): String? {
        val plain = message.toByteArray(Charsets.UTF_8)
        val sealed = encryptBytesEphemeral(plain, recipientPublicHex) ?: return null
        return toHex(sealed)
    }

    fun encryptBytesEphemeral(plain: ByteArray, recipientPublicHex: String): ByteArray? {
        return try {
            val pk = fromHex(recipientPublicHex) ?: return null
            if (pk.size != Box.PUBLICKEYBYTES) return null
            val ephPk = ByteArray(Box.PUBLICKEYBYTES)
            val ephSk = ByteArray(Box.SECRETKEYBYTES)
            if (!sodium.cryptoBoxKeypair(ephPk, ephSk)) return null
            val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
            val cipher = ByteArray(plain.size + Box.MACBYTES)
            if (!sodium.cryptoBoxEasy(cipher, plain, plain.size.toLong(), nonce, pk, ephSk)) {
                Log.e(TAG, "cryptoBoxEasy ephemeral failed")
                return null
            }
            ephSk.fill(0)
            ephPk + nonce + cipher
        } catch (e: Exception) {
            Log.e(TAG, "encryptBytesEphemeral error", e)
            null
        }
    }

    /** Decrypts a payload produced by [encrypt]. Returns null on failure. */
    fun decrypt(payloadHex: String, otherPublicHex: String, myPrivateHex: String): String? {
        return try {
            val payload = fromHex(payloadHex) ?: return null
            val pk = fromHex(otherPublicHex) ?: return null
            val sk = fromHex(myPrivateHex) ?: return null
            if (pk.size != Box.PUBLICKEYBYTES || sk.size != Box.SECRETKEYBYTES) return null
            if (payload.size < Box.NONCEBYTES + Box.MACBYTES) return null

            val nonce = payload.copyOfRange(0, Box.NONCEBYTES)
            val cipher = payload.copyOfRange(Box.NONCEBYTES, payload.size)
            val plain = ByteArray(cipher.size - Box.MACBYTES)
            if (!sodium.cryptoBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, pk, sk)) {
                return null
            }
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt error", e)
            null
        }
    }

    /** Decrypts [encryptEphemeral] / [encryptBytesEphemeral] with recipient identity sk. */
    fun decryptEphemeral(payloadHex: String, myPrivateHex: String): String? {
        val payload = fromHex(payloadHex) ?: return null
        val plain = decryptBytesEphemeral(payload, myPrivateHex) ?: return null
        return runCatching { String(plain, Charsets.UTF_8) }.getOrNull()
    }

    fun decryptBytesEphemeral(payload: ByteArray, myPrivateHex: String): ByteArray? {
        return try {
            val sk = fromHex(myPrivateHex) ?: return null
            if (sk.size != Box.SECRETKEYBYTES) return null
            // eph_pk(32) || nonce(24) || ct+mac
            if (payload.size < Box.PUBLICKEYBYTES + Box.NONCEBYTES + Box.MACBYTES) return null
            val ephPk = payload.copyOfRange(0, Box.PUBLICKEYBYTES)
            val nonce = payload.copyOfRange(Box.PUBLICKEYBYTES, Box.PUBLICKEYBYTES + Box.NONCEBYTES)
            val cipher = payload.copyOfRange(Box.PUBLICKEYBYTES + Box.NONCEBYTES, payload.size)
            val plain = ByteArray(cipher.size - Box.MACBYTES)
            if (!sodium.cryptoBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, ephPk, sk)) {
                return null
            }
            plain
        } catch (e: Exception) {
            Log.e(TAG, "decryptBytesEphemeral error", e)
            null
        }
    }

    /**
     * Binary variant of [encrypt] for file payloads such as voice notes.
     *
     * Same construction and same wire layout (nonce || ciphertext), but raw
     * bytes rather than hex: a voice note is hundreds of kilobytes and hex
     * would double it for no benefit, since the payload is uploaded as a
     * binary body rather than embedded in JSON.
     */
    fun encryptBytes(
        plain: ByteArray,
        recipientPublicHex: String,
        senderPrivateHex: String
    ): ByteArray? {
        return try {
            val pk = fromHex(recipientPublicHex) ?: return null
            val sk = fromHex(senderPrivateHex) ?: return null
            if (pk.size != Box.PUBLICKEYBYTES || sk.size != Box.SECRETKEYBYTES) return null

            val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
            val cipher = ByteArray(plain.size + Box.MACBYTES)
            if (!sodium.cryptoBoxEasy(cipher, plain, plain.size.toLong(), nonce, pk, sk)) {
                Log.e(TAG, "cryptoBoxEasy (bytes) failed")
                return null
            }
            nonce + cipher
        } catch (e: Exception) {
            Log.e(TAG, "encryptBytes error", e)
            null
        }
    }

    /** Decrypts a payload produced by [encryptBytes]. Null on failure. */
    fun decryptBytes(
        payload: ByteArray,
        otherPublicHex: String,
        myPrivateHex: String
    ): ByteArray? {
        return try {
            val pk = fromHex(otherPublicHex) ?: return null
            val sk = fromHex(myPrivateHex) ?: return null
            if (pk.size != Box.PUBLICKEYBYTES || sk.size != Box.SECRETKEYBYTES) return null
            if (payload.size < Box.NONCEBYTES + Box.MACBYTES) return null

            val nonce = payload.copyOfRange(0, Box.NONCEBYTES)
            val cipher = payload.copyOfRange(Box.NONCEBYTES, payload.size)
            val plain = ByteArray(cipher.size - Box.MACBYTES)
            if (!sodium.cryptoBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, pk, sk)) {
                return null
            }
            plain
        } catch (e: Exception) {
            Log.e(TAG, "decryptBytes error", e)
            null
        }
    }

    // ==================== Group "Sender Keys" (crypto_secretbox) ====================
    //
    // WhatsApp/Signal-style group E2EE: each sender generates one symmetric key
    // for messages they send, distributes it pairwise (via encrypt/encryptBytes
    // above) to every other member, then encrypts their own messages with it
    // directly - one encryption per message regardless of group size, instead
    // of once per recipient. Byte-for-byte compatible with the Windows client's
    // Encryption::secretBox* (crypto_secretbox_easy). Wire format: nonce[24] ||
    // ciphertext, hex-encoded when embedded in a JSON string field (group text),
    // raw bytes for uploaded media (group voice / attachments / round video).

    /** Generates a new random Sender Key. Returns its 32-byte hex encoding. */
    fun secretBoxGenerateKey(): String? {
        return try {
            val key = ByteArray(SecretBox.KEYBYTES)
            sodium.cryptoSecretBoxKeygen(key)
            toHex(key)
        } catch (e: Exception) {
            Log.e(TAG, "secretBoxGenerateKey error", e)
            null
        }
    }

    /** Returns nonce||ciphertext, or null on failure. */
    fun secretBoxEncryptBytes(plain: ByteArray, keyHex: String): ByteArray? {
        return try {
            val key = fromHex(keyHex) ?: return null
            if (key.size != SecretBox.KEYBYTES) return null

            val nonce = sodium.randomBytesBuf(SecretBox.NONCEBYTES)
            val cipher = ByteArray(plain.size + SecretBox.MACBYTES)
            if (!sodium.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, key)) {
                Log.e(TAG, "cryptoSecretBoxEasy failed")
                return null
            }
            nonce + cipher
        } catch (e: Exception) {
            Log.e(TAG, "secretBoxEncryptBytes error", e)
            null
        }
    }

    /** Decrypts a payload produced by [secretBoxEncryptBytes]. Null on failure. */
    fun secretBoxDecryptBytes(payload: ByteArray, keyHex: String): ByteArray? {
        return try {
            val key = fromHex(keyHex) ?: return null
            if (key.size != SecretBox.KEYBYTES) return null
            if (payload.size < SecretBox.NONCEBYTES + SecretBox.MACBYTES) return null

            val nonce = payload.copyOfRange(0, SecretBox.NONCEBYTES)
            val cipher = payload.copyOfRange(SecretBox.NONCEBYTES, payload.size)
            val plain = ByteArray(cipher.size - SecretBox.MACBYTES)
            if (!sodium.cryptoSecretBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, key)) {
                return null
            }
            plain
        } catch (e: Exception) {
            Log.e(TAG, "secretBoxDecryptBytes error", e)
            null
        }
    }

    /**
     * Direct-chat security code: SHA-256 of the two identity public keys
     * (32-byte, sorted by lowercase hex) formatted as 4 lines of 4×4 hex groups.
     * Must match Windows Encryption::safetyNumber.
     */
    fun safetyNumber(pubHexA: String, pubHexB: String): String? {
        val a = fromHex(pubHexA.trim().lowercase()) ?: return null
        val b = fromHex(pubHexB.trim().lowercase()) ?: return null
        if (a.size != 32 || b.size != 32) return null
        val aHex = toHex(a)
        val bHex = toHex(b)
        val concat = if (aHex <= bHex) a + b else b + a
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(concat)
        val hex = toHex(digest)
        return hex.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
    }

    fun safetyNumberCompact(formatted: String): String =
        formatted.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun safetyNumberQrPayload(formatted: String): String? {
        val compact = safetyNumberCompact(formatted)
        return if (compact.length == 64) "sn1.$compact" else null
    }

    fun parseSafetyNumberQr(raw: String): String? {
        val s = raw.trim()
        val body = if (s.startsWith("sn1.", ignoreCase = true)) s.substring(4) else s
        val compact = safetyNumberCompact(body)
        return compact.takeIf { it.length == 64 }
    }

    /** Public so callers can hex-encode/decode a Sender Key for storage or pairwise wrapping. */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun x25519(sk: ByteArray, pk: ByteArray): ByteArray? {
        return try {
            if (sk.size != 32 || pk.size != 32) return null
            val q = ByteArray(32)
            if (!sodium.cryptoScalarMult(q, sk, pk)) return null
            q
        } catch (e: Exception) {
            null
        }
    }

    fun secretBoxSeal(plain: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (nonce.size != SecretBox.NONCEBYTES || key.size != SecretBox.KEYBYTES) return null
            val cipher = ByteArray(plain.size + SecretBox.MACBYTES)
            if (!sodium.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, key)) return null
            cipher
        } catch (e: Exception) {
            null
        }
    }

    fun secretBoxOpen(ct: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (nonce.size != SecretBox.NONCEBYTES || key.size != SecretBox.KEYBYTES) return null
            if (ct.size < SecretBox.MACBYTES) return null
            val plain = ByteArray(ct.size - SecretBox.MACBYTES)
            if (!sodium.cryptoSecretBoxOpenEasy(plain, ct, ct.size.toLong(), nonce, key)) return null
            plain
        } catch (e: Exception) {
            null
        }
    }

    data class Envelope(
        val payload: ByteArray,
        val fileName: String = "",
        val forwardedFrom: String = "",
        val durationMs: Long = 0,
        val fileSize: Long = 0,
        val thumbnailUrl: String = "",
        val fileUrl: String = ""
    )

    /**
     * Inner plaintext envelope so filename / duration / size / forward
     * attribution never sit in server columns. Magic EM1\\n + u16be meta JSON + payload.
     */
    fun wrapEnvelope(
        payload: ByteArray,
        fileName: String = "",
        forwardedFrom: String = "",
        durationMs: Long = 0,
        fileSize: Long = 0,
        thumbnailUrl: String = "",
        fileUrl: String = ""
    ): ByteArray {
        val o = JSONObject()
        if (fileName.isNotBlank()) o.put("fn", fileName)
        if (forwardedFrom.isNotBlank()) o.put("fwd", forwardedFrom)
        if (durationMs > 0) o.put("dur", durationMs)
        if (fileSize > 0) o.put("sz", fileSize)
        if (thumbnailUrl.isNotBlank()) o.put("th", thumbnailUrl)
        if (fileUrl.isNotBlank()) o.put("fu", fileUrl)
        val meta = o.toString().toByteArray(Charsets.UTF_8)
        if (meta.size > 0xffff) return payload
        val out = ByteArrayOutputStream(6 + meta.size + payload.size)
        out.write(byteArrayOf(0x45, 0x4D, 0x31, 0x0A))
        out.write((meta.size shr 8) and 0xff)
        out.write(meta.size and 0xff)
        out.write(meta)
        out.write(payload)
        return out.toByteArray()
    }

    fun unwrapEnvelope(data: ByteArray): Envelope {
        if (data.size < 6) return Envelope(data)
        if (data[0] != 0x45.toByte() || data[1] != 0x4D.toByte() ||
            data[2] != 0x31.toByte() || data[3] != 0x0A.toByte()
        ) {
            return Envelope(data)
        }
        val n = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        if (n < 0 || data.size < 6 + n) return Envelope(data)
        return try {
            val meta = JSONObject(String(data, 6, n, Charsets.UTF_8))
            Envelope(
                payload = data.copyOfRange(6 + n, data.size),
                fileName = meta.optString("fn"),
                forwardedFrom = meta.optString("fwd"),
                durationMs = meta.optLong("dur"),
                fileSize = meta.optLong("sz"),
                thumbnailUrl = meta.optString("th"),
                fileUrl = meta.optString("fu")
            )
        } catch (_: Exception) {
            Envelope(data)
        }
    }

    data class FanoutPart(val deviceId: String, val blob: ByteArray)

    fun wrapFanout(parts: List<FanoutPart>): ByteArray? {
        if (parts.isEmpty() || parts.size > 0xffff) return null
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x46, 0x4E, 0x31, 0x0A))
        out.write((parts.size shr 8) and 0xff)
        out.write(parts.size and 0xff)
        for (p in parts) {
            val id = p.deviceId.toByteArray(Charsets.UTF_8)
            if (id.size > 255) return null
            out.write(id.size)
            out.write(id)
            val n = p.blob.size
            out.write((n ushr 24) and 0xff)
            out.write((n ushr 16) and 0xff)
            out.write((n ushr 8) and 0xff)
            out.write(n and 0xff)
            out.write(p.blob)
        }
        return out.toByteArray()
    }

    fun pickFanout(data: ByteArray, deviceId: String): ByteArray? {
        if (data.size < 6 || data[0] != 0x46.toByte() || data[1] != 0x4E.toByte() ||
            data[2] != 0x31.toByte() || data[3] != 0x0A.toByte()
        ) {
            return data
        }
        val n = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        var off = 6
        repeat(n) {
            if (off >= data.size) return null
            val idLen = data[off].toInt() and 0xff
            off++
            if (off + idLen + 4 > data.size) return null
            val id = String(data, off, idLen, Charsets.UTF_8)
            off += idLen
            val blobLen = ((data[off].toInt() and 0xff) shl 24) or
                ((data[off + 1].toInt() and 0xff) shl 16) or
                ((data[off + 2].toInt() and 0xff) shl 8) or
                (data[off + 3].toInt() and 0xff)
            off += 4
            if (blobLen < 0 || off + blobLen > data.size) return null
            val blob = data.copyOfRange(off, off + blobLen)
            off += blobLen
            if (id == deviceId) return blob
        }
        return null
    }

    fun isFanout(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x46.toByte() && data[1] == 0x4E.toByte() &&
            data[2] == 0x31.toByte() && data[3] == 0x0A.toByte()

    /** All parts of an FN1 payload, or empty when [data] is not a fan-out. */
    fun listFanout(data: ByteArray): List<FanoutPart> {
        if (!isFanout(data) || data.size < 6) return emptyList()
        val n = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        val parts = ArrayList<FanoutPart>(n)
        var off = 6
        repeat(n) {
            if (off >= data.size) return parts
            val idLen = data[off].toInt() and 0xff
            off++
            if (off + idLen + 4 > data.size) return parts
            val id = String(data, off, idLen, Charsets.UTF_8)
            off += idLen
            val blobLen = ((data[off].toInt() and 0xff) shl 24) or
                ((data[off + 1].toInt() and 0xff) shl 16) or
                ((data[off + 2].toInt() and 0xff) shl 8) or
                (data[off + 3].toInt() and 0xff)
            off += 4
            if (blobLen < 0 || off + blobLen > data.size) return parts
            parts.add(FanoutPart(id, data.copyOfRange(off, off + blobLen)))
            off += blobLen
        }
        return parts
    }
}
