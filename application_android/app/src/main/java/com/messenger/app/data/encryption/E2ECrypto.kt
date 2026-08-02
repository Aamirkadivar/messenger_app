package com.messenger.app.data.encryption

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.SecretBox

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
    // raw bytes otherwise (would apply to group voice/attachments, not yet wired).

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
}
