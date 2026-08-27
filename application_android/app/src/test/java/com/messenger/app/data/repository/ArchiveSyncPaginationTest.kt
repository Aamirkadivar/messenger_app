package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.model.ArchiveDto
import com.messenger.app.data.model.ArchiveListResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64

/**
 * GATE 4 PHASE 4 - client-side keyset cursor and the completeness signal, JVM.
 *
 * The server-side tests prove no archive is ever skipped across ties and page
 * boundaries. What is checked here is the client half of that contract: that BOTH
 * cursor components are sent, that the loop terminates safely, and - the part that
 * used to be silently wrong - that a truncated sync is never reported as a
 * finished one.
 */
class ArchiveSyncPaginationTest {

    private companion object {
        const val CHAT = "chat-1"
        const val TOKEN = "test-token"
        val CT: String = Base64.getEncoder().encodeToString("sealed".toByteArray())
        const val PAGE = 500
    }

    private class MemoryStore : HistoryKeyringStore {
        private var blob: ByteArray? = null
        private var cache: ByteArray? = null
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() = Result.success(Unit).also { cache = null }

        private var generation: Long? = null
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    private class PassthroughVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x7F) + plaintext.copyOf())
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte()) {
                Result.success(sealed.copyOfRange(1, sealed.size))
            } else Result.failure(IllegalStateException("not sealed"))
    }

    private class NoCipher : ArchiveCipher {
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) = ByteArray(0)
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? = null
    }

    /** A TokenManager whose getAccessToken actually works (mockk cannot fake Result). */
    private fun tokens(): TokenManager = object : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success(TOKEN)
    }

    private fun sync(api: ChatApiService): ArchiveSync {
        val keyring = HistoryKeyringRepository(
            PassthroughVault(), MemoryStore(), HistoryUserProvider { "u1" }
        , HistoryArchiveFeature { true })
        return ArchiveSync(
            MessageArchiver(keyring, NoCipher(), HistoryArchiveFeature.Enabled),
            api, tokens(), HistoryArchiveFeature.Enabled,
            RecoveryTestSupport.disabledLazy(keyring)
        )
    }

    private fun row(i: Int, ts: String) = ArchiveDto(
        messageId = "msg-$i", chatId = CHAT, rootVersion = 1,
        protocolVersion = 1, ciphertextB64 = CT, createdAt = ts
    )

    private fun ok(rows: List<ArchiveDto>) =
        Response.success(ArchiveListResponse(rows, rows.size))

    // ------------------------------------------------------------------ cursor

    @Test
    fun aShortFirstPageIsCompleteAndSendsNoCursor() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        val since = slot<String?>()
        val sinceId = slot<String?>()
        coEvery { api.listArchives(any(), any(), captureNullable(since), captureNullable(sinceId)) } returns
            ok(listOf(row(1, "2026-01-01T00:00:00Z")))

        val page = sync(api).downloadFor(CHAT)
        assertTrue("a short page is the definitive end", page.complete)
        assertEquals(1, page.records.size)
        assertEquals("the first request carries no cursor", null, since.captured)
        assertEquals(null, sinceId.captured)
    }

    /** Both cursor components must advance, or ties across a boundary are lost. */
    @Test
    fun bothCursorComponentsAreSentOnTheSecondPage() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        val full = (1..PAGE).map { row(it, "2026-01-01T00:00:00Z") }
        val since = slot<String?>()
        val sinceId = slot<String?>()

        // returnsMany sequences successive calls. A second coEvery with any()
        // would shadow the first rather than follow it, so both pages must come
        // from one stub; the slots then hold the LAST call's arguments.
        coEvery {
            api.listArchives(any(), any(), captureNullable(since), captureNullable(sinceId))
        } returnsMany listOf(ok(full), ok(listOf(row(PAGE + 1, "2026-01-01T00:00:00Z"))))

        val page = sync(api).downloadFor(CHAT)
        assertEquals("2026-01-01T00:00:00Z", since.captured)
        assertEquals("the tie-break must be the last row's message id", "msg-$PAGE", sinceId.captured)
        assertTrue(page.complete)
        assertEquals(PAGE + 1, page.records.size)
    }

    @Test
    fun allRowsAcrossTwoPagesAreCollectedExactlyOnce() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        val first = (1..PAGE).map { row(it, "2026-01-01T00:00:00Z") }
        val second = (PAGE + 1..PAGE + 7).map { row(it, "2026-01-02T00:00:00Z") }
        coEvery { api.listArchives(any(), any(), any(), any()) } returnsMany
            listOf(ok(first), ok(second))

        val page = sync(api).downloadFor(CHAT)
        assertTrue(page.complete)
        assertEquals(PAGE + 7, page.records.size)
        assertEquals(
            "no record may be duplicated",
            page.records.size, page.records.map { it.messageId }.toSet().size
        )
    }

    // ------------------------------------------------------------------ completeness

    @Test
    fun aFailedRequestIsNeverReportedComplete() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        coEvery { api.listArchives(any(), any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val page = sync(api).downloadFor(CHAT)
        assertFalse("an HTTP failure is not a finished sync", page.complete)
        assertTrue(page.records.isEmpty())
    }

    @Test
    fun aThrownRequestIsNeverReportedComplete() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        coEvery { api.listArchives(any(), any(), any(), any()) } throws java.io.IOException("offline")

        val page = sync(api).downloadFor(CHAT)
        assertFalse(page.complete)
        assertTrue(page.records.isEmpty())
    }

    /** Partial progress must still be returned, and still not claimed complete. */
    @Test
    fun partialProgressIsKeptButNotClaimedComplete() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        val full = (1..PAGE).map { row(it, "2026-01-01T00:00:00Z") }
        coEvery { api.listArchives(any(), any(), any(), any()) } returnsMany listOf(
            ok(full),
            Response.error(503, okhttp3.ResponseBody.create(null, ""))
        )

        val page = sync(api).downloadFor(CHAT)
        assertEquals("what arrived must be kept", PAGE, page.records.size)
        assertFalse("but the sync is not finished", page.complete)
    }

    /** A server that never advances must not be requested forever. */
    @Test
    fun aNonAdvancingCursorTerminatesAndIsIncomplete() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        // Always the same full page: the cursor can never move past it.
        val full = (1..PAGE).map { row(it, "2026-01-01T00:00:00Z") }
        coEvery { api.listArchives(any(), any(), any(), any()) } returns ok(full)

        val page = sync(api).downloadFor(CHAT)
        assertFalse("a stalled cursor is not a completed sync", page.complete)
        assertTrue("and it must terminate rather than loop", page.records.isNotEmpty())
    }

    @Test
    fun aRowWithNoUsableCursorStopsAndIsIncomplete() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        val full = (1..PAGE).map { row(it, "") } // blank created_at
        coEvery { api.listArchives(any(), any(), any(), any()) } returns ok(full)

        val page = sync(api).downloadFor(CHAT)
        assertFalse(page.complete)
    }

    @Test
    fun anEmptyFirstPageIsComplete() = runBlocking {
        val api: ChatApiService = mockk(relaxed = true)
        coEvery { api.listArchives(any(), any(), any(), any()) } returns ok(emptyList())

        val page = sync(api).downloadFor(CHAT)
        assertTrue("nothing to sync is a finished sync", page.complete)
        assertTrue(page.records.isEmpty())
    }
}
