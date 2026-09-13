package com.messenger.app.data.repository

import com.messenger.app.data.model.MessageDto
import com.messenger.app.data.model.MessagesResponse
import com.messenger.app.data.remote.TokenRefreshAuthenticator
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.di.AppModule
import com.messenger.app.security.TokenManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * PHASE 71 - reconnect catch-up (Android criterion 17) against a model of the Phase 71 server:
 * a chat whose messages carry a gap-free seq, `?after=` ascending and `?before=` descending, with
 * an exact has_more. The last test pins the wire contract through the production HTTP stack.
 */
class ChatSyncPagerTest {

    /** The server's view of one chat, and a device's local cache and stored sync points. */
    private class World(total: Int) {
        val server = (1..total).map { seq -> msg(seq.toLong()) }
        val local = linkedMapOf<String, MessageDto>()
        val points = mutableMapOf<String, ChatSyncPager.SyncPoint>()
        val requests = mutableListOf<String>()
        var serverSeqless = false

        fun fetch(before: Long?, after: Long?, limit: Int): MessagesResponse {
            requests += "before=$before after=$after limit=$limit"
            val rows = when {
                after != null -> server.filter { it.seq > after }.sortedBy { it.seq }
                before != null -> server.filter { it.seq < before }.sortedByDescending { it.seq }
                else -> server.sortedByDescending { it.seq }
            }
            val page = rows.take(limit).map { if (serverSeqless) it.copy(seq = 0) else it }
            return MessagesResponse(data = page, hasMore = rows.size > limit)
        }

        fun pager(pageSize: Int = 50, maxPages: Int = 50, maxBackfillPages: Int = 20) = ChatSyncPager(
            fetch = { _, before, after, limit -> fetch(before, after, limit) },
            ingest = { _, rows -> rows.forEach { local[it.id] = it } },
            isKnownLocally = { id -> local.containsKey(id) },
            hasLocalHistory = { _ -> local.isNotEmpty() },
            loadSyncPoint = { chat -> points[chat] },
            saveSyncPoint = { chat, point ->
                val old = points[chat]
                // The stored point never moves backwards.
                if (old != null) assertTrue("sync point moved backwards: $old -> $point", point.lastSeq >= old.lastSeq)
                points[chat] = point
            },
            pageSize = pageSize,
            maxPages = maxPages,
            maxBackfillPages = maxBackfillPages
        )

        fun localSeqs() = local.values.map { it.seq }.sorted()

        companion object {
            fun msg(seq: Long) = MessageDto(
                id = "m-$seq", chatId = CHAT, senderId = "peer", content = "c$seq",
                createdAt = "2026-09-11T10:00:00Z", seq = seq
            )
        }
    }

    private companion object {
        const val CHAT = "chat-under-test"
    }

    @Test
    fun catchUpContinuesUntilSynchronised() = runBlocking {
        val w = World(237)
        (1..12).forEach { w.local["m-$it"] = World.msg(it.toLong()) }
        w.points[CHAT] = ChatSyncPager.SyncPoint(12)
        val out = w.pager(pageSize = 50).sync(CHAT)
        println("P71 A17: pages=${out.pages} ingested=${out.messages} point=${out.syncPoint} complete=${out.complete}")
        assertTrue(out.complete)
        assertEquals(225, out.messages)
        assertEquals((1L..237L).toList(), w.localSeqs())
        assertEquals(237L, w.points.getValue(CHAT).lastSeq)
        assertEquals(5, out.pages) // 225 rows at 50 per page
    }

    @Test
    fun aBoundedPassResumesExactlyWhereItStopped() = runBlocking {
        val w = World(300)
        w.local["m-1"] = World.msg(1)
        w.points[CHAT] = ChatSyncPager.SyncPoint(1)
        val first = w.pager(pageSize = 40, maxPages = 2).sync(CHAT)
        assertFalse(first.complete)
        assertEquals(81L, w.points.getValue(CHAT).lastSeq)
        var passes = 1
        while (!(w.pager(pageSize = 40, maxPages = 2).sync(CHAT).complete)) passes++
        println("P71 A17b: first pass stopped at 81, finished after ${passes + 1} passes")
        assertEquals((1L..300L).toList(), w.localSeqs())
    }

