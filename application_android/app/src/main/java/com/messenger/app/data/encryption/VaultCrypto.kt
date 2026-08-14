package com.messenger.app.data.encryption

import android.util.Base64
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Password-wrapped E2EE vault crypto. Byte-compatible with `back-end/e2ee`
 * and the Windows libsodium client:
 * - Argon2id via crypto_pwhash (libsodium hardcodes parallelism p=1)
 * - XChaCha20-Poly1305-IETF AEAD, wire = nonce(24) || ciphertext||tag
 * - Vault plaintext = JSON
 *
 * Phase 5 wire: MK is both the password-wrapped master and the vault AEAD key
 * (VEK == MK). A separate VEK wrap can be added later without server changes.
 */
object VaultCrypto {

    private const val TAG = "VaultCrypto"

    const val SUITE_VAULT_AEAD = "suite:aead-xchacha20poly1305-v1"
    const val SUITE_NACL_BOX = "suite:nacl-box-xsalsa20poly1305-v1"
    const val KDF_ARGON2ID = "kdf:argon2id-v1"
    const val KDF_HKDF_SHA256 = "kdf:hkdf-sha256-v1"
    const val PROTOCOL_VERSION = 1
    const val VAULT_FORMAT = 1

    private val sodium = LazySodiumAndroid(SodiumAndroid())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class ArgonParams(
        val memoryKiB: Int = 64 * 1024,
        val time: Int = 3,
        val threads: Int = 1,
        val keyLen: Int = 32
    ) {
        fun toParamsJson(): String =
            JSONObject()
                .put("m", memoryKiB)
                .put("t", time)
                .put("p", threads)
                .put("dklen", keyLen)
                .toString()

        companion object {
            fun fromParamsJson(s: String?): ArgonParams {
                if (s.isNullOrBlank()) return ArgonParams()
                val o = JSONObject(s)
                return ArgonParams(
                    memoryKiB = o.optInt("m", 64 * 1024),
                    time = o.optInt("t", 3),
                    threads = o.optInt("p", 1),
                    keyLen = o.optInt("dklen", 32)
                )
            }
        }
    }

    @Serializable
    data class VaultPlaintext(
        @SerialName("format_version") val formatVersion: Int = VAULT_FORMAT,
        @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
        @SerialName("suite_ids") val suiteIds: List<String> = listOf(SUITE_NACL_BOX, SUITE_VAULT_AEAD),
        @SerialName("identity_pub_hex") val identityPubHex: String,
        @SerialName("identity_priv_hex") val identityPrivHex: String,
        @SerialName("own_sender_keys") val ownSenderKeys: Map<String, String> = emptyMap(),
        @SerialName("peer_sender_keys") val peerSenderKeys: Map<String, String> = emptyMap(),
        @SerialName("peer_pubs") val peerPubs: Map<String, String> = emptyMap(),
        @SerialName("direct_ratchets") val directRatchets: Map<String, String> = emptyMap(),
        @SerialName("meta") val meta: Map<String, String> = emptyMap()
    )

    data class BuiltVault(
        val vaultVersion: Int,
        val vaultCiphertext: ByteArray,
        val pwSalt: ByteArray,
        val pwParamsJson: String,
        val pwWrappedMaster: ByteArray,
        val rkSalt: ByteArray? = null,
        val rkWrappedMaster: ByteArray? = null,
        /** Base64 recovery secret — show once; never stored locally. */
        val recoveryKeyDisplay: String? = null,
        val mk: ByteArray? = null
    )

    data class UnlockedVault(
        val plaintext: VaultPlaintext,
        val mk: ByteArray
    )

    fun randomBytes(n: Int): ByteArray = sodium.randomBytesBuf(n)

