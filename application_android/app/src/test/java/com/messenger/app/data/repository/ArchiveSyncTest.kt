package com.messenger.app.data.repository

import com.messenger.app.data.model.ArchiveDto
import com.messenger.app.data.model.ArchiveListResponse
import com.messenger.app.data.model.ArchiveUploadRequest
import com.messenger.app.data.model.ArchiveUploadResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * GATE 4 PHASE 2 - archive wire contract and server-data validation, JVM.
 *
 * Covers the DTO contract against the exact JSON the Go handlers emit, and the
 * validation gate that everything from the server must pass before it is allowed
 * anywhere near Room.
 *
 * The network round-trip itself and the Room write are covered by
 * ArchiveDownloadApplyTest; here the concern is the boundary.
 */
class ArchiveSyncTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private companion object {
        const val CHAT = "11111111-1111-1111-1111-111111111111"
        const val MSG = "22222222-2222-2222-2222-222222222222"
        val CT: String = Base64.getEncoder().encodeToString("sealed-bytes".toByteArray())
    }

    // ------------------------------------------------------------------ wire contract

    /** Exactly the JSON handlers/archive.go emits for an upload. */
    @Test
    fun uploadResponseParsesServerJson() {
        val body = """{"message_id":"$MSG","chat_id":"$CHAT","stored":true}"""
        val parsed = json.decodeFromString<ArchiveUploadResponse>(body)
        assertEquals(MSG, parsed.messageId)
        assertEquals(CHAT, parsed.chatId)
        assertTrue(parsed.stored)
    }

    /** `stored=false` is successful idempotency, not an error - it must parse cleanly. */
    @Test
    fun uploadResponseHandlesIdempotentStoredFalse() {
        val body = """{"message_id":"$MSG","chat_id":"$CHAT","stored":false}"""
        assertEquals(false, json.decodeFromString<ArchiveUploadResponse>(body).stored)
    }

    @Test
    fun uploadRequestSerialisesWithSnakeCaseKeys() {
        val encoded = json.encodeToString(
            ArchiveUploadRequest(
                messageId = MSG, rootVersion = 3, protocolVersion = 1, ciphertextB64 = CT
            )
        )
        for (key in listOf("message_id", "root_version", "protocol_version", "ciphertext_b64")) {
            assertTrue("missing $key in $encoded", encoded.contains("\"$key\""))
        }
        // And nothing secret may appear in an upload body.
        for (bad in listOf("root\":", "history_root", "keyring", "plaintext", "session_mk",
            "snapshot", "sender_key", "master_key")) {
            assertTrue("upload body leaked $bad: $encoded", !encoded.contains(bad))
        }
    }

    @Test
    fun listResponseParsesServerJson() {
        val body = """{"archives":[{"message_id":"$MSG","chat_id":"$CHAT","root_version":2,
            "protocol_version":1,"ciphertext_b64":"$CT","created_at":"2026-08-24T10:00:00Z"}],
            "count":1}""".trimIndent().replace("\n", "")
        val parsed = json.decodeFromString<ArchiveListResponse>(body)
        assertEquals(1, parsed.count)
        assertEquals(MSG, parsed.archives[0].messageId)
        assertEquals(2, parsed.archives[0].rootVersion)
    }

    @Test
    fun listResponseToleratesUnknownAndMissingFields() {
        // Forward compatibility: a future server field must not break parsing,
        // and an absent optional must not throw.
        val body = """{"archives":[{"message_id":"$MSG","chat_id":"$CHAT","root_version":1,
            "protocol_version":1,"ciphertext_b64":"$CT","created_at":"x","future_field":9}]}"""
            .trimIndent().replace("\n", "")
        val parsed = json.decodeFromString<ArchiveListResponse>(body)
        assertEquals(1, parsed.archives.size)
        assertEquals(0, parsed.count)
    }

    @Test
    fun emptyListParses() {
        val parsed = json.decodeFromString<ArchiveListResponse>("""{"archives":[],"count":0}""")
        assertTrue(parsed.archives.isEmpty())
    }

    // ------------------------------------------------------------------ validation

    private fun dto(
        messageId: String = MSG,
        chatId: String = CHAT,
        rootVersion: Int = 1,
        protocolVersion: Int = 1,
        ciphertextB64: String = CT,
    ) = ArchiveDto(messageId, chatId, rootVersion, protocolVersion, ciphertextB64, "2026-08-24T10:00:00Z")

    // The PRODUCTION validation object - ArchiveSync delegates to exactly this,
    // so these assertions cover the real gate rather than a copy of it.
    private fun validate(d: ArchiveDto, expectedChat: String = CHAT) =
        ArchiveValidation.validate(d, expectedChat)

    @Test
    fun wellFormedRecordIsAccepted() {
        val ok = validate(dto())
        assertNotNull(ok)
        assertEquals(MSG, ok!!.messageId)
        assertEquals(1, ok.rootVersion)
    }

    /** The core cross-binding defence: the server does not decide which chat a record is for. */
    @Test
    fun recordForADifferentChatIsRejected() {
        assertNull(validate(dto(chatId = "33333333-3333-3333-3333-333333333333")))
    }

    @Test
    fun blankIdentifiersAreRejected() {
        assertNull(validate(dto(messageId = "")))
        assertNull(validate(dto(chatId = ""), expectedChat = ""))
    }

    /** Version 0 means "no archive"; the server may not assert it. */
    @Test
    fun invalidVersionsAreRejected() {
        assertNull(validate(dto(rootVersion = 0)))
        assertNull(validate(dto(rootVersion = -1)))
        assertNull(validate(dto(protocolVersion = 0)))
        assertNull(validate(dto(protocolVersion = -2)))
    }

    @Test
    fun malformedCiphertextIsRejected() {
        assertNull(validate(dto(ciphertextB64 = "")))
        assertNull(validate(dto(ciphertextB64 = "!!!not base64!!!")))
        // Valid base64 that decodes to nothing is equally useless.
        assertNull(validate(dto(ciphertextB64 = "")))
    }

    @Test
    fun oneBadRecordDoesNotDiscardTheGoodOnes() {
        val page = listOf(
            dto(messageId = "m1"),
            dto(messageId = "m2", rootVersion = 0),        // invalid
            dto(messageId = "m3", ciphertextB64 = "!!!"),  // invalid
            dto(messageId = "m4"),
        )
        val kept = page.mapNotNull { validate(it) }
        assertEquals(listOf("m1", "m4"), kept.map { it.messageId })
    }
}
