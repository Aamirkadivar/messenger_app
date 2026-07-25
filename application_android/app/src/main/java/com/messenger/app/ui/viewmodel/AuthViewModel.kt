package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.repository.AuthRepository
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false
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
    private val tokenManager: TokenManager
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

    /**
     * Handle login submission
     */
    fun login() {
        val currentState = _loginState.value
        if (currentState.email.isBlank() || currentState.password.isBlank()) {
            _loginState.update { it.copy(error = "Email and password are required") }
            return
        }

        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }

            authRepository.login(currentState.email, currentState.password)
                .onSuccess {
                    Log.d(TAG, "Login successful")
                    _loginState.update {
                        LoginUiState(
                            email = currentState.email,
                            password = currentState.password,
                            isLoading = false,
                            isLoggedIn = true
                        )
                    }
                    _isAuthenticated.value = true
                }
                .onFailure { exception ->
                    Log.e(TAG, "Login failed", exception)
                    _loginState.update {
                        LoginUiState(
                            email = currentState.email,
                            password = currentState.password,
                            isLoading = false,
                            error = exception.message ?: "Login failed"
                        )
                    }
                }
        }
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
                        .onSuccess {
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