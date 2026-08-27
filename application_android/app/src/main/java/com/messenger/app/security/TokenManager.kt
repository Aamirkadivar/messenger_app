package com.messenger.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.messenger.app.data.encryption.DoubleRatchet
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interface for JWT token management
 */
interface TokenManager {
    suspend fun saveAccessToken(token: String): Result<Unit>
    suspend fun getAccessToken(): Result<String?>
    suspend fun saveRefreshToken(token: String): Result<Unit>
    suspend fun getRefreshToken(): Result<String?>
    suspend fun saveAccessTokenExpiresAt(expiresAt: Long): Result<Unit>
    suspend fun getAccessTokenExpiresAt(): Result<Long?>
    suspend fun saveCurrentUserId(userId: String): Result<Unit>
    suspend fun getCurrentUserId(): Result<String?>
    // E2EE keypair (hex). Private key never leaves the device.
    suspend fun saveE2EEKeys(userId: String, publicHex: String, privateHex: String): Result<Unit>
    suspend fun getE2EEPrivateKey(userId: String): Result<String?>
    suspend fun getE2EEPublicKey(userId: String): Result<String?>

    /** This device's group Sender Keys for [chatId], as "version:keyHex" (latest pointer + versioned copies). */
    suspend fun saveGroupSenderKey(chatId: String, versionAndKey: String): Result<Unit>
    suspend fun getGroupSenderKey(chatId: String): Result<String?>
    /** All persisted own Sender Key versions for [chatId]: version -> hex. */
    suspend fun loadGroupSenderKeys(chatId: String): Result<Map<Int, String>>

    /** Other members' Sender Keys: persist "senderId|version" -> hex for offline decrypt. */
    suspend fun savePeerSenderKey(chatId: String, senderId: String, version: Int, keyHex: String): Result<Unit>
    suspend fun loadPeerSenderKeys(chatId: String): Result<Map<String, String>>

    /**
     * The last-seen E2EE public key for a direct chat's other participant,
     * used to detect a WhatsApp-style "security code changed" event when it
     * differs from what the server now reports.
     */
    suspend fun saveKnownPublicKey(chatId: String, publicKeyHex: String): Result<Unit>
    suspend fun getKnownPublicKey(chatId: String): Result<String?>
    suspend fun savePendingPublicKey(chatId: String, publicKeyHex: String): Result<Unit>
    suspend fun getPendingPublicKey(chatId: String): Result<String?>
    suspend fun clearPendingPublicKey(chatId: String): Result<Unit>
    suspend fun deleteDirectRatchet(chatId: String): Result<Unit>
    suspend fun markSafetyVerified(chatId: String, pubHex: String): Result<Unit>
    suspend fun clearSafetyVerified(chatId: String): Result<Unit>
    suspend fun isSafetyVerified(chatId: String): Result<Boolean>

    /** Stable per-install device id for E2EE device registry. */
    suspend fun getOrCreateDeviceId(): Result<String>

    /** Export group sender keys for vault: "chatId|version" -> key hex. */
    suspend fun exportVaultSenderKeys(): Result<Map<String, String>>

    /** Export cached direct-chat peer pubs for vault: chatId -> pub hex. */
    suspend fun exportVaultPeerPubs(): Result<Map<String, String>>

    suspend fun restoreVaultSenderKeys(keys: Map<String, String>): Result<Unit>
    suspend fun restoreVaultPeerPubs(pubs: Map<String, String>): Result<Unit>
    suspend fun exportVaultPeerSenderKeys(): Result<Map<String, String>>
    suspend fun restoreVaultPeerSenderKeys(keys: Map<String, String>): Result<Unit>

    /**
     * MLS restore bundles. Neither BouncyCastle nor mlspp can serialize a live
     * MLS group, so a device rebuilds groups after restart from this material
     * (identity keys, published KeyPackage + init key, Welcome) plus the
     * commits the Delivery Service retains. Keystore-wrapped like the ratchets.
     */
    suspend fun saveMlsBundle(key: String, json: String): Result<Unit>

