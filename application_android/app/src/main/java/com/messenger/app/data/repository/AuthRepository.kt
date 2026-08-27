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
    private val userDao: UserDao,
    /**
     * Session-scoped E2EE state to clear on logout. Lazy because the vault
     * repository sits downstream of this one in the graph; resolving it eagerly
     * would close a cycle.
     */
    private val e2eeSession: dagger.Lazy<E2EEVaultRepository>? = null
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
     * When DEV 2FA is on, [AuthResponse.requires2fa] is true and tokens are absent.
     */
    suspend fun login(email: String, password: String): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val response = authApiService.login(LoginRequest(email, password))

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                if (authResponse.requires2fa) {
                    Log.d(TAG, "Login requires 2FA challenge=${authResponse.challengeId}")
                    return@withContext Result.success(authResponse)
                }
                persistSuccessfulAuth(authResponse)?.let { return@withContext it }
                Log.d(TAG, "Login successful for user: ${authResponse.user?.username}")
                Result.success(authResponse)
            } else {
                Result.failure(Exception(extractError(response.errorBody())))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.failure(e)
        }
    }

    /** Completes DEV 2FA: POST /auth/2fa/verify. */
    suspend fun verify2FA(challengeId: String, code: String): Result<AuthResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = authApiService.verify2FA(Verify2FARequest(challengeId, code.trim()))
                if (response.isSuccessful && response.body() != null) {
                    val authResponse = response.body()!!
                    persistSuccessfulAuth(authResponse)?.let { return@withContext it }
                    Result.success(authResponse)
                } else {
                    Result.failure(Exception(extractError(response.errorBody())))
                }
            } catch (e: Exception) {
                Log.e(TAG, "verify2FA error", e)
                Result.failure(e)
            }
        }

    suspend fun startPasswordReset(email: String): Result<PasswordResetStartResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = authApiService.startPasswordReset(
                    PasswordResetStartRequest(email.trim())
                )
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception(extractError(response.errorBody())))
                }
            } catch (e: Exception) {
                Log.e(TAG, "startPasswordReset error", e)
                Result.failure(e)
            }
        }

    suspend fun completePasswordReset(
        challengeId: String,
        code: String,
        newPassword: String,
        totpCode: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = authApiService.completePasswordReset(
                PasswordResetCompleteRequest(challengeId, code.trim(), newPassword, totpCode.trim())
            )
            if (response.isSuccessful) {
                Result.success(response.body()?.message ?: "Password updated. Sign in, then unlock with your recovery key.")
            } else {
                Result.failure(Exception(extractError(response.errorBody())))
            }
        } catch (e: Exception) {
            Log.e(TAG, "completePasswordReset error", e)
            Result.failure(e)
        }
    }

    /** Returns a failure Result if tokens/user are missing; otherwise saves and returns null. */
    private suspend fun persistSuccessfulAuth(authResponse: AuthResponse): Result<AuthResponse>? {
        val user = authResponse.user
        val tokens = authResponse.tokens
        if (user == null || tokens == null) {
            return Result.failure(Exception("Login succeeded but response was incomplete"))
        }
        saveAuthData(user, tokens)
        userDao.insertUser(
            UserEntity(
                id = user.id,
                name = user.displayName ?: user.username,
                username = user.username,
                email = user.email,
                avatarUrl = user.avatarUrl
            )
        )
        val keyGenResult = keyStoreManager.generateEncryptionKey(KeyStoreManager.DEVICE_ENCRYPTION_KEY_ALIAS)
        if (keyGenResult.isFailure) {
            Log.e(TAG, "Failed to generate encryption key", keyGenResult.exceptionOrNull())
        }
        return null
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
                val tokens = authResponse.tokens
                    ?: return@withContext Result.failure(Exception("Refresh response missing tokens"))
                val userId = authResponse.user?.id ?: tokenManager.getCurrentUserId().getOrNull().orEmpty()
                tokenManager.saveAccessToken(tokens.accessToken)
                tokenManager.saveRefreshToken(tokens.refreshToken)
                tokenManager.saveAccessTokenExpiresAt(
                    System.currentTimeMillis() + tokens.expiresIn * 1000
                )
                if (userId.isNotBlank()) tokenManager.saveCurrentUserId(userId)
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

    private suspend fun saveAuthData(user: UserDto, tokens: TokensDto) {
        tokenManager.saveAccessToken(tokens.accessToken)
            .onFailure { Log.e(TAG, "Failed to save access token", it) }
        tokenManager.saveRefreshToken(tokens.refreshToken)
            .onFailure { Log.e(TAG, "Failed to save refresh token", it) }
        tokenManager.saveAccessTokenExpiresAt(
            System.currentTimeMillis() + tokens.expiresIn * 1000
        ).onFailure { Log.e(TAG, "Failed to save token expiry", it) }
        tokenManager.saveCurrentUserId(user.id)
            .onFailure { Log.e(TAG, "Failed to save current user id", it) }
    }

    private suspend fun clearAuthData() {
        // Before the tokens go, drop everything session-scoped: the master key,
        // the one-shot recovery flag, the published-revision counter, and the
        // opened keyring. Durable material - the sealed keyring, its cache, the
        // vault - is deliberately untouched; logging out must not destroy history
        // keys the account still needs on the next sign-in.
        //
        // Without this, a second account signing in on the same process inherits
        // the first account's MK in memory and its "recovery already attempted"
        // flag, so it silently skips its own recovery for that whole session.
        runCatching { e2eeSession?.get()?.clearSessionSecrets() }
            .onFailure { Log.w(TAG, "could not clear E2EE session state on logout: ${it.message}") }
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
