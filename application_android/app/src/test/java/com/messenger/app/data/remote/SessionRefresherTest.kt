package com.messenger.app.data.remote

import com.messenger.app.data.model.AuthResponse
import com.messenger.app.data.model.RefreshTokenRequest
import com.messenger.app.data.model.TokensDto
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.security.PendingRefresh
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * Gate 17 client contract.
 *
 * The scenario these exist for is the one that cannot be observed from a happy
 * path: the server commits a rotation and the reply never arrives. Recovering
 * from that requires the client to retry the SAME logical refresh, which means
 * the same old token AND the same request_id - so most of what is asserted here
 * is about identity of the request rather than success of it.
 */
class SessionRefresherTest {

    // ------------------------------------------------------------ fakes

    /**
     * Only the credential surface is modelled. It is backed by a plain map that
     * survives across "process restarts" in the tests below, standing in for the
     * encrypted preferences file, whose defining property here is that it
     * outlives the object graph.
     */
    private class FakeStore : TokenManager by mockk(relaxed = true) {
        val prefs = mutableMapOf<String, String>()
        var installCount = 0

        override suspend fun getAccessToken(): Result<String?> = Result.success(prefs["access"])
        override suspend fun getRefreshToken(): Result<String?> = Result.success(prefs["refresh"])
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(prefs["user"])

        override suspend fun savePendingRefresh(
            userId: String,
            refreshToken: String,
            requestId: String
        ): Result<Unit> {
            prefs["p_user"] = userId
            prefs["p_refresh"] = refreshToken
            prefs["p_req"] = requestId
            return Result.success(Unit)
        }

        override suspend fun getPendingRefresh(): Result<PendingRefresh?> {
            val u = prefs["p_user"] ?: return Result.success(null)
            val r = prefs["p_refresh"] ?: return Result.success(null)
            val q = prefs["p_req"] ?: return Result.success(null)
            return Result.success(PendingRefresh(u, r, q, 0L))
        }

        override suspend fun clearPendingRefresh(): Result<Unit> {
            prefs.remove("p_user"); prefs.remove("p_refresh"); prefs.remove("p_req")
            return Result.success(Unit)
        }

        override suspend fun installRefreshedTokens(
            accessToken: String,
            refreshToken: String,
            expiresAt: Long
        ): Result<Unit> {
            // Mirrors the real single-commit behaviour: successor in, pending out,
            // with no observable state in between.
            installCount++
            prefs["access"] = accessToken
            prefs["refresh"] = refreshToken
            prefs.remove("p_user"); prefs.remove("p_refresh"); prefs.remove("p_req")
            return Result.success(Unit)
        }
    }

    /** Records every request_id the client actually put on the wire. */
    private class FakeApi : AuthApiService by mockk(relaxed = true) {
        val seenRequestIds = mutableListOf<String>()
        val seenTokens = mutableListOf<String>()
        val calls = AtomicInteger(0)

        /** null = behave normally; set to simulate a lost reply or a refusal. */
        var transportFails = false
        var httpStatus: Int? = null
        var delayMillis: Long = 0
        var successor = "R1"
        /** Server-side: which token has been consumed, and what it produced. */
        val committed = mutableMapOf<String, String>()

        override suspend fun refreshToken(request: RefreshTokenRequest): Response<AuthResponse> {
            calls.incrementAndGet()
            seenRequestIds += request.requestId
            seenTokens += request.refreshToken
            if (delayMillis > 0) delay(delayMillis)

            // The server commits regardless of whether the reply survives - that
            // is the whole point of the lost-response case.
            committed[request.refreshToken] = successor

            if (transportFails) throw IOException("connection reset")
            httpStatus?.let {
                return Response.error(it, "".toResponseBody("application/json".toMediaType()))
            }
            return Response.success(
                AuthResponse(tokens = TokensDto(accessToken = "A1", refreshToken = successor, expiresIn = 3600))
            )
        }
    }