    /**
     * Reads a bundle. Fails - rather than reporting absence - when a stored
     * bundle cannot be unwrapped, so callers can tell "no state yet" apart from
     * "state exists but is unreadable". Conflating the two destroys MLS groups.
     */
    suspend fun loadMlsBundle(key: String): Result<String?>

    /** Whether a bundle is stored, without needing to unwrap it. */
    suspend fun hasMlsBundle(key: String): Result<Boolean>
    suspend fun listMlsBundles(prefix: String): Result<Map<String, String>>
    suspend fun deleteMlsBundle(key: String): Result<Unit>

    suspend fun saveDirectRatchet(chatId: String, json: String): Result<Unit>
    suspend fun loadDirectRatchet(chatId: String): Result<String?>
    suspend fun exportVaultDirectRatchets(): Result<Map<String, String>>
    suspend fun restoreVaultDirectRatchets(sessions: Map<String, String>): Result<Unit>

    suspend fun clearTokens(): Result<Unit>
    suspend fun isAccessTokenExpired(): Result<Boolean>
    fun isAuthenticated(): Boolean
}

/**
 * Manages JWT token storage using Android Keystore and SharedPreferences.
 * Access tokens, refresh tokens, E2EE private keys, and group Sender Keys
 * are wrapped with Android Keystore AES-GCM (`ks1:` prefix).
 */
