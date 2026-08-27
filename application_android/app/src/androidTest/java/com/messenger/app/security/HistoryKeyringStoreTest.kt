package com.messenger.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 3 CHECKPOINT C - the fail-closed Keystore path, on-device.
 *
 * The JVM tests cover the keyring POLICY through the port. What can only be checked here is the
 * port's real implementation: that Android Keystore wrapping actually round-trips, that an
 * unwrapped value is refused rather than trusted, and that "absent" and "unreadable" stay
 * distinguishable.
 *
 * Uses the production keys, because those are what the implementation writes - so the test removes
 * both of them again in @After. Those keys are not used by the running app (the feature is not
 * wired to any UI), and `mls2_snapshot` is never read or written here.
 */
@RunWith(AndroidJUnit4::class)
class HistoryKeyringStoreTest {

    private fun store(): TokenManagerImpl {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    private companion object {
        val PAYLOAD = "gate3-checkpoint-c-keyring-canary".toByteArray(Charsets.UTF_8)
        val CACHE_PAYLOAD = "gate3-checkpoint-c-cache-canary".toByteArray(Charsets.UTF_8)
    }

    @After
    fun cleanUp() = runBlocking {
        val s = store()
        s.deleteHistoryKeyring()
        s.deleteHistoryKeyringCache()
        Unit

    }

    @Test
    fun authoritativeCopyRoundTripsThroughKeystore() = runBlocking {
        val s = store()
        assertTrue(s.saveHistoryKeyring(PAYLOAD).isSuccess)
        val loaded = s.loadHistoryKeyring().getOrThrow()
        assertNotNull(loaded)
        assertArrayEquals(PAYLOAD, loaded)
    }

    @Test
    fun cacheCopyRoundTripsThroughKeystore() = runBlocking {
        val s = store()
        assertTrue(s.saveHistoryKeyringCache(CACHE_PAYLOAD).isSuccess)
        assertArrayEquals(CACHE_PAYLOAD, s.loadHistoryKeyringCache().getOrThrow())
    }

    /** The two copies are independent: writing one must not disturb the other. */
    @Test
    fun theTwoCopiesAreIndependent() = runBlocking {
        val s = store()
        s.saveHistoryKeyring(PAYLOAD).getOrThrow()
        s.saveHistoryKeyringCache(CACHE_PAYLOAD).getOrThrow()

        assertArrayEquals(PAYLOAD, s.loadHistoryKeyring().getOrThrow())
        assertArrayEquals(CACHE_PAYLOAD, s.loadHistoryKeyringCache().getOrThrow())

        s.deleteHistoryKeyringCache().getOrThrow()
        assertNull("the cache must actually be gone", s.loadHistoryKeyringCache().getOrThrow())
        assertArrayEquals(
            "the authoritative copy must survive a cache delete",
            PAYLOAD,
            s.loadHistoryKeyring().getOrThrow()
        )

    }

    /** Absent must be success(null), never a failure - a fresh device is not an error. */
    @Test
    fun absentReadsAsSuccessNull() = runBlocking {
        val s = store()
        s.deleteHistoryKeyring().getOrThrow()
        s.deleteHistoryKeyringCache().getOrThrow()
        val a = s.loadHistoryKeyring()
        val c = s.loadHistoryKeyringCache()
        assertTrue(a.isSuccess)
        assertTrue(c.isSuccess)
        assertNull(a.getOrThrow())
        assertNull(c.getOrThrow())

    }

    /**
     * The fail-closed core: a stored value that is NOT Keystore-wrapped must be refused.
     *
     * `unwrapSecret` trusts an unprefixed value as a legacy plaintext row, which is right for the
     * MLS bundles it was built for. This path never writes plaintext, so an unprefixed value here
     * is corruption or tampering and must not be returned.
     */
    @Test
    fun anUnwrappedValueIsRefusedNotTrusted() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("history_keyring_cache_v1", "not-keystore-wrapped").commit()

        val result = store().loadHistoryKeyringCache()
        assertTrue("an unwrapped keyring must be refused", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("not Keystore-wrapped")
        )
    }

    @Test
    fun deleteIsDurableAndRepeatable() = runBlocking {
        val s = store()
        s.saveHistoryKeyringCache(CACHE_PAYLOAD).getOrThrow()
        assertTrue(s.deleteHistoryKeyringCache().isSuccess)
        assertNull(s.loadHistoryKeyringCache().getOrThrow())
        // Deleting again is a no-op, not an error.
        assertTrue(s.deleteHistoryKeyringCache().isSuccess)

    }

    @Test
    fun largeKeyringsRoundTrip() = runBlocking {
        // A keyring with many chats and versions is still small, but prove the encoding is not
        // length-limited in any surprising way.
        val big = ByteArray(64 * 1024) { (it % 251).toByte() }
        val s = store()
        assertTrue(s.saveHistoryKeyringCache(big).isSuccess)
        assertArrayEquals(big, s.loadHistoryKeyringCache().getOrThrow())
    }
}
