package com.messenger.app.data.model

import kotlinx.serialization.Serializable

/**
 * Authentication request model for login
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * Authentication request model for registration
 */
@Serializable
data class RegisterRequest(
    val name: String,
    val username: String,
    val email: String,
    val password: String
)

/**
 * Authentication response model
 */
@Serializable
data class AuthResponse(
    val token: String,
    val user: User,
    val expiresIn: Long? = null,
    val refreshToken: String? = null
) {
    val tokenExpiry: Long = System.currentTimeMillis() + (expiresIn ?: 3600000)
}

/**
 * Refresh token request
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

/**
 * Password reset request
 */
@Serializable
data class ResetPasswordRequest(
    val email: String
)

/**
 * Password change request
 */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

/**
 * Verification request
 */
@Serializable
data class VerifyRequest(
    val email: String,
    val code: String
)