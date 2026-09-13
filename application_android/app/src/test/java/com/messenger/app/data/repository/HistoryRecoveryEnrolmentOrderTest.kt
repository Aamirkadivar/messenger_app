package com.messenger.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 46B - history keyring recovery must not start before device enrolment.
 *
 * WHAT WENT WRONG. `GET /e2ee/history-keyring` is device-gated: Phase 44 resolves
 * authorization through the session's bound device, and a session acquires that
 * binding only by completing device registration. Recovery used to launch as pure
 * fire-and-forget from session adoption while registration ran separately
 * afterwards, so on real hardware the keyring request lost the race and came back
 * 403 - seven milliseconds before the device became verified. Nothing retried it,
 * because the only production trigger had already fired.
 *
 * WHY THIS TEST IS SHAPED LIKE THIS. The ordering lives between two private
 * functions on E2EEVaultRepository, and the paths that reach them run Argon2id and
 * XChaCha20 through lazysodium's Android native library, which does not load in a
 * plain JVM unit test - no JVM test in this module touches VaultCrypto for exactly
 * that reason. Driving the seam here would therefore fail on the crypto rather
 * than on the ordering, and refactoring the seam purely to make it mockable would
 * add an abstraction the production code does not otherwise need.
 *
 * So this asserts the invariant where it is actually expressible: in the source.
 * It is a regression guard, not a behavioural proof - the behavioural proof is the
 * real-device run, where the observed sequence was
 *
 *     POST /e2ee/devices/challenge -> 201
 *     POST /e2ee/devices          -> 201
 *     "E2EE device verified"
 *     GET  /e2ee/history-keyring  -> 404   (nothing stored; NOT 403)
 *
 * with no keyring request preceding a successful registration. What this test
 * catches is somebody moving the recovery call back outside the guard, which is
 * precisely how the defect was introduced.
 */
class HistoryRecoveryEnrolmentOrderTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/messenger/app/data/repository/E2EEVaultRepository.kt")
        assertTrue("E2EEVaultRepository.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The body of recoverHistoryKeyringInBackground, brace-matched from its signature. */
    private fun recoveryLaunchBody(): String {
        val marker = "private fun recoverHistoryKeyringInBackground()"
        val start = source.indexOf(marker)
        assertTrue("recoverHistoryKeyringInBackground() not found", start >= 0)
        var i = source.indexOf('{', start)
        var depth = 0
        val begin = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(begin, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces in recoverHistoryKeyringInBackground()")
    }

    @Test
    fun `recovery is triggered from exactly one place`() {
        // The whole fix relies on there being a single launch point, so every
        // unlock, create, pairing and reset path inherits the ordering. If a
        // second trigger appears it must carry the same guard, and this test
        // should fail until somebody has thought about that.
        val triggers = Regex("recoverHistoryKeyringInBackground\\(\\)").findAll(source).count()
        // One declaration + one call site.
        assertEquals("expected exactly one call site for recoverHistoryKeyringInBackground", 2, triggers)
    }

    @Test
    fun `enrolment is awaited before recovery runs`() {
        val body = recoveryLaunchBody()
        val enrol = body.indexOf("registerDevice(")
        val recover = body.indexOf("ensureRecovered()")
        assertTrue("registerDevice() must be called inside the recovery coroutine", enrol >= 0)
        assertTrue("ensureRecovered() must be called inside the recovery coroutine", recover >= 0)
        assertTrue(
            "device enrolment must be awaited BEFORE keyring recovery - this is the Phase 46B fix",
            enrol < recover
        )
    }

    @Test
    fun `failed enrolment short-circuits before recovery`() {
        val body = recoveryLaunchBody()
        // The guard must be a negated check that returns, not a logged warning
        // that falls through: an unverified device has to skip recovery entirely.
        assertTrue(
            "a failed registerDevice() must return before ensureRecovered() is reached",
            Regex("if\\s*\\(!registerDevice\\([^)]*\\)\\)\\s*\\{[^}]*return@launch")
                .containsMatchIn(body)
        )
    }

    @Test
    fun `registerDevice reports verification rather than returning Unit`() {
        // The guard is only meaningful if registration actually reports success.
        assertTrue(
            "registerDevice must return Boolean so the ordering guard can depend on it",
            source.contains("private suspend fun registerDevice(token: String): Boolean")
        )
    }

    @Test
    fun `recovery failure remains retryable`() {
        // Phase 46B must not redesign retry semantics: ensureRecovered still
        // clears its one-shot flag on failure so a later attempt can run.
        val sync = File(
            "src/main/java/com/messenger/app/data/repository/HistoryKeyringRecoverySync.kt"
        )
        assertTrue("HistoryKeyringRecoverySync.kt not found", sync.exists())
        val text = sync.readText()
        val onFailure = text.indexOf(".onFailure {")
        assertTrue("ensureRecovered must still have an onFailure branch", onFailure >= 0)
        assertTrue(
            "a failed recovery must still reset recoveryAttempted so it can be retried",
            text.contains("recoveryAttempted = false")
        )
    }
}
