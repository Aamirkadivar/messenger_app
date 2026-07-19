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
        private const val KEY_ALIAS_TOKEN = "messenger_token_key"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun saveAccessToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Encrypt the token using Keystore
            val encryptedToken = keyStoreManager.encryptData(token, KEY_ALIAS_TOKEN)
            if (encryptedToken.isFailure) return@withContext encryptedToken

            // Check if encryption key exists, if not generate it
            val hasKey = keyStoreManager.containsKey(KEY_ALIAS_TOKEN)
            if (hasKey.isSuccess && !hasKey.getOrNull()) {
                // Use the encryption key alias
                val genResult = keyStoreManager.generateEncryptionKey()
                if (genResult.isFailure) return@withContext genResult
            }

            val encryptedResult = keyStoreManager.encryptData(token, KEY_ALIAS_TOKEN)
            if (encryptedResult.isSuccess) {
                with(sharedPreferences.edit()) {
                    putString(KEY_ACCESS_TOKEN_ENCRYPTED, encryptedResult.getOrNull())
                    apply()
                }
            }
            encryptedResult
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

    override suspend fun clearTokens(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            with(sharedPreferences.edit()) {
                remove(KEY_ACCESS_TOKEN_ENCRYPTED)
                remove(KEY_REFRESH_TOKEN)
                remove(KEY_EXPIRES_AT)
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