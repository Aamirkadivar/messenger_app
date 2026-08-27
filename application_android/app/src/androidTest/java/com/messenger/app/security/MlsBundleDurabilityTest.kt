package com.messenger.app.security

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 1 - durability of the MLS bundle write.
 *
 * The defect this guards against is not "the write is slow". It is that
 * `apply()` returns `void` and schedules the write asynchronously, so
 * `saveMlsBundle` returned `Result.success` for state that was not on disk. A
 * process that ended before the flush lost it, and because an MLS author cannot
 * replay its own commit, the device could not catch up afterwards.
 *
 * The important test is [phase2_readBackFromAFreshProcess], which must run in a
 * SEPARATE process from [phase1_writeThenDie]. A same-process read-back would
 * be satisfied by the in-memory SharedPreferences map and would pass even with
 * `apply()` - that is precisely the false positive that hid the original bug.
 *
 * Run in two invocations, no app launch required:
 *
 *   adb shell am instrument -w -e class \
 *     com.messenger.app.security.MlsBundleDurabilityTest#phase1_writeThenDie \
 *     com.messenger.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 *   adb shell am force-stop com.messenger.app.debug
 *
 *   adb shell am instrument -w -e class \
 *     com.messenger.app.security.MlsBundleDurabilityTest#phase2_readBackFromAFreshProcess \
 *     com.messenger.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Uses a dedicated key so it can never disturb a real MLS snapshot.
 */
class MlsBundleDurabilityTest {

    private companion object {
        /** Deliberately not "mls2_snapshot": this test must never touch live state. */
        const val TEST_KEY = "gate1_durability_probe"
        const val PAYLOAD = "gate1-durability-canary-value-0123456789"
    }

    private fun tokenManager(): TokenManager {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    /**
     * Writes the canary and asserts only that the call reported success. The
     * process is then killed externally; nothing here proves durability.
     */
    @Test
    fun phase1_writeThenDie() = runBlocking {
        val tm = tokenManager()
        val result = tm.saveMlsBundle(TEST_KEY, PAYLOAD)
        assertTrue(
            "saveMlsBundle must report success for a committed write",
            result.isSuccess
        )
        // Same-process read-back: necessary but NOT sufficient. It would pass
        // under apply() too, which is the whole point of phase 2.
        assertEquals(PAYLOAD, tm.loadMlsBundle(TEST_KEY).getOrNull())
    }

    /**
     * The real assertion. This runs in a process that never performed the write,
     * so the value can only come from disk.
     */
    @Test
    fun phase2_readBackFromAFreshProcess() = runBlocking {
        val tm = tokenManager()
        val loaded = tm.loadMlsBundle(TEST_KEY).getOrNull()
        assertNotNull(
            "the bundle written by phase 1 must survive process death - " +
                "a null here means the write was never durable",
            loaded
        )
        assertEquals(PAYLOAD, loaded)
    }

    /**
     * Deletion must be durable in the same way: a removal that has not reached
     * disk would resurrect the bundle on the next start.
     */
    @Test
    fun phase3_deleteThenDie() = runBlocking {
        val tm = tokenManager()
        tm.saveMlsBundle(TEST_KEY, PAYLOAD).getOrThrow()
        assertTrue(tm.deleteMlsBundle(TEST_KEY).isSuccess)
        assertNull(tm.loadMlsBundle(TEST_KEY).getOrNull())
    }

    /**
     * Run after phase3 in a fresh process: the bundle must still be gone.
     *
     * The explicit `: Unit` is load-bearing. This body ends with a cleanup call
     * returning `Result<Unit>`, so without it Kotlin infers that as the return
     * type, name-mangles the method because `Result` is an inline value class,
     * and JUnit rejects the whole class with "should be void" before running
     * any test. It compiles either way - the constraint is JUnit's, not
     * Kotlin's - which is why compilation alone did not catch it.
     */
    @Test
    fun phase4_deletionSurvivedFreshProcess(): Unit = runBlocking {
        val tm = tokenManager()
        assertNull(
            "a committed delete must not reappear after process death",
            tokenManager().loadMlsBundle(TEST_KEY).getOrNull()
        )
        // leave storage clean regardless of ordering
        tm.deleteMlsBundle(TEST_KEY)
    }
}
