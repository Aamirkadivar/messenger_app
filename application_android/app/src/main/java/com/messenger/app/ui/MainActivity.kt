package com.messenger.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.messenger.app.data.call.CallStatus
import com.messenger.app.service.RealtimeService
import com.messenger.app.ui.navigation.MainNavGraph
import com.messenger.app.ui.navigation.Routes
import com.messenger.app.ui.screen.CallOverlay
import com.messenger.app.ui.theme.MessengerTheme
import com.messenger.app.ui.viewmodel.AuthViewModel
import com.messenger.app.ui.viewmodel.CallViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main Activity - Entry point of the Messenger app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Sent by the incoming-call notification's Answer action (and its
         * full-screen intent). Answering routes through the Activity rather
         * than a receiver because accepting may need to prompt for the
         * microphone permission, which only an Activity can do.
         */
        const val ACTION_ANSWER_CALL = "com.messenger.app.action.ANSWER_CALL"

        /**
         * Used by the notification's full-screen intent and body tap. It only
         * brings the call UI up - it must never imply consent to answer, since
         * the full-screen intent fires on its own as soon as the phone rings.
         */
        const val ACTION_SHOW_CALL = "com.messenger.app.action.SHOW_CALL"
    }

    /** Set when launched by the Answer action; consumed once by the UI below. */
    private val answerRequest = MutableStateFlow(false)

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications are simply skipped if the user denies */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
        setContent {
            MessengerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MessengerApp(
                        answerRequest = answerRequest.asStateFlow(),
                        onAnswerHandled = { answerRequest.value = false }
                    )
                }
            }
        }
    }

    // launchMode is singleTask, so a notification tap on an already-running
    // process arrives here rather than through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER_CALL) answerRequest.value = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun MessengerApp(
    answerRequest: StateFlow<Boolean>,
    onAnswerHandled: () -> Unit
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsStateWithLifecycle()

    // Owned here (above the NavHost) rather than per-screen, so CallRepository's
    // singleton state can overlay on top of whatever screen is open when a
    // call starts or an invite arrives - matching how a real phone call
    // interrupts the current app instead of living inside chat navigation.
    val callViewModel: CallViewModel = hiltViewModel()
    val callState by callViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var pendingAccept by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // An incoming call rings regardless of mic permission (the callee
        // still needs to see who's calling); only actually connecting the
        // audio track needs it, so the check happens on accept, not on invite.
        if (granted && pendingAccept) callViewModel.acceptCall()
        pendingAccept = false
    }

    fun accept() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            callViewModel.acceptCall()
        } else {
            pendingAccept = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // The background connection only makes sense for a signed-in user, and it
    // has to be torn down on logout so the permanent notification doesn't
    // outlive the session.
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) RealtimeService.start(context) else RealtimeService.stop(context)
    }

    // Answering from the notification: wait until the invite has actually
    // reached this process, since the Activity can be up before the state is.
    val shouldAnswer by answerRequest.collectAsStateWithLifecycle()
    LaunchedEffect(shouldAnswer, callState.status) {
        if (!shouldAnswer) return@LaunchedEffect
        when (callState.status) {
            CallStatus.INCOMING_RINGING -> {
                accept()
                onAnswerHandled()
            }
            // Nothing to answer any more - drop the request so a later,
            // unrelated call isn't auto-accepted.
            CallStatus.IDLE, CallStatus.ENDED -> onAnswerHandled()
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MainNavGraph(
            navController = navController,
            startDestination = if (isAuthenticated) Routes.CHAT_LIST else Routes.LOGIN,
            callViewModel = callViewModel
        )

        if (callState.status != CallStatus.IDLE) {
            CallOverlay(
                state = callState,
                onAccept = { accept() },
                onReject = callViewModel::rejectCall,
                onEnd = callViewModel::endCall,
                onToggleMute = callViewModel::toggleMute,
                onToggleSpeaker = callViewModel::toggleSpeaker,
                onDismissEnded = callViewModel::dismissEnded
            )
        }
    }
}
