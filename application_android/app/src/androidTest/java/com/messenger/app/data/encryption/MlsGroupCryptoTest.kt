package com.messenger.app.data.encryption

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves BouncyCastle MLS actually runs on Android (real device/emulator, real
 * JCE providers, post-R8 classpath) — not just that it compiles.
 */
@RunWith(AndroidJUnit4::class)
class MlsGroupCryptoTest {

    @Test
    fun suiteIsAvailableOnAndroid() {
        val s = MlsGroupCrypto.suite()
        assertNotNull(s)
        assertEquals(MlsGroupCrypto.SUITE_ID, s.suiteID)
    }

    @Test
    fun identityAndKeyPackageRoundTrip() {
        val id = MlsGroupCrypto.newIdentity("alice")
        val pkp = MlsGroupCrypto.newKeyPackage(id)
        assertTrue("KeyPackage must self-verify", pkp.keyPackage.verify())

        // Must survive the wire: the Delivery Service stores it as opaque bytes.
        val encoded = MlsGroupCrypto.encodeKeyPackage(pkp.keyPackage)
        assertTrue(encoded.isNotEmpty())
        val decoded = MlsGroupCrypto.decodeKeyPackage(encoded)
        assertTrue("decoded KeyPackage must verify", decoded.verify())
    }

    /**
     * The real thing: two independent members form a group through the exact
     * blobs the Delivery Service relays (KeyPackage → commit + welcome), then
     * exchange application messages in both directions.
     */
    @Test
    fun twoPartyGroupFormsAndExchangesMessages() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val bob = MlsGroupCrypto.newIdentity("bob")

        // Bob publishes a KeyPackage; only the serialized form reaches Alice.
        val bobKp = MlsGroupCrypto.newKeyPackage(bob)
        val bobKpWire = MlsGroupCrypto.encodeKeyPackage(bobKp.keyPackage)

        // Alice creates the group and adds Bob from the wire bytes.
        var aliceGroup = MlsGroupCrypto.createGroup("chat-42".toByteArray(), alice)
        val added = MlsGroupCrypto.addMember(
            aliceGroup, MlsGroupCrypto.decodeKeyPackage(bobKpWire)
        )
        aliceGroup = added.group
        assertEquals("group must advance one epoch", 1L, aliceGroup.epoch)
        assertTrue("welcome must be produced", added.welcome.isNotEmpty())

        // Bob joins from the welcome alone.
        val bobGroup = MlsGroupCrypto.joinFromWelcome(bobKp, bob, added.welcome)
        assertEquals("both sides must agree on the epoch", aliceGroup.epoch, bobGroup.epoch)

        // Alice → Bob.
        val toBob = MlsGroupCrypto.protect(aliceGroup, "hello bob".toByteArray())
        val gotByBob = MlsGroupCrypto.unprotect(bobGroup, toBob)
        assertNotNull("Bob must decrypt Alice's message", gotByBob)
        assertEquals("hello bob", String(gotByBob!!))
        assertEquals(
            "unprotect must be idempotent — the UI decrypts the same blob more than once",
            "hello bob",
            String(MlsGroupCrypto.unprotect(bobGroup, toBob)!!)
        )

