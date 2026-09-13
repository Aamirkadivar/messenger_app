package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exactly when the vault is required to archive - the question two earlier phases got wrong in
 * opposite directions.
 *
 * Phase 22 saw a seal fail after a restart and concluded a locked vault stops archiving outright.
 * Phase 26 saw a seal succeed after the same restart and concluded the vault must now unlock by
 * itself. Both were reading one case and generalising.
 *
 * The real rule is a property of the KEYRING, not of the session:
 *
 *  - the master key is never persisted, so the vault really is locked after process death;
 *  - but `ensureRoot` serves an EXISTING root from the Keystore cache and writes nothing, so it
 *    needs no vault at all;
 *  - only MINTING a root mutates the keyring, and only a mutation reaches
 *    `persistLocked -> sealHistoryKeyring`, which is what needs the master key.
 *
 * So a locked vault blocks archiving in a chat that has never been archived, and does not block it
 * in a chat that has. These tests pin that boundary in both directions.
 */
class HistoryRootVaultDependencyTest {

    private val user = "1a9713a1-0000-4000-8000-000000000001"
    private val archivedChat = "027f8fe0-94ca-4f78-9852-a373a776ae06"
    private val freshChat = "26af657d-1df3-44a3-85c8-d2dc94a56550"

    private fun keyring(vault: HistoryKeyringVault, store: HistoryKeyringStore) =
        HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, HistoryArchiveFeature { true })

    /**
     * The Phase-26 case. A root minted while the vault was open stays usable after it locks,
     * because reading it touches no vault and writes nothing.
     */
    @Test
    fun anExistingRootIsServedWithoutTheVault() = runBlocking {
        val store = MemoryKeyringStore()
        val minted = keyring(PassthroughVault(), store).ensureRoot(archivedChat).getOrThrow()

        val afterLock = keyring(LockedVault(), store).ensureRoot(archivedChat)

        assertTrue("an existing root must not require the vault", afterLock.isSuccess)
        assertEquals(minted.rootVersion, afterLock.getOrThrow().rootVersion)
    }

    /**
     * The Phase-22 case, and the one that actually loses archives. A chat with no root needs one
     * minted, minting mutates the keyring, and persisting that mutation needs the master key.
     */
    @Test
    fun mintingANewRootRequiresTheVault() = runBlocking {
        val store = MemoryKeyringStore()
        keyring(PassthroughVault(), store).ensureRoot(archivedChat).getOrThrow()

        val fresh = keyring(LockedVault(), store).ensureRoot(freshChat)

        assertTrue("a chat with no root cannot be archived while the vault is locked", fresh.isFailure)
        assertTrue(
            "the reason must name the locked vault",
            fresh.exceptionOrNull()?.message.orEmpty().contains("locked")
        )
    }

    /** And once the vault is available again, the same call mints and succeeds - the retry path. */
    @Test
    fun theSameChatSealsOnceTheVaultIsAvailable() = runBlocking {
        val store = MemoryKeyringStore()
        assertTrue(keyring(LockedVault(), store).ensureRoot(freshChat).isFailure)

        val afterUnlock = keyring(PassthroughVault(), store).ensureRoot(freshChat)

        assertTrue(afterUnlock.isSuccess)
        assertEquals(1, afterUnlock.getOrThrow().rootVersion)
    }

    /**
     * A locked vault must never be answered with an empty keyring: the caller would mint a fresh
     * root over live archives and orphan everything sealed under the old one.
     */
    @Test
    fun aLockedVaultNeverYieldsAnEmptyKeyring() = runBlocking {
        val store = MemoryKeyringStore()
        keyring(PassthroughVault(), store).ensureRoot(archivedChat).getOrThrow()
        store.wipeCacheOnly()

        val result = keyring(LockedVault(), store).ensureRoot(archivedChat)

        assertTrue("with no cache and no vault this must fail, not invent a keyring", result.isFailure)
    }

    // ------------------------------------------------------------------ rig

    private class PassthroughVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x7F) + plaintext.copyOf())
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte()) {
                Result.success(sealed.copyOfRange(1, sealed.size))
            } else {
                Result.failure(IllegalStateException("not sealed"))
            }
    }

    private class LockedVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("history keyring cannot be sealed: vault is locked"))
        override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("history keyring cannot be opened: vault is locked"))
    }

    /** Models the two copies the production store keeps: MK-sealed authoritative, plus Keystore cache. */
    private class MemoryKeyringStore : HistoryKeyringStore {
        private var blob: ByteArray? = null
        private var cache: ByteArray? = null
        private var generation: Long? = null
        fun wipeCacheOnly() { cache = null }
        override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring(owner: String) = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring(owner: String) = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(owner: String, plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache(owner: String) = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache(owner: String) = Result.success(Unit).also { cache = null }
        override suspend fun saveHistoryKeyringGeneration(owner: String, generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration(owner: String) = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration(owner: String) =
            Result.success(Unit).also { generation = null }
    }
}
