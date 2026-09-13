package com.messenger.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V-2 PROOF - is the DURABLE store account-scoped?
 *
 * Gate 24.1 fixed the in-memory Sender Key leak by clearing the maps on an
 * account change, so the next account "reloads from its own durable store".
 * This asks whether that store is in fact its own.
 *
 * These were RED. TokenManagerImpl kept everything in one SharedPreferences
 * file with entries keyed by chatId and no account component:
 *
 *     group_senderkey_$chatId     <- this account's OWN group Sender Key
 *     known_pubkey_$chatId        <- the pinned (TOFU) peer identity
 *     pending_pubkey_$chatId      <- a peer identity awaiting acceptance
 *     verified_pubkey_$chatId     <- safety-number verification status
 *
 * Gate 25.2 files each value under its owner. The assertions are unchanged:
 * what changed is that the production store can now satisfy them.
 *
 * Real TokenManagerImpl, real prefs file, no fakes. Each call names the account
 * it belongs to, so these test OWNERSHIP of the value, not merely that a read
 * returned something.
 */
@RunWith(AndroidJUnit4::class)
class DurableStoreAccountScopeTest {

    private companion object {
        const val ACCOUNT_A = "aaaa1111-0000-0000-0000-0000000011aa"
        const val ACCOUNT_B = "bbbb2222-0000-0000-0000-0000000022bb"
        const val CHAT = "chat-visible-to-both-accounts"
        val A_SENDER_KEY = "7".repeat(64)
        val A_PEER_PIN = "ab".repeat(32)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tm: TokenManagerImpl

    @Before
    fun setUp() {
        ctx.getSharedPreferences("messenger_secure_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    @Test
    fun accountBMustNotInheritAccountAsDurableSenderKey() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveGroupSenderKey(ACCOUNT_A, CHAT, "1:$A_SENDER_KEY")
        check(tm.loadGroupSenderKeys(ACCOUNT_A, CHAT).getOrNull()?.isNotEmpty() == true) {
            "precondition: A stored a Sender Key"
        }

        // Logout, exactly as AuthRepository does it, then B signs in.
        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_B)

        val seenByB = tm.loadGroupSenderKeys(ACCOUNT_B, CHAT).getOrNull().orEmpty()
        assertFalse(
            "PROVEN BROKEN: account B loaded account A's group Sender Key from " +
                "the durable store. B would encrypt its own group traffic under " +
                "A's key. Saw: $seenByB",
            seenByB.containsValue(A_SENDER_KEY)
        )
    }

    @Test
    fun accountBMustNotInheritAccountAsPinnedPeerIdentity() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveKnownPublicKey(ACCOUNT_A, CHAT, A_PEER_PIN)

        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_B)

        assertNull(
            "PROVEN BROKEN: account B inherited account A's PINNED peer identity " +
                "for this chat. TOFU is the trust anchor for the whole direct " +
                "conversation, and B never verified this key - it was pinned by a " +
                "different account.",
            tm.getKnownPublicKey(ACCOUNT_B, CHAT).getOrNull()
        )
    }

    @Test
    fun accountBMustNotInheritAccountAsSafetyVerification() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveKnownPublicKey(ACCOUNT_A, CHAT, A_PEER_PIN)
        tm.markSafetyVerified(ACCOUNT_A, CHAT, A_PEER_PIN)
        check(tm.isSafetyVerified(ACCOUNT_A, CHAT).getOrNull() == true) {
            "precondition: A verified this chat"
        }

        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_B)

        assertFalse(
            "PROVEN BROKEN: account B sees this chat as SAFETY-VERIFIED because " +
                "account A verified it. B is shown a verified badge for a key it " +
                "never checked.",
            tm.isSafetyVerified(ACCOUNT_B, CHAT).getOrNull() == true
        )
    }

    /**
     * A's own state must still be A's after the round trip. Isolation that works
     * by destroying the outgoing account's data is not isolation, and Gate 25.1
     * ruled explicitly against buying it that way.
     */
    @Test
    fun accountAKeepsItsOwnStateAcrossTheSwitch() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveGroupSenderKey(ACCOUNT_A, CHAT, "1:$A_SENDER_KEY")
        tm.saveKnownPublicKey(ACCOUNT_A, CHAT, A_PEER_PIN)
        tm.markSafetyVerified(ACCOUNT_A, CHAT, A_PEER_PIN)

        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_B)
        tm.saveGroupSenderKey(ACCOUNT_B, CHAT, "1:" + "3".repeat(64))
        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_A)

        assertFalse(
            "A's own Sender Key was lost across the switch (saw " +
                "${tm.loadGroupSenderKeys(ACCOUNT_A, CHAT).getOrNull()})",
            tm.loadGroupSenderKeys(ACCOUNT_A, CHAT).getOrNull()
                ?.containsValue(A_SENDER_KEY) != true
        )
        assertFalse(
            "A's own pinned peer identity was lost across the switch",
            tm.getKnownPublicKey(ACCOUNT_A, CHAT).getOrNull() != A_PEER_PIN
        )
        assertFalse(
            "A's own safety verification was lost across the switch",
            tm.isSafetyVerified(ACCOUNT_A, CHAT).getOrNull() != true
        )
    }
}
