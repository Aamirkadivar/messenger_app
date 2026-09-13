package com.messenger.app.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V-2 PROOF, expanded - the asset classes beyond the four originally named.
 *
 * A mechanical sweep of TokenManagerImpl found the same defect on three more,
 * two of which were verbatim stop conditions:
 *
 *     dr3_$chatId                 <- Double Ratchet session state
 *     peer_senderkey_$chatId|...  <- peers' group Sender Keys
 *     history_keyring_cache_v1    <- the history keyring, stored PLAIN
 *
 * All three lived in the single global "messenger_secure_prefs" file and
 * survived clearTokens(), which removes only tokens, current_user_id and mls1_*.
 *
 * The Keystore wrap never helped: wrapSecret() uses one DEVICE-level alias, so
 * any account on the device can unwrap any other account's value. Ownership had
 * to move into the key, and in Gate 25.2 it did.
 *
 * Real TokenManagerImpl, real prefs file, no fakes.
 */
@RunWith(AndroidJUnit4::class)
class DurableStoreExpandedScopeTest {

    private companion object {
        const val ACCOUNT_A = "aaaa3333-0000-0000-0000-0000000033aa"
        const val ACCOUNT_B = "bbbb4444-0000-0000-0000-0000000044bb"
        const val CHAT = "chat-shared-between-accounts"
        const val PEER = "peer-account-id"
        val A_RATCHET = """{"rk":"${"9".repeat(64)}","seq":7}"""
        val A_PEER_KEY = "5".repeat(64)
        val A_KEYRING = "ACCOUNT-A-HISTORY-KEYRING-PLAINTEXT".toByteArray()
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tm: TokenManagerImpl

    @Before
    fun setUp() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    private suspend fun logoutAndSignInB() {
        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_B)
    }

    /** STOP CONDITION: account-less ratchet persistence. */
    @Test
    fun accountBMustNotInheritAccountAsDirectRatchetSession() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveDirectRatchet(ACCOUNT_A, CHAT, A_RATCHET)
        check(tm.loadDirectRatchet(ACCOUNT_A, CHAT).getOrNull() != null) {
            "precondition: A stored a ratchet"
        }

        logoutAndSignInB()

        assertNull(
            "PROVEN BROKEN: account B loaded account A's DOUBLE RATCHET SESSION " +
                "state. The session carries A's root and chain keys, so B could " +
                "derive message keys for A's conversation.",
            tm.loadDirectRatchet(ACCOUNT_B, CHAT).getOrNull()
        )
    }

    @Test
    fun accountBMustNotInheritAccountAsPeerSenderKeys() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.savePeerSenderKey(ACCOUNT_A, CHAT, PEER, 1, A_PEER_KEY)
        check(tm.loadPeerSenderKeys(ACCOUNT_A, CHAT).getOrNull()?.isNotEmpty() == true) {
            "precondition: A stored a peer sender key"
        }

        logoutAndSignInB()

        val seenByB = tm.loadPeerSenderKeys(ACCOUNT_B, CHAT).getOrNull().orEmpty()
        assertFalse(
            "PROVEN BROKEN: account B loaded the peers' group Sender Keys that " +
                "account A collected. These decrypt A's group history. Saw: $seenByB",
            seenByB.values.any { it.contains(A_PEER_KEY, ignoreCase = true) }
        )
    }

    /** STOP CONDITION: account-less recovery state that can restore crypto identity. */
    @Test
    fun accountBMustNotInheritAccountAsHistoryKeyringCache() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveHistoryKeyringCache(ACCOUNT_A, A_KEYRING)
        check(tm.loadHistoryKeyringCache(ACCOUNT_A).getOrNull() != null) {
            "precondition: A cached its history keyring"
        }

        logoutAndSignInB()

        assertNull(
            "PROVEN BROKEN: account B loaded account A's HISTORY KEYRING CACHE - " +
                "the keyring in plaintext, protected only by a device-level " +
                "Keystore alias that every account on this device can unwrap. It " +
                "decrypts A's archived message history.",
            tm.loadHistoryKeyringCache(ACCOUNT_B).getOrNull()
        )
    }

    @Test
    fun accountBMustNotInheritAccountAsSealedKeyringOrGeneration() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveHistoryKeyring(ACCOUNT_A, A_KEYRING)
        tm.saveHistoryKeyringGeneration(ACCOUNT_A, 42L)

        logoutAndSignInB()

        assertNull(
            "PROVEN BROKEN: account B loaded account A's authoritative history keyring",
            tm.loadHistoryKeyring(ACCOUNT_B).getOrNull()
        )
        assertNull(
            "PROVEN BROKEN: account B inherited account A's keyring generation " +
                "marker, which is what decides whether a cache is trusted",
            tm.loadHistoryKeyringGeneration(ACCOUNT_B).getOrNull()
        )
    }

    /** Isolation must not be bought by destroying the outgoing account's data. */
    @Test
    fun accountAKeepsItsOwnDurableStateAcrossTheSwitch() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveDirectRatchet(ACCOUNT_A, CHAT, A_RATCHET)
        tm.savePeerSenderKey(ACCOUNT_A, CHAT, PEER, 1, A_PEER_KEY)
        tm.saveHistoryKeyringCache(ACCOUNT_A, A_KEYRING)

        logoutAndSignInB()
        tm.saveDirectRatchet(ACCOUNT_B, CHAT, """{"rk":"${"1".repeat(64)}","seq":1}""")
        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_A)

        assertEquals(
            "A's own ratchet was lost across the switch",
            A_RATCHET, tm.loadDirectRatchet(ACCOUNT_A, CHAT).getOrNull()
        )
        assertEquals(
            "A's own peer Sender Key was lost across the switch",
            A_PEER_KEY,
            tm.loadPeerSenderKeys(ACCOUNT_A, CHAT).getOrNull()?.values?.firstOrNull()
        )
        assertEquals(
            "A's own history keyring cache was lost across the switch",
            String(A_KEYRING),
            tm.loadHistoryKeyringCache(ACCOUNT_A).getOrNull()?.let { String(it) }
        )
    }
}
