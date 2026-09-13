package com.messenger.app.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 25.2 PHASE 10 - memory clearing and durable ownership, composed.
 *
 * Gate 24.1 tested the memory layer alone and passed. The durable layer it
 * reloaded from was shared, so the composition still leaked: clearing the maps
 * only sent the next account to disk for the previous account's key.
 *
 * The lesson encoded here: a reload is not evidence. Every assertion below names
 * WHOSE material came back.
 *
 * Real TokenManagerImpl, real prefs, one process, no fakes.
 */
@RunWith(AndroidJUnit4::class)
class CrossLayerAccountIsolationTest {

    private companion object {
        const val A = "5555aaaa-0000-0000-0000-0000000055aa"
        const val B = "6666bbbb-0000-0000-0000-0000000066bb"
        const val CHAT = "chat-both-accounts-open"
        const val PEER = "the-peer"

        val A_SENDER = "a1".repeat(32)
        val A_PEER_SENDER = "a2".repeat(32)
        val A_PIN = "a3".repeat(32)
        val A_PENDING = "a4".repeat(32)
        val A_RATCHET = """{"rk":"${"a5".repeat(32)}","seq":9}"""
        val A_KEYRING = "A-KEYRING".toByteArray()

        val B_SENDER = "b1".repeat(32)
        val B_PIN = "b3".repeat(32)
        val B_RATCHET = """{"rk":"${"b5".repeat(32)}","seq":1}"""
        val B_KEYRING = "B-KEYRING".toByteArray()
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tm: TokenManagerImpl

    @Before
    fun setUp() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    /** Everything account A durably owns for this chat. */
    private suspend fun establishA() {
        tm.saveCurrentUserId(A)
        tm.saveGroupSenderKey(A, CHAT, "1:$A_SENDER")
        tm.savePeerSenderKey(A, CHAT, PEER, 1, A_PEER_SENDER)
        tm.saveKnownPublicKey(A, CHAT, A_PIN)
        tm.savePendingPublicKey(A, CHAT, A_PENDING)
        tm.markSafetyVerified(A, CHAT, A_PIN)
        tm.saveDirectRatchet(A, CHAT, A_RATCHET)
        tm.saveHistoryKeyringCache(A, A_KEYRING)
        tm.saveHistoryKeyring(A, A_KEYRING)
        tm.saveHistoryKeyringGeneration(A, 7L)
    }

    private suspend fun signIn(account: String) {
        tm.clearTokens()
        tm.saveCurrentUserId(account)
    }

    /** Every reload path, asked as account [who]. */
    private suspend fun reloadEverythingAs(who: String) = listOf(
        "ownSenderKeys" to tm.loadGroupSenderKeys(who, CHAT).getOrNull().orEmpty().values.toList(),
        "peerSenderKeys" to tm.loadPeerSenderKeys(who, CHAT).getOrNull().orEmpty().values.toList(),
        "pinned" to listOfNotNull(tm.getKnownPublicKey(who, CHAT).getOrNull()),
        "pending" to listOfNotNull(tm.getPendingPublicKey(who, CHAT).getOrNull()),
        "ratchet" to listOfNotNull(tm.loadDirectRatchet(who, CHAT).getOrNull()),
        "keyringCache" to listOfNotNull(tm.loadHistoryKeyringCache(who).getOrNull()?.let { String(it) }),
        "keyring" to listOfNotNull(tm.loadHistoryKeyring(who).getOrNull()?.let { String(it) })
    )

    @Test
    fun accountBReceivesNoneOfAccountAsDurableMaterial() = runBlocking {
        establishA()
        signIn(B)

        val aMaterial = listOf(A_SENDER, A_PEER_SENDER, A_PIN, A_PENDING, A_RATCHET, String(A_KEYRING))
        for ((label, values) in reloadEverythingAs(B)) {
            for (v in values) {
                assertFalse(
                    "PROVEN BROKEN: account B's $label reload returned account A's " +
                        "material ($v). A reload happening is not the property - " +
                        "WHOSE material came back is.",
                    aMaterial.any { it.equals(v, ignoreCase = true) }
                )
            }
        }
        assertFalse(
            "PROVEN BROKEN: B inherited A's safety verification",
            tm.isSafetyVerified(B, CHAT).getOrNull() == true
        )
        assertNull(
            "PROVEN BROKEN: B inherited A's keyring generation marker",
            tm.loadHistoryKeyringGeneration(B).getOrNull()
        )
    }

    @Test
    fun accountAReconstructsOnlyItsOwnMaterialAfterTheRoundTrip() = runBlocking {
        establishA()

        // B signs in and builds its own independent state on the same chat id.
        signIn(B)
        tm.saveGroupSenderKey(B, CHAT, "1:$B_SENDER")
        tm.saveKnownPublicKey(B, CHAT, B_PIN)
        tm.saveDirectRatchet(B, CHAT, B_RATCHET)
        tm.saveHistoryKeyringCache(B, B_KEYRING)

        // Back to A. Every slot must hold exactly what A left.
        signIn(A)
        assertEquals(
            "A's own Sender Key did not come back",
            listOf(A_SENDER),
            tm.loadGroupSenderKeys(A, CHAT).getOrNull()?.values?.toList()
        )
        assertEquals("A's pin did not come back", A_PIN, tm.getKnownPublicKey(A, CHAT).getOrNull())
        assertEquals("A's ratchet did not come back", A_RATCHET, tm.loadDirectRatchet(A, CHAT).getOrNull())
        assertEquals(
            "A's keyring cache did not come back",
            String(A_KEYRING), tm.loadHistoryKeyringCache(A).getOrNull()?.let { String(it) }
        )
        assertTrue(
            "A's safety verification did not survive the round trip",
            tm.isSafetyVerified(A, CHAT).getOrNull() == true
        )
        assertEquals(
            "A's keyring generation marker did not come back. The marker decides " +
                "whether a cache is trusted, so reading someone else's - or none - " +
                "silently changes which keyring is believed.",
            7L, tm.loadHistoryKeyringGeneration(A).getOrNull()
        )

        // ...and B's remains B's.
        assertEquals(
            "B's own Sender Key was disturbed by A",
            listOf(B_SENDER),
            tm.loadGroupSenderKeys(B, CHAT).getOrNull()?.values?.toList()
        )
        assertEquals("B's pin was disturbed by A", B_PIN, tm.getKnownPublicKey(B, CHAT).getOrNull())
    }

    /**
     * A fresh TokenManagerImpl reads only what is on disk - the closest this
     * harness gets to a process restart without killing the instrumentation.
     */
    @Test
    fun theSamePropertyHoldsForAFreshStoreInstance() = runBlocking {
        establishA()
        signIn(B)

        val fresh = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        assertTrue(
            "PROVEN BROKEN: a cold store handed B account A's Sender Key",
            fresh.loadGroupSenderKeys(B, CHAT).getOrNull().orEmpty().isEmpty()
        )
        assertNull(
            "PROVEN BROKEN: a cold store handed B account A's ratchet",
            fresh.loadDirectRatchet(B, CHAT).getOrNull()
        )
        assertNull(
            "PROVEN BROKEN: a cold store handed B account A's history keyring cache",
            fresh.loadHistoryKeyringCache(B).getOrNull()
        )
        assertEquals(
            "a cold store lost account A's own Sender Key",
            listOf(A_SENDER),
            fresh.loadGroupSenderKeys(A, CHAT).getOrNull()?.values?.toList()
        )
    }

    /**
     * PHASE 3 - a durable write that outlives its account.
     *
     * The owner is captured before the (simulated) network call. The account
     * changes during it. The write must still land in the namespace of the
     * account the operation began as, and must not appear in the new one.
     *
     * "Current account at commit time" is not ownership - Gate 22.1's rule,
     * applied to durable state.
     */
    @Test
    fun aDurableWriteCarriesTheOwnerCapturedAtOperationStart() = runBlocking {
        tm.saveCurrentUserId(A)
        val capturedOwner = tm.getCurrentUserId().getOrNull().orEmpty()
        assertEquals(A, capturedOwner)

        // ... the account switches while the operation is in flight ...
        signIn(B)

        // ... and the reply lands, still owned by A.
        tm.saveGroupSenderKey(capturedOwner, CHAT, "3:$A_SENDER")
        tm.saveDirectRatchet(capturedOwner, CHAT, A_RATCHET)
        tm.saveKnownPublicKey(capturedOwner, CHAT, A_PIN)

        assertTrue(
            "PROVEN BROKEN: a write owned by A landed in B's namespace because B " +
                "was the current account when it committed",
            tm.loadGroupSenderKeys(B, CHAT).getOrNull().orEmpty().isEmpty()
        )
        assertNull(
            "PROVEN BROKEN: a ratchet owned by A landed in B's namespace",
            tm.loadDirectRatchet(B, CHAT).getOrNull()
        )
        assertNull(
            "PROVEN BROKEN: a pin owned by A landed in B's namespace",
            tm.getKnownPublicKey(B, CHAT).getOrNull()
        )
        assertEquals(
            "the write did not reach its own account either - it must be filed " +
                "under A, not discarded",
            A_RATCHET, tm.loadDirectRatchet(A, CHAT).getOrNull()
        )
    }

    /** An empty owner must never address a slot. */
    @Test
    fun anUnownedDurableWriteIsRefused() = runBlocking {
        val failed = runCatching { tm.saveGroupSenderKey("", CHAT, "1:$A_SENDER") }
        val alsoFailed = runCatching { tm.loadGroupSenderKeys("   ", CHAT) }
        assertTrue(
            "PROVEN BROKEN: a durable write with no owner was accepted, which is " +
                "how an unattributable slot gets created in the first place",
            failed.isFailure || failed.getOrNull()?.isFailure == true
        )
        assertTrue(
            "PROVEN BROKEN: a durable read with a blank owner was accepted",
            alsoFailed.isFailure || alsoFailed.getOrNull()?.isFailure == true
        )
    }
}