        // Bob → Alice.
        val toAlice = MlsGroupCrypto.protect(bobGroup, "hi alice".toByteArray())
        val gotByAlice = MlsGroupCrypto.unprotect(aliceGroup, toAlice)
        assertNotNull("Alice must decrypt Bob's message", gotByAlice)
        assertEquals("hi alice", String(gotByAlice!!))
    }

    /**
     * Restart survival. Neither BouncyCastle nor mlspp can serialize a live
     * group, so persistence stores the *inputs* (identity keys, published
     * KeyPackage with its init key, the Welcome) and rebuilds. This proves the
     * rebuild is a real equivalent: a message sealed by the inviter BEFORE the
     * "restart" must still open on the rebuilt session.
     */
    @Test
    fun groupIsRebuiltFromPersistedMaterialAfterRestart() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val bob = MlsGroupCrypto.newIdentity("bob")
        val bobKp = MlsGroupCrypto.newKeyPackage(bob)

        var aliceGroup = MlsGroupCrypto.createGroup("chat-persist".toByteArray(), alice)
        val added = MlsGroupCrypto.addMember(
            aliceGroup, MlsGroupCrypto.decodeKeyPackage(MlsGroupCrypto.encodeKeyPackage(bobKp.keyPackage))
        )
        aliceGroup = added.group

        // Everything Bob's device would write to disk.
        val identityJson = MlsGroupCrypto.encodeIdentity(bob)
        val kpJson = MlsGroupCrypto.encodePublishedKeyPackage(bobKp)
        val welcome = added.welcome

        // A message sent before the restart.
        val sealed = MlsGroupCrypto.protect(aliceGroup, "sent before restart".toByteArray())

        // ---- restart: nothing but the serialized strings survive ----
        val restoredIdentity = MlsGroupCrypto.decodeIdentity(identityJson)
        assertNotNull("identity must round-trip", restoredIdentity)
        val restoredKp = MlsGroupCrypto.decodePublishedKeyPackage(kpJson)
        assertNotNull("published key package must round-trip", restoredKp)

        val rebuilt = MlsGroupCrypto.joinFromWelcome(restoredKp!!, restoredIdentity!!, welcome)
        assertEquals("rebuilt group must be at the same epoch", aliceGroup.epoch, rebuilt.epoch)

        val opened = MlsGroupCrypto.unprotect(rebuilt, sealed)
        assertNotNull("rebuilt session must decrypt a pre-restart message", opened)
        assertEquals("sent before restart", String(opened!!))

        // And it keeps working forward.
        val after = MlsGroupCrypto.protect(aliceGroup, "after restart".toByteArray())
        assertEquals("after restart", String(MlsGroupCrypto.unprotect(rebuilt, after)!!))
    }

    /**
     * External join: the recovery path for a device that lost its group state
     * entirely — the creator case, where no Welcome exists and BouncyCastle
     * cannot serialize state.
     *
     * The rejoiner rebuilds from the public GroupInfo alone, produces a commit,
     * and the existing member applies it. Both must then agree and be able to
     * exchange messages.
     */
    @Test
    fun externalJoinRecoversAMemberFromGroupInfoAlone() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val bob = MlsGroupCrypto.newIdentity("bob")
        val bobKp = MlsGroupCrypto.newKeyPackage(bob)

        // Alice creates and admits Bob so the group has two members.
        var aliceGroup = MlsGroupCrypto.createGroup("chat-ext".toByteArray(), alice)
        val added = MlsGroupCrypto.addMember(aliceGroup, bobKp.keyPackage)
        aliceGroup = added.group
        var bobGroup = MlsGroupCrypto.joinFromWelcome(bobKp, bob, added.welcome)

        // Alice publishes GroupInfo, as she would after committing.
        val groupInfo = MlsGroupCrypto.exportGroupInfo(aliceGroup)
        assertTrue("GroupInfo must be produced", groupInfo.isNotEmpty())

        // Carol lost everything and rejoins with only the public GroupInfo.
        val carol = MlsGroupCrypto.newIdentity("carol")
        val carolKp = MlsGroupCrypto.newKeyPackage(carol)
        val rejoined = MlsGroupCrypto.externalJoin(carolKp, carol, groupInfo)
        assertTrue("external join must yield a commit", rejoined.commit.isNotEmpty())

        // Existing members apply the external commit.
        aliceGroup = MlsGroupCrypto.applyCommit(aliceGroup, rejoined.commit)
            ?: error("alice must apply the external commit")
        bobGroup = MlsGroupCrypto.applyCommit(bobGroup, rejoined.commit)
            ?: error("bob must apply the external commit")

        assertEquals(
            "alice and the rejoiner must agree on the epoch",
            aliceGroup.epoch, rejoined.group.epoch
        )

        // And the rejoined device can actually talk to the group.
        val fromCarol = MlsGroupCrypto.protect(rejoined.group, "carol is back".toByteArray())
        val seenByAlice = MlsGroupCrypto.unprotect(aliceGroup, fromCarol)
        assertNotNull("alice must read the rejoiner's message", seenByAlice)
        assertEquals("carol is back", String(seenByAlice!!))

        val fromAlice = MlsGroupCrypto.protect(aliceGroup, "welcome back".toByteArray())
        val seenByCarol = MlsGroupCrypto.unprotect(rejoined.group, fromAlice)
        assertNotNull("rejoiner must read alice's message", seenByCarol)
        assertEquals("welcome back", String(seenByCarol!!))
    }

    /** A non-member must not be able to read group traffic. */
    @Test
    fun outsiderCannotDecryptGroupTraffic() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val mallory = MlsGroupCrypto.newIdentity("mallory")
        val group = MlsGroupCrypto.createGroup("chat-secure".toByteArray(), alice)
        val outsiderGroup = MlsGroupCrypto.createGroup("chat-secure".toByteArray(), mallory)

        val ct = MlsGroupCrypto.protect(group, "members only".toByteArray())
        assertNull(
            "an outsider group must not open member traffic",
            MlsGroupCrypto.unprotect(outsiderGroup, ct)
        )
    }

    @Test
    fun createsGroupAndProtectsMessageToSelf() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val group = MlsGroupCrypto.createGroup("test-group".toByteArray(), alice)
        assertEquals(0L, group.epoch)

        val ct = MlsGroupCrypto.protect(group, "hello mls".toByteArray())
        assertTrue("ciphertext must not be empty", ct.isNotEmpty())
        // The plaintext must not appear in the ciphertext.
        assertTrue(
            "plaintext leaked into ciphertext",
            !String(ct, Charsets.ISO_8859_1).contains("hello mls")
        )
    }

    @Test
    fun applyCommitReturnsNullOnGarbageRatherThanPretendingItApplied() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val group = MlsGroupCrypto.createGroup("chat-bad-commit".toByteArray(), alice)
        assertNull(
            "a garbage commit must not be treated as a no-op success",
            MlsGroupCrypto.applyCommit(group, byteArrayOf(1, 2, 3, 4))
        )
    }
}
