package com.messenger.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * These models mirror the real backend contract exactly (back-end/handlers/auth.go).
 * Register does NOT return tokens - only /auth/login and /auth/refresh do.
 */

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String
)

@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class TokensDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val type: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 86400
)

/** Response from POST /auth/register - no tokens included. */
@Serializable
data class RegisterResponse(
    val message: String? = null,
    val user: UserDto
)

/** Response from POST /auth/login and POST /auth/refresh. */
@Serializable
data class AuthResponse(
    val message: String? = null,
    val user: UserDto,
    val tokens: TokensDto
)

@Serializable
data class ApiErrorResponse(
    val error: String? = null,
    val message: String? = null
)
