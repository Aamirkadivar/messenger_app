package com.messenger.app.di

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The network logger must never put credentials in logcat.
 *
 * This is a real regression: the interceptor ran at Level.BODY on debug builds,
 * so a live run left the account password (POST /auth/login), access and refresh
 * tokens (2FA response) and pw_wrapped_master_b64 - the password-wrapped vault
 * master key (PUT /e2ee/vault) - sitting in logcat, readable by anything with
 * adb or READ_LOGS.
 *
 * Synthetic values only; no real secret appears here or in the assertions.
 */
class SecretSafeHttpLoggerTest {

    private lateinit var server: MockWebServer
    private val lines = mutableListOf<String>()
    private val captured: String get() = lines.joinToString("\n")

    /** Distinctive stand-ins, so a hit cannot be a coincidence. */
    private val password = "synthetic-password-Zx91Qw"
    private val bearer = "synthetic-jwt-Aa11Bb22Cc33"
    private val wrappedMaster = "synthetic-wrapped-master-Kk44Ll55"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(path: String, body: String, debug: Boolean = true) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"$bearer","refresh_token":"$bearer"}""")
        )
        val logger = HttpLoggingInterceptor.Logger { lines += it }
        val client = OkHttpClient.Builder()
            .addInterceptor(secretSafeHttpLogger(debug = debug, logger = logger))
            .build()
        val request = Request.Builder()
            .url(server.url(path))
            .header("Authorization", "Bearer $bearer")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.string() }
    }

    @Test
    fun loginPasswordNeverReachesTheLog() {
        call("/api/v1/auth/login", """{"email":"a@b.test","password":"$password"}""")
        assertFalse("password leaked into the log", captured.contains(password))
    }

    @Test
    fun bearerTokenNeverReachesTheLog() {
        call("/api/v1/auth/2fa/verify", """{"challenge_id":"x","code":"000000"}""")
        assertFalse("bearer/JWT leaked into the log", captured.contains(bearer))
        assertTrue(
            "Authorization should be present but redacted",
            captured.contains("Authorization: ██")
        )
    }

    @Test
    fun vaultKeyMaterialNeverReachesTheLog() {
        call("/api/v1/e2ee/vault", """{"pw_wrapped_master_b64":"$wrappedMaster"}""")
        assertFalse("vault key material leaked into the log", captured.contains(wrappedMaster))
    }

    @Test
    fun safeDiagnosticsAreStillLogged() {
        call("/api/v1/e2ee/vault", """{"pw_wrapped_master_b64":"$wrappedMaster"}""")
        // The point of keeping HEADERS rather than NONE: these must survive.
        assertTrue("method+URL missing", captured.contains("POST") && captured.contains("/e2ee/vault"))
        assertTrue("status code missing", captured.contains("200"))
        assertTrue("content-length missing", captured.contains("Content-Length", ignoreCase = true))
    }

    @Test
    fun releaseBuildsLogNothing() {
        call("/api/v1/auth/login", """{"password":"$password"}""", debug = false)
        assertTrue("release builds must not log at all", lines.isEmpty())
    }
}
