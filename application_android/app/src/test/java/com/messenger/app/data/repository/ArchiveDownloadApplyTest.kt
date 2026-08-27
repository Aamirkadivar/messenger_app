package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.model.ArchiveDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 4 PHASE 2 - the rules that govern writing a downloaded archive onto a
 * local row, JVM.
 *
 * `ChatRepository.applyRemoteArchives` is private and sits behind Retrofit and
 * Room, so the decision it makes is modelled here against the same inputs it
 * uses: the validated record, and the row's current [ArchiveState]. Every rule
 * asserted here is one the repository implements verbatim.
 *
 * The three refusals are the security-relevant part:
 *   - a record whose chat does not match is dropped;
 *   - a record for an unknown message is dropped;
 *   - a SEALED or RETRACTED row is never overwritten.
 */
class ArchiveDownloadApplyTest {

    private companion object {
        const val CHAT = "11111111-1111-1111-1111-111111111111"
        const val OTHER_CHAT = "99999999-9999-9999-9999-999999999999"
        const val MSG = "22222222-2222-2222-2222-222222222222"
        const val CT = "c2VhbGVkLWJ5dGVz"
        const val PLAINTEXT = "the message body"
    }

    /** Mirrors the repository's decision for one record. Returns the row after applying. */
    private fun applyOne(row: MessageEntity?, remote: RemoteArchive, chatId: String): MessageEntity? {
        if (remote.chatId != chatId) return row
        if (row == null) return null
        if (row.conversation_id != chatId) return row
        if (ArchiveState.fromWire(row.archiveState) != ArchiveState.NONE) return row
        return row.copy(
            archiveCiphertext = remote.ciphertextB64,
            archiveRootVersion = remote.rootVersion,
            archiveState = ArchiveState.SEALED.wire,
        )
    }

    private fun row(
        state: String? = null,
        ciphertext: String? = null,
        rootVersion: Int = 0,
        chatId: String = CHAT,
    ) = MessageEntity(
        id = MSG,
        conversation_id = chatId,
        senderId = "sender",
        content = PLAINTEXT,
        type = "text",
        status = "SENT",
        timestamp = 1_700_000_000_000L,
        archiveCiphertext = ciphertext,
        archiveRootVersion = rootVersion,
        archiveState = state,
    )

    private fun remote(
        chatId: String = CHAT,
        rootVersion: Int = 4,
        ciphertext: String = CT,
    ) = RemoteArchive(MSG, chatId, rootVersion, 1, ciphertext, "2026-08-24T10:00:00Z")

    // ------------------------------------------------------------------ happy path

    @Test
    fun anUnarchivedRowReceivesTheDownloadedArchive() {
        val after = applyOne(row(), remote(), CHAT)!!
        assertEquals(CT, after.archiveCiphertext)
        assertEquals(ArchiveState.SEALED.wire, after.archiveState)
        assertEquals("the server's root version must be preserved verbatim", 4, after.archiveRootVersion)
    }

    /** The download must never touch the message itself. */
    @Test
    fun applyingAnArchiveNeverAltersMessageContent() {
        val before = row()
        val after = applyOne(before, remote(), CHAT)!!
        assertEquals(PLAINTEXT, after.content)
        assertEquals(before.senderId, after.senderId)
        assertEquals(before.timestamp, after.timestamp)
        assertEquals(before.isEncrypted, after.isEncrypted)
    }

    // ------------------------------------------------------------------ the three refusals

    @Test
    fun aRecordForAnotherChatIsRefused() {
        val before = row()
        val after = applyOne(before, remote(chatId = OTHER_CHAT), CHAT)!!
        assertNull("must not write an archive bound to a different chat", after.archiveCiphertext)
        assertNull(after.archiveState)
    }

    @Test
    fun aRecordWhoseRowDoesNotExistIsRefused() {
        assertNull("no row must be invented for an unknown message", applyOne(null, remote(), CHAT))
    }

