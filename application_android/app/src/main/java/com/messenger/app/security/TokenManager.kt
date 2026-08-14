package com.messenger.app.security

import android.content.Context
import android.content.SharedPreferences
import com.messenger.app.data.encryption.DoubleRatchet
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
) : TokenManager {

    companion object {
        private const val SHARED_PREFS_NAME = "messenger_secure_prefs"
        private const val KEY_ACCESS_TOKEN_ENCRYPTED = "access_token_enc"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_CURRENT_USER_ID = "current_user_id"
        private const val KEY_E2EE_DEVICE_ID = "e2ee_device_id"
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
            val existing = sharedPreferences.getString(KEY_E2EE_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return@withContext Result.success(existing)
            val id = java.util.UUID.randomUUID().toString()
            sharedPreferences.edit().putString(KEY_E2EE_DEVICE_ID, id).apply()
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
                apply()
            }
            Result.success(Unit)
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
}