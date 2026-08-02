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
import androidx.core.content.ContextCompat
import com.messenger.app.R
import com.messenger.app.data.call.CallRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.security.TokenManager
import com.messenger.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the real-time WebSocket connected while the app isn't open.
 *
 * Without this the app can only be reached while its UI is running: the socket
 * is opened by a ViewModel and [CallRepository] - the thing that listens for
 * call:invite - is only constructed once MainActivity exists. Swiping the app
 * away therefore killed the process and with it any chance of the phone
 * ringing.
 *
 * Injecting CallRepository here is not incidental: constructing it is what
 * subscribes it to the socket's call signals (see its init block).
 *
 * The usual alternative is a push notification waking the process on demand,
 * which needs a real Firebase project - app/google-services.json is currently
 * a placeholder, so a held-open connection is the only route that works today.
 */
@AndroidEntryPoint
class RealtimeService : Service() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    @Suppress("unused") // constructing it is the point - see the class comment
    lateinit var callRepository: CallRepository

    @Inject
    lateinit var tokenManager: TokenManager

    companion object {
        private const val NOTIFICATION_ID = 4243
        private const val CHANNEL_ID = "messenger_service"

        /** Safe to call repeatedly - a second start just re-runs onStartCommand. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RealtimeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground has to happen promptly whatever we decide below, or
        // the system kills us for not becoming foreground in time.
        startForegroundCompat()

        if (!tokenManager.isAuthenticated()) {
            stopSelf()
            return START_NOT_STICKY
        }

        chatRepository.connectRealtime()
        // START_STICKY so the system brings the connection back after it
        // reclaims memory - the whole point is surviving without the UI.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Messenger")
            .setContentText("Ready for calls and messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            // MIN keeps the permanent notification collapsed and silent - it is
            // a status indicator the system requires, not something to read.
            NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the app reachable for calls and messages"
                setShowBadge(false)
            }
        )
    }
}