    private fun refresher(store: FakeStore, api: FakeApi) =
        SessionRefresher(store, Provider { api })

    private fun seeded(): Pair<FakeStore, FakeApi> {
        val store = FakeStore()
        store.prefs["user"] = "user-1"
        store.prefs["access"] = "A0"
        store.prefs["refresh"] = "R0"
        return store to FakeApi()
    }

    // ------------------------------------------------------------ tests

    @Test
    fun `a logical refresh sends exactly one request_id and installs the successor`() = runBlocking {
        val (store, api) = seeded()
        val outcome = refresher(store, api).refresh()

        assertTrue(outcome is SessionRefresher.Outcome.Rotated)
        assertEquals(1, api.seenRequestIds.size)
        assertTrue("request_id must be a UUID", isUuid(api.seenRequestIds.single()))
        assertEquals("R1", store.prefs["refresh"])
        assertEquals(1, store.installCount)
        assertNull("pending state must be cleared after install", store.prefs["p_req"])
    }

    /**
     * THE lost-response scenario, end to end.
     *
     * The server commits R0 -> R1 and the reply is lost. The client must come
     * back with the SAME token and the SAME request_id so the server recognises
     * one logical refresh and returns the successor it already minted.
     */
    @Test
    fun `a lost response is recovered by retrying the same token and request_id`() = runBlocking {
        val (store, api) = seeded()

        // Attempt 1: server commits, reply lost.
        api.transportFails = true
        val first = refresher(store, api).refresh()
        assertTrue(first is SessionRefresher.Outcome.RetryLater)
        assertEquals("the server did commit", "R1", api.committed["R0"])
        val firstRequestId = api.seenRequestIds.single()

        // The client must still hold the OLD credential plus its pending record -
        // never neither.
        assertEquals("R0", store.prefs["refresh"])
        assertEquals(firstRequestId, store.prefs["p_req"])

        // Attempt 2, through a REBUILT refresher: the pending record is read back
        // from storage, standing in for the object (or process) having been
        // recreated in between.
        api.transportFails = false
        val second = refresher(store, api).refresh()

        assertTrue(second is SessionRefresher.Outcome.Rotated)
        assertEquals("the retry must reuse the SAME request_id", 2, api.seenRequestIds.size)
        assertEquals(firstRequestId, api.seenRequestIds[1])
        assertEquals("the retry must resend the OLD token", "R0", api.seenTokens[1])
        assertEquals("successor installed", "R1", store.prefs["refresh"])
        assertNull(store.prefs["p_req"])
    }

    @Test
    fun `a transport retry never invents a second request_id`() = runBlocking {
        val (store, api) = seeded()
        api.transportFails = true
        val r = refresher(store, api)
        r.refresh(); r.refresh(); r.refresh()

        assertEquals(3, api.seenRequestIds.size)
        assertEquals(
            "every attempt of one logical refresh shares an id",
            1, api.seenRequestIds.toSet().size
        )
    }

    @Test
    fun `concurrent callers perform exactly one rotation and all observe it`() = runBlocking {
        val (store, api) = seeded()
        api.delayMillis = 60
        val r = refresher(store, api)

        val results = (1..8).map { async { r.refresh("A0") } }.awaitAll()

        assertEquals("exactly one network rotation", 1, api.calls.get())
        assertEquals("exactly one install", 1, store.installCount)
        assertEquals(1, api.seenRequestIds.toSet().size)
        val rotated = results.count { it is SessionRefresher.Outcome.Rotated }
        val fresh = results.count { it is SessionRefresher.Outcome.AlreadyFresh }
        assertEquals("one caller rotates", 1, rotated)
        assertEquals("the rest observe the same result", 7, fresh)
    }

    @Test
    fun `a failed refresh releases the lock so a later attempt can proceed`() = runBlocking {
        val (store, api) = seeded()
        api.transportFails = true
        val r = refresher(store, api)
        assertTrue(r.refresh() is SessionRefresher.Outcome.RetryLater)

        // If the mutex leaked, this second call would deadlock rather than run.
        api.transportFails = false
        assertTrue(r.refresh() is SessionRefresher.Outcome.Rotated)
        assertEquals("R1", store.prefs["refresh"])
    }

