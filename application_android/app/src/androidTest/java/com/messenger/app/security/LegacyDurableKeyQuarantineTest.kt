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
 * GATE 25.2 PHASE 4 - the pre-scoping keys are quarantined, permanently.
 *
 * Deployed installations already hold values under the v1 names. Their owner
 * cannot be established: the file records who wrote them nowhere, and login
 * order is not provenance. Adopting them would hand one account another
 * account's Sender Keys, pinned identities and ratchets - the very defect V-2
 * is, only slower.
 *
 * So they are never read, never migrated, and never a fallback. The "acct2_"
 * component makes that structural rather than a promise: no argument to the key
 * helper can reproduce a v1 name.
 *
 * This mirrors the ruling getOrCreateDeviceId already makes for its own
 * pre-scoping value, and the database legacy-quarantine rule from Gate 19.
 */
@RunWith(AndroidJUnit4::class)
class LegacyDurableKeyQuarantineTest {

    private companion object {
        const val ACCOUNT_A = "1111cccc-0000-0000-0000-0000000011cc"
        const val ACCOUNT_B = "2222dddd-0000-0000-0000-0000000022dd"
        const val CHAT = "legacy-chat"
        val LEGACY_SENDER_KEY = "e".repeat(64)
        val LEGACY_PEER_PIN = "fa".repeat(32)
        val LEGACY_RATCHET = """{"rk":"${"d".repeat(64)}","seq":3}"""
        val LEGACY_KEYRING = "LEGACY-UNATTRIBUTABLE-KEYRING".toByteArray()
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tm: TokenManagerImpl

    /** Exactly the shapes a pre-Gate-25.2 install would already hold. */
    private fun plantLegacyState() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("group_senderkey_$CHAT", "1:$LEGACY_SENDER_KEY")
            .putString("group_senderkey_$CHAT|1", LEGACY_SENDER_KEY)
            .putString("peer_senderkey_$CHAT|someone|1", LEGACY_SENDER_KEY)
            .putString("known_pubkey_$CHAT", LEGACY_PEER_PIN)
            .putString("pending_pubkey_$CHAT", LEGACY_PEER_PIN)
            .putString("verified_pubkey_$CHAT", LEGACY_PEER_PIN)
            .putString("dr3_$CHAT", LEGACY_RATCHET)
            .putString("history_keyring_v1", String(LEGACY_KEYRING))
            .putString("history_keyring_cache_v1", String(LEGACY_KEYRING))
            .putLong("history_keyring_gen_v1", 99L)
            .commit()
    }

    @Before
    fun setUp() {
        plantLegacyState()
        tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    private suspend fun assertSeesNothing(account: String) {
        assertTrue(
            "$account adopted a legacy group Sender Key",
            tm.loadGroupSenderKeys(account, CHAT).getOrNull().orEmpty().isEmpty()
        )
        assertTrue(
            "$account adopted legacy peer Sender Keys",
            tm.loadPeerSenderKeys(account, CHAT).getOrNull().orEmpty().isEmpty()
        )
        assertNull(
            "$account adopted a legacy pinned peer identity",
            tm.getKnownPublicKey(account, CHAT).getOrNull()
        )
        assertNull(
            "$account adopted a legacy pending peer identity",
            tm.getPendingPublicKey(account, CHAT).getOrNull()
        )
        assertFalse(
            "$account adopted a legacy safety verification",
            tm.isSafetyVerified(account, CHAT).getOrNull() == true
        )
        assertNull(
            "$account adopted a legacy direct ratchet",
            tm.loadDirectRatchet(account, CHAT).getOrNull()
        )
        assertNull(
            "$account adopted a legacy history keyring",
            tm.loadHistoryKeyring(account).getOrNull()
        )
        assertNull(
            "$account adopted a legacy history keyring cache",
            tm.loadHistoryKeyringCache(account).getOrNull()
        )
        assertNull(
            "$account adopted a legacy keyring generation marker",
            tm.loadHistoryKeyringGeneration(account).getOrNull()
        )
    }

    @Test
    fun theFirstAccountToLogInCannotClaimLegacyState() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        assertSeesNothing(ACCOUNT_A)
    }

