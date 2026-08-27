package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyring
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryRootEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT A - history keyring policy, JVM.
 *
 * The real crypto is proven in Gate 2 (HistoryCryptoTest, HistoryMessageCipherAeadTest); the fakes
 * here stand in for it so the POLICY can be tested off-device. What matters in this file is the
 * fail-closed behaviour: what happens when the vault is locked, when the Keystore is unavailable,
 * and when stored bytes are unreadable.
 *
 * All key material is TEST-ONLY.
 */
class HistoryKeyringRepositoryTest {

    /**
     * Reversible stand-in for vault sealing. It is NOT encryption - it only has to round-trip and
     * to be able to fail on demand. It copies its input because the repository wipes the plaintext
     * buffer after sealing, exactly as the real XChaCha20-Poly1305 path produces a fresh array.
     */
    private class FakeVault : HistoryKeyringVault {
        var locked = false
        var openFails = false
        var sealCalls = 0

        override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> {
            sealCalls++
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            return Result.success(byteArrayOf(SEALED_MARKER) + plaintext.copyOf())
        }

        override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> {
            if (locked || openFails) {
                return Result.failure(IllegalStateException("history keyring failed authentication"))
            }
            if (sealed.isEmpty() || sealed[0] != SEALED_MARKER) {
                return Result.failure(IllegalStateException("not a sealed keyring"))
            }
            return Result.success(sealed.copyOfRange(1, sealed.size))
        }

        private companion object {
            const val SEALED_MARKER: Byte = 0x7F
        }
    }

    private class FakeStore : HistoryKeyringStore {
        /** Authoritative, MK-sealed copy. */
        var blob: ByteArray? = null
        /** Keystore-only cache holding the plain keyring encoding. */
        var cache: ByteArray? = null

        var saveFails = false
        var loadFails = false
        var cacheSaveFails = false
        var cacheLoadFails = false
        var cacheDeleteFails = false
        var writes = 0
        var cacheWrites = 0
        var cacheDeletes = 0

        override suspend fun saveHistoryKeyring(sealed: ByteArray): Result<Unit> {
            if (saveFails) {
                return Result.failure(IllegalStateException("Keystore wrapping unavailable"))
            }
            writes++
            blob = sealed.copyOf()
            return Result.success(Unit)
        }

        override suspend fun loadHistoryKeyring(): Result<ByteArray?> {
            if (loadFails) return Result.failure(IllegalStateException("could not be unwrapped"))
            return Result.success(blob?.copyOf())
        }

        override suspend fun deleteHistoryKeyring(): Result<Unit> {
            blob = null
            return Result.success(Unit)
        }

        override suspend fun saveHistoryKeyringCache(plain: ByteArray): Result<Unit> {
            if (cacheSaveFails) {
                return Result.failure(IllegalStateException("Keystore wrapping unavailable"))
            }
            cacheWrites++
            cache = plain.copyOf()
            return Result.success(Unit)
        }

        override suspend fun loadHistoryKeyringCache(): Result<ByteArray?> {
            if (cacheLoadFails) return Result.failure(IllegalStateException("cache unreadable"))
            return Result.success(cache?.copyOf())
        }

        override suspend fun deleteHistoryKeyringCache(): Result<Unit> {
            cacheDeletes++
            if (cacheDeleteFails) return Result.failure(IllegalStateException("cache delete failed"))
            cache = null
            return Result.success(Unit)
        }

