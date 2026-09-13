package com.messenger.app.data.model

import com.messenger.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `GET /messages/{chat}` page carries a `cursor` object whose type depends on the
 * backend lineage: the deployed dd4a379 server sends message UUIDs (or "" on an empty
 * page), the Phase 71 server sends int64 seqs. The client declared it `Long?`, so every
 * page from the deployed server failed at `$.cursor.after` and was discarded whole,
 * before caching (Phase 72N-X on the S20 FE: 156 x HTTP 200, 0 rows cached).
 *
 * Nothing reads the cursor - ChatSyncPager advances on `data[].seq` - so the DTO does
 * not declare it, and the production Json's ignoreUnknownKeys skips it whatever its
 * type. These pin that a page from either lineage delivers its messages, through the
 * same Json the Retrofit converter uses. Synthetic values only.
 */
class MessagesResponseCursorTest {

    private val json = AppModule.provideJson()

    @Test
    fun deployedServerPageWithUuidCursorDeliversItsMessages() {
        // Key order as the deployed server writes it: Go marshals a map sorted, so
        // `cursor` precedes `data` - which is why the old parse died at offset 19.
        val page = json.decodeFromString<MessagesResponse>(
            """{"cursor":{"after":"$ID_2","before":"$ID_1"},""" +
                """"data":[${deployedRow(ID_1)},${deployedRow(ID_2)}],""" +
                """"has_more":true,"limit":100,"offset":0,"total":262}"""
        )
        assertEquals(listOf(ID_1, ID_2), page.data.map { it.id })
        assertEquals(262L, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun deployedServerEmptyPageWithUuidCursorParses() {
        val page = json.decodeFromString<MessagesResponse>(
            """{"data":[],"cursor":{"before":"$ID_1","after":"$ID_2"}}"""
        )
        assertTrue(page.data.isEmpty())
    }

    @Test
    fun phase71ServerPageWithSeqCursorStillDeliversItsMessages() {
        val page = json.decodeFromString<MessagesResponse>(
            """{"data":[{"id":"m-13","chat_id":"$CHAT","sender_id":"$PEER","content":"synthetic",""" +
                """"encrypted":true,"created_at":"2026-09-11T10:00:00Z","seq":13}],""" +
                """"total":13,"limit":100,"order":"asc","has_more":false,"cursor":{"before":13,"after":13}}"""
        )
        assertEquals(13L, page.data.single().seq)
        assertEquals("asc", page.order)
        assertFalse(page.hasMore)
    }

    /** One row exactly as the deployed GetMessages builds it (its DecryptedMessage struct). */
    private fun deployedRow(id: String) =
        """{"id":"$id","chat_id":"$CHAT","sender_id":"$PEER",""" +
            """"sender":{"id":"$PEER","email":"peer@example.invalid","username":"peer","display_name":"Peer"},""" +
            """"content":"synthetic","encrypted":true,"file_size":0,"duration_ms":0,"thumbnail_url":"",""" +
            """"key_version":0,"encryption_version":4,"sender_device_id":"","is_forwarded":false,""" +
            """"forwarded_from_name":"","type":"text","delivered_at":null,"read_at":null,""" +
            """"created_at":"2026-09-13T10:00:00Z","updated_at":"2026-09-13T10:00:00Z"}"""

    private companion object {
        const val CHAT = "00000000-0000-0000-0000-00000000c4a7"
        const val PEER = "00000000-0000-0000-0000-00000000beef"
        const val ID_1 = "00000000-0000-0000-0000-000000000001"
        const val ID_2 = "00000000-0000-0000-0000-000000000002"
    }
}