    @Test
    fun theSecondAccountToLogInCannotClaimLegacyStateEither() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        assertSeesNothing(ACCOUNT_A)
        tm.clearTokens()
        tm.saveCurrentUserId(ACCOUNT_B)
        assertSeesNothing(ACCOUNT_B)
    }

    @Test
    fun noLoginOrderEverClaimsLegacyState() = runBlocking {
        for (account in listOf(ACCOUNT_B, ACCOUNT_A, ACCOUNT_B)) {
            tm.clearTokens()
            tm.saveCurrentUserId(account)
            assertSeesNothing(account)
        }
    }

    /**
     * The legacy entries are not a fallback even when the account's own slot is
     * genuinely empty - that absence is the exact condition under which a
     * "helpful" migration would fire.
     */
    @Test
    fun anEmptyScopedSlotDoesNotFallBackToLegacy() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        assertNull(tm.getKnownPublicKey(ACCOUNT_A, CHAT).getOrNull())

        // A writes its OWN value; the legacy one must be untouched and unused.
        val mine = "0c".repeat(32)
        tm.saveKnownPublicKey(ACCOUNT_A, CHAT, mine)
        assertEquals(mine, tm.getKnownPublicKey(ACCOUNT_A, CHAT).getOrNull())

        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        assertEquals(
            "the legacy entry must be left exactly as found - quarantined, not migrated",
            LEGACY_PEER_PIN, prefs.getString("known_pubkey_$CHAT", null)
        )
    }

    /**
     * Nothing an account writes may land outside its namespace - not even a value
     * nothing reads back. A stray write into the quarantined namespace is how two
     * accounts start sharing a slot again.
     */
    @Test
    fun noWriteEverLandsOutsideTheAccountNamespace() = runBlocking {
        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tm.saveCurrentUserId(ACCOUNT_A)

        tm.saveGroupSenderKey(ACCOUNT_A, CHAT, "1:" + "ab".repeat(32))
        tm.savePeerSenderKey(ACCOUNT_A, CHAT, "someone", 1, "cd".repeat(32))
        tm.saveKnownPublicKey(ACCOUNT_A, CHAT, LEGACY_PEER_PIN)
        tm.savePendingPublicKey(ACCOUNT_A, CHAT, LEGACY_PEER_PIN)
        tm.markSafetyVerified(ACCOUNT_A, CHAT, LEGACY_PEER_PIN)
        tm.saveDirectRatchet(ACCOUNT_A, CHAT, LEGACY_RATCHET)
        tm.saveHistoryKeyring(ACCOUNT_A, LEGACY_KEYRING)
        tm.saveHistoryKeyringCache(ACCOUNT_A, LEGACY_KEYRING)
        tm.saveHistoryKeyringGeneration(ACCOUNT_A, 5L)

        // Genuinely install-global and carrying no account-sensitive material:
        // the session pointers themselves, and the device id, which is already
        // scoped by its own owner field.
        val allowedGlobal = setOf(
            "current_user_id", "access_token_enc", "refresh_token",
            "access_token_expires_at", "pending_refresh_token",
            "pending_refresh_request_id", "pending_refresh_user",
            "pending_refresh_started_at"
        )
        val stray = prefs.all.keys.filter {
            !it.startsWith("acct2_") &&
                it !in allowedGlobal &&
                !it.startsWith("e2ee_device_")
        }
        assertTrue(
            "PROVEN BROKEN: these writes landed outside the account namespace - " +
                "the quarantined one, where two accounts share a slot: $stray",
            stray.isEmpty()
        )
    }

    /** No scoped key may ever collide with a v1 name. */
    @Test
    fun scopedKeysAreDisjointFromLegacyKeys() = runBlocking {
        tm.saveCurrentUserId(ACCOUNT_A)
        tm.saveGroupSenderKey(ACCOUNT_A, CHAT, "1:" + "ab".repeat(32))
        tm.saveDirectRatchet(ACCOUNT_A, CHAT, LEGACY_RATCHET)
        tm.saveHistoryKeyringCache(ACCOUNT_A, LEGACY_KEYRING)

        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        val written = prefs.all.keys.filter { it.startsWith("acct2_") }
        assertTrue("expected account-scoped keys to have been written", written.isNotEmpty())
        for (legacy in listOf("group_senderkey_", "peer_senderkey_", "known_pubkey_",
                              "pending_pubkey_", "verified_pubkey_", "dr3_",
                              "history_keyring_")) {
            assertTrue(
                "a scoped key collided with the legacy namespace: $written",
                written.none { it.startsWith(legacy) }
            )
        }
    }
}
