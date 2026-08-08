package com.messenger.app.data.remote.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
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
    val timestamp: String,
    val contentType: String = "text",
    val fileUrl: String? = null,
    val fileType: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val durationMs: Long = 0,
    /** Which of the sender's group Sender Key versions encrypted this message. */
    val keyVersion: Int = 0,
    val replyToId: String = "",
    val isForwarded: Boolean = false,
    val forwardedFromName: String = "",
    val forwardedFromMessageId: String = ""
)

/** Someone retracted a message for everyone. */
data class DeletedMessage(
    val chatId: String,
    val messageId: String
)

data class IncomingTyping(
    val chatId: String,
    val userId: String,
    val isTyping: Boolean
)

// Pushed when the other participant marks a chat's messages as read - lets us
// flip our sent messages' checkmarks to "seen" in real time.
data class IncomingReadReceipt(
    val chatId: String,
    val readerId: String,
    val readAt: String
)

/**
 * One call-signaling event (see back-end/websocket/calls.go). Only SDP
 * offer/answer and ICE candidates ever cross the server - the fields present
 * depend on [type]: "call:invite" carries sdp+chatId+fromName, "call:answer"
 * carries sdp, "call:ice_candidate" carries candidate/sdpMid/sdpMLineIndex,
 * "call:reject"/"call:end" carry reason. The server fills in call_id for a
 * fresh invite if the caller didn't set one, so it's always present here.
 * "call:invite" also carries video (whether the caller wants a video call),
 * and "call:media" carries camera (the peer turning their camera on/off
 * mid-call).
 */
data class IncomingCallSignal(
    val type: String,
    val callId: String,
    val fromUserId: String,
    val chatId: String = "",
    val fromName: String = "",
    val sdp: String = "",
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val reason: String = "",
    val video: Boolean = false,
    val cameraOn: Boolean = true,
    /** Shared group-session id when this pairwise signal is a mesh edge. */
    val groupCallId: String = "",
    /** Current roster for group_invite / group_join / group_leave. */
    val participantIds: List<String> = emptyList()
)

// Pushed whenever any user connects/disconnects - not scoped to a chat room,
// since the user could appear in several of our conversations at once.
data class IncomingPresence(
    val userId: String,
    val isOnline: Boolean
)

