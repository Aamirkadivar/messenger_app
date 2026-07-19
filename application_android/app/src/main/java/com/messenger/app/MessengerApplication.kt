package com.messenger.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.messenger.app.di.initKoin
import com.messenger.app.notification.NotificationHelper
import com.messenger.app.websocket.WebSocketManager
import org.koin.android.KoinAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level

/**
 * Main Application class for the Messenger app.
 * Initializes Koin DI, Notification Channels, and WebSocket connection.
 */
class MessengerApplication : Application() {

    companion object {
        private const val TAG = "MessengerApplication"
        const val CHANNEL_ID_MESSAGES = "messenger_messages"
        const val CHANNEL_ID_NOTIFICATIONS = "messenger_notifications"
        const val CHANNEL_ID_WEBSOCKET = "messenger_websocket"
        const val NOTIFICATION_ID_WEBSOCKET = 1
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Koin
        initKoin()

        // Create notification channels
        createNotificationChannels()

        // Initialize WebSocket connection
        try {
            val webSocketManager = getComponent<WebSocketManager>()
            webSocketManager.connect()
            Log.d(TAG, "WebSocket manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebSocket manager", e)
        }

        // Initialize notification helper
        try {
            NotificationHelper.initialize(this)
            Log.d(TAG, "Notification helper initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize notification helper", e)
        }
    }

    /**
     * Create notification channels for different message types
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Messages channel - for incoming messages
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                enableLights(true)
                lightColor = android.graphics.Color.rgb(0, 122, 255)
            }

            // Notifications channel - for general notifications
            val notificationsChannel = NotificationChannel(
                CHANNEL_ID_NOTIFICATIONS,
                "Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for group events and updates"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250)
                enableLights(true)
                lightColor = android.graphics.Color.rgb(0, 122, 255)
            }

            // WebSocket channel - for foreground service
            val websocketChannel = NotificationChannel(
                CHANNEL_ID_WEBSOCKET,
                "WebSocket Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the app connected to messages"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(notificationsChannel)
            notificationManager.createNotificationChannel(websocketChannel)

            Log.d(TAG, "Notification channels created")
        }
    }

    /**
     * Helper function to get a component from Koin
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getComponent(cls: Class<T>): T {
        return (koin as KoinAndroid).get(cls)
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up WebSocket connection
        try {
            val webSocketManager = getComponent<WebSocketManager>()
            webSocketManager.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect WebSocket", e)
        }
    }

    companion object {
        @Volatile
        private var instance: MessengerApplication? = null

        fun getInstance(): MessengerApplication {
            return instance ?: throw IllegalStateException("Application instance not initialized")
        }
    }
}