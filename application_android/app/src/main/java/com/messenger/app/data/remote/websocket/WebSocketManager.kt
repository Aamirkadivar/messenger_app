package com.messenger.app.data.remote.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferCapacity
import kotlinx.coroutines.flow.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * WebSocket message types for real-time communication
 */
sealed class WebSocketMessage {
    data class TextMessage(
        val id: String,
        val conversationId: String,
        val senderId: String,
        val content: String,
        val encryptedContent: String,
        val timestamp: Long,
        val type: MessageType = MessageType.TEXT,
        val fileId: String? = null
    ) : WebSocketMessage() {
        enum class MessageType {
            TEXT, IMAGE, VIDEO, AUDIO, FILE, SYSTEM
        }
    }

    data class TypingIndicator(
        val conversationId: String,
        val userId: String,
        val userName: String,
        val isTyping: Boolean
    ) : WebSocketMessage()

    data class PresenceUpdate(
        val userId: String,
        val userName: String,
        val status: PresenceStatus,
        val lastSeen: Long? = null
    ) : WebSocketMessage() {
        enum class PresenceStatus {
            ONLINE, OFFLINE, AWAY, BUSY
        }
    }

    data class ReadReceipt(
        val conversationId: String,
        val messageId: String,
        val userId: String,
        val timestamp: Long
    ) : WebSocketMessage()

    data class ReactionUpdate(
        val conversationId: String,
        val messageId: String,
        val userId: String,
        val emoji: String,
        val action: ReactionAction
    ) : WebSocketMessage() {
        enum class ReactionAction {
            ADD, REMOVE
        }
    }

    data class ConversationUpdate(
        val conversationId: String,
        val type: UpdateType,
        val data: Map<String, Any>? = null
    ) : WebSocketMessage() {
        enum class UpdateType {
            CREATED, UPDATED, DELETED, MEMBER_ADDED, MEMBER_REMOVED
        }
    }

    data class Heartbeat(
        val timestamp: Long = System.currentTimeMillis()
    ) : WebSocketMessage()

    data class Error(
        val code: Int,
        val message: String,
        val details: String? = null
    ) : WebSocketMessage()
}

/**
 * WebSocket message envelope for authenticated communication
 */
data class MessageEnvelope(
    val type: String,
    val payload: Any,
    val signature: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return buildString {
            append("{\"type\":\"$type\",\"payload\":")
            when (payload) {
                is Map<*, *> -> {
                    append("{")
                    payload.entries.joinTo(this, ",") { entry ->
                        "\"${entry.key}\":\"${entry.value}\""
                    }
                    append("}")
                }
                is String -> append("\"$payload\"")
                else -> append("\"$payload\"")
            }
            append(",\"timestamp\":$timestamp")
            if (!signature.isNullOrEmpty()) append(",\"signature\":\"$signature\"")
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): MessageEnvelope {
            val `obj` = JSONObject(json)
            val type = `obj`.getString("type")
            val timestamp = `obj`.getLong("timestamp")
            val signature = if (`obj`.has("signature")) `obj`.getString("signature") else null
            
            val payload = `obj`.get("payload")
            
            return MessageEnvelope(type, payload, signature, timestamp)
        }
    }
}

/**
 * WebSocket connection manager for real-time messaging.
 * Handles connection lifecycle, reconnection, message serialization, and authentication.
 */
