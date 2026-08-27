package com.messenger.app.data.remote

import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.di.AppModule
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * GATE 5 PHASE 5 - the history-keyring HTTP contract over the PRODUCTION stack.
 *
 * Every other client suite talks to a hand-written fake that IMPLEMENTS
 * [ChatApiService]. That is the right tool for behaviour, but it is structurally
 * incapable of catching the class of bug that lives in the annotations and the
 * interceptor chain: a wrong verb, a wrong path, a missing header, a field that
 * serialises under the wrong name. A fake satisfies the Kotlin signature and
 * never consults any of them.
 *
 * The earlier version of this suite built a bare `Retrofit.Builder()` and so did
 * not exercise the production OkHttp chain at all - which meant a missing
 * `X-Device-Id` would have sailed through, even though the server rejects all
 * three keyring routes without it. This version calls the REAL
 * [AppModule.provideOkHttpClient] and [AppModule.provideJson], so the actual
 * device-id interceptor and the actual serializer configuration are under test.
 *
 * No production service is contacted: [MockWebServer] is an in-process loopback
 * socket that exists only for the duration of each test.
 */
class HistoryKeyringHttpContractTest {

    private companion object {
        const val DEVICE = "device-under-test"
    }

    /**
     * Hand-written rather than stubbed. MockK cannot express a suspend function
     * returning `Result` - an inline value class - and produces a
     * ClassCastException at the call site; delegation gives a real override.
     */
    private class Tokens(var deviceId: String) : TokenManager by mockk(relaxed = true) {
        override suspend fun getOrCreateDeviceId(): Result<String> = Result.success(deviceId)
    }

    private lateinit var server: MockWebServer
    private lateinit var api: ChatApiService
    private lateinit var tokens: Tokens

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // The interceptor asks TokenManager for the device id; everything else on
        // that interface is irrelevant to the HTTP contract.
        tokens = Tokens(DEVICE)

        val client = AppModule.provideOkHttpClient(mockk(relaxed = true), tokens)
        val json: Json = AppModule.provideJson()
        api = Retrofit.Builder()
            // The trailing path mirrors production's versioned base URL, so a
            // relative annotation that escapes the prefix shows up here.
            .baseUrl(server.url("/api/v1/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChatApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------- device identity

    /**
     * The gap this suite existed to close. All three keyring routes sit behind
     * `RequireDeviceIdentity`, which answers 400 without this header.
     */
    @Test
    fun everyKeyringRequestCarriesTheDeviceHeader(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"ciphertext_b64":"YWJj"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":2,"created":false}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"deleted":true}"""))

        api.getHistoryKeyring("Bearer t")
        api.putHistoryKeyring("Bearer t", HistoryKeyringPutRequest(1, "Y2lwaGVy"))
        api.deleteHistoryKeyring("Bearer t")

        repeat(3) {
            val sent = server.takeRequest()
            assertEquals(
                "${sent.method} ${sent.path} must carry the device identity",
                DEVICE, sent.getHeader("X-Device-Id")
            )
            assertEquals("Bearer t", sent.getHeader("Authorization"))
        }
    }

    /** The interceptor omits the header rather than sending a blank one. */
    @Test
    fun aMissingDeviceIdSendsNoHeaderRatherThanAnEmptyOne() = runBlocking {
        tokens.deviceId = ""
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"device id required"}"""))

        val resp = api.getHistoryKeyring("Bearer t")

        assertNull(server.takeRequest().getHeader("X-Device-Id"))
        assertEquals("and the server's rejection must be visible to the caller", 400, resp.code())
    }

    // ------------------------------------------------------------------ DELETE

    @Test
    fun deleteUsesTheDeleteVerbOnTheVersionedPath() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"deleted":true}"""))

        val resp = api.deleteHistoryKeyring("Bearer test-token")
        assertTrue(resp.isSuccessful)

        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/api/v1/e2ee/history-keyring", sent.path)
    }

    @Test
    fun deleteSendsNoBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"deleted":false}"""))
        api.deleteHistoryKeyring("Bearer t")
        assertEquals("a delete must not carry key material", 0L, server.takeRequest().bodySize)
    }

    @Test
    fun deleteSurfacesServerErrorsRatherThanThrowing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        val resp = api.deleteHistoryKeyring("Bearer t")
        assertTrue(resp.isSuccessful.not())
        assertEquals(403, resp.code())
    }

    // ------------------------------------------------------------------ GET/PUT

    @Test
    fun getReadsTheVersionedBlob() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"version":7,"ciphertext_b64":"YWJj"}""")
        )

        val resp = api.getHistoryKeyring("Bearer t")

        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/api/v1/e2ee/history-keyring", sent.path)
        assertEquals(7, resp.body()!!.version)
        assertEquals("YWJj", resp.body()!!.ciphertextB64)
    }

    @Test
    fun aMissingBlobArrivesAs404NotAnEmptyKeyring() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val resp = api.getHistoryKeyring("Bearer t")
        assertEquals(404, resp.code())
        assertNull("a 404 must not decode into a usable body", resp.body())
    }

    @Test
    fun putSerialisesTheSnakeCaseWireFormat() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"created":true}"""))

        api.putHistoryKeyring("Bearer t", HistoryKeyringPutRequest(0, "Y2lwaGVy"))

        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        val body = sent.body.readUtf8()
        // expected_version must appear even at 0: it is the difference between
        // "create only" and a blind overwrite, and kotlinx omits defaults unless
        // the property has none - exactly the silent-omission trap this catches.
        assertTrue("expected_version must be on the wire, got: $body", body.contains("\"expected_version\":0"))
        assertTrue(body.contains("\"ciphertext_b64\":\"Y2lwaGVy\""))
    }

    @Test
    fun aVersionConflictArrivesAs409() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":"version conflict","server_version":4}""")
        )
        val resp = api.putHistoryKeyring("Bearer t", HistoryKeyringPutRequest(1, "Y2lwaGVy"))
        assertEquals("a conflict must stay distinguishable from success", 409, resp.code())
    }

    /** The server caps a blob at 1 MiB; the client must read that as a refusal. */
    @Test
    fun anOversizedBlobArrivesAs413() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(413).setBody("""{"error":"ciphertext too large"}""")
        )
        val resp = api.putHistoryKeyring("Bearer t", HistoryKeyringPutRequest(1, "Y2lwaGVy"))
        assertEquals(413, resp.code())
        assertTrue("413 must not be mistaken for success", !resp.isSuccessful)
        assertNull(resp.body())
    }

    // ------------------------------------------------- malformed successes

    /**
     * The degenerate 2xx shapes. Retrofit must surface them as something the sync
     * layer can reject; what it must NOT do is invent a usable body.
     */
    @Test
    fun malformedSuccessfulResponsesDoNotProduceUsableBlobs(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":3,"ciphertext_b64":""}"""))
        assertEquals("", api.getHistoryKeyring("Bearer t").body()?.ciphertextB64)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":3}"""))
        val missing = api.getHistoryKeyring("Bearer t")
        assertNotNull(missing.body())
        assertTrue(
            "an absent ciphertext must not decode into content",
            missing.body()!!.ciphertextB64.isBlank()
        )
        server.takeRequest()
    }

    @Test
    fun anUnparseableSuccessBodyIsNotSilentlyAccepted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this is not json"))
        val threw = runCatching { api.getHistoryKeyring("Bearer t") }.isFailure
        assertTrue("a non-JSON 200 must not decode into a usable object", threw)
    }
}
