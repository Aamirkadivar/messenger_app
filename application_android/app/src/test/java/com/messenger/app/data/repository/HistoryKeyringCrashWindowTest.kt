package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringCacheFormat
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 5 PHASE 5 - the durable-write crash window.
 *
 * `persistLocked` performs three durable writes that cannot be made atomic:
 *
 *     1. generation marker      2. authoritative keyring      3. stamped cache
 *
 * Before this gate there were only two, in the order (authoritative, cache), and
 * a process death between them left the cache silently older than the
 * authoritative copy. Because the cache is the only copy readable without MK, a
 * cold start read it FIRST and returned it - so the newer authoritative keyring
 * was shadowed indefinitely, `ensureRoot` minted a rival root for a version that
 * already existed, and the next recovery merge hit the conflict rule and failed
 * closed forever.
 *
 * What these tests exercise is the real interruption, not a simulation of its
 * symptoms: [CrashingStore] fails a chosen durable write exactly as a process
 * death would - the writes before it have landed, the writes after it never
 * happen - and the repository is then rebuilt over the same storage, which is
 * what a restart is. No production code is stubbed; the store port is the true
 * process boundary, since everything past it is the OS.
 *
 * All key material is TEST-ONLY.
 */
class HistoryKeyringCrashWindowTest {

    private companion object {
        const val USER = "crash-user"
        const val CHAT = "crash-chat"
        const val OTHER = "crash-chat-2"
    }

    /**
     * Device storage that survives "process death", plus a one-shot fault.
     *
     * [failAfter] names the write that never lands. Everything before it is
     * already durable, which is exactly the state a kill leaves behind.
     */
    private class CrashingStore : HistoryKeyringStore {
        var authoritative: ByteArray? = null
        var cache: ByteArray? = null
        var generation: Long? = null

        /** "gen", "authoritative", or "cache"; null means no fault. */
        var failAfter: String? = null

        /**
         * Set the instant the fault fires. This is what makes the model a process
         * DEATH rather than a write error: after it, no further write lands -
         * including the compensating `deleteHistoryKeyringCache` that
         * `writeCache` performs when it observes a failure. That compensation is
         * a correct handler for an observed error, and modelling only that would
         * have tested the safe path and missed the window entirely.
         */
        private var dead = false

        private fun crash(stage: String): Boolean {
            if (dead) return true
            if (failAfter != stage) return false
            failAfter = null
            dead = true
            return true
        }

        /**
         * Storage outlives the process. Only the death flag clears - an armed
         * fault is the test's instruction for the NEXT process, and the fault
         * itself is consumed when it fires.
         */
        fun restartProcess() { dead = false }

        override suspend fun saveHistoryKeyring(sealed: ByteArray): Result<Unit> {
            if (crash("authoritative")) return Result.failure(SimulatedProcessDeath())
            authoritative = sealed.copyOf()
            return Result.success(Unit)
        }
        override suspend fun loadHistoryKeyring() = Result.success(authoritative?.copyOf())
        override suspend fun deleteHistoryKeyring(): Result<Unit> {
            if (crash("del-auth")) return Result.failure(SimulatedProcessDeath())
            authoritative = null
            return Result.success(Unit)
        }

        override suspend fun saveHistoryKeyringCache(plain: ByteArray): Result<Unit> {
            if (crash("cache")) return Result.failure(SimulatedProcessDeath())
            cache = plain.copyOf()
            return Result.success(Unit)
        }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        // A delete is a durable write too. It matters here specifically because
        // `writeCache` compensates for an observed save failure by deleting the
        // cache - and in a dead process that compensation never runs either,
        // which is precisely how a stale cache is left behind.
        override suspend fun deleteHistoryKeyringCache(): Result<Unit> {
            if (crash("del-cache")) return Result.failure(SimulatedProcessDeath())
            cache = null
            return Result.success(Unit)
        }

        override suspend fun saveHistoryKeyringGeneration(generation: Long): Result<Unit> {
            if (crash("gen")) return Result.failure(SimulatedProcessDeath())
            this.generation = generation
            return Result.success(Unit)
        }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration(): Result<Unit> {
            if (crash("del-gen")) return Result.failure(SimulatedProcessDeath())
            generation = null
            return Result.success(Unit)
        }
    }

    private class SimulatedProcessDeath : IllegalStateException("process died mid-write")