class WebSocketManager private constructor(
    private val serverUrl: String,
    private val tokenProvider: () -> String?
) : androidx.lifecycle.AndroidViewModel(com.messenger.app.MessengerApplication.getInstance()) {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        private const val RECONNECT_DELAY = 3000L // 3 seconds
        private const val MAX_RECONNECT_DELAY = 30000L // 30 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 10

        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(
            serverUrl: String,
            tokenProvider: () -> String?
        ): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager(serverUrl, tokenProvider).also { instance = it }
            }
        }

        fun clearInstance() {
            instance?.disconnect()
            instance = null
        }
    }

    private var webSocketClient: WebSocketClient? = null
    private var isConnected: Boolean = false
    private var isConnecting: Boolean = false
    private var reconnectAttempts: Int = 0
    private var reconnectDelay: Long = RECONNECT_DELAY

    private val _messages = MutableSharedFlow<WebSocketMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<WebSocketMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _presenceMap = MutableStateFlow<Map<String, WebSocketMessage.PresenceUpdate>>(emptyMap())
    val presenceMap: StateFlow<Map<String, WebSocketMessage.PresenceUpdate>> = _presenceMap.asStateFlow()

    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    enum class ConnectionState {
        CONNECTED, CONNECTING, DISCONNECTED, DISCONNECTING, RECONNECTING
    }

    init {
        Log.d(TAG, "WebSocketManager initialized with URL: $serverUrl")
    }

    /**
     * Connect to the WebSocket server with authentication
     */
    fun connect() {
        if (isConnected || isConnecting) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        val token = tokenProvider()
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "No authentication token available")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        isConnecting = true
        _connectionState.value = ConnectionState.CONNECTING

        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        val uri = URI("$wsUrl/ws?token=$token")

        val drafts: List<Draft> = listOf(Draft_6455(listOf(DefaultExtension())))

        webSocketClient = object : WebSocketClient(uri, drafts) {
            override fun onOpen(handshake: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                isConnecting = false
                reconnectAttempts = 0
                reconnectDelay = RECONNECT_DELAY
                _connectionState.value = ConnectionState.CONNECTED
                startHeartbeat()
            }

            override fun onMessage(text: String?) {
                text?.let { handleMessage(it) }
            }

            override fun onMessage(bytes: java.nio.ByteBuffer?) {
                bytes?.let {
                    val text = String(it.array())
                    handleMessage(text)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.w(TAG, "WebSocket closed: code=$code, reason=$reason")
                isConnected = false
                stopHeartbeat()
                _connectionState.value = ConnectionState.DISCONNECTED

                if (remote) {
                    scheduleReconnect()
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error: ${ex?.message}")
                isConnected = false
                isConnecting = false
                stopHeartbeat()
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }

        try {
            webSocketClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            isConnecting = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    /**
     * Disconnect from the WebSocket server
     */
    fun disconnect() {
        isConnecting = false
        _connectionState.value = ConnectionState.DISCONNECTING
        stopHeartbeat()
        reconnectJob?.cancel()

        webSocketClient?.close()
        webSocketClient = null
        isConnected = false
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "WebSocket disconnected")
    }

    /**
     * Send a message through the WebSocket connection
     */
    fun sendMessage(message: Any): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Cannot send message: not connected")
            return false
        }

        val json = when (message) {
            is MessageEnvelope -> message.toJson()
            is WebSocketMessage.TextMessage -> buildTextMessageEnvelope(message).toJson()
            is WebSocketMessage.TypingIndicator -> buildTypingEnvelope(message).toJson()
            else -> return false
        }

        try {
            webSocketClient?.send(json)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
            return false
        }
    }

    /**
     * Send typing indicator
     */
    fun sendTypingIndicator(conversationId: String, isTyping: Boolean): Boolean {
        val typingMessage = WebSocketMessage.TypingIndicator(
            conversationId = conversationId,
            userId = "", // Will be filled from token
            userName = "",
            isTyping = isTyping
        )
        return sendMessage(typingMessage)
    }

    /**
     * Ping the server to keep connection alive
     */
    fun ping(): Boolean {
        if (!isConnected) return false
        return try {
            webSocketClient?.ping()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed: ${e.message}")
            false
        }
    }

    /**
     * Check if the connection is healthy
     */
    fun isHealthy(): Boolean = isConnected

    /**
     * Handle incoming messages
     */
    private fun handleMessage(json: String) {
        try {
            val `obj` = JSONObject(json)
            val type = `obj`.getString("type")

            val message = when (type) {
                "message" -> {
                    val payload = `obj`.getJSONObject("payload")
                    WebSocketMessage.TextMessage(
                        id = payload.getString("id"),
                        conversationId = payload.getString("conversationId"),
                        senderId = payload.getString("senderId"),
                        content = payload.optString("content", ""),
                        encryptedContent = payload.optString("encryptedContent", ""),
                        timestamp = payload.optLong("timestamp", System.currentTimeMillis()),
                        type = WebSocketMessage.TextMessage.MessageType.valueOf(
                            payload.optString("type", "TEXT")
                        ),
                        fileId = payload.optString("fileId", null)
                    )
                }
                "typing" -> {
                    val payload = `obj`.getJSONObject("payload")
                    WebSocketMessage.TypingIndicator(
                        conversationId = payload.getString("conversationId"),
                        userId = payload.getString("userId"),
                        userName = payload.optString("userName", ""),
                        isTyping = payload.optBoolean("isTyping", true)
                    )
                }
                "presence" -> {
                    val payload = `obj`.getJSONObject("payload")
                    val update = WebSocketMessage.PresenceUpdate(
                        userId = payload.getString("userId"),
                        userName = payload.optString("userName", ""),
                        status = WebSocketMessage.PresenceUpdate.PresenceStatus.valueOf(
                            payload.optString("status", "OFFLINE")
                        ),
                        lastSeen = payload.optLong("lastSeen", -1).takeIf { it >= 0 }
                    )
                    // Update presence map
                    val currentPresence = _presenceMap.value.toMutableMap()
                    currentPresence[update.userId] = update
                    _presenceMap.value = currentPresence
                    return // Don't emit to messages flow
                }
                "read_receipt" -> {
                    val payload = `obj`.getJSONObject("payload")
                    WebSocketMessage.ReadReceipt(
                        conversationId = payload.getString("conversationId"),
                        messageId = payload.getString("messageId"),
                        userId = payload.getString("userId"),
                        timestamp = payload.optLong("timestamp", System.currentTimeMillis())
                    )
                }
                "reaction" -> {
                    val payload = `obj`.getJSONObject("payload")
                    WebSocketMessage.ReactionUpdate(
                        conversationId = payload.getString("conversationId"),
                        messageId = payload.getString("messageId"),
                        userId = payload.getString("userId"),
                        emoji = payload.getString("emoji"),
                        action = WebSocketMessage.ReactionUpdate.ReactionAction.valueOf(
                            payload.optString("action", "ADD")
                        )
                    )
                }
                "conversation" -> {
                    val payload = `obj`.getJSONObject("payload")
                    WebSocketMessage.ConversationUpdate(
                        conversationId = payload.getString("conversationId"),
                        type = WebSocketMessage.ConversationUpdate.UpdateType.valueOf(
                            payload.optString("type", "UPDATED")
                        ),
                        data = null
                    )
                }
                "heartbeat" -> WebSocketMessage.Heartbeat(
                    timestamp = `obj`.optLong("timestamp", System.currentTimeMillis())
                )
                "error" -> {
                    val code = `obj`.getInt("code")
                    val errorMsg = `obj`.getString("message")
                    WebSocketMessage.Error(
                        code = code,
                        message = errorMsg,
                        details = `obj`.optString("details")
                    )
                }
                else -> return // Unknown message type
            }

            CoroutineScope(Dispatchers.Main).launch {
                _messages.emit(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
        }
    }

    /**
     * Build envelope for text message
     */
    private fun buildTextMessageEnvelope(message: WebSocketMessage.TextMessage): MessageEnvelope {
        val payload = mapOf(
            "id" to message.id,
            "conversationId" to message.conversationId,
            "senderId" to message.senderId,
            "content" to message.content,
            "encryptedContent" to message.encryptedContent,
            "type" to message.type.name,
            "timestamp" to message.timestamp
        )
        return MessageEnvelope("message", payload, null, message.timestamp)
    }

    /**
     * Build envelope for typing indicator
     */
    private fun buildTypingEnvelope(message: WebSocketMessage.TypingIndicator): MessageEnvelope {
        val payload = mapOf(
            "conversationId" to message.conversationId,
            "isTyping" to message.isTyping.toString()
        )
        return MessageEnvelope("typing", payload, null, message.timestamp)
    }

    /**
     * Start heartbeat to keep connection alive
     */
    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(HEARTBEAT_INTERVAL)
                if (isConnected) {
                    ping()
                    // Send heartbeat message
                    val heartbeat = WebSocketMessage.Heartbeat()
                    val envelope = MessageEnvelope("heartbeat", mapOf("timestamp" to heartbeat.timestamp))
                    sendMessage(envelope)
                }
            }
        }
    }

    /**
     * Stop heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Schedule reconnection with exponential backoff
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.RECONNECTING

        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        // Exponential backoff with jitter
        reconnectDelay = minOf(
            MAX_RECONNECT_DELAY,
            RECONNECT_DELAY * (1L shl (reconnectAttempts - 1))
        )
        val jitter = (Math.random() * 0.25 * reconnectDelay).toLong()
        val delayWithJitter = reconnectDelay + jitter

        Log.d(TAG, "Reconnecting in ${delayWithJitter}ms (attempt $reconnectAttempts)")

        CoroutineScope(Dispatchers.IO).launch {
            delay(delayWithJitter)
            if (isConnected.not()) {
                connect()
            }
        }
    }
}