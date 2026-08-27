package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyring
import com.messenger.app.data.encryption.history.HistoryKeyringCacheFormat
import com.messenger.app.data.encryption.history.HistoryKeyringMerge
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryRootEntry
import com.messenger.app.data.encryption.history.HistoryUserProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 5 PHASE 1 - history keyring recovery, JVM.
 *
 * Covers the union merge rules, the dedicated recovery AAD domain, and the
 * import path's account binding and fail-closed behaviour. The real repository
 * and the real merge object are exercised; only the vault and store are fakes.
 *
 * All key material is TEST-ONLY.
 */
class HistoryKeyringRecoveryTest {

    private companion object {
        const val USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val CHAT = "chat-1"
        const val OTHER = "chat-2"
    }

    private fun root(seed: Byte) = ByteArray(32) { seed }
    private fun entry(chat: String, version: Int, seed: Byte) =
        HistoryRootEntry(chat, version, root(seed))
    private fun keyring(vararg e: HistoryRootEntry) = HistoryKeyring.of(e.toList()).getOrThrow()

    private class DeviceStore : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
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

    /** Models MK-sealing: the owner is tagged in, and a different owner cannot open. */
    private class AccountVault(var user: String?, var locked: Boolean = false) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        private fun seal(domain: String, plaintext: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            return Result.success(("$domain|$u|").toByteArray(Charsets.UTF_8) + plaintext.copyOf())
        }

