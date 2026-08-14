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
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String
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

/** GET /users/me wraps the user object as {"user": {...}}. */
@Serializable
data class MeResponse(
    val user: UserDto
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

/** Response from POST /auth/login, /auth/refresh, and /auth/2fa/verify. */
@Serializable
data class AuthResponse(
    val message: String? = null,
    val user: UserDto? = null,
    val tokens: TokensDto? = null,
    @SerialName("requires_2fa") val requires2fa: Boolean = false,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("relay_hint") val relayHint: String? = null,
    @SerialName("two_factor_method") val twoFactorMethod: String? = null
)

@Serializable
data class Verify2FARequest(
    @SerialName("challenge_id") val challengeId: String,
    val code: String
)

@Serializable
data class PasswordResetStartRequest(
    val email: String
)

@Serializable
data class PasswordResetCompleteRequest(
    @SerialName("challenge_id") val challengeId: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("totp_code") val totpCode: String = ""
)

@Serializable
data class PasswordResetStartResponse(
    val message: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("relay_hint") val relayHint: String? = null,
    @SerialName("totp_required") val totpRequired: Boolean = false
)

@Serializable
data class PasswordResetCompleteResponse(
    val message: String? = null
)

@Serializable
data class ApiErrorResponse(
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class TotpStatusDto(
    @SerialName("totp_enabled") val totpEnabled: Boolean = false,
    @SerialName("backup_codes") val backupCodes: List<String> = emptyList()
)

@Serializable
data class TotpSetupDto(
    val secret: String = "",
    @SerialName("otpauth_url") val otpauthUrl: String = ""
)

@Serializable
data class TotpConfirmRequest(val code: String)

@Serializable
data class TotpDisableRequest(val password: String, val code: String)
