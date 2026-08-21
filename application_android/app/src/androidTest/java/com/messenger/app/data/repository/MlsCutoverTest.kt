package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.encryption.MlsGroupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the MLS cutover.
 *
 * The cutover is deliberately per-group rather than a flag day: a group uses
 * MLS only once it actually has MLS state on this device, and everything else
 * keeps using Sender Keys. `MlsRepository.protect()` returns null when a chat
 * has no group, which is what makes ChatRepository fall back instead of
 * emitting ciphertext nobody can read.
 */
@RunWith(AndroidJUnit4::class)
class MlsCutoverTest {

    /**
     * v5 must be distinct from the direct-message versions. A collision would
     * route a group message into the pairwise ratchet, where it cannot open.
     */
    @Test
    fun mlsVersionDoesNotCollideWithDirectVersions() {
        val mls = ChatRepository.MLS_ENCRYPTION_VERSION
        assertEquals(5, mls)
        listOf(1, 2, 3, 4).forEach { direct ->
            assertNotEquals("v$direct must not collide with MLS", direct, mls)
        }
    }

    /**
     * Where MLS state exists, a group message round-trips to another member.
     *
     * Note it is deliberately decrypted by a *different* member: in MLS the
     * sender cannot open its own application message, because the sending
     * ratchet does not retain the message keys it just used. That is why the
     * UI keeps an optimistic local echo for messages you send — and why the
     * cross-device fix compares sender *device*, not sender user.
     */
    @Test
    fun groupWithMlsStateRoundTripsToAnotherMember() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val bob = MlsGroupCrypto.newIdentity("bob")
        val bobKp = MlsGroupCrypto.newKeyPackage(bob)

        var aliceGroup = MlsGroupCrypto.createGroup("chat-with-mls".toByteArray(), alice)
        val added = MlsGroupCrypto.addMember(aliceGroup, bobKp.keyPackage)
        aliceGroup = added.group
        val bobGroup = MlsGroupCrypto.joinFromWelcome(bobKp, bob, added.welcome)

        val sealed = MlsGroupCrypto.protect(aliceGroup, "group text".toByteArray())
        assertNotNull(sealed)
        assertEquals("group text", String(MlsGroupCrypto.unprotect(bobGroup, sealed)!!))
        // The other device of the same account is just another leaf, so it
        // must survive the UI decrypting the same ciphertext twice.
        assertEquals("group text", String(MlsGroupCrypto.unprotect(bobGroup, sealed)!!))
        // The sending device cannot reopen its own MLS generation, but protect()
        // remembers the plaintext so history refetch on that device still works.
        assertEquals("group text", String(MlsGroupCrypto.unprotect(aliceGroup, sealed)!!))
    }

    /**
     * A message sealed for one group must not open in another. This is what
     * stops a mis-routed chat id silently decrypting under the wrong group's
     * key schedule.
     */
    @Test
    fun ciphertextDoesNotOpenUnderADifferentGroup() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val groupA = MlsGroupCrypto.createGroup("chat-A".toByteArray(), alice)
        val groupB = MlsGroupCrypto.createGroup("chat-B".toByteArray(), alice)
        val sealed = MlsGroupCrypto.protect(groupA, "for A only".toByteArray())
        org.junit.Assert.assertNull(
            "group B must not open group A's ciphertext",
            MlsGroupCrypto.unprotect(groupB, sealed)
        )
    }
}
