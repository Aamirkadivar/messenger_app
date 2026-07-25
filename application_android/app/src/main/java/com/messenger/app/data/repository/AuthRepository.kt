package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.local.entity.UserEntity
import com.messenger.app.data.model.*
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.security.KeyStoreManager
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

/**
 * Repository for authentication operations.
 * Handles login, registration, token refresh, and user management against the
 * real backend's /auth endpoints.
 */
class AuthRepository(
    private val authApiService: AuthApiService,
    private val tokenManager: TokenManager,
    private val keyStoreManager: KeyStoreManager,
    private val userDao: UserDao
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Register a new user. The backend does not issue tokens on register -
     * callers should follow a successful registration with [login].
     */
    suspend fun register(
        username: String,
        email: String,
        password: String
    ): Result<UserDto> = withContext(Dispatchers.IO) {
        try {
            val response = authApiService.register(RegisterRequest(username, email, password))
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Registration successful for user: $username")
                Result.success(response.body()!!.user)
            } else {
                Result.failure(Exception(extractError(response.errorBody())))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            Result.failure(e)
        }
    }

    /**
     * Login with email and password.
     */
    suspend fun login(email: String, password: String): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val response = authApiService.login(LoginRequest(email, password))

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                saveAuthData(authResponse)

                userDao.insertUser(
                    UserEntity(
                        id = authResponse.user.id,
                        name = authResponse.user.displayName ?: authResponse.user.username,
                        username = authResponse.user.username,
                        email = authResponse.user.email,
                        avatarUrl = authResponse.user.avatarUrl
                    )
                )

                // Generate a local encryption key for this device if one doesn't exist yet
                val keyGenResult = keyStoreManager.generateEncryptionKey(KeyStoreManager.DEVICE_ENCRYPTION_KEY_ALIAS)
                if (keyGenResult.isFailure) {
                    Log.e(TAG, "Failed to generate encryption key", keyGenResult.exceptionOrNull())
                }

                Log.d(TAG, "Login successful for user: ${authResponse.user.username}")
                Result.success(authResponse)
            } else {
                Result.failure(Exception(extractError(response.errorBody())))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh access token using the stored refresh token.
     */
    suspend fun refreshToken(): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val refreshTokenResult = tokenManager.getRefreshToken()
            val refreshToken = refreshTokenResult.getOrNull()
            if (refreshTokenResult.isFailure || refreshToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No refresh token available"))
            }

            val response = authApiService.refreshToken(RefreshTokenRequest(refreshToken))
            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                saveAuthData(authResponse)
                Log.d(TAG, "Token refreshed successfully")
                Result.success(authResponse)
            } else {
                clearAuthData()
                Result.failure(Exception(extractError(response.errorBody())))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            clearAuthData()
            Result.failure(e)
        }
    }

    /**
     * Logout and clear local auth data. The backend has no /auth/logout endpoint,
     * so this only clears local state.
     */
    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        clearAuthData()
        Result.success(Unit)
    }

    fun isAuthenticated(): Boolean = tokenManager.isAuthenticated()

    suspend fun getAuthToken(): String? = tokenManager.getAccessToken().getOrNull()

    private suspend fun saveAuthData(authResponse: AuthResponse) {
        tokenManager.saveAccessToken(authResponse.tokens.accessToken)
            .onFailure { Log.e(TAG, "Failed to save access token", it) }
        tokenManager.saveRefreshToken(authResponse.tokens.refreshToken)
            .onFailure { Log.e(TAG, "Failed to save refresh token", it) }
        tokenManager.saveAccessTokenExpiresAt(
            System.currentTimeMillis() + authResponse.tokens.expiresIn * 1000
        ).onFailure { Log.e(TAG, "Failed to save token expiry", it) }
        tokenManager.saveCurrentUserId(authResponse.user.id)
            .onFailure { Log.e(TAG, "Failed to save current user id", it) }
    }

    private suspend fun clearAuthData() {
        tokenManager.clearTokens()
    }

    private fun extractError(errorBody: ResponseBody?): String {
        val raw = errorBody?.string()
        if (raw.isNullOrBlank()) return "Something went wrong"
        return try {
            val parsed = json.decodeFromString<ApiErrorResponse>(raw)
            parsed.error ?: parsed.message ?: raw
        } catch (e: Exception) {
            raw
        }
    }
}
