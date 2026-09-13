package com.messenger.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat-list preview decrypts `last_message`, and for MLS (v6) it must be
 * able to tell this device's own row from a peer's - handing our own ciphertext
 * to OpenMLS earns "Cannot decrypt own messages" on every refresh.
 *
 * That decision needs `sender_device_id`, which the DTO did not carry. These
 * pin the wire contract in both directions: present and absent.
 */
class LastMessageDtoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun senderDeviceIdIsParsedFromTheWire() {
        val dto = json.decodeFromString<LastMessageDto>(
            """
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "sender_id": "22222222-2222-4222-8222-222222222222",
              "content": "deadbeef",
              "encrypted": true,
              "key_version": 0,
              "encryption_version": 6,
              "sender_device_id": "device-123",
              "created_at": "2026-09-04T12:00:00Z"
            }
            """.trimIndent()
        )
        assertEquals("device-123", dto.senderDeviceId)
        assertEquals(6, dto.encryptionVersion)
    }

    @Test
    fun senderDeviceIdDefaultsToEmptyWhenTheServerOmitsIt() {
        // Backward compatibility: an older server sends no such field, and the
        // client must still parse the row rather than throwing.
        val dto = json.decodeFromString<LastMessageDto>(
            """
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "sender_id": "22222222-2222-4222-8222-222222222222",
              "content": "deadbeef",
              "encrypted": true,
              "encryption_version": 6,
              "created_at": "2026-09-04T12:00:00Z"
            }
            """.trimIndent()
        )
        assertEquals("", dto.senderDeviceId)
    }

    @Test
    fun existingFieldsAreUnaffected() {
        val dto = json.decodeFromString<LastMessageDto>(
            """
            {
              "id": "abc",
              "sender_id": "def",
              "content": "cafe",
              "content_type": "text",
              "encrypted": true,
              "key_version": 3,
              "encryption_version": 4,
              "sender_device_id": "d1",
              "created_at": "2026-09-04T12:00:00Z"
            }
            """.trimIndent()
        )
        assertEquals("abc", dto.id)
        assertEquals("def", dto.senderId)
        assertEquals("cafe", dto.content)
        assertEquals(3, dto.keyVersion)
        assertEquals(4, dto.encryptionVersion)
        assertEquals("d1", dto.senderDeviceId)
    }
}
