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
    /** Direct-chat protocol: 1 = static box, 2 = ephemeral box. */
    val encryptionVersion: Int = 1,
    val senderDeviceId: String = "",
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
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "WebSocketManager"
        /** First retry is fast; later attempts back off (Telegram-style). */
        private const val RECONNECT_DELAY = 1000L
        private const val MAX_RECONNECT_DELAY = 30000L
        private const val CONNECT_WATCHDOG_MS = 20_000L
        /** Detect half-open sockets; Java-WebSocket pings at this interval. */
        private const val CONNECTION_LOST_TIMEOUT_SEC = 20

        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(
            serverUrl: String,
            tokenProvider: () -> String?,
            deviceIdProvider: () -> String? = { null }
        ): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager(serverUrl, tokenProvider, deviceIdProvider).also { instance = it }
            }
        }

        fun clearInstance() {
            instance?.disconnect()
            instance = null
        }
    }

    enum class ConnectionState { CONNECTED, CONNECTING, DISCONNECTED, RECONNECTING }

    private val lock = Any()
    private var webSocketClient: WebSocketClient? = null
    private var isConnected: Boolean = false
    private var isConnecting: Boolean = false
    private var reconnectAttempts: Int = 0
    private var shuttingDown: Boolean = false
    private val joinedChats = mutableSetOf<String>()
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var connectWatchdog: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingChatMessage>(replay = 0, extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingChatMessage> = _incomingMessages.asSharedFlow()

    /** A message retracted for everyone; open chats drop it on the spot. */
    /**
     * chatIds whose MLS group just advanced an epoch. The server pushes this
     * after any accepted commit; without acting on it a Welcome created while
     * we were already running is never fetched, so the invite sits unconsumed
     * and this device never becomes a member.
     */
    private val _mlsCommits = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val mlsCommits = _mlsCommits.asSharedFlow()

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
        val token = tokenProvider()
        synchronized(lock) {
            if (shuttingDown) {
                shuttingDown = false
            }
            if (isConnected || isConnecting) {
                Log.d(TAG, "Already connected or connecting")
                return
            }
            if (token.isNullOrEmpty()) {
                Log.w(TAG, "No authentication token; will retry")
                _connectionState.value = ConnectionState.RECONNECTING
            } else {
                isConnecting = true
                _connectionState.value = ConnectionState.CONNECTING
            }
        }
        if (token.isNullOrEmpty()) {
            scheduleReconnect()
            return
        }

        val uri = buildWsUri(token)
        val draft: Draft = Draft_6455(listOf(DefaultExtension()))

        val client = object : WebSocketClient(uri, draft) {
            override fun onOpen(handshake: ServerHandshake?) {
                synchronized(lock) {
                    if (webSocketClient !== this) return
                    Log.d(TAG, "WebSocket connected")
                    isConnected = true
                    isConnecting = false
                    reconnectAttempts = 0
                }
                reconnectJob?.cancel()
                connectWatchdog?.cancel()
                _connectionState.value = ConnectionState.CONNECTED
                rejoinChats()
            }

            override fun onMessage(text: String?) {
                if (webSocketClient !== this) return
                text?.let { handleMessage(it) }
            }

            override fun onMessage(bytes: java.nio.ByteBuffer?) {
                if (webSocketClient !== this) return
                bytes?.let { handleMessage(String(it.array())) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                synchronized(lock) {
                    if (webSocketClient !== this) return
                }
                Log.w(TAG, "WebSocket closed: code=$code reason=$reason remote=$remote")
                handleSocketFailure()
            }

            override fun onError(ex: Exception?) {
                synchronized(lock) {
                    if (webSocketClient !== this) return
                }
                Log.e(TAG, "WebSocket error: ${ex?.message}")
                handleSocketFailure()
            }
        }
        client.connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SEC

        synchronized(lock) {
            val previous = webSocketClient
            webSocketClient = client
            if (previous != null && previous !== client) {
                try {
                    previous.close()
                } catch (_: Exception) {
                }
            }
        }

        try {
            client.connect()
            startConnectWatchdog()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            handleSocketFailure()
        }
    }

    /**
     * Immediate retry when the network returns or the app is foregrounded.
     * Does nothing if already connected or a handshake is in progress — tearing
     * those down is what left the emulator stuck on "Connecting…".
     */
    fun nudgeReconnect() {
        synchronized(lock) {
            if (shuttingDown || isConnected || isConnecting) return
            reconnectAttempts = 0
        }
        reconnectJob?.cancel()
        connect()
    }

    /** Start a connect only if we are fully idle (not connected, not handshaking). */
    fun ensureConnected() {
        synchronized(lock) {
            if (shuttingDown || isConnected || isConnecting) return
        }
        connect()
    }

    fun disconnect() {
        synchronized(lock) {
            shuttingDown = true
            isConnected = false
            isConnecting = false
        }
        reconnectJob?.cancel()
        connectWatchdog?.cancel()
        try {
            webSocketClient?.close()
        } catch (_: Exception) {
        }
        webSocketClient = null
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
     * Build the WS URL from the origin only. [serverUrl] is the REST base
     * (`http://host:3000/api/v1/`); the hub is `GET /ws`, not `/api/v1/ws`.
     * Hitting the REST prefix never upgrades, so the UI stays on Connecting.
     */
    private fun buildWsUri(token: String): URI {
        var origin = serverUrl.trim()
        val apiIdx = origin.indexOf("/api/")
        if (apiIdx >= 0) origin = origin.substring(0, apiIdx)
        origin = origin.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val deviceId = deviceIdProvider()?.takeIf { it.isNotBlank() }
        val qs = buildString {
            append("token=").append(java.net.URLEncoder.encode(token, "UTF-8"))
            if (deviceId != null) {
                append("&device_id=").append(java.net.URLEncoder.encode(deviceId, "UTF-8"))
            }
        }
        val uri = URI("$origin/ws?$qs")
        Log.d(TAG, "WS connect $origin/ws")
        return uri
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
                        messageId = data.str("message_id"),
                        senderId = data.str("sender_id"),
                        content = data.str("content"),
                        encrypted = data.optBoolean("encrypted", false),
                        timestamp = data.str("timestamp"),
                        contentType = data.str("file_type").ifBlank { "text" },
                        fileUrl = data.str("file_url").takeIf { it.isNotBlank() },
                        fileType = data.str("file_type").takeIf { it.isNotBlank() },
                        fileName = data.str("file_name").takeIf { it.isNotBlank() },
                        fileSize = data.optLong("file_size", 0),
                        durationMs = data.optLong("duration_ms", 0),
                        keyVersion = data.optInt("key_version", 0),
                        encryptionVersion = data.optInt("encryption_version", 1).let { if (it == 0) 1 else it },
                        senderDeviceId = data.str("sender_device_id"),
                        replyToId = data.str("reply_to_id"),
                        isForwarded = data.optBoolean("is_forwarded", false),
                        forwardedFromName = data.str("forwarded_from_name"),
                        forwardedFromMessageId = data.str("forwarded_from_message_id")
                    )
                    CoroutineScope(Dispatchers.Main).launch { _incomingMessages.emit(message) }
                }
                "mls:commit" -> {
                    // chat_id is at the TOP level for this notice (see
                    // handlers/mls.go); there is no "data" object at all, so
                    // the old `?: return` bailed out every time and a Welcome
                    // issued while we were running was never fetched.
                    val chatId = obj.str("chat_id").ifBlank {
                        obj.optJSONObject("data")?.str("chat_id").orEmpty()
                    }
                    if (chatId.isNotBlank()) {
                        CoroutineScope(Dispatchers.Main).launch { _mlsCommits.emit(chatId) }
                    }
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

    private fun handleSocketFailure() {
        val shouldRetry: Boolean
        synchronized(lock) {
            isConnected = false
            isConnecting = false
            shouldRetry = !shuttingDown
        }
        connectWatchdog?.cancel()
        if (shouldRetry) {
            scheduleReconnect()
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun startConnectWatchdog() {
        connectWatchdog?.cancel()
        connectWatchdog = reconnectScope.launch {
            delay(CONNECT_WATCHDOG_MS)
            val stillTrying: Boolean
            synchronized(lock) {
                stillTrying = isConnecting && !isConnected && !shuttingDown
                if (stillTrying) isConnecting = false
            }
            if (stillTrying) {
                Log.w(TAG, "Connect timed out; retrying")
                try {
                    webSocketClient?.close()
                } catch (_: Exception) {
                }
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (synchronized(lock) { shuttingDown }) return
        if (reconnectJob?.isActive == true) return

        val attempt: Int
        synchronized(lock) {
            reconnectAttempts++
            attempt = reconnectAttempts
        }
        _connectionState.value = ConnectionState.RECONNECTING

        val exponent = (attempt - 1).coerceIn(0, 20)
        val delayMs = minOf(MAX_RECONNECT_DELAY, RECONNECT_DELAY * (1L shl exponent))
        val jitter = (Math.random() * 0.25 * delayMs).toLong()
        Log.d(TAG, "Reconnect in ${delayMs + jitter}ms (attempt $attempt)")

        reconnectJob = reconnectScope.launch {
            delay(delayMs + jitter)
            if (synchronized(lock) { shuttingDown || isConnected }) return@launch
            synchronized(lock) { isConnecting = false }
            connect()
        }
    }
}

/**
 * Null-safe string read.
 *
 * [org.json.JSONObject.optString] returns the four-character string `"null"`
 * when the value is JSON null — not an empty string. That made every message
 * with `"reply_to_id": null` look like a reply to a message whose id is
 * literally "null", which the UI then rendered as a quote reading "original
 * message unavailable". The same trap applies to `sender_device_id` and every
 * other optional string on the wire, so read them all through this.
 */
private fun org.json.JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key)