    fun derivePasswordKek(password: String, salt: ByteArray, params: ArgonParams): ByteArray? {
        return try {
            if (salt.size != PwHash.SALTBYTES) {
                Log.e(TAG, "salt must be ${PwHash.SALTBYTES} bytes")
                return null
            }
            val out = ByteArray(params.keyLen)
            val pwBytes = password.toByteArray(Charsets.UTF_8)
            val memLimit = NativeLong(params.memoryKiB.toLong() * 1024L)
            val ok = sodium.cryptoPwHash(
                out,
                out.size,
                pwBytes,
                pwBytes.size,
                salt,
                params.time.toLong(),
                memLimit,
                PwHash.Alg.PWHASH_ALG_ARGON2ID13
            )
            if (!ok) {
                Log.e(TAG, "cryptoPwHash failed")
                return null
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "derivePasswordKek error", e)
            null
        }
    }

    fun deriveRecoveryKek(recoveryKey: ByteArray, salt: ByteArray): ByteArray? {
        if (recoveryKey.size < 32 || salt.size < 16) return null
        return try {
            HkdfSha256.derive(
                recoveryKey,
                salt,
                "messenger-e2ee-recovery-kek-v1".toByteArray(Charsets.UTF_8),
                32
            )
        } catch (e: Exception) {
            Log.e(TAG, "deriveRecoveryKek error", e)
            null
        }
    }

