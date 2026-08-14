package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.repository.AuthRepository
import com.messenger.app.data.repository.E2EEVaultRepository
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for login screen
 */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val otpCode: String = "",
    val challengeId: String? = null,
    val twoFactorHint: String? = null,
    val awaiting2FA: Boolean = false,
    /** Vault password wrap failed; enter recovery key. */
    val awaitingRecoveryKey: Boolean = false,
    val recoveryKeyInput: String = "",
    /** New device waiting for an old device to approve a link code. */
    val awaitingDevicePairing: Boolean = false,
    val pairingCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    /** One-shot recovery secret after first vault creation. */
    val recoveryKeyToShow: String? = null,
    val awaitingPasswordReset: Boolean = false,
    val resetCodeSent: Boolean = false,
    val resetNewPassword: String = "",
    val resetInfo: String? = null,
    val resetTotpRequired: Boolean = false,
    val resetTotpCode: String = ""
)

/**
 * UI state for registration screen
 */
data class RegisterUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isRegistered: Boolean = false
)

/**
 * ViewModel for authentication screens (login and registration)
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val e2eeVaultRepository: E2EEVaultRepository
) : ViewModel() {

    companion object {
        private const val TAG = "AuthViewModel"
    }

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _registerState = MutableStateFlow(RegisterUiState())
    val registerState: StateFlow<RegisterUiState> = _registerState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    init {
        checkAuthStatus()
    }

    /**
     * Check if user is already authenticated
     */
    private fun checkAuthStatus() {
        _isAuthenticated.value = tokenManager.isAuthenticated()
    }

    // ==================== Login ====================

    /**
     * Update login email field
     */
    fun setLoginEmail(email: String) {
        _loginState.update { it.copy(email = email, error = null) }
    }

    /**
     * Update login password field
     */
    fun setLoginPassword(password: String) {
        _loginState.update { it.copy(password = password, error = null) }
    }

    fun setOtpCode(code: String) {
        val filtered = if (_loginState.value.awaiting2FA)
            code.filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(9)
        else
            code.filter { ch -> ch.isDigit() }.take(6)
        _loginState.update { it.copy(otpCode = filtered, error = null) }
    }

    fun cancelTwoFactor() {
        _loginState.update {
            it.copy(
                awaiting2FA = false,
                challengeId = null,
                twoFactorHint = null,
                otpCode = "",
                error = null,
                isLoading = false
            )
        }
    }

    fun beginPasswordReset() {
        _loginState.update {
            it.copy(
                awaitingPasswordReset = true,
                resetCodeSent = false,
                otpCode = "",
                resetNewPassword = "",
                challengeId = null,
                error = null,
                resetInfo = "A reset code will be sent to the DEV 2FA bot / server log. After reset, sign in and unlock history with your recovery key.",
                resetTotpRequired = false,
                resetTotpCode = ""
            )
        }
    }

    fun cancelPasswordReset() {
        _loginState.update {
            it.copy(
                awaitingPasswordReset = false,
                resetCodeSent = false,
                otpCode = "",
                resetNewPassword = "",
                challengeId = null,
                error = null,
                resetInfo = null,
                isLoading = false,
                resetTotpRequired = false,
                resetTotpCode = ""
            )
        }
    }

    fun setResetNewPassword(password: String) {
        _loginState.update { it.copy(resetNewPassword = password, error = null) }
    }

    fun setResetTotpCode(code: String) {
        val filtered = code.filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(9)
        _loginState.update { it.copy(resetTotpCode = filtered, error = null) }
    }

    fun startPasswordReset() {
        val email = _loginState.value.email
        if (email.isBlank()) {
            _loginState.update { it.copy(error = "Email is required") }
            return
        }
        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }
            authRepository.startPasswordReset(email)
                .onSuccess { resp ->
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            resetCodeSent = true,
                            challengeId = resp.challengeId,
                            twoFactorHint = resp.relayHint ?: resp.message,
                            resetInfo = resp.message,
                            otpCode = "",
                            resetTotpRequired = resp.totpRequired,
                            resetTotpCode = ""
                        )
                    }
                }
                .onFailure { e ->
                    _loginState.update {
                        it.copy(isLoading = false, error = e.message ?: "Could not start reset")
                    }
                }
        }
    }

    fun completePasswordReset() {
        val s = _loginState.value
        val challenge = s.challengeId
        if (challenge.isNullOrBlank()) {
            _loginState.update { it.copy(error = "Request a reset code first") }
            return
        }
        if (s.otpCode.length < 6) {
            _loginState.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        if (s.resetNewPassword.length < 8) {
            _loginState.update { it.copy(error = "New password must be at least 8 characters") }
            return
        }
        if (s.resetTotpRequired && s.resetTotpCode.length < 6) {
            _loginState.update { it.copy(error = "Enter an authenticator or backup code") }
            return
        }
        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }
            authRepository.completePasswordReset(challenge, s.otpCode, s.resetNewPassword, s.resetTotpCode)
                .onSuccess { msg ->
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            awaitingPasswordReset = false,
                            resetCodeSent = false,
                            otpCode = "",
                            resetNewPassword = "",
                            challengeId = null,
                            password = "",
                            resetInfo = null,
                            resetTotpRequired = false,
                            resetTotpCode = "",
                            error = msg
                        )
                    }
                }
                .onFailure { e ->
                    _loginState.update {
                        it.copy(isLoading = false, error = e.message ?: "Reset failed")
                    }
                }
        }
    }

    /**
     * Handle login submission
     */
    fun login() {
        val currentState = _loginState.value
        if (currentState.awaitingPasswordReset) {
            if (currentState.resetCodeSent) completePasswordReset() else startPasswordReset()
            return
        }
        if (currentState.awaitingRecoveryKey) {
            submitRecoveryKey()
            return
        }
        if (currentState.awaiting2FA) {
            verify2FA()
            return
        }
        if (currentState.email.isBlank() || currentState.password.isBlank()) {
            _loginState.update { it.copy(error = "Email and password are required") }
            return
        }

        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }

            authRepository.login(currentState.email, currentState.password)
                .onSuccess { auth ->
                    if (auth.requires2fa) {
                        val challenge = auth.challengeId
                        if (challenge.isNullOrBlank()) {
                            _loginState.update {
                                it.copy(isLoading = false, error = "2FA required but no challenge was issued")
                            }
                            return@onSuccess
                        }
                        Log.d(TAG, "2FA challenge issued")
                        _loginState.update {
                            it.copy(
                                isLoading = false,
                                awaiting2FA = true,
                                challengeId = challenge,
                                twoFactorHint = auth.relayHint
                                    ?: if (auth.twoFactorMethod == "totp")
                                        "Enter the 6-digit authenticator code or a backup code"
                                    else
                                        "Enter the code sent to the DEV 2FA relay account",
                                otpCode = "",
                                error = null
                            )
                        }
                        return@onSuccess
                    }
                    Log.d(TAG, "Login successful")
                    syncVaultThenEnter(currentState.email, currentState.password)
                }
                .onFailure { exception ->
                    Log.e(TAG, "Login failed", exception)
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            error = exception.message ?: "Login failed"
                        )
                    }
                }
        }
    }

    fun verify2FA() {
        val currentState = _loginState.value
        val challengeId = currentState.challengeId
        if (challengeId.isNullOrBlank()) {
            _loginState.update { it.copy(error = "No 2FA challenge — sign in again") }
            return
        }
        if (currentState.otpCode.length < 6) {
            _loginState.update { it.copy(error = "Enter the authenticator or backup code") }
            return
        }

        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }
            authRepository.verify2FA(challengeId, currentState.otpCode)
                .onSuccess {
                    Log.d(TAG, "2FA verify successful")
                    syncVaultThenEnter(currentState.email, currentState.password)
                }
                .onFailure { exception ->
                    Log.e(TAG, "2FA verify failed", exception)
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            otpCode = "",
                            error = exception.message ?: "Invalid code"
                        )
                    }
                }
        }
    }

    fun dismissRecoveryKey() {
        _loginState.update { it.copy(recoveryKeyToShow = null) }
    }

    fun setRecoveryKeyInput(value: String) {
        _loginState.update { it.copy(recoveryKeyInput = value.trim(), error = null) }
    }

    fun cancelRecoveryUnlock() {
        _loginState.update {
            it.copy(
                awaitingRecoveryKey = false,
                awaitingDevicePairing = false,
                pairingCode = "",
                recoveryKeyInput = "",
                error = null,
                isLoading = false
            )
        }
    }

    fun startLinkFromOtherDevice() {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrBlank()) {
                _loginState.update { it.copy(error = "Not signed in") }
                return@launch
            }
            _loginState.update {
                it.copy(isLoading = true, error = null, awaitingRecoveryKey = false)
            }
            e2eeVaultRepository.startDevicePairing(token)
                .onSuccess { code ->
                    _loginState.update {
                        it.copy(
                            isLoading = true,
                            awaitingDevicePairing = true,
                            pairingCode = code,
                            error = null
                        )
                    }
                    when (val r = e2eeVaultRepository.awaitDevicePairing(token)) {
                        is E2EEVaultRepository.VaultSyncResult.Unlocked -> {
                            val email = _loginState.value.email
                            _loginState.update {
                                LoginUiState(email = email, isLoading = false, isLoggedIn = true)
                            }
                            _isAuthenticated.value = true
                        }
                        is E2EEVaultRepository.VaultSyncResult.Failed ->
                            _loginState.update {
                                it.copy(
                                    isLoading = false,
                                    awaitingDevicePairing = false,
                                    pairingCode = "",
                                    awaitingRecoveryKey = true,
                                    error = r.message
                                )
                            }
                        else ->
                            _loginState.update {
                                it.copy(
                                    isLoading = false,
                                    awaitingDevicePairing = false,
                                    pairingCode = "",
                                    awaitingRecoveryKey = true,
                                    error = "Device link failed"
                                )
                            }
                    }
                }
                .onFailure { e ->
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            awaitingRecoveryKey = true,
                            error = e.message ?: "Could not start device link"
                        )
                    }
                }
        }
    }

    fun submitRecoveryKey() {
        val state = _loginState.value
        if (state.recoveryKeyInput.isBlank()) {
            _loginState.update { it.copy(error = "Enter your recovery key") }
            return
        }
        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrBlank()) {
                _loginState.update { it.copy(isLoading = false, error = "Not signed in") }
                return@launch
            }
            when (
                val r = e2eeVaultRepository.unlockWithRecoveryKey(
                    token,
                    state.recoveryKeyInput,
                    newPassword = state.password.takeIf { it.isNotBlank() }
                )
            ) {
                is E2EEVaultRepository.VaultSyncResult.Unlocked -> {
                    _loginState.update {
                        LoginUiState(
                            email = state.email,
                            isLoading = false,
                            isLoggedIn = true
                        )
                    }
                    _isAuthenticated.value = true
                }
                is E2EEVaultRepository.VaultSyncResult.Failed ->
                    _loginState.update {
                        it.copy(isLoading = false, error = r.message)
                    }
                else ->
                    _loginState.update {
                        it.copy(isLoading = false, error = "Recovery unlock failed")
                    }
            }
        }
    }

    private suspend fun syncVaultThenEnter(email: String, password: String) {
        val token = tokenManager.getAccessToken().getOrNull()
        var recoveryKey: String? = null
        if (!token.isNullOrBlank() && password.isNotBlank()) {
            when (val r = e2eeVaultRepository.syncAfterPasswordLogin(token, password)) {
                is E2EEVaultRepository.VaultSyncResult.Failed ->
                    Log.w(TAG, "E2EE vault sync: ${r.message}")
                is E2EEVaultRepository.VaultSyncResult.NeedsRecovery -> {
                    Log.w(TAG, "E2EE vault needs recovery: ${r.message}")
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            awaiting2FA = false,
                            awaitingRecoveryKey = true,
                            error = r.message,
                            // Keep password so recovery can rewrap under it.
                            password = password
                        )
                    }
                    return
                }
                is E2EEVaultRepository.VaultSyncResult.Created -> {
                    Log.d(TAG, "E2EE vault created")
                    recoveryKey = r.recoveryKeyDisplay
                }
                else -> Log.d(TAG, "E2EE vault sync: $r")
            }
        }
        _loginState.update {
            LoginUiState(
                email = email,
                password = "",
                isLoading = false,
                isLoggedIn = true,
                recoveryKeyToShow = recoveryKey
            )
        }
        _isAuthenticated.value = true
    }

    // ==================== Registration ====================

    /**
     * Update registration fields
     */
    fun setRegisterUsername(username: String) {
        _registerState.update { it.copy(username = username, error = null) }
    }

    fun setRegisterEmail(email: String) {
        _registerState.update { it.copy(email = email, error = null) }
    }

    fun setRegisterPassword(password: String) {
        _registerState.update { it.copy(password = password, error = null) }
    }

    fun setRegisterConfirmPassword(confirmPassword: String) {
        _registerState.update { it.copy(confirmPassword = confirmPassword, error = null) }
    }

    /**
     * Handle registration submission
     */
    fun register() {
        val currentState = _registerState.value

        // Validate inputs
        when {
            currentState.username.isBlank() -> {
                _registerState.update { it.copy(error = "Username is required") }
                return
            }
            currentState.email.isBlank() -> {
                _registerState.update { it.copy(error = "Email is required") }
                return
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(currentState.email).matches() -> {
                _registerState.update { it.copy(error = "Invalid email format") }
                return
            }
            currentState.password.isBlank() -> {
                _registerState.update { it.copy(error = "Password is required") }
                return
            }
            currentState.password.length < 8 -> {
                _registerState.update { it.copy(error = "Password must be at least 8 characters") }
                return
            }
            currentState.password != currentState.confirmPassword -> {
                _registerState.update { it.copy(error = "Passwords do not match") }
                return
            }
        }

        viewModelScope.launch {
            _registerState.update { it.copy(isLoading = true, error = null) }

            authRepository.register(currentState.username, currentState.email, currentState.password)
                .onSuccess {
                    // Registration succeeded but issues no tokens (matches the real
                    // backend contract) - log in with the same credentials to get one.
                    Log.d(TAG, "Registration successful, logging in")
                    authRepository.login(currentState.email, currentState.password)
                        .onSuccess { auth ->
                            if (auth.requires2fa) {
                                val challenge = auth.challengeId
                                _registerState.update {
                                    it.copy(
                                        isLoading = false,
                                        isRegistered = true,
                                        error = "Account created — enter the 2FA code on the sign-in screen"
                                    )
                                }
                                if (!challenge.isNullOrBlank()) {
                                    _loginState.update {
                                        LoginUiState(
                                            email = currentState.email,
                                            password = currentState.password,
                                            awaiting2FA = true,
                                            challengeId = challenge,
                                            twoFactorHint = auth.relayHint
                                                ?: "Enter the code sent to the DEV 2FA relay account"
                                        )
                                    }
                                }
                                return@onSuccess
                            }
                            _registerState.update {
                                RegisterUiState(
                                    username = currentState.username,
                                    email = currentState.email,
                                    password = currentState.password,
                                    confirmPassword = currentState.confirmPassword,
                                    isLoading = false,
                                    isRegistered = true
                                )
                            }
                            _isAuthenticated.value = true
                        }
                        .onFailure { exception ->
                            _registerState.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Account created - please sign in"
                                )
                            }
                        }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Registration failed", exception)
                    _registerState.update {
                        RegisterUiState(
                            username = currentState.username,
                            email = currentState.email,
                            password = currentState.password,
                            confirmPassword = currentState.confirmPassword,
                            isLoading = false,
                            error = exception.message ?: "Registration failed"
                        )
                    }
                }
        }
    }

    // ==================== Logout ====================

    /**
     * Handle logout
     */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _isAuthenticated.value = false
            _loginState.update { LoginUiState() }
            _registerState.update { RegisterUiState() }
            Log.d(TAG, "User logged out")
        }
    }

    // ==================== Token Refresh ====================

    /**
     * Refresh authentication token
     */
    fun refreshToken() {
        viewModelScope.launch {
            authRepository.refreshToken()
                .onSuccess {
                    Log.d(TAG, "Token refreshed successfully")
                }
                .onFailure { exception ->
                    Log.e(TAG, "Token refresh failed", exception)
                    _isAuthenticated.value = false
                }
        }
    }
}