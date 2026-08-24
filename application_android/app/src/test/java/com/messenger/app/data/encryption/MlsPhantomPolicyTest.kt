package com.messenger.app.data.encryption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for phantom detection.
 *
 * These call the real predicate. The previous guard for this code read the
 * method body as a string and asserted it mentioned `pendingDeviceIds` - which
 * is the defect, so the test passed *because* the bug was there and would have
 * kept passing through any variation of it. A rule you can only assert by
 * quoting the source is a rule nothing is testing.
 *
 * The server decides what "outstanding" means (GetCoverage in
 * back-end/handlers/mls.go, pinned by mls_coverage_db_test.go). What is under
 * test here is the client half: intersecting that evidence with the local
 * ratchet tree, and refusing to act without it.
 */
class MlsPhantomPolicyTest {

    private val me = "my-device"
    private val roster = listOf(
        "alice|my-device",
        "bob|bob-device",
        "carol|carol-device"
    )

    @Test
    fun `a leaf the server reports as outstanding is a phantom`() {
        assertEquals(
            listOf("bob|bob-device"),
            MlsPolicy.phantomMembers(roster, me, setOf("bob-device"))
        )
    }

    @Test
    fun `nothing reported evicts nobody`() {
        assertTrue(
            "an empty pending set is not evidence of anything",
            MlsPolicy.phantomMembers(roster, me, emptySet()).isEmpty()
        )
    }

    /**
     * The Samsung regression, at the client boundary. The device genuinely
     * joined through a newer Welcome, so the server no longer reports it - and
     * the client must take that at face value rather than reasoning about
     * Welcomes itself. Evicting here is what removed a joined phone twice and
     * walked a live group from epoch 4 to epoch 8.
     */
    @Test
    fun `a member the server does not report is left alone even though it has a leaf`() {
        val pending = setOf("carol-device")
        val phantoms = MlsPolicy.phantomMembers(roster, me, pending)
        assertTrue(
            "bob is in the tree and not reported outstanding; it must survive",
            !phantoms.contains("bob|bob-device")
        )
        assertEquals(listOf("carol|carol-device"), phantoms)
    }

    @Test
    fun `a reported device with no leaf is not a phantom`() {
        assertTrue(
            "a phantom is a leaf that has not joined; no leaf, nothing to remove",
            MlsPolicy.phantomMembers(roster, me, setOf("dave-device")).isEmpty()
        )
    }

    @Test
    fun `this device is never its own phantom`() {
        assertTrue(
            "evicting ourselves would take the group down from the inside",
            MlsPolicy.phantomMembers(roster, me, setOf("my-device")).isEmpty()
        )
    }

    @Test
    fun `a blank pending id matches nobody`() {
        // Welcomes predating per-device addressing carry an empty device id.
        // Matching them against the empty device half of a malformed credential
        // would evict an arbitrary leaf.
        assertTrue(
            MlsPolicy.phantomMembers(roster + "malformed-credential", me, setOf("")).isEmpty()
        )
    }

    @Test
    fun `a credential without a device half is ignored`() {
        assertTrue(
            MlsPolicy.phantomMembers(listOf("no-separator"), me, setOf("bob-device")).isEmpty()
        )
    }

    @Test
    fun `every reported leaf is returned, not just the first`() {
        assertEquals(
            listOf("bob|bob-device", "carol|carol-device"),
            MlsPolicy.phantomMembers(roster, me, setOf("bob-device", "carol-device"))
        )
    }

    @Test
    fun `only a newer Welcome permits replacement of an existing local group`() {
        assertTrue(MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = 4, welcomeEpoch = 8))
        assertTrue(MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = 0, welcomeEpoch = 1))
        assertTrue(!MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = 8, welcomeEpoch = 8))
        assertTrue(!MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = 9, welcomeEpoch = 8))
        assertTrue(!MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = null, welcomeEpoch = 8))
        assertTrue(!MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = -1, welcomeEpoch = 8))
    }

    @Test
    fun `a current Welcome never authorizes a destructive replacement`() {
        assertTrue(
            "a current group may have joined before its ack completed; do not reopen its Welcome",
            !MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch = 8, welcomeEpoch = 8)
        )
    }
}
