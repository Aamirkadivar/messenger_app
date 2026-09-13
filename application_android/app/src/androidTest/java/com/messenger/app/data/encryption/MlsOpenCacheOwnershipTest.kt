package com.messenger.app.data.encryption

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.security.MlsOwner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 26 - MlsGroupCrypto's opened-plaintext cache must not answer across owners.
 *
 * The cache exists because the UI opens the same blob several times (store,
 * paint, chat-list preview) and MLS will not decrypt one twice. It was keyed by
 * the SHA-256 of the ciphertext alone, and lookupOpen returns before the group
 * is consulted at all - so it answered on possession of the bytes. Two accounts
 * on one device can be in the same group and therefore hold the same ciphertext,
 * which is exactly when that matters.
 *
 * The decisive case is a lookup by an owner whose group CANNOT decrypt the
 * message: anything returned then came from the cache and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
class MlsOpenCacheOwnershipTest {

    private val ownerA = MlsOwner("account-a", "device-a")
    private val ownerB = MlsOwner("account-b", "device-b")

    /** A group that has nothing to do with the one the message was sealed for. */
    private fun unrelatedGroup() =
        MlsGroupCrypto.createGroup(
            "unrelated-chat".toByteArray(),
            MlsGroupCrypto.newIdentity("stranger")
        )

    @Test
    fun plaintextCachedForOneOwnerIsNotReturnedToAnother() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val group = MlsGroupCrypto.createGroup("cache-chat".toByteArray(), alice)
        val secret = "account A's message".toByteArray()

        // Sealing populates the cache for owner A.
        val sealed = MlsGroupCrypto.protect(ownerA, group, secret)

        // Owner A may read it back from the cache: that is what the cache is for.
        assertArrayEquals(
            "precondition: the owner that sealed it can read it back",
            secret, MlsGroupCrypto.unprotect(ownerA, group, sealed)
        )

        // Owner B asks with a group that cannot open this message. Anything
        // returned can only have come from the cache.
        assertNull(
            "PROVEN BROKEN: account B was handed account A's plaintext out of the " +
                "shared open cache. The cache answered on possession of the " +
                "ciphertext without consulting group state or ownership at all.",
            MlsGroupCrypto.unprotect(ownerB, unrelatedGroup(), sealed)
        )
    }

    @Test
    fun theSameAccountOnAnotherDeviceIsAlsoADifferentOwner() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val group = MlsGroupCrypto.createGroup("cache-chat-2".toByteArray(), alice)
        val secret = "device one's message".toByteArray()
        val sameAccountOtherDevice = MlsOwner(ownerA.accountId, "device-two")

        val sealed = MlsGroupCrypto.protect(ownerA, group, secret)

        assertNull(
            "PROVEN BROKEN: a second device of the same account read cached " +
                "plaintext belonging to the first. Each device is its own MLS " +
                "leaf and its own owner.",
            MlsGroupCrypto.unprotect(sameAccountOtherDevice, unrelatedGroup(), sealed)
        )
    }

    @Test
    fun forgettingOneOwnerLeavesAnotherOwnersCacheIntact() {
        val alice = MlsGroupCrypto.newIdentity("alice")
        val group = MlsGroupCrypto.createGroup("cache-chat-3".toByteArray(), alice)
        val mine = "still mine".toByteArray()
        val sealed = MlsGroupCrypto.protect(ownerA, group, mine)

        MlsGroupCrypto.forgetOpenCache(ownerB)

        assertArrayEquals(
            "clearing one owner's cached plaintext must not disturb another's",
            mine, MlsGroupCrypto.unprotect(ownerA, group, sealed)
        )
    }
}