    private class Vault(private val user: String?, private val locked: Boolean = false) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun mask(b: ByteArray, tag: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor tag).toByte() }
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            if (locked) Result.failure(IllegalStateException("vault locked"))
            else Result.success(byteArrayOf(0x11) + mask(plaintext, 0x11))
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (locked) Result.failure(IllegalStateException("vault locked"))
            else if (sealed.isNotEmpty() && sealed[0] == 0x11.toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), 0x11))
            else Result.failure(IllegalStateException("not sealed"))
        override suspend fun sealForRecovery(plaintext: ByteArray) =
            if (user == null) Result.failure(IllegalStateException("no mk"))
            else Result.success(byteArrayOf(0x22) + mask(plaintext, 0x22))
        override suspend fun openFromRecovery(sealed: ByteArray) =
            if (user == null) Result.failure(IllegalStateException("no mk"))
            else if (sealed.isNotEmpty() && sealed[0] == 0x22.toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), 0x22))
            else Result.failure(IllegalStateException("not a recovery blob"))
    }

    /** A brand-new repository over the same storage: this is what a restart is. */
    private fun restart(store: CrashingStore, user: String? = USER): HistoryKeyringRepository {
        store.restartProcess()
        return HistoryKeyringRepository(
            Vault(user), store, HistoryUserProvider { user }, HistoryArchiveFeature.Enabled
        )
    }

    /** A restart whose vault is still locked: no MK, so only the cache is readable. */
    private fun restartLocked(store: CrashingStore, user: String? = USER): HistoryKeyringRepository {
        store.restartProcess()
        return HistoryKeyringRepository(
            Vault(user, locked = true), store, HistoryUserProvider { user },
            HistoryArchiveFeature.Enabled
        )
    }

    // ================================================== the window itself

    /**
     * THE regression. The authoritative copy advances, the cache write never
     * lands, the process restarts - and the newer keyring must win.
     */
    @Test
    fun anInterruptedCacheWriteDoesNotShadowTheNewerAuthoritativeKeyring() = runBlocking {
        val store = CrashingStore()
        val first = restart(store).ensureRoot(CHAT).getOrThrow()

        // Now add a SECOND chat, dying before the cache is refreshed.
        store.failAfter = "cache"
        val second = restart(store)
        assertTrue(
            "the interrupted write must be reported as a failure, not swallowed",
            second.ensureRoot(OTHER).isFailure
        )

        // The authoritative copy holds both roots; the cache still holds only one.
        val staleCache = store.cache
        assertNotNull(staleCache)

        val afterRestart = restart(store).load().getOrThrow()
        assertNotNull("the root written before the crash must survive", afterRestart.latest(CHAT))
        assertNotNull(
            "the root the authoritative copy already holds must NOT be shadowed by the stale cache",
            afterRestart.latest(OTHER)
        )
        assertArrayEquals(first.root, afterRestart.latest(CHAT)!!.root)
    }

    /**
     * The consequence that made the above a blocker rather than a nuisance: a
     * shadowed root gets re-minted under a version that already exists, and the
     * merge rule then refuses to choose between them forever.
     */
    @Test
    fun aShadowedRootIsNeverReMintedUnderAnExistingVersion() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        store.failAfter = "cache"
        val authoritativeRoot = run {
            val r = restart(store)
            r.rotate(CHAT) // advances to v2 authoritatively, cache write dies
            restart(store).find(CHAT, 2).getOrThrow()
        }
        assertNotNull("precondition: v2 exists authoritatively", authoritativeRoot)

        // A restart must adopt v2, not mint a rival v2.
        val adopted = restart(store).ensureRoot(CHAT).getOrThrow()
        assertEquals(2, adopted.rootVersion)
        assertArrayEquals(
            "a rival root for an existing version is what poisons recovery forever",
            authoritativeRoot!!.root, adopted.root
        )
    }

    /** And therefore recovery still converges instead of failing closed forever. */
    @Test
    fun recoveryDoesNotBecomePermanentlyConflictedAfterACrash() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()
        // What the server already holds, captured before the crash.
        val published = restart(store).exportForRecovery().getOrThrow()

        store.failAfter = "cache"
        restart(store).rotate(CHAT)

        // Re-importing the published keyring must merge, not conflict.
        val merged = restart(store).importFromRecovery(published).getOrThrow()
        assertNotNull(merged.find(CHAT, 1))
        assertNotNull(merged.find(CHAT, 2))
    }

    // ================================================== each crash point

    @Test
    fun crashBeforeTheAuthoritativeWriteLeavesTheOlderStateIntact() = runBlocking {
        val store = CrashingStore()
        val original = restart(store).ensureRoot(CHAT).getOrThrow()

        store.failAfter = "authoritative"
        assertTrue(restart(store).ensureRoot(OTHER).isFailure)

        // The marker ran ahead and the authoritative copy did not move. The old
        // state is still the truth, and it must be served - not refused, and not
        // replaced by an empty keyring.
        val after = restart(store).load().getOrThrow()
        assertArrayEquals(original.root, after.latest(CHAT)!!.root)
        assertNull("a write that never landed must not appear", after.latest(OTHER))
    }

    @Test
    fun crashAtTheMarkerLeavesEverythingConsistent() = runBlocking {
        val store = CrashingStore()
        val original = restart(store).ensureRoot(CHAT).getOrThrow()

        store.failAfter = "gen"
        assertTrue(restart(store).ensureRoot(OTHER).isFailure)

        val after = restart(store).load().getOrThrow()
        assertArrayEquals(original.root, after.latest(CHAT)!!.root)
        assertNull(after.latest(OTHER))
    }

    // ================================================== reconciliation

    @Test
    fun aSuccessfulVaultReadRestampsTheCache() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        store.failAfter = "cache"
        restart(store).ensureRoot(OTHER)
        val markerAfterCrash = store.generation

        // The load that reads through the vault is itself the repair.
        restart(store).load().getOrThrow()

        val decoded = HistoryKeyringCacheFormat.decode(store.cache, USER)
        assertTrue(decoded is HistoryKeyringCacheFormat.Decoded.Owned)
        assertEquals(
            "the cache must be re-stamped to the current marker",
            markerAfterCrash,
            (decoded as HistoryKeyringCacheFormat.Decoded.Owned).generation
        )

        // Which means the NEXT cold start can use the cache again, without MK.
        val fromCache = restartLocked(store).load().getOrThrow()
        assertNotNull(fromCache.latest(CHAT))
        assertNotNull(fromCache.latest(OTHER))
    }

    /**
     * The cache remains usable without MK in the ordinary case - that is the
     * entire reason it exists, and the freshness check must not cost it.
     */
    @Test
    fun anUninterruptedCacheStillServesAColdStartWithoutMk() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        assertNotNull(restartLocked(store).load().getOrThrow().latest(CHAT))
    }

    /** A stale cache with a locked vault must refuse, never serve stale roots. */
    @Test
    fun aStaleCacheWithNoVaultAccessFailsClosed() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()
        store.failAfter = "cache"
        restart(store).ensureRoot(OTHER)

        val result = restartLocked(store).load()
        assertTrue(
            "serving a cache that is not known to match is the defect itself",
            result.isFailure
        )
    }

    // ================================================== invariants preserved

    @Test
    fun theGenerationStampDoesNotWeakenAccountBinding() = runBlocking {
        val store = CrashingStore()
        restart(store, "user-a").ensureRoot(CHAT).getOrThrow()

        // Same device, different account: still ForAnotherUser, not Owned.
        assertEquals(
            HistoryKeyringCacheFormat.Decoded.ForAnotherUser,
            HistoryKeyringCacheFormat.decode(store.cache, "user-b")
        )
        // And a truncated stamp is Unusable, never a downgrade to someone else's.
        // Cut inside the generation field: a tail truncation would only shorten
        // the keyring payload, which is a different failure.
        val idLen = "user-a".toByteArray(Charsets.UTF_8).size
        val truncated = store.cache!!.copyOfRange(0, 5 + idLen + 4)
        assertEquals(
            HistoryKeyringCacheFormat.Decoded.Unusable,
            HistoryKeyringCacheFormat.decode(truncated, "user-a")
        )
    }

    @Test
    fun anUnstampedLegacyCacheIsUnusableNotEmpty() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        // A version-2 cache (account-bound, unstamped) must not be trusted, and
        // with no authoritative copy to arbitrate it must refuse rather than mint.
        store.cache = byteArrayOf(2) + store.cache!!.copyOfRange(1, store.cache!!.size)
        store.authoritative = null

        assertTrue(
            "unknown freshness with nothing to check against must fail closed",
            restart(store).load().isFailure
        )
    }

    @Test
    fun theMarkerAdvancesMonotonically() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()
        val g1 = store.generation!!
        restart(store).ensureRoot(OTHER).getOrThrow()
        val g2 = store.generation!!
        assertTrue("each durable mutation must advance the marker", g2 > g1)

        // A failed write must not roll it back - a lagging marker would
        // re-validate a stale cache, which is the defect this exists to prevent.
        store.failAfter = "cache"
        restart(store).rotate(CHAT)
        assertTrue(store.generation!! > g2)
    }

    @Test
    fun readsDoNotAdvanceTheMarker() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()
        val g = store.generation
        repeat(3) { restart(store).load().getOrThrow() }
        assertEquals("a read is not a mutation", g, store.generation)
    }

    @Test
    fun aNoOpImportDoesNotAdvanceTheMarker() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()
        val exported = restart(store).exportForRecovery().getOrThrow()
        val g = store.generation

        restart(store).importFromRecovery(exported).getOrThrow()
        assertEquals("importing what we already have changes nothing", g, store.generation)
        assertFalse(store.cache == null)
    }

    // ================================================== upgrade from no marker

    /**
     * A device that already held a keyring before the marker existed.
     *
     * This state is reachable in the field: rotation used to bypass the feature
     * flag, so a production device could have minted and persisted roots with the
     * feature switched off, leaving an authoritative copy and an unstamped cache
     * and no marker at all. The upgrade must converge to a usable cache rather
     * than reading through the vault forever - otherwise every locked cold start
     * fails from then on.
     */
    @Test
    fun aDeviceWithNoMarkerConvergesOnFirstUnlockedRead() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        // Roll storage back to the pre-marker world: v2-style cache, no marker.
        store.generation = null
        store.cache = byteArrayOf(2) + store.cache!!.copyOfRange(1, store.cache!!.size)

        // Locked, it must refuse rather than trust an unstamped cache.
        assertTrue(restartLocked(store).load().isFailure)

        // Unlocked, the read repairs both the marker and the cache.
        assertNotNull(restart(store).load().getOrThrow().latest(CHAT))
        assertNotNull("a marker must have been established", store.generation)

        val decoded = HistoryKeyringCacheFormat.decode(store.cache, USER)
        assertTrue(decoded is HistoryKeyringCacheFormat.Decoded.Owned)
        assertEquals(
            store.generation,
            (decoded as HistoryKeyringCacheFormat.Decoded.Owned).generation
        )

        // And from here the cache serves a locked cold start again.
        assertNotNull(restartLocked(store).load().getOrThrow().latest(CHAT))
    }

    @Test
    fun anEstablishedMarkerIsNotReSeededOnEveryRead() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()
        store.generation = null
        store.cache = byteArrayOf(2) + store.cache!!.copyOfRange(1, store.cache!!.size)

        restart(store).load().getOrThrow()
        val seeded = store.generation
        repeat(3) { restart(store).load().getOrThrow() }
        assertEquals("the marker must settle, not drift on reads", seeded, store.generation)
    }

    // ================================================== reset discard crash matrix

    /**
     * GATE 6.1: process death during local keyring destruction.
     *
     * discardForMkReplacement performs three durable deletes - marker, cache,
     * authoritative - and a crash can land between any two. The requirement is
     * that no intermediate state ever serves the OLD generation's roots as the
     * new generation's, because that silently undoes a reset the user asked for.
     *
     * These are fault injection at the durable-store boundary, not real process
     * kills.
     */
    @Test
    fun noCrashDuringDiscardEverServesOldRootsUnderTheReplacementKey() = runBlocking {
        // Each stage is the delete that never lands; everything before it has.
        for (stage in listOf("del-gen", "del-cache", "del-auth", null)) {
            val store = CrashingStore()
            val minted = restart(store).ensureRoot(CHAT).getOrThrow()
            assertNotNull(minted)

            store.failAfter = stage
            // The reset's local teardown, interrupted at `stage`.
            val outcome = runCatching { restart(store).discardForMkReplacement() }
                .getOrElse { Result.failure(it) }

            val nothingLanded = store.generation != null &&
                store.cache != null && store.authoritative != null

            // A NEW generation now reads.
            val served = restartLocked(store).load().getOrNull()?.latest(CHAT)

            if (nothingLanded) {
                // Zero progress is a different situation from a half-done
                // teardown: the device is exactly as it was, and the caller is
                // told cleanup did not happen (IncompleteAfterReplacement).
                assertTrue(
                    "a teardown that changed nothing must report failure",
                    outcome.isFailure
                )
            } else {
                // PARTIAL progress is the dangerous case, and the one the old
                // ordering got wrong: with the authoritative copy deleted first,
                // cache and marker still agreed and the stale cache was trusted
                // forever. Removing the marker first makes every partial state
                // fail closed instead.
                assertNull(
                    "crash at '$stage' served an old root to the new generation",
                    served
                )
            }
        }
    }

    @Test
    fun aCompletedDiscardLeavesACleanEmptyGeneration() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        restart(store).discardForMkReplacement().getOrThrow()

        assertNull(store.authoritative)
        assertNull(store.cache)
        assertNull(store.generation)
        // A clean device reads an empty keyring, not a failure.
        val after = restart(store).load().getOrThrow()
        assertNull(after.latest(CHAT))
    }

    @Test
    fun theMarkerIsRemovedBeforeTheCopiesItValidates() = runBlocking {
        val store = CrashingStore()
        restart(store).ensureRoot(CHAT).getOrThrow()

        // Interrupt immediately after the first delete. If the marker were not
        // first, cache + marker would still agree and the stale cache would be
        // trusted - which is exactly the defect this ordering removes.
        // Interrupt at the SECOND delete, so exactly one has landed. If the
        // marker were not first, cache + marker would still agree here and the
        // stale cache would be trusted - the defect this ordering removes.
        store.failAfter = "del-cache"
        runCatching { restart(store).discardForMkReplacement() }

        assertNull("the marker must be the first thing to go", store.generation)
        assertNotNull("the copies it validated are still present", store.cache)
        assertNotNull(store.authoritative)
    }
}
