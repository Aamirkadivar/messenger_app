package com.messenger.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthCheck = object : Runnable {
        override fun run() {
            if (tokenManager.isAuthenticated()) {
                chatRepository.ensureRealtime()
            }
            mainHandler.postDelayed(this, HEALTH_CHECK_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (tokenManager.isAuthenticated()) chatRepository.nudgeRealtime()
        }
    }

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (tokenManager.isAuthenticated()) chatRepository.nudgeRealtime()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4243
        private const val CHANNEL_ID = "messenger_service"
        private const val HEALTH_CHECK_MS = 15_000L

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
        registerNetworkCallback()
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        mainHandler.postDelayed(healthCheck, HEALTH_CHECK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (!tokenManager.isAuthenticated()) {
            stopSelf()
            return START_NOT_STICKY
        }

        chatRepository.connectRealtime()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(healthCheck)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        unregisterNetworkCallback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

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
            NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the app reachable for calls and messages"
                setShowBadge(false)
            }
        )
    }
}
