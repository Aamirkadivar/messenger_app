package com.messenger.app.data.remote.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * Real-time client for the backend's WebSocket hub (back-end/websocket/handler.go).
 * Authentication happens via the ?token= query param at connect time - the server
 * validates it before allowing the upgrade, so there is no separate auth handshake.
 * To receive messages for a chat, the client must join its room ("join") first;
 * the backend then pushes any message broadcast for that chat_id to the room's members.
 */
data class IncomingChatMessage(
    val chatId: String,
    val messageId: String,
    val senderId: String,
    val content: String,
    val encrypted: Boolean,
    val timestamp: String
)

data class IncomingTyping(
    val chatId: String,
    val userId: String,
    val isTyping: Boolean
)

class WebSocketManager private constructor(
    private val serverUrl: String,
    private val tokenProvider: () -> String?
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY = 3000L
        private const val MAX_RECONNECT_DELAY = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 10

        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(serverUrl: String, tokenProvider: () -> String?): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager(serverUrl, tokenProvider).also { instance = it }
            }
        }

        fun clearInstance() {
            instance?.disconnect()
            instance = null
        }
    }

    enum class ConnectionState { CONNECTED, CONNECTING, DISCONNECTED, RECONNECTING }

    private var webSocketClient: WebSocketClient? = null
    private var isConnected: Boolean = false
    private var isConnecting: Boolean = false
    private var reconnectAttempts: Int = 0
    private var shuttingDown: Boolean = false
    private val joinedChats = mutableSetOf<String>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingChatMessage>(replay = 0, extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingChatMessage> = _incomingMessages.asSharedFlow()

    private val _typingUpdates = MutableSharedFlow<IncomingTyping>(replay = 0, extraBufferCapacity = 16)
    val typingUpdates: SharedFlow<IncomingTyping> = _typingUpdates.asSharedFlow()

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

        shuttingDown = false
        isConnecting = true
        _connectionState.value = ConnectionState.CONNECTING

        val uri = buildWsUri(token)
        val draft: Draft = Draft_6455(listOf(DefaultExtension()))

        webSocketClient = object : WebSocketClient(uri, draft) {
            override fun onOpen(handshake: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                isConnecting = false
                reconnectAttempts = 0
                _connectionState.value = ConnectionState.CONNECTED
                rejoinChats()
            }

            override fun onMessage(text: String?) {
                text?.let { handleMessage(it) }
            }

            override fun onMessage(bytes: java.nio.ByteBuffer?) {
                bytes?.let { handleMessage(String(it.array())) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.w(TAG, "WebSocket closed: code=$code, reason=$reason")
                isConnected = false
                _connectionState.value = ConnectionState.DISCONNECTED
                if (remote && !shuttingDown) scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error: ${ex?.message}")
                isConnected = false
                isConnecting = false
                _connectionState.value = ConnectionState.DISCONNECTED
                if (!shuttingDown) scheduleReconnect()
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

    fun disconnect() {
        shuttingDown = true
        webSocketClient?.close()
        webSocketClient = null
        isConnected = false
        joinedChats.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "WebSocket disconnected")
    }

    fun joinChat(chatId: String) {
        joinedChats.add(chatId)
        sendEnvelope("join", mapOf("chat_id" to chatId))
    }

    fun leaveChat(chatId: String) {
        joinedChats.remove(chatId)
        sendEnvelope("leave", mapOf("chat_id" to chatId))
    }

    fun sendTypingIndicator(chatId: String, userId: String, isTyping: Boolean) {
        sendEnvelope("typing", mapOf("chat_id" to chatId, "user_id" to userId, "typing" to isTyping))
    }

    fun isHealthy(): Boolean = isConnected

    /**
     * Build the WS URL from the origin only - API_BASE_URL includes the /api/v1
     * REST path, but the backend's WebSocket route is just /ws (see main.go).
     */
    private fun buildWsUri(token: String): URI {
        val httpUri = URI(serverUrl)
        val scheme = if (httpUri.scheme == "https") "wss" else "ws"
        val port = if (httpUri.port != -1) ":${httpUri.port}" else ""
        return URI("$scheme://${httpUri.host}$port/ws?token=$token")
    }

    private fun rejoinChats() {
        joinedChats.forEach { chatId -> sendEnvelope("join", mapOf("chat_id" to chatId)) }
    }

    private fun sendEnvelope(type: String, data: Map<String, Any>) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send '$type': not connected")
            return
        }
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("data", JSONObject(data))
        try {
            webSocketClient?.send(obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending '$type': ${e.message}")
        }
    }

    private fun handleMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "message" -> {
                    val data = obj.optJSONObject("data") ?: return
                    val message = IncomingChatMessage(
                        chatId = data.optString("chat_id"),
                        messageId = data.optString("message_id"),
                        senderId = data.optString("sender_id"),
                        content = data.optString("content"),
                        encrypted = data.optBoolean("encrypted", false),
                        timestamp = data.optString("timestamp")
                    )
                    CoroutineScope(Dispatchers.Main).launch { _incomingMessages.emit(message) }
                }
                "typing" -> {
                    val data = obj.optJSONObject("data") ?: return
                    val typing = IncomingTyping(
                        chatId = data.optString("chat_id"),
                        userId = data.optString("user_id"),
                        isTyping = data.optBoolean("typing", false)
                    )
                    CoroutineScope(Dispatchers.Main).launch { _typingUpdates.emit(typing) }
                }
                "error" -> {
                    val data = obj.optJSONObject("data")
                    Log.e(TAG, "Server error: ${data?.optString("error")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING
        val delay = minOf(MAX_RECONNECT_DELAY, RECONNECT_DELAY * (1L shl (reconnectAttempts - 1)))
        val jitter = (Math.random() * 0.25 * delay).toLong()

        CoroutineScope(Dispatchers.IO).launch {
            delay(delay + jitter)
            if (!isConnected) connect()
        }
    }
}