    /** RFC 5869 HKDF-SHA256 (matches Go golang.org/x/crypto/hkdf). */
    private object HkdfSha256 {
        fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val prk = hmacSha256(salt, ikm)
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                val input = t + info + byteArrayOf(counter.toByte())
                t = hmacSha256(prk, input)
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                counter++
            }
            return out
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }

    fun formatRecoveryKeyDisplay(recoveryKey: ByteArray): String =
        b64(recoveryKey)

    fun masterKeyAad(userId: String, purpose: String): ByteArray =
        "mk|$userId|$purpose|$SUITE_VAULT_AEAD".toByteArray(Charsets.UTF_8)

    fun vaultAad(userId: String, vaultVersion: Int): ByteArray =
        "vault|$userId|$vaultVersion|$SUITE_VAULT_AEAD".toByteArray(Charsets.UTF_8)

    fun sealXChaCha(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            if (key.size != AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) return null
            val nonce = sodium.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
            val cipher = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                cipher,
                null,
                plaintext,
                plaintext.size.toLong(),
                aad,
                aad.size.toLong(),
                null,
                nonce,
                key
            )
            if (!ok) return null
            nonce + cipher
        } catch (e: Exception) {
            Log.e(TAG, "sealXChaCha error", e)
            null
        }
    }

    fun openXChaCha(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            if (key.size != AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) return null
            val npub = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
            val abytes = AEAD.XCHACHA20POLY1305_IETF_ABYTES
            if (sealed.size < npub + abytes) return null
            val nonce = sealed.copyOfRange(0, npub)
            val cipher = sealed.copyOfRange(npub, sealed.size)
            val plain = ByteArray(cipher.size - abytes)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                plain,
                null,
                null,
                cipher,
                cipher.size.toLong(),
                aad,
                aad.size.toLong(),
                nonce,
                key
            )
            if (!ok) return null
            plain
        } catch (e: Exception) {
            Log.e(TAG, "openXChaCha error", e)
            null
        }
    }

    fun encodeVault(v: VaultPlaintext): ByteArray =
        json.encodeToString(v).toByteArray(Charsets.UTF_8)

    fun decodeVault(bytes: ByteArray): VaultPlaintext? = try {
        json.decodeFromString(String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        Log.e(TAG, "decodeVault error", e)
        null
    }

    /** Union maps; identity from [local]; ratchets by Double Ratchet seq. */
    fun mergePlaintext(local: VaultPlaintext, remote: VaultPlaintext): VaultPlaintext {
        val pub = local.identityPubHex.ifBlank { remote.identityPubHex }
        val priv = local.identityPrivHex.ifBlank { remote.identityPrivHex }
        return local.copy(
            identityPubHex = pub,
            identityPrivHex = priv,
            ownSenderKeys = remote.ownSenderKeys + local.ownSenderKeys,
            peerPubs = remote.peerPubs + local.peerPubs,
            peerSenderKeys = remote.peerSenderKeys + local.peerSenderKeys,
            directRatchets = DoubleRatchet.mergeSessionMaps(local.directRatchets, remote.directRatchets)
        )
    }

    fun createVault(
        userId: String,
        password: String,
        pubHex: String,
        privHex: String,
        ownSenderKeys: Map<String, String> = emptyMap(),
        peerPubs: Map<String, String> = emptyMap(),
        peerSenderKeys: Map<String, String> = emptyMap(),
        vaultVersion: Int = 1,
        params: ArgonParams = ArgonParams(),
        directRatchets: Map<String, String> = emptyMap(),
    ): BuiltVault? {
        val salt = randomBytes(PwHash.SALTBYTES)
        val kek = derivePasswordKek(password, salt, params) ?: return null
        val mk = randomBytes(32)
        val wrappedMk = sealXChaCha(kek, mk, masterKeyAad(userId, "password")) ?: return null

        val recoveryKey = randomBytes(32)
        val rkSalt = randomBytes(PwHash.SALTBYTES)
        val rkKek = deriveRecoveryKek(recoveryKey, rkSalt) ?: return null
        val wrappedRk = sealXChaCha(rkKek, mk, masterKeyAad(userId, "recovery")) ?: return null

        val plain = encodeVault(
            VaultPlaintext(
                identityPubHex = pubHex,
                identityPrivHex = privHex,
                ownSenderKeys = ownSenderKeys,
                peerPubs = peerPubs,
                peerSenderKeys = peerSenderKeys,
                directRatchets = directRatchets
            )
        )
        val sealed = sealXChaCha(mk, plain, vaultAad(userId, vaultVersion)) ?: return null
        return BuiltVault(
            vaultVersion = vaultVersion,
            vaultCiphertext = sealed,
            pwSalt = salt,
            pwParamsJson = params.toParamsJson(),
            pwWrappedMaster = wrappedMk,
            rkSalt = rkSalt,
            rkWrappedMaster = wrappedRk,
            recoveryKeyDisplay = formatRecoveryKeyDisplay(recoveryKey),
            mk = mk
        )
    }

    fun unlockVault(
        userId: String,
        password: String,
        vaultVersion: Int,
        vaultCiphertext: ByteArray,
        pwSalt: ByteArray,
        pwParamsJson: String?,
        pwWrappedMaster: ByteArray
    ): UnlockedVault? {
        val params = ArgonParams.fromParamsJson(pwParamsJson)
        val kek = derivePasswordKek(password, pwSalt, params) ?: return null
        val mk = openXChaCha(kek, pwWrappedMaster, masterKeyAad(userId, "password")) ?: return null
        return openVaultWithMk(userId, vaultVersion, vaultCiphertext, mk)
    }

    /**
     * Unlock using the one-time recovery secret (base64). Independent of the
     * account password wrap — used after password reset when KEK_pw is stale.
     */
    fun unlockVaultWithRecovery(
        userId: String,
        recoveryKeyB64: String,
        vaultVersion: Int,
        vaultCiphertext: ByteArray,
        rkSalt: ByteArray,
        rkWrappedMaster: ByteArray
    ): UnlockedVault? {
        val recoveryKey = unb64(recoveryKeyB64.trim()) ?: return null
        if (recoveryKey.size < 32) return null
        val kek = deriveRecoveryKek(recoveryKey, rkSalt) ?: return null
        val mk = openXChaCha(kek, rkWrappedMaster, masterKeyAad(userId, "recovery")) ?: return null
        return openVaultWithMk(userId, vaultVersion, vaultCiphertext, mk)
    }

    /** Open vault ciphertext with an already-known MK (device pairing / session). */
    fun openVaultWithMk(
        userId: String,
        vaultVersion: Int,
        vaultCiphertext: ByteArray,
        mk: ByteArray
    ): UnlockedVault? {
        val plainBytes = openXChaCha(mk, vaultCiphertext, vaultAad(userId, vaultVersion)) ?: return null
        val plain = decodeVault(plainBytes) ?: return null
        return UnlockedVault(plain, mk)
    }

    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateX25519KeyPair(): X25519KeyPair? {
        return try {
            val pk = ByteArray(32)
            val sk = ByteArray(32)
            if (!sodium.cryptoBoxKeypair(pk, sk)) return null
            X25519KeyPair(sk, pk)
        } catch (e: Exception) {
            Log.e(TAG, "generateX25519KeyPair error", e)
            null
        }
    }

    /** Old device: seal MK to the new device's ephemeral public key (crypto_box). */
    fun sealPairingMk(recipientPub: ByteArray, mk: ByteArray): Pair<ByteArray, ByteArray>? {
        if (recipientPub.size != 32 || mk.size != 32) return null
        return try {
            val senderPk = ByteArray(32)
            val senderSk = ByteArray(32)
            if (!sodium.cryptoBoxKeypair(senderPk, senderSk)) return null
            val nonce = sodium.randomBytesBuf(24)
            val cipher = ByteArray(mk.size + 16)
            if (!sodium.cryptoBoxEasy(cipher, mk, mk.size.toLong(), nonce, recipientPub, senderSk)) {
                return null
            }
            senderPk to (nonce + cipher)
        } catch (e: Exception) {
            Log.e(TAG, "sealPairingMk error", e)
            null
        }
    }

    /** New device: open sealed MK with our ephemeral private key. */
    fun openPairingMk(ourPriv: ByteArray, senderPub: ByteArray, sealed: ByteArray): ByteArray? {
        if (ourPriv.size != 32 || senderPub.size != 32 || sealed.size < 24 + 16) return null
        return try {
            val nonce = sealed.copyOfRange(0, 24)
            val cipher = sealed.copyOfRange(24, sealed.size)
            val plain = ByteArray(cipher.size - 16)
            if (!sodium.cryptoBoxOpenEasy(
                    plain, cipher, cipher.size.toLong(), nonce, senderPub, ourPriv
                )
            ) {
                return null
            }
            plain
        } catch (e: Exception) {
            Log.e(TAG, "openPairingMk error", e)
            null
        }
    }

    fun formatPairingString(sessionId: String, ephemeralPub: ByteArray): String =
        "mp1.$sessionId.${ephemeralPub.joinToString("") { "%02x".format(it) }}"

    fun parsePairingString(raw: String): Pair<String, ByteArray>? {
        val s = raw.trim()
        if (s.length < 8 || s.length > 256) return null
        val parts = s.split(".")
        if (parts.size != 3 || parts[0] != "mp1") return null
        val sessionId = parts[1]
        if (sessionId.isBlank() || sessionId.length > 80) return null
        if (parts[2].length != 64) return null
        val pub = try {
            parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return null
        }
        if (pub.size != 32) return null
        return sessionId to pub
    }

    /** Re-seal vault plaintext under an already-unlocked MK (version must increase). */
    fun resealVault(
        userId: String,
        mk: ByteArray,
        plaintext: VaultPlaintext,
        vaultVersion: Int
    ): ByteArray? {
        val plain = encodeVault(plaintext)
        return sealXChaCha(mk, plain, vaultAad(userId, vaultVersion))
    }

    /** Wrap MK under a new account password (after recovery unlock / password change). */
    fun wrapMasterWithPassword(
        userId: String,
        password: String,
        mk: ByteArray,
        params: ArgonParams = ArgonParams()
    ): Triple<ByteArray, ByteArray, String>? {
        val salt = randomBytes(PwHash.SALTBYTES)
        val kek = derivePasswordKek(password, salt, params) ?: return null
        val wrapped = sealXChaCha(kek, mk, masterKeyAad(userId, "password")) ?: return null
        return Triple(salt, wrapped, params.toParamsJson())
    }

    fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun unb64(s: String): ByteArray? = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}
