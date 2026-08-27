package com.messenger.app.data.encryption.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.repository.HistoryKeyringRepository
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 5 PHASE 4 - recovery durability against the REAL Android Keystore.
 *
 * The JVM suites cover recovery policy through in-memory fakes. What only a
 * device can settle is whether the keyring genuinely survives the boundary the
 * whole design rests on: a root minted by one process must still be there, byte
 * for byte, after that process is gone. A fake store cannot fail that test, so
 * passing it in JVM proves nothing about it.
 *
 * Process death is modelled the only way it can be in-process, and the way that
 * actually matters here: every object holding cached state is discarded and
 * rebuilt over the same real Keystore. If durability depended on anything in RAM
 * rather than on what was written, these assertions break.
 *
 * Uses the production key aliases because those are what the implementation
 * writes, so @After removes both copies again. The feature is not wired to any
 * UI, so the running app neither reads nor writes them; `mls2_snapshot` is never
 * touched. All key material here is TEST-ONLY.
 */
@RunWith(AndroidJUnit4::class)
class HistoryRecoveryDurabilityTest {

    private companion object {
        const val USER = "durability-user"
        const val CHAT_A = "durability-chat-a"
        const val CHAT_B = "durability-chat-b"
    }

    /** A deterministic stand-in for MK sealing; the Keystore is what is under test. */
    private class Vault(private val user: String?) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun mask(b: ByteArray, tag: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor tag).toByte() }
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x11) + mask(plaintext, 0x11))
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x11.toByte())
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

    /** A brand-new store instance every time - no shared in-memory state. */
    private fun store(): TokenManagerImpl {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    /** A brand-new repository over a brand-new store: one simulated process. */
    private fun process(user: String? = USER) =
        HistoryKeyringRepository(Vault(user), store(), HistoryUserProvider { user }, HistoryArchiveFeature { true })

    @After
    fun cleanUp() = runBlocking {
        val s = store()
        s.deleteHistoryKeyring()
        s.deleteHistoryKeyringCache()
        Unit
    }

    // ------------------------------------------------------------ durability

    @Test
    fun aMintedRootSurvivesProcessDeath() = runBlocking {
        val minted = process().ensureRoot(CHAT_A).getOrThrow()

        // Everything from the first "process" is now unreachable.
        val reopened = process().load().getOrThrow().latest(CHAT_A)

        assertNotNull("the root must survive the process that minted it", reopened)
        assertEquals(minted.rootVersion, reopened!!.rootVersion)
        assertArrayEquals("a surviving root that changed value is worse than none", minted.root, reopened.root)
    }

    @Test
    fun ensureRootIsStableAcrossProcesses() = runBlocking {
        val first = process().ensureRoot(CHAT_A).getOrThrow()
        // A second process must ADOPT the stored root, never quietly mint a rival
        // one - two roots for one chat means half the archive cannot be opened.
        val second = process().ensureRoot(CHAT_A).getOrThrow()

        assertEquals(first.rootVersion, second.rootVersion)
        assertArrayEquals(first.root, second.root)
    }

    @Test
    fun rotationHistorySurvivesProcessDeath() = runBlocking {
        process().ensureRoot(CHAT_A).getOrThrow()
        val rotated = process().rotate(CHAT_A).getOrThrow()
        assertEquals(2, rotated.rootVersion)

        // The OLD version must still be openable - archives sealed under v1 are
        // still on the server, and an append-only keyring is what keeps them
        // readable after a rotation.
        val later = process()
        assertNotNull("v1 must not be dropped by rotation", later.find(CHAT_A, 1).getOrThrow())
        assertEquals(2, later.load().getOrThrow().latest(CHAT_A)!!.rootVersion)
    }

    @Test
    fun manyChatsSurviveTogether() = runBlocking {
        val a = process().ensureRoot(CHAT_A).getOrThrow()
        val b = process().ensureRoot(CHAT_B).getOrThrow()

        val reopened = process().load().getOrThrow()
        assertArrayEquals(a.root, reopened.latest(CHAT_A)!!.root)
        assertArrayEquals(b.root, reopened.latest(CHAT_B)!!.root)
    }

    // ------------------------------------------------------------ recovery

    @Test
    fun recoveryRestoresRootsOntoAWipedDevice() = runBlocking {
        process().ensureRoot(CHAT_A).getOrThrow()
        process().rotate(CHAT_A).getOrThrow()
        val exported = process().exportForRecovery().getOrThrow()

        // Wipe both Keystore copies: this is a fresh install of the same account.
        store().deleteHistoryKeyring()
        store().deleteHistoryKeyringCache()
        assertNull("precondition: the device really is empty", process().load().getOrThrow().latest(CHAT_A))

        process().importFromRecovery(exported).getOrThrow()

        // And it must be durable, not merely present in the importing instance.
        val after = process().load().getOrThrow()
        assertEquals(2, after.latest(CHAT_A)!!.rootVersion)
        assertNotNull("the pre-rotation root must come back too", process().find(CHAT_A, 1).getOrThrow())
    }

    @Test
    fun recoveryMergesRatherThanReplaces() = runBlocking {
        // The device holds a root the recovery blob has never seen.
        process().ensureRoot(CHAT_A).getOrThrow()
        val exported = process().exportForRecovery().getOrThrow()

        store().deleteHistoryKeyring()
        store().deleteHistoryKeyringCache()
        val local = process().ensureRoot(CHAT_B).getOrThrow()

        process().importFromRecovery(exported).getOrThrow()

        val merged = process().load().getOrThrow()
        assertNotNull("the recovered chat must arrive", merged.latest(CHAT_A))
        assertNotNull("and the local-only chat must not be destroyed by recovery", merged.latest(CHAT_B))
        assertArrayEquals(local.root, merged.latest(CHAT_B)!!.root)
    }

    @Test
    fun anUnopenableAuthoritativeCopyFailsClosed() = runBlocking {
        process().ensureRoot(CHAT_A).getOrThrow()
        val recoveryBlob = process().exportForRecovery().getOrThrow()

        // The device and recovery copies live under different AAD domains, so a
        // recovery blob is a realistic wrong-domain payload for the device slot.
        // The owned cache is removed first on purpose: a valid owned cache is
        // returned before the authoritative copy is ever read (the ratified
        // cold-start behaviour), so leaving it in place would test nothing.
        val s = store()
        s.deleteHistoryKeyringCache()
        assertTrue(s.saveHistoryKeyring(recoveryBlob).isSuccess)

        val reopened = process().load()

        assertTrue(
            "an authoritative copy that will not open must fail, never mint a replacement",
            reopened.isFailure
        )
    }

    @Test
    fun anUnreadableStateNeverSilentlyBecomesAnEmptyKeyring() = runBlocking {
        // The costliest possible failure: concluding "this device has no history"
        // while archives sealed under a real root sit on the server. An empty
        // keyring must never be the answer to an unresolved state.
        process().ensureRoot(CHAT_A).getOrThrow()
        val recoveryBlob = process().exportForRecovery().getOrThrow()

        val s = store()
        s.deleteHistoryKeyringCache()
        s.saveHistoryKeyring(recoveryBlob)

        val reopened = process().load()
        assertTrue(reopened.isFailure)
        assertNull(
            "a failed load must yield nothing at all, not an empty keyring",
            reopened.getOrNull()
        )
    }

    // ------------------------------------------------ device replacement

    /**
     * DEVICE REPLACEMENT, end to end, with real primitives throughout.
     *
     * Device A archives a message under a root it minted. The device is then
     * wiped - both Keystore slots and the generation marker - which is the
     * closest a single instrumented process can come to a physical replacement:
     * everything device-local is gone, and only the account's MK and the
     * recovery blob remain, exactly as on a new handset after sign-in.
     *
     * The claim being made is precise. This is NOT a second physical device and
     * NOT a reinstall of the APK; it is a controlled wipe of every piece of
     * state the feature keeps on the device, followed by recovery from the blob.
     * What it genuinely proves is that the recovered roots are the same bytes and
     * that ciphertext sealed before the wipe still opens after it - using the
     * real Android Keystore and the real XChaCha20-Poly1305 AEAD, not a stand-in.
     */
    @Test
    fun anArchiveSealedBeforeReplacementStillOpensAfterRecovery() = runBlocking {
        val ctx = HistoryContext(
            userId = USER, chatId = CHAT_A, messageId = "msg-replacement",
            rootVersion = 1, protocolVersion = 1
        )
        val plaintext = "history that must survive the handset".toByteArray(Charsets.UTF_8)

        // --- device A
        val rootA = process().ensureRoot(CHAT_A).getOrThrow()
        assertEquals(1, rootA.rootVersion)
        val sealed = HistoryMessageCipher.seal(rootA.root, ctx, plaintext)
        assertNotNull("sealing must succeed on a real device", sealed)
        val recoveryBlob = process().exportForRecovery().getOrThrow()

        // --- the replacement: every device-local trace goes
        val wiped = store()
        wiped.deleteHistoryKeyring()
        wiped.deleteHistoryKeyringCache()
        wiped.deleteHistoryKeyringGeneration()
        assertNull(
            "precondition: the replacement device knows nothing",
            process().load().getOrThrow().latest(CHAT_A)
        )

        // --- device B: same account, so the same MK opens the recovery blob
        process().importFromRecovery(recoveryBlob).getOrThrow()

        val recovered = process().load().getOrThrow().latest(CHAT_A)
        assertNotNull("the root must come back", recovered)
        assertArrayEquals("and it must be the SAME root, byte for byte", rootA.root, recovered!!.root)

        // The real proof: the ciphertext opens under the recovered root.
        val opened = HistoryMessageCipher.open(recovered.root, ctx, sealed!!)
        assertNotNull("archive sealed before replacement must open after it", opened)
        assertArrayEquals(plaintext, opened)

        // And the recovered material is re-bound to this account at rest.
        val cache = wiped.loadHistoryKeyringCache().getOrThrow()
        assertNotNull(cache)
        assertTrue(
            "imported roots must be stored account-bound, not trusted as they arrived",
            HistoryKeyringCacheFormat.decode(cache!!, USER)
                is HistoryKeyringCacheFormat.Decoded.Owned
        )
    }

    /** Historical versions must survive replacement, or older archives go dark. */
    @Test
    fun everyRootVersionSurvivesDeviceReplacement() = runBlocking {
        val ctx1 = HistoryContext(USER, CHAT_A, "msg-v1", 1, 1)
        val v1 = process().ensureRoot(CHAT_A).getOrThrow()
        val sealedUnderV1 = HistoryMessageCipher.seal(v1.root, ctx1, "old message".toByteArray())
        assertNotNull(sealedUnderV1)

        process().rotate(CHAT_A).getOrThrow()
        val blob = process().exportForRecovery().getOrThrow()

        val wiped = store()
        wiped.deleteHistoryKeyring()
        wiped.deleteHistoryKeyringCache()
        wiped.deleteHistoryKeyringGeneration()

        process().importFromRecovery(blob).getOrThrow()

        val restoredV1 = process().find(CHAT_A, 1).getOrThrow()
        assertNotNull("the pre-rotation root must survive replacement", restoredV1)
        assertArrayEquals(
            "a message archived before the rotation must still open",
            "old message".toByteArray(),
            HistoryMessageCipher.open(restoredV1!!.root, ctx1, sealedUnderV1!!)
        )
        assertEquals(2, process().load().getOrThrow().latest(CHAT_A)!!.rootVersion)
    }

    /** The generation marker must survive the same boundary the keyring does. */
    @Test
    fun theGenerationMarkerRoundTripsThroughRealKeystore() = runBlocking {
        process().ensureRoot(CHAT_A).getOrThrow()
        val marker = store().loadHistoryKeyringGeneration().getOrThrow()
        assertNotNull("a durable mutation must have stamped a marker", marker)

        // A fresh store instance must read back exactly what was written.
        assertEquals(marker, store().loadHistoryKeyringGeneration().getOrThrow())

        // And the cache written alongside it must carry the same stamp.
        val cacheBytes = store().loadHistoryKeyringCache().getOrThrow()
        assertNotNull(cacheBytes)
        val decoded = HistoryKeyringCacheFormat.decode(cacheBytes!!, USER)
        assertTrue(decoded is HistoryKeyringCacheFormat.Decoded.Owned)
        assertEquals(marker, (decoded as HistoryKeyringCacheFormat.Decoded.Owned).generation)
    }
}
