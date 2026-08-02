package com.messenger.app.security

import android.content.Context
import android.content.SharedPreferences
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

    /** This device's current group Sender Key for [chatId], as "version:keyHex". */
    suspend fun saveGroupSenderKey(chatId: String, versionAndKey: String): Result<Unit>
    suspend fun getGroupSenderKey(chatId: String): Result<String?>

    /**
     * The last-seen E2EE public key for a direct chat's other participant,
     * used to detect a WhatsApp-style "security code changed" event when it
     * differs from what the server now reports.
     */
    suspend fun saveKnownPublicKey(chatId: String, publicKeyHex: String): Result<Unit>
    suspend fun getKnownPublicKey(chatId: String): Result<String?>

    suspend fun clearTokens(): Result<Unit>
    suspend fun isAccessTokenExpired(): Result<Boolean>
    fun isAuthenticated(): Boolean
}

/**
 * Manages JWT token storage using Android Keystore and SharedPreferences.
 * Access tokens are encrypted using Keystore, refresh tokens stored in secure SharedPreferences.
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
        private const val KEY_ALIAS_TOKEN = "messenger_token_key"
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
            with(sharedPreferences.edit()) {
                putString(KEY_REFRESH_TOKEN, token)
                apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getRefreshToken(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
            Result.success(token)
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
                with(sharedPreferences.edit()) {
                    putString("e2ee_pub_$userId", publicHex)
                    putString("e2ee_priv_$userId", privateHex)
                    apply()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getE2EEPrivateKey(userId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sharedPreferences.getString("e2ee_priv_$userId", null))
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
                with(sharedPreferences.edit()) {
                    putString("group_senderkey_$chatId", versionAndKey)
                    apply()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getGroupSenderKey(chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sharedPreferences.getString("group_senderkey_$chatId", null))
        } catch (e: Exception) {
            Result.failure(e)
        }
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
}