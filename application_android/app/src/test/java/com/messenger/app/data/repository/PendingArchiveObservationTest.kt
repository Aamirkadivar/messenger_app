package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wiring test for the observation added in Phase 28.
 *
 * `pendingArchiveCount` existed since Phase 26 with no caller, so a stalled archive - the locked
 * vault case - was visible only as a per-message warning at the instant it failed. Nothing could
 * say afterwards that anything was still outstanding.
 *
 * These exercise the repository accessor that the refresh now reads. The classification itself is
 * already pinned by `MessageArchiveStatusTest`; what is tested here is that the count aggregates it
 * correctly over a chat, which is what the observation reports.
 */
class PendingArchiveObservationTest {

    private val chat = "027f8fe0-94ca-4f78-9852-a373a776ae06"
    private val messageDao: ScopedMessageDao = mockk(relaxed = true)

    private fun repo(): ChatRepository {
        val tokens: TokenManager = mockk(relaxed = true)
        coEvery { tokens.getCurrentUserId() } returns Result.success("owner-id")
        return ChatRepository(
            chatApiService = mockk<ChatApiService>(relaxed = true),
            messageDao = messageDao,
            conversationDao = mockk<ScopedConversationDao>(relaxed = true),
            cachedChatDao = mockk<ScopedCachedChatDao>(relaxed = true),
            webSocketManager = mockk<WebSocketManager>(relaxed = true),
            tokenManager = tokens,
            groupRepository = mockk(relaxed = true),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    private fun row(
        id: String,
        isEncrypted: Boolean = false,
        content: String = "readable text",
        fileType: String? = "text",
        archiveState: String? = null,
        archiveCiphertext: String? = null,
    ) = MessageEntity(
        id = id,
        conversation_id = chat,
        senderId = "ecfb93c5-0000-4000-8000-000000000002",
        content = content,
        type = "text",
        status = "SENT",
        timestamp = 1_756_000_000_000L,
        isEncrypted = isEncrypted,
        encryptionVersion = 6,
        senderDeviceId = "ead6306e-0000-4000-8000-000000000003",
        fileType = fileType,
        archiveState = archiveState,
        archiveCiphertext = archiveCiphertext,
    )

    private fun countWith(rows: List<MessageEntity>): Int {
        coEvery { messageDao.getAllMessagesByConversation(chat) } returns rows
        return runBlocking { repo().pendingArchiveCount(chat) }
    }

    /** A. Nothing outstanding: a fully archived chat reports zero. */
    @Test
    fun aFullyArchivedChatReportsZero() {
        assertEquals(
            0,
            countWith(
                listOf(
                    row("a", archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk"),
                    row("b", archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk"),
                )
            )
        )
    }

    /** B. The locked-vault shape: readable and eligible, no archive. */
    @Test
    fun oneEligibleUnarchivedMessageIsCounted() {
        assertEquals(
            1,
            countWith(
                listOf(
                    row("a", archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk"),
                    row("b"),
                )
            )
        )
    }

    /** C. After the existing retry seals it, the count falls back to zero. */
    @Test
    fun theCountFallsToZeroOnceTheArchiveIsSealed() {
        val before = countWith(listOf(row("b")))
        val after = countWith(
            listOf(row("b", archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk"))
        )

        assertEquals(1, before)
        assertEquals(0, after)
    }

    /**
     * D. Rows that were never archivable must not inflate the number. A count that includes them
     * would sit permanently above zero and stop meaning anything.
     */
    @Test
    fun ineligibleRowsDoNotInflateTheCount() {
        assertEquals(
            0,
            countWith(
                listOf(
                    row("enc", isEncrypted = true, content = "00010002ab"),
                    row("media", fileType = "image"),
                    row("blank", content = ""),
                    row("placeholder", content = ChatRepository.ENCRYPTED_PLACEHOLDER),
                    row("retracted", archiveState = ArchiveState.RETRACTED.wire, archiveCiphertext = "c2VhbGVk"),
                )
            )
        )
    }

    /** Mixed traffic: only the eligible-and-unarchived rows are counted. */
    @Test
    fun onlyPendingRowsAreCountedInAMixedChat() {
        assertEquals(
            2,
            countWith(
                listOf(
                    row("sealed", archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk"),
                    row("pending1"),
                    row("encrypted", isEncrypted = true, content = "00010002ab"),
                    row("pending2"),
                    row("retracted", archiveState = ArchiveState.RETRACTED.wire, archiveCiphertext = "c2VhbGVk"),
                )
            )
        )
    }

    /** An empty chat is not a stalled one. */
    @Test
    fun anEmptyChatReportsZero() {
        assertEquals(0, countWith(emptyList()))
    }
}