class WebSocketManager private constructor(
    private val serverUrl: String,
    private val tokenProvider: () -> String?
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY = 3000L
        private const val MAX_RECONNECT_DELAY = 30000L

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

    /** A message retracted for everyone; open chats drop it on the spot. */
    private val _deletedMessages = MutableSharedFlow<DeletedMessage>(extraBufferCapacity = 16)
    val deletedMessages = _deletedMessages.asSharedFlow()

    private val _typingUpdates = MutableSharedFlow<IncomingTyping>(replay = 0, extraBufferCapacity = 16)
    val typingUpdates: SharedFlow<IncomingTyping> = _typingUpdates.asSharedFlow()

    private val _readReceipts = MutableSharedFlow<IncomingReadReceipt>(replay = 0, extraBufferCapacity = 16)
    val readReceipts: SharedFlow<IncomingReadReceipt> = _readReceipts.asSharedFlow()

    private val _presenceUpdates = MutableSharedFlow<IncomingPresence>(replay = 0, extraBufferCapacity = 16)
    val presenceUpdates: SharedFlow<IncomingPresence> = _presenceUpdates.asSharedFlow()

    private val _callSignals = MutableSharedFlow<IncomingCallSignal>(replay = 0, extraBufferCapacity = 16)
    val callSignals: SharedFlow<IncomingCallSignal> = _callSignals.asSharedFlow()

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

    /** Sends a call:* signaling message - see [IncomingCallSignal] for the field contract. */
    fun sendCallSignal(type: String, data: Map<String, Any>) {
        sendEnvelope(type, data)
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
        val payload = JSONObject()
        for ((key, value) in data) {
            when (value) {
                is JSONArray -> payload.put(key, value)
                is JSONObject -> payload.put(key, value)
                is Collection<*> -> payload.put(key, JSONArray(value))
                else -> payload.put(key, value)
            }
        }
        obj.put("data", payload)
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
                    // NOTE: the server's "file_type" key actually carries the message's
                    // content_type ("text"/"audio"/"image"/"file") - see back-end's
                    // handlers/message.go SendMessage, which sets it from
                    // message.ContentType rather than a real MIME/file type.
                    val message = IncomingChatMessage(
                        chatId = data.optString("chat_id"),
                        messageId = data.optString("message_id"),
                        senderId = data.optString("sender_id"),
                        content = data.optString("content"),
                        encrypted = data.optBoolean("encrypted", false),
                        timestamp = data.optString("timestamp"),
                        contentType = data.optString("file_type", "text").ifBlank { "text" },
                        fileUrl = data.optString("file_url").takeIf { it.isNotBlank() },
                        fileType = data.optString("file_type").takeIf { it.isNotBlank() },
                        fileName = data.optString("file_name").takeIf { it.isNotBlank() },
                        fileSize = data.optLong("file_size", 0),
                        durationMs = data.optLong("duration_ms", 0),
                        keyVersion = data.optInt("key_version", 0),
                        replyToId = data.optString("reply_to_id"),
                        isForwarded = data.optBoolean("is_forwarded", false),
                        forwardedFromName = data.optString("forwarded_from_name"),
                        forwardedFromMessageId = data.optString("forwarded_from_message_id")
                    )
                    CoroutineScope(Dispatchers.Main).launch { _incomingMessages.emit(message) }
                }
                "message:deleted" -> {
                    val data = obj.optJSONObject("data") ?: return
                    CoroutineScope(Dispatchers.Main).launch {
                        _deletedMessages.emit(
                            DeletedMessage(
                                chatId = data.optString("chat_id"),
                                messageId = data.optString("message_id")
                            )
                        )
                    }
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
                "read" -> {
                    val data = obj.optJSONObject("data") ?: return
                    val receipt = IncomingReadReceipt(
                        chatId = data.optString("chat_id"),
                        readerId = data.optString("reader_id"),
                        readAt = data.optString("read_at")
                    )
                    CoroutineScope(Dispatchers.Main).launch { _readReceipts.emit(receipt) }
                }
                "presence" -> {
                    val data = obj.optJSONObject("data") ?: return
                    val presence = IncomingPresence(
                        userId = data.optString("user_id"),
                        isOnline = data.optBoolean("is_online", false)
                    )
                    CoroutineScope(Dispatchers.Main).launch { _presenceUpdates.emit(presence) }
                }
                "error" -> {
                    val data = obj.optJSONObject("data")
                    Log.e(TAG, "Server error: ${data?.optString("error")}")
                }
                "call:invite", "call:answer", "call:ice_candidate", "call:reject", "call:end", "call:media",
                "call:group_invite", "call:group_join", "call:group_leave" -> {
                    val data = obj.optJSONObject("data") ?: return
                    val participantIds = mutableListOf<String>()
                    val idsArr = data.optJSONArray("participant_ids")
                    if (idsArr != null) {
                        for (i in 0 until idsArr.length()) {
                            val id = idsArr.optString(i)
                            if (id.isNotBlank()) participantIds.add(id)
                        }
                    }
                    val signal = IncomingCallSignal(
                        type = obj.optString("type"),
                        callId = data.optString("call_id"),
                        fromUserId = data.optString("from_user_id"),
                        chatId = data.optString("chat_id"),
                        fromName = data.optString("from_name"),
                        sdp = data.optString("sdp"),
                        candidate = data.optString("candidate"),
                        sdpMid = data.optString("sdp_mid"),
                        sdpMLineIndex = data.optInt("sdp_mline_index", 0),
                        reason = data.optString("reason"),
                        video = data.optBoolean("video", false),
                        cameraOn = data.optBoolean("camera", true),
                        groupCallId = data.optString("group_call_id"),
                        participantIds = participantIds
                    )
                    CoroutineScope(Dispatchers.Main).launch { _callSignals.emit(signal) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        _connectionState.value = ConnectionState.RECONNECTING

        // Back off exponentially up to MAX_RECONNECT_DELAY, then keep retrying
        // at that interval forever. This deliberately never gives up: it used
        // to stop after 10 attempts (~2.5 min), and since reconnectAttempts
        // only resets on a successful connect, any outage longer than that
        // left the app silently offline until it was manually restarted -
        // messages stopped arriving and incoming calls rang against nobody,
        // with nothing in the UI saying so.
        // The shift is clamped separately from the minOf: at ~63 attempts
        // 1L shl n overflows to a negative delay, which would busy-loop.
        val exponent = (reconnectAttempts - 1).coerceIn(0, 20)
        val delay = minOf(MAX_RECONNECT_DELAY, RECONNECT_DELAY * (1L shl exponent))
        val jitter = (Math.random() * 0.25 * delay).toLong()

        CoroutineScope(Dispatchers.IO).launch {
            delay(delay + jitter)
            if (!isConnected) connect()
        }
    }
}
