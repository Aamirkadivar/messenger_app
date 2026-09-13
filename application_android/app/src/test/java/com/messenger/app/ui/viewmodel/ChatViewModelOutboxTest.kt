package com.messenger.app.ui.viewmodel

import com.messenger.app.data.model.SendMessageResponseData
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.MessageOutbox
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * PHASE 71 - the composer's send path in ChatViewModel.
 *
 * Android criterion 13: with the WebSocket DISCONNECTED the message is still handed to the durable
 * HTTP send path (ChatRepository.sendText) immediately - the old code returned early and left the
 * bubble on a clock with no attempt made. Also pins the bubble lifecycle: PENDING while queued, the
 * server id once accepted, FAILED when refused, and a retry that reuses the same client_message_id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelOutboxTest {

    private companion object {
        const val CHAT = "0f1e2d3c-4b5a-4968-8776-655443322110"
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("access-token")
        override suspend fun getCurrentUserId(): Result<String?> = Result.success("me")
        override suspend fun getOrCreateDeviceId(): Result<String> = Result.success("my-device")
    }

    private val dispatcher = StandardTestDispatcher()
    private val socket = MutableStateFlow(WebSocketManager.ConnectionState.DISCONNECTED)
    private val outboxEvents = MutableSharedFlow<MessageOutbox.Event>(extraBufferCapacity = 8)
    private lateinit var repo: ChatRepository
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mockk(relaxed = true)
        every { repo.connectionState } returns socket
        every { repo.outboxEvents } returns outboxEvents
        vm = ChatViewModel(
            chatRepository = repo,
            mlsRepository = mockk(relaxed = true),
            voiceRepository = mockk(relaxed = true),
            attachmentRepository = mockk(relaxed = true),
            voiceRecorder = mockk(relaxed = true),
            voicePlayer = mockk(relaxed = true),
            roundVideoRecorder = mockk(relaxed = true),
            roundVideoRepository = mockk(relaxed = true),
            playerPool = mockk(relaxed = true),
            tokenManager = Tokens(),
            historyRotation = mockk(relaxed = true)
        )
        // Stand in an open direct chat without running openChat's network choreography.
        val field = ChatViewModel::class.java.getDeclaredField("_chatState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(vm) as MutableStateFlow<ChatUiState>).value = ChatUiState(chatId = CHAT, chatType = "direct")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun sendingWithTheSocketDisconnectedStillSubmitsOverHttp() = runTest(dispatcher) {
        val cmid = slot<String>()
        coEvery { repo.sendText(any(), CHAT, "direct", "hello", any(), any(), capture(cmid)) } answers {
            ChatRepository.SendOutcome.Queued(cmid.captured, "ConnectException")
        }
        vm.sendMessage("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.sendText("access-token", CHAT, "direct", "hello", any(), any(), any()) }
        val bubble = vm.chatState.value.messages.single()
        println("P71 A13(vm): socket=${socket.value} sendText called; bubble=${bubble.id} ${bubble.deliveryStatus}")
        assertEquals(WebSocketManager.ConnectionState.DISCONNECTED, socket.value)
        assertEquals(OUTBOX_BUBBLE_PREFIX + cmid.captured, bubble.id)
        assertEquals(DeliveryStatus.PENDING, bubble.deliveryStatus)
    }

    @Test
    fun aQueuedMessageSettlesThroughOutboxEvents() = runTest(dispatcher) {
        val cmid = slot<String>()
        coEvery { repo.sendText(any(), any(), any(), any(), any(), any(), capture(cmid)) } answers {
            ChatRepository.SendOutcome.Queued(cmid.captured, "offline")
        }
        vm.sendMessage("later")
        advanceUntilIdle()
        outboxEvents.emit(
            MessageOutbox.Event.Accepted(
                cmid.captured, CHAT,
                SendMessageResponseData(id = "server-id-1", chatId = CHAT, senderId = "me", createdAt = "2026-09-11T10:00:00Z")
            )
        )
        advanceUntilIdle()
        val bubble = vm.chatState.value.messages.single()
        assertEquals("server-id-1", bubble.id)
        assertEquals(DeliveryStatus.SENT, bubble.deliveryStatus)
    }

    @Test
    fun aRefusedMessageIsMarkedFailedAndItsRetryReusesTheSameId() = runTest(dispatcher) {
        val cmid = slot<String>()
        coEvery { repo.sendText(any(), any(), any(), any(), any(), any(), capture(cmid)) } answers {
            ChatRepository.SendOutcome.Failed(cmid.captured, Exception("HTTP 403"))
        }
        coEvery { repo.retryOutbox(any()) } returns true
        vm.sendMessage("refused")
        advanceUntilIdle()
        val failed = vm.chatState.value.messages.single()
        assertEquals(DeliveryStatus.FAILED, failed.deliveryStatus)

        vm.retryOutgoing(failed.id)
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.retryOutbox(cmid.captured) }
        println("P71 A14(vm): failed bubble retried with the same client_message_id")
        assertEquals(DeliveryStatus.PENDING, vm.chatState.value.messages.single().deliveryStatus)
    }
}