    @Test
    fun `a server refusal ends the session and drops the pending record`() = runBlocking {
        val (store, api) = seeded()
        api.httpStatus = 401
        val outcome = refresher(store, api).refresh()

        assertTrue(outcome is SessionRefresher.Outcome.SessionOver)
        assertNull("a refused credential must not leave a pending record to hammer with",
            store.prefs["p_req"])
    }

    /**
     * A 5xx is the server failing, not the server judging the credential. Its
     * transaction rolled back, so the token is still current - and it may even
     * have committed a rotation whose successor is sitting in the replay cache.
     * Discarding the pending record here would throw away the only thing that
     * can recover it, and would make the next attempt a SECOND logical refresh.
     */
    @Test
    fun `a server-side failure keeps the pending record for a same-id retry`() = runBlocking {
        val (store, api) = seeded()
        api.httpStatus = 500
        val r = refresher(store, api)
        val outcome = r.refresh()

        assertTrue("a 5xx is not a credential verdict", outcome is SessionRefresher.Outcome.RetryLater)
        assertEquals("the old credential must be retained", "R0", store.prefs["refresh"])
        assertNotNull("the pending record must survive a server fault", store.prefs["p_req"])
        val firstRequestId = api.seenRequestIds.single()

        // The retry must be the SAME logical refresh, so it can pick up a
        // successor the server may already have committed.
        api.httpStatus = null
        val second = r.refresh()
        assertTrue(second is SessionRefresher.Outcome.Rotated)
        assertEquals("the retry must reuse the same request_id", firstRequestId, api.seenRequestIds[1])
        assertEquals("the retry must resend the same credential", "R0", api.seenTokens[1])
        assertEquals("R1", store.prefs["refresh"])
    }

    @Test
    fun `a 403 still ends the session`() = runBlocking {
        val (store, api) = seeded()
        api.httpStatus = 403
        assertTrue(refresher(store, api).refresh() is SessionRefresher.Outcome.SessionOver)
        assertNull(store.prefs["p_req"])
    }

    @Test
    fun `logout invalidates pending refresh state`() = runBlocking {
        val (store, api) = seeded()
        api.transportFails = true
        val r = refresher(store, api)
        r.refresh()
        assertNotNull(store.prefs["p_req"])

        r.invalidatePending()
        assertNull(store.prefs["p_req"])
        assertNull(store.prefs["p_user"])
        assertNull(store.prefs["p_refresh"])
    }

    /**
     * A pending record belonging to a previous account must never be replayed for
     * the current one: its request_id names a rotation on a session this account
     * does not own.
     */
    @Test
    fun `a pending record from another account is not reused`() = runBlocking {
        val (store, api) = seeded()
        api.transportFails = true
        refresher(store, api).refresh()
        val otherAccountsRequestId = store.prefs["p_req"]!!

        // Account switch: same device, different user, different credential.
        store.prefs["user"] = "user-2"
        store.prefs["refresh"] = "R0-user2"
        api.transportFails = false

        val outcome = refresher(store, api).refresh()
        assertTrue(outcome is SessionRefresher.Outcome.Rotated)
        assertNotEquals(
            "the new account must start its own logical refresh",
            otherAccountsRequestId, api.seenRequestIds.last()
        )
        assertEquals("and must send its own credential", "R0-user2", api.seenTokens.last())
    }

    @Test
    fun `no refresh token means the session is over rather than a blank request`() = runBlocking {
        val store = FakeStore().apply { prefs["user"] = "user-1" }
        val api = FakeApi()
        assertTrue(refresher(store, api).refresh() is SessionRefresher.Outcome.SessionOver)
        assertEquals("nothing may be sent", 0, api.calls.get())
    }

    private fun isUuid(s: String) = runCatching { UUID.fromString(s) }.isSuccess
}
