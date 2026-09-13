package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.local.dao.ScopedUserDao
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
    private val userDao: ScopedUserDao,
    /**
     * Session-scoped E2EE state to clear on logout. Lazy because the vault
     * repository sits downstream of this one in the graph; resolving it eagerly
     * would close a cycle.
     */
    private val e2eeSession: dagger.Lazy<E2EEVaultRepository>? = null,
    /**
     * Releases the outgoing account's cache handle on logout. Optional so
     * existing constructions keep compiling; when absent the holder still
     * re-resolves the account on the next access, so isolation does not depend
     * on this being wired - it just closes the file promptly.
     */
    private val cacheHolder: com.messenger.app.data.local.AccountCacheHolder? = null,
    /**
     * Drops the outgoing account's in-memory conversation and crypto state.
     * Optional so existing constructions keep compiling; ChatRepository also
     * re-checks the signed-in account on every account-sensitive access, so
     * isolation does not depend on this being wired - it just releases the
     * state promptly rather than at next touch.
     */
    private val chatState: dagger.Lazy<ChatRepository>? = null,
    // Optional so existing constructions keep compiling; when absent this
    // repository simply has no refresh path of its own.
    private val sessionRefresher: com.messenger.app.data.remote.SessionRefresher? = null
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
            // Owner is taken from the authenticated response itself, not from
            // current_user_id read afterwards: it is the immutable identity this
            // operation belongs to.
            owner = user.id,
            user = UserEntity(
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
    /**
     * Refreshes via the shared [com.messenger.app.data.remote.SessionRefresher].
     *
     * This used to build its own request and store the result itself, which meant
     * it could run concurrently with the OkHttp authenticator. Under the Gate 17
     * server two overlapping rotations send two request_ids for one credential;
     * the second is a new logical refresh, and once the first successor is spent
     * the server reads it as a fork and revokes the session. Delegating keeps one
     * single-flight lock and one request_id lifecycle for the whole app.
     */
    suspend fun refreshToken(): Result<AuthResponse> = withContext(Dispatchers.IO) {
        val refresher = sessionRefresher
            ?: return@withContext Result.failure(Exception("No session refresher configured"))
        when (val outcome = refresher.refresh()) {
            is com.messenger.app.data.remote.SessionRefresher.Outcome.Rotated -> {
                Log.d(TAG, "Token refreshed successfully")
                Result.success(AuthResponse(tokens = outcome.tokens))
            }
            is com.messenger.app.data.remote.SessionRefresher.Outcome.AlreadyFresh ->
                // Another caller rotated while this one waited. Nothing to do and
                // nothing to store; the stored credential is already current.
                Result.failure(Exception("Refresh already performed by another caller"))
            com.messenger.app.data.remote.SessionRefresher.Outcome.SessionOver -> {
                clearAuthData()
                Result.failure(Exception("Session expired"))
            }
            com.messenger.app.data.remote.SessionRefresher.Outcome.RetryLater ->
                // Transport failure: the pending record is intact so the same
                // logical refresh can be retried. Do NOT clear credentials over a
                // network blip.
                Result.failure(Exception("Refresh could not be completed; will retry"))
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
        // Drop any in-flight refresh belonging to the outgoing session. The
        // account check in SessionRefresher already refuses to replay another
        // account's record, but leaving credential material behind after sign-out
        // is its own problem.
        runCatching { tokenManager.clearPendingRefresh() }
            .onFailure { Log.w(TAG, "could not clear pending refresh on logout: ${it.message}") }
        tokenManager.clearTokens()
        // Gate 19: release the outgoing account's cache FILE, but never delete
        // it. Logging out ends access to that account's offline history; it does
        // not end the history. Signing back in reopens the same namespace.
        //
        // Ordering matters: clearTokens() removes current_user_id first, so by
        // the time anything asks the holder again there is no account and the
        // scoped DAOs read as empty rather than as the previous account.
        runCatching { cacheHolder?.deactivate() }
            .onFailure { Log.w(TAG, "could not release account cache on logout: ${it.message}") }
        // Gate 24.1: the database namespace is only half of it. ChatRepository is
        // a @Singleton holding ratchet sessions, Sender Keys, peer keys and chat
        // metadata in process memory, none of which is account-namespaced. Drop
        // it here, before any next account can touch it.
        runCatching { chatState?.get()?.clearAccountScopedState() }
            .onFailure { Log.w(TAG, "could not clear chat state on logout: ${it.message}") }
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
