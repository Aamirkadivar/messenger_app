package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.model.*
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.security.KeyStoreManager
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Repository for authentication operations.
 * Handles login, registration, token refresh, and user management.
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

    /**
     * Register a new user
     */
    suspend fun register(
        username: String,
        email: String,
        password: String
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val request = RegisterRequest(
                username = username,
                email = email,
                password = password
            )

            val response = authApiService.register(request)

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                saveAuthData(authResponse)
                Log.d(TAG, "Registration successful for user: $username")
                Result.success(authResponse)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Registration failed: $errorBody")
                Result.failure(Exception("Registration failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            Result.failure(e)
        }
    }

    /**
     * Login with email/username and password
     */
    suspend fun login(
        username: String,
        password: String
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val request = LoginRequest(
                username = username,
                password = password
            )

            val response = authApiService.login(request)

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                saveAuthData(authResponse)
                
                // Generate encryption keys after successful login
                val keyGenResult = keyStoreManager.generateEncryptionKey()
                if (keyGenResult.isFailure) {
                    Log.e(TAG, "Failed to generate encryption key", keyGenResult.exceptionOrNull())
                }
                
                Log.d(TAG, "Login successful for user: $username")
                Result.success(authResponse)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Login failed: $errorBody")
                Result.failure(Exception("Login failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh access token using refresh token
     */
    suspend fun refreshToken(): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = tokenManager.getRefreshToken()
            if (refreshToken.isFailure || refreshToken.getOrNull().isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No refresh token available"))
            }

            val request = RefreshTokenRequest(refreshToken = refreshToken.getOrNull()!!)
            val response = authApiService.refreshToken(request)

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                saveAuthData(authResponse)
                Log.d(TAG, "Token refreshed successfully")
                Result.success(authResponse)
            } else {
                Log.e(TAG, "Token refresh failed")
                clearAuthData()
                Result.failure(Exception("Token refresh failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            clearAuthData()
            Result.failure(e)
        }
    }

    /**
     * Logout and clear auth data
     */
    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        return try {
            val token = tokenManager.getAccessToken()
            if (token.isSuccess && !token.getOrNull().isNullOrEmpty()) {
                try {
                    authApiService.logout("Bearer ${token.getOrNull()}")
                } catch (e: Exception) {
                    Log.w(TAG, "Logout API call failed, clearing local data anyway", e)
                }
            }
            clearAuthData()
            Result.success(Unit)
        } catch (e: Exception) {
            clearAuthData()
            Result.failure(e)
        }
    }

    /**
     * Get current user profile
     */
    suspend fun getCurrentUser(): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val token = tokenManager.getAccessToken()
            if (token.isFailure || token.getOrNull().isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token available"))
            }

            val response = authApiService.getCurrentUser("Bearer ${token.getOrNull()}")
            if (response.isSuccessful && response.body() != null) {
                // Save to local DB
                val user = response.body()!!
                userDao.upsertUser(
                    com.messenger.app.data.local.entity.UserEntity(
                        id = user.id,
                        username = user.username,
                        email = user.email,
                        avatarUrl = user.avatarUrl,
                        lastSeen = user.lastSeen
                    )
                )
                Result.success(user)
            } else {
                Result.failure(Exception("Failed to get user profile"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get current user error", e)
            Result.failure(e)
        }
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean = tokenManager.isAuthenticated()

    /**
     * Check if access token is expired
     */
    suspend fun isAccessTokenExpired(): Result<Boolean> = tokenManager.isAccessTokenExpired()

    /**
     * Get authentication token for API calls
     */
    suspend fun getAuthToken(): Result<String?> = tokenManager.getAccessToken()

    /**
     * Save authentication data (tokens)
     */
    private suspend fun saveAuthData(authResponse: AuthResponse): Result<Unit> {
        return withContext(Dispatchers.IO) {
            var success = true

            val tokenResult = tokenManager.saveAccessToken(authResponse.accessToken)
            if (tokenResult.isFailure) {
                Log.e(TAG, "Failed to save access token", tokenResult.exceptionOrNull())
                success = false
            }

            val refreshTokenResult = tokenManager.saveRefreshToken(authResponse.refreshToken)
            if (refreshTokenResult.isFailure) {
                Log.e(TAG, "Failed to save refresh token", refreshTokenResult.exceptionOrNull())
                success = false
            }

            val expiresAtResult = tokenManager.saveAccessTokenExpiresAt(
                System.currentTimeMillis() + authResponse.expiresIn * 1000
            )
            if (expiresAtResult.isFailure) {
                Log.e(TAG, "Failed to save expires at", expiresAtResult.exceptionOrNull())
                success = false
            }

            if (success) Result.success(Unit)
            else Result.failure(Exception("Failed to save some auth data"))
        }
    }

    /**
     * Clear all authentication data
     */
    private suspend fun clearAuthData(): Result<Unit> = withContext(Dispatchers.IO) {
        tokenManager.clearTokens()
    }
}