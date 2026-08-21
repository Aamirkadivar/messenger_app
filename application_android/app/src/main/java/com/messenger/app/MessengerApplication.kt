package com.messenger.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.messenger.app.data.remote.websocket.IncomingChatMessage
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.security.TokenManager
import com.messenger.app.ui.MainActivity
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main Application class for the Messenger app.
 * Hilt handles DI setup automatically via @HiltAndroidApp. This class also
 * owns a process-lifetime notification listener: it observes real-time
 * incoming messages (see ChatRepository/WebSocketManager) and surfaces a
 * system notification whenever a message arrives from someone else while
 * the app isn't in the foreground.
 */
@HiltAndroidApp
class MessengerApplication : Application() {

    companion object {
        const val CHANNEL_ID_MESSAGES = "messenger_messages"
        const val CHANNEL_ID_NOTIFICATIONS = "messenger_notifications"
    }

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var tokenManager: TokenManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Phase 0 of the shared Rust core: proves library loading, the JNI
        // marshalling contract, panic containment and the log channel before
        // any MLS exists. Non-fatal - group chats stay on the existing path
        // until cutover. See docs/mls-v2-architecture.md.
        com.messenger.app.data.encryption.MlsCore.selfTest()
        // Phase 5: exercise the real handle API on-device, so a JNI marshalling
        // or .so packaging problem is distinguishable from a protocol problem.
        com.messenger.app.data.encryption.runSelfTest()
        createNotificationChannels()
        observeIncomingMessagesForNotifications()
    }

    private fun observeIncomingMessagesForNotifications() {
        appScope.launch {
            chatRepository.incomingMessages.collect { message ->
                val myId = tokenManager.getCurrentUserId().getOrNull()
                if (myId != null && message.senderId == myId) return@collect

                val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                if (isForeground) return@collect

                // Muting a chat previously only hid it in Settings - it never
                // actually silenced anything, so honour it here.
                if (chatRepository.isChatMuted(message.chatId)) return@collect

                showMessageNotification(message)
            }
        }
    }

    private suspend fun showMessageNotification(message: IncomingChatMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val senderName = resolveChatName(message.chatId)
        // Decrypt the E2EE ciphertext for the notification preview.
        val text = chatRepository.decryptFor(
            message.chatId,
            message.content,
            message.encrypted,
            message.senderId,
            message.keyVersion,
            message.encryptionVersion
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            message.chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(message.chatId.hashCode(), notification)
    }

    private suspend fun resolveChatName(chatId: String): String {
        val token = tokenManager.getAccessToken().getOrNull() ?: return "New message"
        return chatRepository.getChats(token).getOrNull()
            ?.firstOrNull { it.id == chatId }
            ?.let { chat ->
                chat.otherUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: chat.otherUser?.username
            }
            ?: "New message"
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }

            val notificationsChannel = NotificationChannel(
                CHANNEL_ID_NOTIFICATIONS,
                "Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
            }

            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(notificationsChannel)
        }
    }
}