class TokenManagerImpl(
    private val context: Context,
    private val keyStoreManager: KeyStoreManager
) : TokenManager, HistoryKeyringStore {

    companion object {
        private const val SHARED_PREFS_NAME = "messenger_secure_prefs"
        /** MK-sealed Layer B history keyring. Deliberately not an `mls1_` bundle. */
        private const val KEY_HISTORY_KEYRING = "history_keyring_v1"
        /** Keystore-only cache of the same keyring, for cold-start reads without a password. */
        private const val KEY_HISTORY_KEYRING_CACHE = "history_keyring_cache_v1"
        private const val KEY_HISTORY_KEYRING_GEN = "history_keyring_gen_v1"
        private const val KEY_ACCESS_TOKEN_ENCRYPTED = "access_token_enc"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_CURRENT_USER_ID = "current_user_id"
        private const val KEY_E2EE_DEVICE_ID = "e2ee_device_id"
        private const val KEY_E2EE_DEVICE_OWNER = "e2ee_device_owner"
        private const val KEY_ALIAS_TOKEN = "messenger_token_key"
        private const val KS_WRAP_PREFIX = "ks1:"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun saveAccessToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Check if encryption key exists, if not generate it
            val hasKey = keyStoreManager.containsKey(KEY_ALIAS_TOKEN)
            if (hasKey.isSuccess && hasKey.getOrNull() != true) {
                val genResult = keyStoreManager.generateEncryptionKey(KEY_ALIAS_TOKEN)
                if (genResult.isFailure) return@withContext genResult
            }

            val encryptedResult = keyStoreManager.encryptData(token, KEY_ALIAS_TOKEN)
            if (encryptedResult.isSuccess) {
                with(sharedPreferences.edit()) {
                    putString(KEY_ACCESS_TOKEN_ENCRYPTED, encryptedResult.getOrNull())
                    apply()
                }
                Result.success(Unit)
            } else {
                Result.failure(encryptedResult.exceptionOrNull() ?: Exception("Failed to encrypt access token"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAccessToken(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val encryptedToken = sharedPreferences.getString(KEY_ACCESS_TOKEN_ENCRYPTED, null)
            if (encryptedToken.isNullOrEmpty()) return@withContext Result.success(null)

            val decrypted = keyStoreManager.decryptData(encryptedToken, KEY_ALIAS_TOKEN)
            if (decrypted.isSuccess) {
                Result.success(decrypted.getOrNull())
            } else {
                // If decryption fails, key may have been rotated, try to get from secure storage
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveRefreshToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wrapped = wrapSecret(token)
            if (wrapped.isFailure) return@withContext Result.failure(
                wrapped.exceptionOrNull() ?: Exception("Failed to wrap refresh token")
            )
            with(sharedPreferences.edit()) {
                putString(KEY_REFRESH_TOKEN, wrapped.getOrNull())
                apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getRefreshToken(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val stored = sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
            if (stored.isNullOrEmpty()) return@withContext Result.success(null)
            val plain = unwrapSecret(stored)
            if (plain != null && !stored.startsWith(KS_WRAP_PREFIX)) {
                wrapSecret(plain).getOrNull()?.let { wrapped ->
                    sharedPreferences.edit().putString(KEY_REFRESH_TOKEN, wrapped).apply()
                }
            }
            Result.success(plain)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveAccessTokenExpiresAt(expiresAt: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            with(sharedPreferences.edit()) {
                putLong(KEY_EXPIRES_AT, expiresAt)
                apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAccessTokenExpiresAt(): Result<Long?> = withContext(Dispatchers.IO) {
        try {
            val expiresAt = sharedPreferences.getLong(KEY_EXPIRES_AT, 0)
            Result.success(if (expiresAt > 0) expiresAt else null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveCurrentUserId(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            with(sharedPreferences.edit()) {
                putString(KEY_CURRENT_USER_ID, userId)
                apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUserId(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sharedPreferences.getString(KEY_CURRENT_USER_ID, null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveE2EEKeys(userId: String, publicHex: String, privateHex: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val wrapped = wrapSecret(privateHex)
                if (wrapped.isFailure) return@withContext Result.failure(
                    wrapped.exceptionOrNull() ?: Exception("Failed to wrap E2EE private key")
                )
                with(sharedPreferences.edit()) {
                    putString("e2ee_pub_$userId", publicHex)
                    putString("e2ee_priv_$userId", wrapped.getOrNull())
                    apply()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getE2EEPrivateKey(userId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val stored = sharedPreferences.getString("e2ee_priv_$userId", null)
            if (stored.isNullOrEmpty()) return@withContext Result.success(null)
            val plain = unwrapSecret(stored)
            if (plain != null && !stored.startsWith(KS_WRAP_PREFIX)) {
                wrapSecret(plain).getOrNull()?.let { wrapped ->
                    sharedPreferences.edit().putString("e2ee_priv_$userId", wrapped).apply()
                }
            }
            Result.success(plain)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getE2EEPublicKey(userId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sharedPreferences.getString("e2ee_pub_$userId", null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveGroupSenderKey(chatId: String, versionAndKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val sep = versionAndKey.indexOf(':')
                val edit = sharedPreferences.edit()
                wrapSecret(versionAndKey).getOrNull()?.let { edit.putString("group_senderkey_$chatId", it) }
                    ?: edit.putString("group_senderkey_$chatId", versionAndKey)
                if (sep > 0) {
                    val version = versionAndKey.substring(0, sep).toIntOrNull()
                    val hex = versionAndKey.substring(sep + 1)
                    if (version != null && hex.isNotBlank()) {
                        val stored = wrapSecret(hex).getOrNull() ?: hex
                        edit.putString("group_senderkey_$chatId|$version", stored)
                    }
                }
                edit.apply()
                pruneOldSenderKeysLocked(chatId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getGroupSenderKey(chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val all = loadGroupSenderKeysInternal(chatId)
            val latest = all.maxByOrNull { it.key }
            Result.success(latest?.let { "${it.key}:${it.value}" })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadGroupSenderKeys(chatId: String): Result<Map<Int, String>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(loadGroupSenderKeysInternal(chatId))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun savePeerSenderKey(
        chatId: String,
        senderId: String,
        version: Int,
        keyHex: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (chatId.isBlank() || senderId.isBlank() || keyHex.isBlank()) {
                return@withContext Result.success(Unit)
            }
            val stored = wrapSecret(keyHex).getOrNull() ?: keyHex
            sharedPreferences.edit()
                .putString("peer_senderkey_$chatId|$senderId|$version", stored)
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadPeerSenderKeys(chatId: String): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val out = mutableMapOf<String, String>()
                val prefix = "peer_senderkey_$chatId|"
                for ((key, value) in sharedPreferences.all) {
                    if (key !is String || value !is String || !key.startsWith(prefix)) continue
                    val rest = key.removePrefix(prefix) // senderId|version
                    val hex = unwrapSecret(value) ?: continue
                    if (hex.isNotBlank()) out[rest] = hex
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun loadGroupSenderKeysInternal(chatId: String): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        val prefix = "group_senderkey_$chatId"
        val migrations = mutableListOf<Pair<String, String>>()
        for ((key, value) in sharedPreferences.all) {
            if (key !is String || value !is String || value.isBlank()) continue
            when {
                key == prefix -> {
                    val plain = unwrapSecret(value) ?: continue
                    if (!value.startsWith(KS_WRAP_PREFIX)) {
                        wrapSecret(plain).getOrNull()?.let { migrations.add(key to it) }
                    }
                    val sep = plain.indexOf(':')
                    val version = plain.substring(0, sep).toIntOrNull()
                    val hex = if (sep > 0) plain.substring(sep + 1) else ""
                    if (version != null && hex.isNotBlank()) out.putIfAbsent(version, hex)
                }
                key.startsWith("$prefix|") -> {
                    val version = key.substring(prefix.length + 1).toIntOrNull() ?: continue
                    val hex = unwrapSecret(value) ?: continue
                    if (!value.startsWith(KS_WRAP_PREFIX)) {
                        wrapSecret(hex).getOrNull()?.let { migrations.add(key to it) }
                    }
                    out[version] = hex
                }
            }
        }
        if (migrations.isNotEmpty()) {
            val edit = sharedPreferences.edit()
            migrations.forEach { (k, v) -> edit.putString(k, v) }
            edit.apply()
        }
        return out
    }

    private fun pruneOldSenderKeysLocked(chatId: String) {
        val prefix = "group_senderkey_$chatId|"
        val versions = mutableListOf<Int>()
        for ((key, _) in sharedPreferences.all) {
            if (key !is String || !key.startsWith(prefix)) continue
            key.substring(prefix.length).toIntOrNull()?.let { versions.add(it) }
        }
        if (versions.size <= 64) return
        val drop = versions.sorted().dropLast(64)
        val edit = sharedPreferences.edit()
        drop.forEach { v -> edit.remove("group_senderkey_$chatId|$v") }
        edit.apply()
    }

    override suspend fun saveKnownPublicKey(chatId: String, publicKeyHex: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                with(sharedPreferences.edit()) {
                    putString("known_pubkey_$chatId", publicKeyHex)
                    apply()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getKnownPublicKey(chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sharedPreferences.getString("known_pubkey_$chatId", null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun savePendingPublicKey(chatId: String, publicKeyHex: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                sharedPreferences.edit().putString("pending_pubkey_$chatId", publicKeyHex).apply()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getPendingPublicKey(chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sharedPreferences.getString("pending_pubkey_$chatId", null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearPendingPublicKey(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.edit().remove("pending_pubkey_$chatId").apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteDirectRatchet(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.edit().remove("dr3_$chatId").apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markSafetyVerified(chatId: String, pubHex: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                sharedPreferences.edit().putString("verified_pubkey_$chatId", pubHex).apply()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun clearSafetyVerified(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.edit().remove("verified_pubkey_$chatId").apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isSafetyVerified(chatId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val known = sharedPreferences.getString("known_pubkey_$chatId", null)
            val verified = sharedPreferences.getString("verified_pubkey_$chatId", null)
            Result.success(!known.isNullOrBlank() && known.equals(verified, ignoreCase = true))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateDeviceId(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Scoped PER ACCOUNT. A single install-wide id meant a second
            // account signing in here inherited the first account's device
            // identity: both users registered under one device_id, so
            // per-device fan-out (FN1) and the ratchet sessions keyed by
            // "chatId|deviceId" collided and messages stopped opening.
            //
            // The pre-scoping id is deliberately NOT adopted. There is no local
            // way to tell which account originally registered it, and guessing
            // reproduced the very collision this fixes. Each account mints its
            // own; the old device row lingers server-side until revoked, and v4
            // re-keys cleanly against a new device id because its root comes
            // from the identity keys rather than the session.
            val userId = sharedPreferences.getString(KEY_CURRENT_USER_ID, null).orEmpty()
            if (userId.isBlank()) {
                // No account context yet. Do not mint: an id stored under no
                // user would never be looked up again.
                return@withContext Result.success("")
            }

            // Key version 2: v1 scoped values may have been written by an
            // earlier build whose migration adopted the install-wide id and so
            // handed one account another account's device identity.
            val scopedKey = KEY_E2EE_DEVICE_ID + "_v2_" + userId
            sharedPreferences.getString(scopedKey, null)?.takeIf { it.isNotBlank() }
                ?.let { return@withContext Result.success(it) }

            val id = java.util.UUID.randomUUID().toString()
            sharedPreferences.edit().putString(scopedKey, id).apply()
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportVaultSenderKeys(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val out = mutableMapOf<String, String>()
            for ((key, value) in sharedPreferences.all) {
                if (key !is String || value !is String) continue
                if (!key.startsWith("group_senderkey_")) continue
                val rest = key.removePrefix("group_senderkey_")
                val plain = unwrapSecret(value) ?: continue
                if (rest.contains('|')) {
                    val sep = rest.lastIndexOf('|')
                    val chatId = rest.substring(0, sep)
                    val version = rest.substring(sep + 1)
                    if (plain.isNotBlank()) out["$chatId|$version"] = plain
                    continue
                }
                val sep = plain.indexOf(':')
                if (sep <= 0) continue
                val version = plain.substring(0, sep)
                val keyHex = plain.substring(sep + 1)
                val composite = "$rest|$version"
                if (keyHex.isNotBlank()) out.putIfAbsent(composite, keyHex)
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportVaultPeerPubs(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val out = mutableMapOf<String, String>()
            for ((key, value) in sharedPreferences.all) {
                if (key !is String || value !is String) continue
                if (!key.startsWith("known_pubkey_")) continue
                val chatId = key.removePrefix("known_pubkey_")
                if (value.isNotBlank()) out[chatId] = value
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreVaultSenderKeys(keys: Map<String, String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val edit = sharedPreferences.edit()
                keys.forEach { (composite, keyHex) ->
                    val sep = composite.lastIndexOf('|')
                    if (sep <= 0 || keyHex.isBlank()) return@forEach
                    val chatId = composite.substring(0, sep)
                    val version = composite.substring(sep + 1)
                    val wrappedHex = wrapSecret(keyHex).getOrNull() ?: keyHex
                    edit.putString("group_senderkey_$chatId|$version", wrappedHex)
                    val verInt = version.toIntOrNull()
                    if (verInt != null) {
                        val existingStored = sharedPreferences.getString("group_senderkey_$chatId", null)
                        val existingPlain = existingStored?.let { unwrapSecret(it) }
                        val existingVer = existingPlain?.substringBefore(':')?.toIntOrNull() ?: -1
                        if (verInt >= existingVer) {
                            val latest = wrapSecret("$version:$keyHex").getOrNull() ?: "$version:$keyHex"
                            edit.putString("group_senderkey_$chatId", latest)
                        }
                    }
                }
                edit.apply()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun restoreVaultPeerPubs(pubs: Map<String, String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val edit = sharedPreferences.edit()
                pubs.forEach { (chatId, pub) ->
                    if (pub.isNotBlank()) edit.putString("known_pubkey_$chatId", pub)
                }
                edit.apply()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun exportVaultPeerSenderKeys(): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val out = mutableMapOf<String, String>()
                for ((key, value) in sharedPreferences.all) {
                    if (key !is String || value !is String) continue
                    if (!key.startsWith("peer_senderkey_")) continue
                    val rest = key.removePrefix("peer_senderkey_")
                    val hex = unwrapSecret(value) ?: continue
                    if (hex.isNotBlank()) out[rest] = hex
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun restoreVaultPeerSenderKeys(keys: Map<String, String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val edit = sharedPreferences.edit()
                keys.forEach { (composite, hex) ->
                    if (composite.count { it == '|' } < 2 || hex.isBlank()) return@forEach
                    val stored = wrapSecret(hex).getOrNull() ?: hex
                    edit.putString("peer_senderkey_$composite", stored)
                }
                edit.apply()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun clearTokens(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            with(sharedPreferences.edit()) {
                remove(KEY_ACCESS_TOKEN_ENCRYPTED)
                remove(KEY_REFRESH_TOKEN)
                remove(KEY_EXPIRES_AT)
                remove(KEY_CURRENT_USER_ID)
                // The MLS store belongs to the account that just signed out.
                // Leaving it behind let the NEXT account restore it and publish
                // KeyPackages carrying the previous account's credential and
                // signature key - two accounts presenting one MLS identity,
                // which MLS then refuses to admit to the same group.
                //
                // Scoped to the mls1_ prefix: message history, cached chats and
                // the per-account device id are deliberately untouched.
                for (key in sharedPreferences.all.keys) {
                    if (key.startsWith("mls1_")) remove(key)
                }
                apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Durably stores an MLS bundle. The `Result` is a promise the caller relies
     * on, so this must not return success for a write that has not landed.
     *
     * `commit()`, never `apply()`. `apply()` returns `void` and schedules the
     * write on a background thread, so `Result.success` after it is a claim this
     * function cannot support: a process that exits before the flush loses the
     * write while the caller has already been told it succeeded. That is not
     * hypothetical - it is how a device came to sit on a stale MLS epoch after
     * the Delivery Service had accepted its commit. The merge was reported
     * persisted, the process ended, and the snapshot on disk was still the
     * pre-commit one. Because an author cannot replay its own MLS commit, that
     * device could not catch up by any ordinary path.
     *
     * `commit()` blocks until the write is on disk and reports whether it
     * worked. We are already on [Dispatchers.IO], so blocking here is correct.
     */
    override suspend fun saveMlsBundle(key: String, json: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wrapped = wrapSecret(json).getOrElse { json }
            val committed = sharedPreferences.edit().putString("mls1_$key", wrapped).commit()
            if (!committed) {
                Result.failure(
                    IllegalStateException(
                        "MLS bundle '$key' was not committed to storage; " +
                            "treat the state as unpersisted rather than saved"
                    )
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads an MLS bundle, keeping "absent" and "unreadable" distinguishable.
     *
     *   no stored value            -> success(null)
     *   stored and unwrapped       -> success(plaintext)
     *   stored but not unwrappable -> failure
     *
     * The third case used to be indistinguishable from the second: this returned
     * `unwrapSecret(stored) ?: stored`, handing back the still-wrapped ciphertext
     * as though it were the plaintext. MLS restore then failed its magic check,
     * the caller concluded the device had no MLS state at all, and the next
     * persist replaced a complete whole-store snapshot with an empty one -
     * destroying every group irrecoverably.
     *
     * The stored value is left untouched on failure so recovery stays possible.
     */
    override suspend fun loadMlsBundle(key: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val stored = sharedPreferences.getString("mls1_$key", null)
                ?: return@withContext Result.success(null)
            val plain = unwrapSecret(stored)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "MLS bundle '$key' is present but could not be unwrapped; " +
                            "refusing to report it as absent"
                    )
                )
            Result.success(plain)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Whether a bundle is physically stored, regardless of whether it can be
     * unwrapped. Lets a caller tell "absent" from "unreadable" without a read
     * that might itself fail.
     */
    override suspend fun hasMlsBundle(key: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Result.success(!sharedPreferences.getString("mls1_$key", null).isNullOrBlank())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listMlsBundles(prefix: String): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val full = "mls1_$prefix"
            val out = mutableMapOf<String, String>()
            for ((key, value) in sharedPreferences.all) {
                if (key !is String || value !is String) continue
                if (!key.startsWith(full)) continue
                val id = key.removePrefix("mls1_")
                if (id.isBlank()) continue
                val plain = unwrapSecret(value) ?: continue
                if (plain.isNotBlank()) out[id] = plain
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Durably removes an MLS bundle. `commit()` for the same reason
     * [saveMlsBundle] uses it, in the other direction: an `apply()`d delete that
     * has not reached disk when the process ends leaves the bundle in place, so
     * a caller that has been told the state was discarded would find it back on
     * the next start.
     */
    override suspend fun deleteMlsBundle(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val committed = sharedPreferences.edit().remove("mls1_$key").commit()
            if (!committed) {
                Result.failure(
                    IllegalStateException("MLS bundle '$key' was not removed from storage")
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveDirectRatchet(chatId: String, json: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wrapped = wrapSecret(json).getOrElse { json }
            sharedPreferences.edit().putString("dr3_$chatId", wrapped).apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadDirectRatchet(chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val stored = sharedPreferences.getString("dr3_$chatId", null) ?: return@withContext Result.success(null)
            Result.success(unwrapSecret(stored) ?: stored)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportVaultDirectRatchets(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val out = mutableMapOf<String, String>()
            for ((key, value) in sharedPreferences.all) {
                if (key !is String || value !is String) continue
                if (!key.startsWith("dr3_")) continue
                val chatId = key.removePrefix("dr3_")
                if (chatId.isBlank()) continue
                val plain = unwrapSecret(value) ?: continue
                if (plain.isNotBlank()) out[chatId] = plain
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreVaultDirectRatchets(sessions: Map<String, String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            for ((chatId, json) in sessions) {
                if (chatId.isBlank() || json.isBlank()) continue
                val existing = loadDirectRatchet(chatId).getOrNull().orEmpty()
                saveDirectRatchet(chatId, DoubleRatchet.preferSessionJson(existing, json))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isAccessTokenExpired(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val expiresAt = sharedPreferences.getLong(KEY_EXPIRES_AT, 0)
            if (expiresAt == 0L) return@withContext Result.success(true)
            val isExpired = System.currentTimeMillis() > expiresAt
            Result.success(isExpired)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isAuthenticated(): Boolean {
        val token = sharedPreferences.getString(KEY_ACCESS_TOKEN_ENCRYPTED, null)
        return !token.isNullOrEmpty()
    }

    private suspend fun ensureWrapKey(): Result<Unit> {
        val hasKey = keyStoreManager.containsKey(KEY_ALIAS_TOKEN)
        if (hasKey.isSuccess && hasKey.getOrNull() == true) return Result.success(Unit)
        return keyStoreManager.generateEncryptionKey(KEY_ALIAS_TOKEN)
    }

    private suspend fun wrapSecret(plaintext: String): Result<String> {
        val ready = ensureWrapKey()
        if (ready.isFailure) return Result.failure(ready.exceptionOrNull() ?: Exception("Keystore unavailable"))
        val encrypted = keyStoreManager.encryptData(plaintext, KEY_ALIAS_TOKEN)
        val blob = encrypted.getOrNull() ?: return Result.failure(
            encrypted.exceptionOrNull() ?: Exception("Encrypt failed")
        )
        return Result.success(KS_WRAP_PREFIX + blob)
    }

    private suspend fun unwrapSecret(stored: String): String? {
        if (!stored.startsWith(KS_WRAP_PREFIX)) return stored
        val ready = ensureWrapKey()
        if (ready.isFailure) return null
        return keyStoreManager.decryptData(stored.removePrefix(KS_WRAP_PREFIX), KEY_ALIAS_TOKEN).getOrNull()
    }

    // ---------------------------------------------------------------- HistoryKeyringStore
    //
    // A separate, FAIL-CLOSED path. It shares the preferences file and the Keystore alias with the
    // rest of this class but deliberately not the behaviour of `saveMlsBundle`, which does
    //
    //     wrapSecret(json).getOrElse { json }
    //
    // and therefore writes plaintext when the Keystore is unavailable. That fallback exists for
    // legacy MLS migration and is intentionally left untouched. History roots must never take it:
    // a root written in the clear would defeat the whole layer, so these operations fail instead.
    //
    // Two copies are kept, both through the same fail-closed helpers below:
    //
    //   KEY_HISTORY_KEYRING       the MK-sealed keyring. Recovery copy and source of truth;
    //                             reading it usefully requires an unlocked vault.
    //   KEY_HISTORY_KEYRING_CACHE the keyring's plain encoding, protected by the Keystore alone,
    //                             so an ordinary restart can read archived history with no
    //                             password prompt.
    //
    // The cache holds root material under Keystore protection only. That is the explicit cost of
    // cold-start access; it is never written unprotected, and a device whose Keystore is
    // compromised loses those roots whichever copy is stored.

    override suspend fun saveHistoryKeyring(sealed: ByteArray): Result<Unit> =
        putSecretBytes(KEY_HISTORY_KEYRING, sealed, "history keyring")

    override suspend fun loadHistoryKeyring(): Result<ByteArray?> =
        getSecretBytes(KEY_HISTORY_KEYRING, "history keyring")

    override suspend fun deleteHistoryKeyring(): Result<Unit> =
        removeSecret(KEY_HISTORY_KEYRING, "history keyring")

    override suspend fun saveHistoryKeyringCache(plain: ByteArray): Result<Unit> =
        putSecretBytes(KEY_HISTORY_KEYRING_CACHE, plain, "history keyring cache")

    override suspend fun loadHistoryKeyringCache(): Result<ByteArray?> =
        getSecretBytes(KEY_HISTORY_KEYRING_CACHE, "history keyring cache")

    override suspend fun deleteHistoryKeyringCache(): Result<Unit> =
        removeSecret(KEY_HISTORY_KEYRING_CACHE, "history keyring cache")

    /**
     * The generation marker.
     *
     * Wrapped like the other two rather than stored as a bare integer: it is not
     * secret, but a value an attacker could edit freely would let a stale cache be
     * re-validated, which is exactly the failure the marker exists to detect.
     * Stored big-endian so the bytes order the same way the number does.
     */
    override suspend fun saveHistoryKeyringGeneration(generation: Long): Result<Unit> {
        val bytes = ByteArray(8) { i -> ((generation ushr (56 - 8 * i)) and 0xFF).toByte() }
        return putSecretBytes(KEY_HISTORY_KEYRING_GEN, bytes, "history keyring generation")
    }

    override suspend fun loadHistoryKeyringGeneration(): Result<Long?> =
        getSecretBytes(KEY_HISTORY_KEYRING_GEN, "history keyring generation").map { raw ->
            if (raw == null || raw.size != 8) return@map null
            var v = 0L
            for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
            if (v < 0) null else v
        }

    override suspend fun deleteHistoryKeyringGeneration(): Result<Unit> =
        removeSecret(KEY_HISTORY_KEYRING_GEN, "history keyring generation")

    /**
     * Keystore-wraps and commits, or fails. There is deliberately no `getOrElse { plaintext }`
     * here - that is the difference from [saveMlsBundle].
     */
    private suspend fun putSecretBytes(
        key: String,
        bytes: ByteArray,
        label: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val wrapped = wrapSecret(encoded).getOrElse {
                return@withContext Result.failure(
                    IllegalStateException(
                        "$label not saved: Keystore wrapping unavailable. " +
                            "Refusing to persist it unprotected.",
                        it
                    )
                )
            }
            val committed = sharedPreferences.edit().putString(key, wrapped).commit()
            if (!committed) {
                Result.failure(
                    IllegalStateException(
                        "$label was not committed to storage; treat it as unpersisted rather than saved"
                    )
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Absent is success(null); present-but-unreadable is a failure.
     *
     * Reporting an unreadable keyring as absent would look like a fresh device and invite the
     * caller to mint a new root, orphaning every archive sealed under the old one.
     */
    private suspend fun getSecretBytes(key: String, label: String): Result<ByteArray?> =
        withContext(Dispatchers.IO) {
            try {
                val stored = sharedPreferences.getString(key, null)
                    ?: return@withContext Result.success(null)
                if (!stored.startsWith(KS_WRAP_PREFIX)) {
                    // This path never writes unwrapped values, so an unprefixed one is corruption
                    // or tampering - not a legacy row to be trusted the way `unwrapSecret` trusts
                    // one.
                    return@withContext Result.failure(
                        IllegalStateException("$label is not Keystore-wrapped; refusing to read it")
                    )
                }
                val plain = unwrapSecret(stored)
                    ?: return@withContext Result.failure(
                        IllegalStateException(
                            "$label is present but could not be unwrapped; " +
                                "refusing to report it as absent"
                        )
                    )
                Result.success(Base64.decode(plain, Base64.NO_WRAP))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun removeSecret(key: String, label: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val committed = sharedPreferences.edit().remove(key).commit()
                if (!committed) {
                    Result.failure(IllegalStateException("$label deletion was not committed"))
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}