        private fun open(domain: String, sealed: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            val prefix = ("$domain|$u|").toByteArray(Charsets.UTF_8)
            if (sealed.size < prefix.size ||
                !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)
            ) {
                return Result.failure(IllegalStateException("failed authentication"))
            }
            return Result.success(sealed.copyOfRange(prefix.size, sealed.size))
        }

        override suspend fun sealHistoryKeyring(plaintext: ByteArray) = seal("local", plaintext)
        override suspend fun openHistoryKeyring(sealed: ByteArray) = open("local", sealed)
        override suspend fun sealForRecovery(plaintext: ByteArray) = seal("recovery", plaintext)
        override suspend fun openFromRecovery(sealed: ByteArray) = open("recovery", sealed)
    }

    private fun repo(store: DeviceStore, vault: AccountVault, user: String?) =
        HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, HistoryArchiveFeature { true })

    // ------------------------------------------------------------------ merge rules

    @Test
    fun remoteOnlyRootsAreImported() {
        val merged = HistoryKeyringMerge.union(
            keyring(entry(CHAT, 1, 1)),
            keyring(entry(OTHER, 1, 2))
        ).getOrThrow()
        assertEquals(2, merged.entries.size)
        assertNotNull(merged.find(OTHER, 1))
    }

    @Test
    fun localOnlyRootsArePreserved() {
        val merged = HistoryKeyringMerge.union(
            keyring(entry(CHAT, 1, 1), entry(OTHER, 3, 9)),
            keyring(entry(CHAT, 1, 1))
        ).getOrThrow()
        assertEquals(2, merged.entries.size)
        assertArrayEquals(root(9), merged.find(OTHER, 3)!!.root)
    }

    @Test
    fun identicalDuplicatesAreAccepted() {
        val merged = HistoryKeyringMerge.union(
            keyring(entry(CHAT, 1, 5)),
            keyring(entry(CHAT, 1, 5))
        ).getOrThrow()
        assertEquals(1, merged.entries.size)
        assertArrayEquals(root(5), merged.find(CHAT, 1)!!.root)
    }

    /** The security rule: two different roots under one identity is never "last write wins". */
    @Test
    fun conflictingRootsForOneIdentityFailClosed() {
        val result = HistoryKeyringMerge.union(
            keyring(entry(CHAT, 1, 5)),
            keyring(entry(CHAT, 1, 6))
        )
        assertTrue("conflicting roots must abort the import", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("conflict"))
    }

    @Test
    fun aRemoteKeyringNeverDeletesALocalEntry() {
        val local = keyring(entry(CHAT, 1, 1), entry(CHAT, 2, 2), entry(OTHER, 1, 3))
        val merged = HistoryKeyringMerge.union(local, keyring()).getOrThrow()
        assertEquals(3, merged.entries.size)
        for (v in 1..2) assertNotNull(merged.find(CHAT, v))
        assertNotNull(merged.find(OTHER, 1))
    }

    @Test
    fun newerLocalVersionsSurviveAnOlderRemoteKeyring() {
        val local = keyring(entry(CHAT, 1, 1), entry(CHAT, 2, 2), entry(CHAT, 3, 3))
        val remote = keyring(entry(CHAT, 1, 1))
        val merged = HistoryKeyringMerge.union(local, remote).getOrThrow()
        assertEquals(3, merged.entries.size)
        assertEquals("the newest version must not be downgraded", 3, merged.latest(CHAT)!!.rootVersion)
    }

    @Test
    fun mergeIsOrderIndependentForCompatibleKeyrings() {
        val a = keyring(entry(CHAT, 1, 1), entry(OTHER, 2, 4))
        val b = keyring(entry(CHAT, 2, 2))
        val ab = HistoryKeyringMerge.union(a, b).getOrThrow()
        val ba = HistoryKeyringMerge.union(b, a).getOrThrow()
        assertEquals(ab.entries.size, ba.entries.size)
        assertArrayEquals(ab.encode(), ba.encode())
    }

    // ------------------------------------------------------------------ recovery import

    @Test
    fun recoveryImportRoundTripsThroughTheDedicatedDomain() = runBlocking {
        val store = DeviceStore()
        val vault = AccountVault(USER_A)
        val source = repo(store, vault, USER_A)
        source.ensureRoot(CHAT).getOrThrow()

        val exported = source.exportForRecovery().getOrThrow()
        val sealed = vault.sealForRecovery(exported).getOrThrow()
        val reopened = vault.openFromRecovery(sealed).getOrThrow()
        assertArrayEquals(exported, reopened)
    }

    /** A recovery blob must not open as a local keyring, or vice versa. */
    @Test
    fun recoveryAndLocalDomainsDoNotInterchange() = runBlocking {
        val vault = AccountVault(USER_A)
        val payload = keyring(entry(CHAT, 1, 1)).encode()

        val recoverySealed = vault.sealForRecovery(payload).getOrThrow()
        assertTrue(
            "a recovery blob must not open as a local keyring",
            vault.openHistoryKeyring(recoverySealed).isFailure
        )
        val localSealed = vault.sealHistoryKeyring(payload).getOrThrow()
        assertTrue(
            "a local keyring must not open as a recovery blob",
            vault.openFromRecovery(localSealed).isFailure
        )
    }

    @Test
    fun aBlobSealedForAnotherUserCannotBeOpened() = runBlocking {
        val payload = keyring(entry(CHAT, 1, 1)).encode()
        val sealedByA = AccountVault(USER_A).sealForRecovery(payload).getOrThrow()
        assertTrue(
            "B must not be able to open A's recovery blob",
            AccountVault(USER_B).openFromRecovery(sealedByA).isFailure
        )
    }

    /** An E2EE reset mints a fresh MK; the old blob must simply not open. */
    @Test
    fun aBlobSealedUnderAPreviousMkCannotBeOpened() = runBlocking {
        val payload = keyring(entry(CHAT, 1, 1)).encode()
        val oldMk = AccountVault(USER_A)
        val sealed = oldMk.sealForRecovery(payload).getOrThrow()

        // Same account, different key material - modelled by a different domain owner.
        val afterReset = AccountVault("$USER_A-post-reset")
        assertTrue(afterReset.openFromRecovery(sealed).isFailure)
    }

    /**
     * Corruption of the sealed envelope is refused.
     *
     * This asserts what the fake vault can actually model - a damaged domain or
     * owner header fails to open. Bit-level AEAD integrity inside the payload is
     * NOT asserted here, because the fake has no MAC; that property belongs to
     * XChaCha20-Poly1305 and is proven against libsodium in the Gate 2
     * instrumented suite (`flippingAnyBitAnywhereFails`).
     */
    @Test
    fun corruptedBlobEnvelopeIsRejected() = runBlocking {
        val vault = AccountVault(USER_A)
        val sealed = vault.sealForRecovery(keyring(entry(CHAT, 1, 1)).encode()).getOrThrow()

        val damagedHeader = sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(vault.openFromRecovery(damagedHeader).isFailure)
        assertTrue(vault.openFromRecovery(ByteArray(0)).isFailure)
        assertTrue(vault.openFromRecovery(byteArrayOf(1, 2, 3)).isFailure)
    }

    /** A structurally invalid payload is refused by the keyring decoder itself. */
    @Test
    fun structurallyInvalidPayloadIsRejected() {
        for (bad in listOf(byteArrayOf(9, 9, 9), byteArrayOf(1), ByteArray(0))) {
            assertTrue(HistoryKeyring.decode(bad).isFailure)
        }
    }

    @Test
    fun malformedKeyringPayloadIsRejectedOnImport() = runBlocking {
        val store = DeviceStore()
        val r = repo(store, AccountVault(USER_A), USER_A)
        assertTrue(r.importFromRecovery(byteArrayOf(9, 9, 9)).isFailure)
        assertTrue(r.importFromRecovery(ByteArray(0)).isFailure)
    }

    @Test
    fun importMergesRemoteRootsAndPreservesLocalOnes() = runBlocking {
        val store = DeviceStore()
        val vault = AccountVault(USER_A)
        val r = repo(store, vault, USER_A)
        val localRoot = r.ensureRoot(CHAT).getOrThrow().root

        val remote = keyring(entry(OTHER, 1, 7)).encode()
        val merged = r.importFromRecovery(remote).getOrThrow()

        assertEquals(2, merged.entries.size)
        assertArrayEquals("the local root must survive", localRoot, merged.find(CHAT, 1)!!.root)
        assertArrayEquals("the remote root must be imported", root(7), merged.find(OTHER, 1)!!.root)
    }

    @Test
    fun importDoesNotMintOrRotateAnyRoot() = runBlocking {
        val store = DeviceStore()
        val r = repo(store, AccountVault(USER_A), USER_A)
        r.ensureRoot(CHAT).getOrThrow()

        val before = r.load().getOrThrow()
        r.importFromRecovery(keyring(entry(CHAT, 1, before.find(CHAT, 1)!!.root[0])).encode())

        val after = r.load().getOrThrow()
        assertEquals("recovery must not add versions", before.entries.size, after.entries.size)
        assertEquals(1, after.latest(CHAT)!!.rootVersion)
    }

    /** Imported material is re-bound to the importing account before it is persisted. */
    @Test
    fun importedRootsAreReboundToTheImportingAccount() = runBlocking {
        val store = DeviceStore()
        val r = repo(store, AccountVault(USER_B), USER_B)
        r.importFromRecovery(keyring(entry(CHAT, 1, 4)).encode()).getOrThrow()

        assertNotNull("a cache must have been written", store.cache)
        assertEquals(
            HistoryKeyringCacheFormat.CACHE_FORMAT_VERSION, store.cache!![0].toInt()
        )
        val decoded = HistoryKeyringCacheFormat.decode(store.cache, USER_B)
        assertTrue("the cache must be bound to the importer", decoded is HistoryKeyringCacheFormat.Decoded.Owned)
        assertTrue(
            "and must be foreign to anyone else",
            HistoryKeyringCacheFormat.decode(store.cache, USER_A)
                is HistoryKeyringCacheFormat.Decoded.ForAnotherUser
        )
    }

    @Test
    fun conflictingImportLeavesTheLocalKeyringUntouched() = runBlocking {
        val store = DeviceStore()
        val r = repo(store, AccountVault(USER_A), USER_A)
        val localRoot = r.ensureRoot(CHAT).getOrThrow().root

        // Same (chat, version), different material.
        val hostile = keyring(entry(CHAT, 1, 99)).encode()
        assertTrue(r.importFromRecovery(hostile).isFailure)

        r.clearCache()
        assertArrayEquals(
            "a refused import must not have changed anything",
            localRoot, r.load().getOrThrow().find(CHAT, 1)!!.root
        )
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    fun exportFailsClosedWhenTheKeyringCannotBeRead() = runBlocking {
        val store = DeviceStore()
        // A legacy, unbound cache: ownership unknown, so the keyring is unusable.
        store.cache = keyring(entry(CHAT, 1, 1)).encode()
        val r = repo(store, AccountVault(USER_A, locked = true), USER_A)
        assertTrue("an unreadable keyring must never export as empty", r.exportForRecovery().isFailure)
    }

    @Test
    fun importFailsClosedWhenTheVaultIsLocked() = runBlocking {
        val store = DeviceStore()
        val unlocked = repo(store, AccountVault(USER_A), USER_A)
        unlocked.ensureRoot(CHAT).getOrThrow()

        // New material to merge, but nothing can be re-sealed while locked.
        val locked = repo(store, AccountVault(USER_A, locked = true), USER_A)
        locked.clearCache()
        store.cache = null
        assertTrue(locked.importFromRecovery(keyring(entry(OTHER, 1, 8)).encode()).isFailure)
    }

    @Test
    fun missingIdentityFailsClosedOnImport() = runBlocking {
        val store = DeviceStore()
        val r = repo(store, AccountVault(null), null)
        assertTrue(r.importFromRecovery(keyring(entry(CHAT, 1, 1)).encode()).isFailure)
    }

    @Test
    fun aNoOpImportSucceedsWithoutRequiringAnUnlockedVault() = runBlocking {
        val store = DeviceStore()
        val r = repo(store, AccountVault(USER_A), USER_A)
        val localRoot = r.ensureRoot(CHAT).getOrThrow().root

        // Remote contributes nothing new. The remote entry must carry the ACTUAL
        // local root - a synthesized one would differ and count as a conflict.
        val same = keyring(HistoryRootEntry(CHAT, 1, localRoot)).encode()
        val merged = r.importFromRecovery(same)
        assertTrue("an import that adds nothing must not fail", merged.isSuccess)
        assertFalse(merged.getOrThrow().entries.isEmpty())
    }
}