    @Test
    fun aChatWithNoLocalHistoryStartsAtTheHeadWithoutBulkDownload() = runBlocking {
        val w = World(5000)
        val out = w.pager(pageSize = 100).sync(CHAT)
        println("P71 A17c: no local history -> ingested ${out.messages}, point=${out.syncPoint}")
        assertEquals(100, out.messages)
        assertEquals(ChatSyncPager.SyncPoint(5000, null), w.points[CHAT])
    }

    @Test
    fun aGapBehindTheHeadIsBackfilledDownToTheLocalCache() = runBlocking {
        // The device holds 1..40, then went offline while 41..400 arrived. No stored point yet
        // (first run after upgrade): the newest page alone would leave 41..300 unfetched.
        val w = World(400)
        (1..40).forEach { w.local["m-$it"] = World.msg(it.toLong()) }
        val out = w.pager(pageSize = 100).sync(CHAT)
        println("P71 A17d: gap backfill -> ingested ${out.messages}, point=${out.syncPoint}, complete=${out.complete}")
        assertTrue(out.complete)
        assertEquals((1L..400L).toList(), w.localSeqs())
        assertEquals(ChatSyncPager.SyncPoint(400, null), w.points[CHAT])
    }

    @Test
    fun backfillIsBoundedAndResumable() = runBlocking {
        val w = World(1000)
        (1..10).forEach { w.local["m-$it"] = World.msg(it.toLong()) }
        var out = w.pager(pageSize = 100, maxBackfillPages = 2).sync(CHAT)
        assertFalse(out.complete)
        assertEquals(701L, w.points.getValue(CHAT).backfillBefore)
        var passes = 1
        while (!out.complete) {
            out = w.pager(pageSize = 100, maxBackfillPages = 2).sync(CHAT)
            passes++
        }
        println("P71 A17e: backfill finished in $passes bounded passes")
        assertEquals((1L..1000L).toList(), w.localSeqs())
        assertNull(w.points.getValue(CHAT).backfillBefore)
    }

    @Test
    fun aServerWithoutSeqCannotRewindOrLoop() = runBlocking {
        val w = World(120)
        w.serverSeqless = true
        w.points[CHAT] = ChatSyncPager.SyncPoint(0)
        val out = w.pager(pageSize = 50).sync(CHAT)
        assertTrue(out.complete)
        assertEquals("one page, then stop: seq 0 is not progress", 1, out.pages)
    }

    /** The wire contract through the production OkHttp client and Json. */
    @Test
    fun catchUpRequestsAfterAndParsesTheCursor() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"m-13","chat_id":"$CHAT","sender_id":"p","content":"c","encrypted":true,""" +
                        """"created_at":"2026-09-11T10:00:00Z","seq":13}],"total":13,"limit":100,"order":"asc",""" +
                        """"has_more":false,"cursor":{"before":13,"after":13}}"""
                )
            )
            val tokens = object : TokenManager by mockk(relaxed = true) {
                override suspend fun getOrCreateDeviceId(): Result<String> = Result.success("dev")
            }
            val api = Retrofit.Builder()
                .baseUrl(server.url("/api/v1/"))
                .client(AppModule.provideOkHttpClient(
                    mockk<TokenRefreshAuthenticator> { every { authenticate(any(), any()) } returns null }, tokens
                ))
                .addConverterFactory(AppModule.provideJson().asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ChatApiService::class.java)
            val response = api.getMessages("Bearer t", CHAT, 100, before = null, after = "12")
            val recorded = server.takeRequest()
            println("P71 A17f: ${recorded.path}")
            assertEquals("/api/v1/messages/$CHAT?limit=100&after=12", recorded.path)
            val body = response.body()!!
            assertEquals(13L, body.data.single().seq)
            assertEquals("asc", body.order)
            assertFalse(body.hasMore)
        } finally {
            server.shutdown()
        }
    }
}