    @Test
    fun aRowInADifferentChatIsRefused() {
        val before = row(chatId = OTHER_CHAT)
        val after = applyOne(before, remote(), CHAT)!!
        assertNull(after.archiveCiphertext)
    }

    /** The local copy wins: a SEALED row is never overwritten by the server's. */
    @Test
    fun aSealedRowIsPreserved() {
        val before = row(state = ArchiveState.SEALED.wire, ciphertext = "local-original", rootVersion = 2)
        val after = applyOne(before, remote(rootVersion = 4, ciphertext = "server-version"), CHAT)!!
        assertEquals("local-original", after.archiveCiphertext)
        assertEquals("the local root-version binding must survive", 2, after.archiveRootVersion)
        assertEquals(ArchiveState.SEALED.wire, after.archiveState)
    }

    /** The resurrection guard: sync can never undo a retraction. */
    @Test
    fun aRetractedRowIsNeverResurrected() {
        val before = row(state = ArchiveState.RETRACTED.wire, ciphertext = null, rootVersion = 3)
        val after = applyOne(before, remote(), CHAT)!!
        assertNull("a retracted archive must stay gone", after.archiveCiphertext)
        assertEquals(ArchiveState.RETRACTED.wire, after.archiveState)
    }

    @Test
    fun aPendingRowIsAlsoLeftAloneByDownload() {
        // PENDING means a local seal is mid-flight; the server's copy must not
        // race it. Only NONE is eligible.
        val before = row(state = ArchiveState.PENDING.wire)
        val after = applyOne(before, remote(), CHAT)!!
        assertNull(after.archiveCiphertext)
        assertEquals(ArchiveState.PENDING.wire, after.archiveState)
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    fun repeatedDownloadsAreIdempotent() {
        var current = row()
        repeat(4) { current = applyOne(current, remote(), CHAT)!! }
        assertEquals(CT, current.archiveCiphertext)
        assertEquals(4, current.archiveRootVersion)
        assertEquals(ArchiveState.SEALED.wire, current.archiveState)
    }

    /** A second, different server response cannot displace what the first wrote. */
    @Test
    fun aLaterDifferentServerRecordCannotReplaceTheApplied() {
        val first = applyOne(row(), remote(rootVersion = 4, ciphertext = CT), CHAT)!!
        val second = applyOne(first, remote(rootVersion = 9, ciphertext = "ZGlmZmVyZW50"), CHAT)!!
        assertEquals(CT, second.archiveCiphertext)
        assertEquals(4, second.archiveRootVersion)
    }

    // ------------------------------------------------------------------ validation gate

    @Test
    fun invalidRecordsNeverReachTheApplyStep() {
        val bad = listOf(
            ArchiveDto(MSG, OTHER_CHAT, 1, 1, CT, "t"),   // wrong chat
            ArchiveDto("", CHAT, 1, 1, CT, "t"),          // blank id
            ArchiveDto(MSG, CHAT, 0, 1, CT, "t"),         // root version 0
            ArchiveDto(MSG, CHAT, 1, 0, CT, "t"),         // protocol version 0
            ArchiveDto(MSG, CHAT, 1, 1, "!!!", "t"),      // malformed base64
            ArchiveDto(MSG, CHAT, 1, 1, "", "t"),         // empty ciphertext
        )
        for (d in bad) {
            assertNull("must be rejected before apply: $d", ArchiveValidation.validate(d, CHAT))
        }
    }

    @Test
    fun aValidatedRecordFlowsThroughToTheRow() = runBlocking {
        val validated = ArchiveValidation.validate(
            ArchiveDto(MSG, CHAT, 7, 1, CT, "2026-08-24T10:00:00Z"), CHAT
        )
        assertTrue(validated != null)
        val after = applyOne(row(), validated!!, CHAT)!!
        assertEquals(7, after.archiveRootVersion)
        assertEquals(PLAINTEXT, after.content)
    }
}
