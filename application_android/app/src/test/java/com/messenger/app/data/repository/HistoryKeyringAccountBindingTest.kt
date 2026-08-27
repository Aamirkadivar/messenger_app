package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyring
import com.messenger.app.data.encryption.history.HistoryKeyringCacheFormat
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 4 PHASE 4 - the Keystore keyring cache is account-bound, JVM.
 *
 * The defect this closes: the cold-start cache lived under one global preferences
 * key with no owner recorded, and logout clears tokens and `mls1_` state but not
 * this cache. A second account signing in on the same device read the cache first
 * and silently adopted the first account's history roots.
 *
 * These tests drive the REAL [HistoryKeyringRepository] and the REAL encoded cache
 * representation - the store is in-memory, but the bytes and the decision logic
 * are production code.
 *
 * All key material is TEST-ONLY.
 */
class HistoryKeyringAccountBindingTest {

    private companion object {
        const val USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val CHAT = "chat-1"
        const val MSG = "msg-1"
        const val TEXT = "phase 4 canary"
    }

    /** Shared device storage: survives "logout" exactly as SharedPreferences does. */
    private class DeviceStore : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        var cacheSaveFails = false

        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray): Result<Unit> =
            if (cacheSaveFails) Result.failure(IllegalStateException("Keystore unavailable"))
            else Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() = Result.success(Unit).also { cache = null }

        private var generation: Long? = null
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    /**
     * The authoritative copy is MK-sealed and user-bound in production. Modelled
     * here by tagging the owner into the sealed bytes and refusing to open under
     * a different user, which is what `historyKeyringAad(userId, …)` achieves.
     */
    private class AccountVault(var user: String?, var locked: Boolean = false) : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            return Result.success(
                byteArrayOf(0x7F) + (u + "|").toByteArray(Charsets.UTF_8) + plaintext.copyOf()
            )
        }
        override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            val prefix = byteArrayOf(0x7F) + (u + "|").toByteArray(Charsets.UTF_8)
            if (sealed.size < prefix.size || !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)) {
                return Result.failure(IllegalStateException("sealed for another account"))
            }
            return Result.success(sealed.copyOfRange(prefix.size, sealed.size))
        }
    }

    private class Cipher : ArchiveCipher {
        private fun tag(root: ByteArray, c: HistoryContext) =
            "${root.joinToString(""){ b -> "%02x".format(b) }}|${c.userId}|${c.chatId}|" +
                "${c.messageId}|${c.rootVersion}|${c.protocolVersion}"
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) =
            (tag(historyRoot, ctx) + "|" + String(plaintext, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? {
            val text = String(sealed, Charsets.UTF_8)
            val prefix = tag(historyRoot, ctx) + "|"
            return if (text.startsWith(prefix)) text.removePrefix(prefix).toByteArray(Charsets.UTF_8) else null
        }
    }

    /** One signed-in session over shared device storage. */
    private fun session(store: DeviceStore, user: String?, locked: Boolean = false):
        Pair<HistoryKeyringRepository, MessageArchiver> {
        val keyring = HistoryKeyringRepository(
            AccountVault(user, locked), store, HistoryUserProvider { user },
            HistoryArchiveFeature.Enabled
        )
        return keyring to MessageArchiver(keyring, Cipher(), HistoryArchiveFeature.Enabled)
    }

    // ------------------------------------------------------------------ 1-3. binding

    @Test
    fun cacheWrittenForUserAIsRejectedForUserB() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        val aRoot = a.ensureRoot(CHAT).getOrThrow().root
        assertTrue("A must have written a cache", store.cache != null)

        // B signs in on the same device. A's cache is still physically present.
        val (b, _) = session(store, USER_B)
        val bKeyring = b.load().getOrThrow()
        assertEquals("B must not inherit A's roots", 0, bKeyring.entries.size)

        val bRoot = b.ensureRoot(CHAT).getOrThrow().root
        assertFalse("B's root must not equal A's", aRoot.contentEquals(bRoot))
    }

    @Test
    fun cacheWrittenForUserBIsAcceptedForUserB() = runBlocking {
        val store = DeviceStore()
        val (b1, _) = session(store, USER_B)
        val minted = b1.ensureRoot(CHAT).getOrThrow().root

        // Fresh process, same account, vault LOCKED - the cold-start path.
        val (b2, _) = session(store, USER_B, locked = true)
        assertArrayEquals(
            "B's own cache must still serve a cold start",
            minted,
            b2.load().getOrThrow().latest(CHAT)!!.root
        )
    }

    @Test
    fun legacyUnboundCacheIsRejected() = runBlocking {
        val store = DeviceStore()
        // Exactly what the old code wrote: the bare keyring encoding, no owner.
        store.cache = HistoryKeyring.of(emptyList()).getOrThrow().encode()
        assertEquals(
            "a legacy cache begins with the keyring format version",
            HistoryKeyring.FORMAT_VERSION, store.cache!![0].toInt()
        )

        val (r, _) = session(store, USER_A, locked = true)
        val result = r.load()
        assertTrue(
            "legacy material has unknown ownership and must not be used",
            result.isFailure
        )
    }

    // ------------------------------------------------------------------ 4-5. account switching

    @Test
    fun logoutThenLoginAsDifferentUserCannotReuseRoots() = runBlocking {
        val store = DeviceStore()
        val (a, aArch) = session(store, USER_A)
        a.ensureRoot(CHAT).getOrThrow()
        val aSealed = aArch.seal(USER_A, CHAT, MSG, TEXT).getOrThrow()

        // Logout clears tokens; the cache and the authoritative blob physically remain.
        assertTrue(store.cache != null && store.blob != null)

        val (b, bArch) = session(store, USER_B)
        val bSealed = bArch.seal(USER_B, CHAT, "msg-b", TEXT).getOrThrow()
        assertNotEquals(
            "B's archive must not be produced under A's root",
            aSealed.ciphertextB64, bSealed.ciphertextB64
        )
    }

    @Test
    fun switchingBackToOriginalUserDoesNotSilentlyReuseAnotherUsersRoots() = runBlocking {
        val store = DeviceStore()
        val (a1, _) = session(store, USER_A)
        val aRoot = a1.ensureRoot(CHAT).getOrThrow().root

        // B signs in, mints its own root, and overwrites the cache with B's.
        val (b, _) = session(store, USER_B)
        val bRoot = b.ensureRoot(CHAT).getOrThrow().root
        assertFalse(aRoot.contentEquals(bRoot))

        // A signs back in. The cache now belongs to B, so it must be refused; A's
        // own authoritative copy is what may restore A - and B overwrote it here,
        // so A must fail closed rather than adopt B's root.
        val (a2, _) = session(store, USER_A)
        val reloaded = a2.load()
        if (reloaded.isSuccess) {
            val root = reloaded.getOrThrow().latest(CHAT)?.root
            if (root != null) {
                assertFalse("A must never end up holding B's root", root.contentEquals(bRoot))
            }
        }
    }

    /** Process death between logout and the next login changes nothing: the binding is on disk. */
    @Test
    fun processDeathBetweenLogoutAndLoginDoesNotLeakRoots() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        val aRoot = a.ensureRoot(CHAT).getOrThrow().root
        // No clearCache() call, no graceful shutdown - just a new process as B.
        val (b, _) = session(store, USER_B)
        assertEquals(0, b.load().getOrThrow().entries.size)
        assertFalse(aRoot.contentEquals(b.ensureRoot(CHAT).getOrThrow().root))
    }

    // ------------------------------------------------------------------ 6-7. fail closed

    @Test
    fun missingCurrentUserFailsClosed() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        a.ensureRoot(CHAT).getOrThrow()

        // Signed out: no identity to evaluate the binding against.
        val (none, _) = session(store, null, locked = true)
        assertTrue("no identity means no cached roots may be accepted", none.load().isFailure)
    }

    @Test
    fun corruptUserBindingFailsClosed() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        a.ensureRoot(CHAT).getOrThrow()

        // Truncate the binding header.
        store.cache = store.cache!!.copyOfRange(0, 3)
        val (r, _) = session(store, USER_A, locked = true)
        assertTrue("a corrupt binding must not be usable", r.load().isFailure)
    }

    @Test
    fun corruptPayloadBehindAValidBindingFailsClosed() = runBlocking {
        val store = DeviceStore()
        store.cache = HistoryKeyringCacheFormat.encode(USER_A, 1L, byteArrayOf(9, 9, 9))
        val (r, _) = session(store, USER_A, locked = true)
        assertTrue(r.load().isFailure)
    }

    @Test
    fun keystoreFailureOnCacheWriteStillLeavesTheRootDurable() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        store.cacheSaveFails = true
        // The authoritative copy is what makes the root durable; the cache is an
        // optimisation and a failure to write it must not fail the mint.
        assertTrue(a.ensureRoot(CHAT).isSuccess)
        assertTrue(store.blob != null)
    }

    // ------------------------------------------------------------------ 8-9. preserved behaviour

    @Test
    fun coldStartStillReadsCorrectUsersAuthoritativeKeyring() = runBlocking {
        val store = DeviceStore()
        val (a1, _) = session(store, USER_A)
        val minted = a1.ensureRoot(CHAT).getOrThrow().root

        // Cache lost (cleared by the OS), vault unlocked: recover authoritatively.
        store.cache = null
        val (a2, _) = session(store, USER_A)
        assertArrayEquals(minted, a2.load().getOrThrow().latest(CHAT)!!.root)
        assertTrue("recovery must repopulate a BOUND cache", store.cache != null)
        assertEquals(
            HistoryKeyringCacheFormat.CACHE_FORMAT_VERSION, store.cache!![0].toInt()
        )
    }

    @Test
    fun lockedVaultStillPreventsMinting() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        a.ensureRoot(CHAT).getOrThrow()

        val (cold, _) = session(store, USER_A, locked = true)
        assertTrue("existing roots stay readable from the bound cache", cold.load().isSuccess)
        assertTrue("but a NEW chat cannot be minted while locked", cold.ensureRoot("chat-2").isFailure)
    }

    // ------------------------------------------------------------------ 10. crypto separation

    @Test
    fun newArchiveForUserBIsNotDecryptableWithUserARoot() = runBlocking {
        val store = DeviceStore()
        val (_, aArch) = session(store, USER_A)
        val (bKeyring, bArch) = session(store, USER_B)

        val bSealed = bArch.seal(USER_B, CHAT, MSG, TEXT).getOrThrow()
        assertEquals(TEXT, bArch.open(USER_B, CHAT, MSG, bSealed.rootVersion, bSealed.ciphertextB64).getOrThrow())

        // A's archiver holds A's keyring; opening B's archive must fail on both
        // counts - wrong root, and userId is bound into the AAD.
        assertTrue(
            "A must not be able to open B's archive",
            aArch.open(USER_A, CHAT, MSG, bSealed.rootVersion, bSealed.ciphertextB64).isFailure
        )
        assertTrue(bKeyring.find(CHAT, bSealed.rootVersion).getOrThrow() != null)
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun concurrentLoadsUnderOneAccountStayConsistent() = runBlocking {
        val store = DeviceStore()
        val (a, _) = session(store, USER_A)
        a.ensureRoot(CHAT).getOrThrow()
        a.clearCache()

        val results = (1..8).map { async { a.load() } }.awaitAll()
        assertTrue("every concurrent load must succeed", results.all { it.isSuccess })
        val roots = results.map { it.getOrThrow().latest(CHAT)!!.root.toList() }.toSet()
        assertEquals("all loads must agree on one root", 1, roots.size)
    }

    // ------------------------------------------------------------------ format unit checks

    @Test
    fun theEncodedCacheRecordsTheOwnerAndRoundTrips() {
        val payload = HistoryKeyring.of(emptyList()).getOrThrow().encode()
        val bound = HistoryKeyringCacheFormat.encode(USER_A, 1L, payload)

        assertEquals(HistoryKeyringCacheFormat.CACHE_FORMAT_VERSION, bound[0].toInt())
        assertTrue("the owner must appear in the encoding", String(bound, Charsets.UTF_8).contains(USER_A))

        val decoded = HistoryKeyringCacheFormat.decode(bound, USER_A)
        assertTrue(decoded is HistoryKeyringCacheFormat.Decoded.Owned)
        assertArrayEquals(payload, (decoded as HistoryKeyringCacheFormat.Decoded.Owned).keyringBytes)
    }

    @Test
    fun theFormatDistinguishesForeignFromUnusable() {
        val payload = HistoryKeyring.of(emptyList()).getOrThrow().encode()
        val bound = HistoryKeyringCacheFormat.encode(USER_A, 1L, payload)

        // Wrong user is PROVABLY not ours - safe to treat as absent.
        assertTrue(
            HistoryKeyringCacheFormat.decode(bound, USER_B)
                is HistoryKeyringCacheFormat.Decoded.ForAnotherUser
        )
        // Legacy, corrupt, empty, and "no identity" are all ownership-unknown.
        for (case in listOf(payload, byteArrayOf(2), byteArrayOf(), byteArrayOf(2, 0, 0, 0, 99))) {
            assertTrue(
                "must be Unusable: ${case.toList()}",
                HistoryKeyringCacheFormat.decode(case, USER_A)
                    is HistoryKeyringCacheFormat.Decoded.Unusable
            )
        }
        assertTrue(
            HistoryKeyringCacheFormat.decode(bound, null)
                is HistoryKeyringCacheFormat.Decoded.Unusable
        )
    }
}