        private var generation: Long? = null
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    private fun repo(
        vault: FakeVault = FakeVault(),
        store: FakeStore = FakeStore(),
    ) = Triple(HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true }), vault, store)

    // ------------------------------------------------------------------ load

    @Test
    fun freshDeviceLoadsAnEmptyKeyring() = runBlocking {
        val (r, _, _) = repo()
        val keyring = r.load().getOrThrow()
        assertEquals(0, keyring.entries.size)
    }

    /**
     * The invariant that matters most. A storage fault must NOT look like a fresh device, or the
     * caller mints a new root and every archive sealed under the old one becomes unopenable.
     */
    @Test
    fun unreadableStorageIsAFailureNotAnEmptyKeyring() = runBlocking {
        val (r, _, store) = repo()
        store.blob = byteArrayOf(1, 2, 3)
        store.loadFails = true
        val result = r.load()
        assertTrue("a storage fault must surface as failure", result.isFailure)
    }

    /**
     * With no Keystore cache to fall back on, a locked vault must fail rather than read as empty.
     *
     * The cache deliberately makes the ordinary cold start succeed without a password - that is
     * covered by [coldStartReadsTheKeyringWithTheVaultLocked]. This test removes the cache so the
     * only remaining copy is the MK-sealed one, which genuinely needs an unlocked vault.
     */
    @Test
    fun lockedVaultWithNoCacheIsAFailureNotAnEmptyKeyring() = runBlocking {
        val (r, vault, store) = repo()
        r.ensureRoot("c1").getOrThrow()
        r.clearCache()
        store.cache = null
        vault.locked = true
        assertTrue("a locked vault must not read as an empty keyring", r.load().isFailure)
        assertTrue("stored bytes must remain", store.blob != null)
    }

    @Test
    fun tamperedKeyringIsAFailure() = runBlocking {
        val (r, vault, store) = repo()
        r.ensureRoot("c1").getOrThrow()
        r.clearCache()
        store.cache = null
        vault.openFails = true
        assertTrue(r.load().isFailure)
    }

    @Test
    fun corruptPlaintextIsAFailure() = runBlocking {
        val vault = FakeVault()
        val store = FakeStore()
        // A blob that unseals fine but is not a valid keyring encoding.
        store.blob = byteArrayOf(0x7F, 0x02, 0x03)
        val r = HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        assertTrue(r.load().isFailure)
    }

    // ------------------------------------------------------------------ ensureRoot

    @Test
    fun ensureRootMintsPersistsAndReturnsAThirtyTwoByteRoot() = runBlocking {
        val (r, _, store) = repo()
        val entry = r.ensureRoot("c1").getOrThrow()
        assertEquals("c1", entry.chatId)
        assertEquals(1, entry.rootVersion)
        assertEquals(32, entry.root.size)
        assertTrue("the root must be durable before it is returned", store.blob != null)
    }

    @Test
    fun ensureRootIsStableAcrossCalls() = runBlocking {
        val (r, _, store) = repo()
        val first = r.ensureRoot("c1").getOrThrow()
        val writesAfterFirst = store.writes
        val second = r.ensureRoot("c1").getOrThrow()
        assertArrayEquals(first.root, second.root)
        assertEquals("an existing root must not be rewritten", writesAfterFirst, store.writes)
    }

    @Test
    fun ensureRootSurvivesACacheDrop() = runBlocking {
        val (r, _, _) = repo()
        val first = r.ensureRoot("c1").getOrThrow()
        r.clearCache()
        assertArrayEquals(first.root, r.ensureRoot("c1").getOrThrow().root)
    }

    @Test
    fun differentChatsGetDifferentRoots() = runBlocking {
        val (r, _, _) = repo()
        assertNotEquals(
            r.ensureRoot("c1").getOrThrow().root.toList(),
            r.ensureRoot("c2").getOrThrow().root.toList()
        )
    }

    /** A root that could not be sealed must not be handed out, or messages seal under a lost key. */
    @Test
    fun ensureRootFailsClosedWhenSealingFails() = runBlocking {
        val (r, vault, store) = repo()
        vault.locked = true
        val result = r.ensureRoot("c1")
        assertTrue(result.isFailure)
        assertNull("nothing may be persisted when sealing fails", store.blob)
        assertEquals(0, store.writes)
    }

    @Test
    fun ensureRootFailsClosedWhenStorageFails() = runBlocking {
        val (r, vault, store) = repo()
        store.saveFails = true
        assertTrue(r.ensureRoot("c1").isFailure)
        assertNull(store.blob)
        assertTrue("sealing was attempted", vault.sealCalls > 0)

        // The cache must not have absorbed the unpersisted root either.
        store.saveFails = false
        r.clearCache()
        assertEquals(0, r.load().getOrThrow().entries.size)
    }

    @Test
    fun ensureRootRejectsEmptyChatId() {
        val (r, _, _) = repo()
        var threw = false
        try {
            runBlocking { r.ensureRoot("") }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("empty chatId must be rejected", threw)
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun rotationAddsAVersionAndKeepsTheOldOne() = runBlocking {
        val (r, _, _) = repo()
        val v1 = r.ensureRoot("c1").getOrThrow()
        val v2 = r.rotate("c1").getOrThrow()

        assertEquals(1, v1.rootVersion)
        assertEquals(2, v2.rootVersion)
        assertNotEquals(v1.root.toList(), v2.root.toList())

        // The old root must remain retrievable - archives sealed under it still need it.
        assertArrayEquals(v1.root, r.find("c1", 1).getOrThrow()!!.root)
        assertArrayEquals(v2.root, r.find("c1", 2).getOrThrow()!!.root)
    }

    @Test
    fun ensureRootFollowsTheLatestVersionAfterRotation() = runBlocking {
        val (r, _, _) = repo()
        r.ensureRoot("c1").getOrThrow()
        val rotated = r.rotate("c1").getOrThrow()
        assertArrayEquals(rotated.root, r.ensureRoot("c1").getOrThrow().root)
    }

    @Test
    fun rotationOnAnUnknownChatStartsAtVersionOne() = runBlocking {
        val (r, _, _) = repo()
        assertEquals(1, r.rotate("brand-new").getOrThrow().rootVersion)
    }

    @Test
    fun rotationFailsClosedAndLeavesTheKeyringIntact() = runBlocking {
        val (r, _, store) = repo()
        val v1 = r.ensureRoot("c1").getOrThrow()
        store.saveFails = true

        assertTrue(r.rotate("c1").isFailure)

        store.saveFails = false
        r.clearCache()
        val reloaded = r.load().getOrThrow()
        assertEquals("the failed rotation must not have been recorded", 1, reloaded.entries.size)
        assertArrayEquals(v1.root, reloaded.latest("c1")!!.root)
    }

    // ------------------------------------------------------------------ lookup

    @Test
    fun findReturnsNullForAVersionThisDeviceDoesNotHold() = runBlocking {
        val (r, _, _) = repo()
        r.ensureRoot("c1").getOrThrow()
        assertNull(r.find("c1", 99).getOrThrow())
        assertNull(r.find("other", 1).getOrThrow())
    }

    @Test
    fun keyringSurvivesAFullSealStoreLoadRoundTrip() = runBlocking {
        val store = FakeStore()
        val a = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val c1 = a.ensureRoot("c1").getOrThrow()
        val c2 = a.rotate("c2").getOrThrow()

        // A second repository over the same storage - i.e. a fresh process.
        val b = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val loaded = b.load().getOrThrow()
        assertEquals(2, loaded.entries.size)
        assertArrayEquals(c1.root, loaded.find("c1", 1)!!.root)
        assertArrayEquals(c2.root, loaded.find("c2", 1)!!.root)
    }

    // ------------------------------------------------------------------ cold start / cache

    /**
     * The cold-start requirement: after a restart with the vault LOCKED, archived history must
     * still be readable. Only the Keystore cache can satisfy this - the MK-sealed copy cannot.
     */
    @Test
    fun coldStartReadsTheKeyringWithTheVaultLocked() = runBlocking {
        val vault = FakeVault()
        val store = FakeStore()
        val first = HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val minted = first.ensureRoot("c1").getOrThrow()
        assertTrue("a cache copy must have been written", store.cache != null)

        // Fresh process, vault never unlocked.
        val lockedVault = FakeVault().also { it.locked = true }
        val afterRestart = HistoryKeyringRepository(lockedVault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val loaded = afterRestart.load().getOrThrow()
        assertArrayEquals(
            "history must be readable after restart without a password",
            minted.root,
            loaded.latest("c1")!!.root
        )
    }

    /** Minting still needs the vault: a new root has to reach the authoritative copy. */
    @Test
    fun mintingARootStillRequiresAnUnlockedVault() = runBlocking {
        val vault = FakeVault()
        val store = FakeStore()
        HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true }).ensureRoot("c1").getOrThrow()

        val locked = HistoryKeyringRepository(FakeVault().also { it.locked = true }, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        assertTrue("existing roots stay readable", locked.load().isSuccess)
        assertTrue("but a NEW chat cannot be minted while locked", locked.ensureRoot("c2").isFailure)
    }

    @Test
    fun cacheIsRefreshedWhenRecoveringThroughTheVault() = runBlocking {
        val vault = FakeVault()
        val store = FakeStore()
        val a = HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val minted = a.ensureRoot("c1").getOrThrow()

        // Simulate a device whose cache was lost but whose recovery copy survives.
        store.cache = null
        val cacheWritesBefore = store.cacheWrites

        val b = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        assertArrayEquals(minted.root, b.load().getOrThrow().latest("c1")!!.root)
        assertTrue("recovery must repopulate the cache", store.cacheWrites > cacheWritesBefore)
        assertTrue(store.cache != null)
    }

    @Test
    fun corruptCacheFallsBackToTheAuthoritativeCopy() = runBlocking {
        val store = FakeStore()
        val minted = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true }).ensureRoot("c1").getOrThrow()
        store.cache = byteArrayOf(9, 9, 9)

        val r = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        assertArrayEquals(minted.root, r.load().getOrThrow().latest("c1")!!.root)
    }

    /** ...but with no recovery copy to arbitrate, a broken cache must never read as "fresh". */
    @Test
    fun unreadableCacheWithNoRecoveryCopyFailsRatherThanMinting() = runBlocking {
        val store = FakeStore()
        store.cacheLoadFails = true
        val r = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val result = r.load()
        assertTrue("must not silently mint a replacement keyring", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("refusing to mint"),
        )
    }

    @Test
    fun corruptCacheWithLockedVaultFails() = runBlocking {
        val store = FakeStore()
        HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true }).ensureRoot("c1").getOrThrow()
        store.cache = byteArrayOf(9, 9, 9)

        val locked = HistoryKeyringRepository(FakeVault().also { it.locked = true }, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        assertTrue("no readable copy is available, so this must fail", locked.load().isFailure)
    }

    /**
     * A cache that cannot be refreshed must be REMOVED, not left behind. A stale cache is worse
     * than none: it wins on the next cold start and would serve roots that no longer match.
     */
    @Test
    fun unwritableCacheIsRemovedRatherThanLeftStale() = runBlocking {
        val store = FakeStore()
        val r = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        r.ensureRoot("c1").getOrThrow()
        assertTrue(store.cache != null)

        store.cacheSaveFails = true
        assertTrue("rotation still succeeds - the recovery copy is durable", r.rotate("c1").isSuccess)
        assertNull("the stale cache must be gone", store.cache)
        assertTrue(store.cacheDeletes > 0)
    }

    @Test
    fun failureToRemoveAStaleCacheIsReported() = runBlocking {
        val store = FakeStore()
        val r = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        r.ensureRoot("c1").getOrThrow()

        store.cacheSaveFails = true
        store.cacheDeleteFails = true
        val result = r.rotate("c1")
        assertTrue("an unremovable stale cache must surface as failure", result.isFailure)
    }

    @Test
    fun cacheHoldsTheKeyringEncodingNotTheSealedBlob() = runBlocking {
        val store = FakeStore()
        val r = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        r.ensureRoot("c1").getOrThrow()
        assertFalse(
            "the two copies must be distinct representations",
            store.cache!!.contentEquals(store.blob!!)
        )
    }

    @Test
    fun rotationStaysConsistentAcrossBothCopies() = runBlocking {
        val store = FakeStore()
        val a = HistoryKeyringRepository(FakeVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        a.ensureRoot("c1").getOrThrow()
        val v2 = a.rotate("c1").getOrThrow()

        // Cold start from the cache alone must see BOTH versions.
        val cold = HistoryKeyringRepository(FakeVault().also { it.locked = true }, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val loaded = cold.load().getOrThrow()
        assertEquals(2, loaded.entries.size)
        assertArrayEquals(v2.root, loaded.latest("c1")!!.root)
        assertTrue(loaded.find("c1", 1) != null)
    }

    @Test
    fun storedBlobIsNeverThePlainKeyringEncoding() = runBlocking {
        val (r, _, store) = repo()
        r.ensureRoot("c1").getOrThrow()
        val stored = store.blob!!
        val plainEncoding = HistoryKeyring.of(
            listOf(HistoryRootEntry("c1", 1, r.find("c1", 1).getOrThrow()!!.root))
        ).getOrThrow().encode()
        assertFalse(
            "the keyring must be persisted sealed, never as its plain encoding",
            stored.contentEquals(plainEncoding)
        )
    }
}
