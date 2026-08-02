package com.messenger.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.messenger.app.R
import com.messenger.app.data.call.CallStatus
import com.messenger.app.data.call.CallRepository
import com.messenger.app.data.call.CallUiState
import com.messenger.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Keeps a call's audio alive while the app is backgrounded, rings the phone for
 * an incoming call, and shows the "call in progress" notification a real phone
 * call needs - without this, Android can suspend/kill the process mid-call the
 * moment the user leaves the app screen.
 *
 * This service doesn't do any signaling or WebRTC work itself - [CallRepository]
 * (a singleton, unaffected by this service's own lifecycle) already owns
 * that. The service only exists to hold a foreground-priority process
 * reference for as long as CallRepository's state isn't IDLE, and starts
 * itself before any point.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject
    lateinit var callRepository: CallRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ringer by lazy { Ringer(this) }

    /** Tracks the last state so the ringtone starts/stops exactly once per call. */
    private var ringing = false

    companion object {
        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "calls"
        /**
         * Incoming calls get their own channel so they can be IMPORTANCE_HIGH
         * (heads-up + full-screen) without the ongoing in-call notification
         * popping up over the UI on every state change.
         */
        private const val CHANNEL_ID_INCOMING = "calls_incoming"

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannelsIfNeeded()

        callRepository.state
            .onEach { state ->
                // ENDED is a brief in-app "call ended" screen, not ongoing
                // foreground work - nothing left to hold a notification for.
                if (state.status == CallStatus.IDLE || state.status == CallStatus.ENDED) {
                    stopRinging()
                    stopSelf()
                } else {
                    if (state.status == CallStatus.INCOMING_RINGING) startRinging() else stopRinging()
                    startForegroundCompat(buildNotification(state), state.status)
                }
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    private fun startRinging() {
        if (ringing) return
        ringing = true
        ringer.start()
    }

    private fun stopRinging() {
        if (!ringing) return
        ringing = false
        ringer.stop()
    }

    private fun startForegroundCompat(notification: Notification, status: CallStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // An unanswered incoming call has no audio yet; declaring
            // phoneCall before the mic is live is what the type is for, but
            // Android 14 requires the microphone type only once we're actually
            // capturing, so phoneCall is used throughout.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: CallUiState): Notification =
        if (state.status == CallStatus.INCOMING_RINGING) {
            buildIncomingNotification(state)
        } else {
            buildOngoingNotification(state)
        }

    /**
     * A full call-style notification: it takes over the lock screen via the
     * full-screen intent, and offers Answer/Decline without opening the app.
     */
    private fun buildIncomingNotification(state: CallUiState): Notification {
        val caller = Person.Builder()
            .setName(state.peerName.ifBlank { "Unknown" })
            .setImportant(true)
            .build()

        val answerIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ANSWER_CALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Deliberately NOT the answer intent: the full-screen intent fires by
        // itself the moment the call rings, so pointing it at "answer" picks
        // up every incoming call automatically. This one only shows the
        // in-app call UI, where the user chooses.
        val showCallIntent = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_SHOW_CALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(this, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_DECLINE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Incoming call")
            .setContentText(state.peerName.ifBlank { "Unknown" })
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, answerIntent))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(showCallIntent)
            // The whole point: on a locked or idle screen this launches the
            // call UI directly instead of leaving a notification the user has
            // to notice on their own.
            .setFullScreenIntent(showCallIntent, true)
            .build()
    }

    private fun buildOngoingNotification(state: CallUiState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = when (state.status) {
            CallStatus.OUTGOING_RINGING -> "Calling ${state.peerName.ifBlank { "..." }}" to "Ringing"
            CallStatus.CONNECTING -> "Call with ${state.peerName.ifBlank { "..." }}" to "Connecting"
            CallStatus.CONNECTED -> "Call with ${state.peerName.ifBlank { "..." }}" to "In progress"
            else -> "Call" to ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun createChannelsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Voice call status"
                }
            )
        }

        if (manager.getNotificationChannel(CHANNEL_ID_INCOMING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_INCOMING,
                    "Incoming calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Ringing for incoming voice calls"
                    // Silent + no vibration on purpose: Ringer drives a looping
                    // ringtone instead, since a channel sound plays only once.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }
}
