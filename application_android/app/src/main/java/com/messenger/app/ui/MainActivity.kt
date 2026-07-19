package com.messenger.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.fcm.FirebaseMessagingService
import com.messenger.app.security.TokenManager
import com.messenger.app.ui.navigation.MainNavGraph
import com.messenger.app.ui.navigation.Routes
import com.messenger.app.ui.screen.AuthViewModel
import com.messenger.app.ui.theme.MessengerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main Activity - Entry point of the Messenger app
 * Handles app initialization, theme, and navigation
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var webSocketManager: WebSocketManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MessengerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MessengerTheme.colors.background
                ) {
                    MessengerApp()
                }
            }
        }
    }

    @Composable
    private fun MessengerApp() {
        val context = LocalContext.current
        val navController = rememberNavController()
        val authViewModel: AuthViewModel = viewModel()
        val authState by authViewModel.authState.collectAsStateWithLifecycle()

        // Initialize Firebase Cloud Messaging
        LaunchedEffect(Unit) {
            FirebaseMessagingService.initialize(context)
        }

        // WebSocket connection management
        var wsConnected by remember { mutableStateOf(false) }
        
        DisposableEffect(webSocketManager) {
            val listener = object : WebSocketManager.ConnectionListener {
                override fun onConnected() {
                    wsConnected = true
                }

                override fun onDisconnected() {
                    wsConnected = false
                }

                override fun onError(throwable: Throwable) {
                    wsConnected = false
                }
            }
            
            webSocketManager.addConnectionListener(listener)
            webSocketManager.connect()
            
            onDispose {
                webSocketManager.removeConnectionListener(listener)
            }
        }

        // Navigate based on authentication state
        LaunchedEffect(authState) {
            when (authState) {
                is AuthState.Unauthenticated -> {
                    // Navigation handled by NavGraph
                }
                is AuthState.Authenticated -> {
                    val token = (authState as AuthState.Authenticated).token
                    // Auto-login with stored token
                    webSocketManager.reconnectWithToken(token.refreshToken)
                }
                is AuthState.Error -> {}
            }
        }

        MainNavGraph(
            navController = navController,
            startDestination = if (authState is AuthState.Authenticated) {
                Routes.CHAT_LIST
            } else {
                Routes.LOGIN
            }
        )
    }
}

/**
 * Auth state for navigation
 */
sealed class AuthState {
    data object Unauthenticated : AuthState()
    data class Authenticated(val token: com.messenger.app.data.model.AuthResponse) : AuthState()
    data class Error(val message: String) : AuthState()
}