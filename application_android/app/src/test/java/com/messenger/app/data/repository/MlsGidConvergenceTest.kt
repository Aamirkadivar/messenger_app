package com.messenger.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Convergence on the Delivery Service active group id.
 *
 * A device that missed a group recreation still holds a perfectly valid local
 * group for the abandoned tree. It loads cleanly, so every path that could have
 * noticed - send, chat open, handshake sync - short-circuited on that success
 * and the DS was never asked. The device then encrypted into a group nobody else
 * was on, in both directions, indefinitely: it could neither read the others nor
 * be read by them, while reporting no error at all.
 *
 * The behaviour is exercised for real in mls-core/tests/ds_contract.rs; these are
 * the source-level guards for the Kotlin wiring, which sits behind a JNI
 * boundary and a live Delivery Service.
 */
class MlsGidConvergenceTest {

    private companion object {
        val SOURCE = File("src/main/java/com/messenger/app/data/repository/MlsV2Repository.kt")
    }

    private fun body(declaration: String): String {
        val src = SOURCE.readText()
        val start = src.indexOf(declaration)
        assertTrue("could not find " + declaration, start >= 0)
        val open = src.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return src.substring(open, i + 1) }
            }
            i++
        }
        error("unbalanced braces reading " + declaration)
    }

    @Test
    fun `the server gid is checked before a local group is accepted`() {
        val b = body("suspend fun hasGroup(")
        val check = b.indexOf("ensureCurrentGid(")
        val live = b.indexOf("liveGroups.contains(")
        val load = b.indexOf("loadGroup(")

        assertTrue("hasGroup must consult the DS gid", check >= 0)
        assertTrue("expected the liveGroups fast path", live >= 0)
        assertTrue("expected loadGroup", load >= 0)
        assertTrue(
            "the gid check must precede the liveGroups fast path, or a stale group " +
                "cached earlier in the run is accepted without ever asking the DS",
            check < live
        )
        assertTrue(
            "the gid check must precede loadGroup - a stale group loads just as " +
                "cleanly as a current one",
            check < load
        )
    }

    @Test
    fun `a differing server gid discards the stale group and adopts the server value`() {
        val b = body("private suspend fun ensureCurrentGid(")
        assertTrue(
            "adoption must reuse forgetLocal - not a second teardown path",
            b.contains("forgetLocal(chatId)")
        )
        assertTrue(
            "adoption must reuse setActiveGid - not a second persistence mechanism",
            b.contains("setActiveGid(chatId, serverGid)")
        )
        val drop = b.indexOf("forgetLocal(chatId)")
        val adopt = b.indexOf("setActiveGid(chatId, serverGid)")
        assertTrue("the stale group must be dropped before the new id is adopted", drop < adopt)
        assertTrue(
            "adoption must be conditional on the ids actually differing",
            b.contains("if (!serverGid.contentEquals(")
        )
    }

    @Test
    fun `a matching gid is not torn down`() {
        val b = body("private suspend fun ensureCurrentGid(")
        val guard = b.indexOf("if (!serverGid.contentEquals(")
        val drop = b.indexOf("forgetLocal(chatId)")
        assertTrue("expected the equality guard", guard >= 0)
        assertTrue(
            "forgetLocal must sit INSIDE the mismatch branch; an unconditional drop " +
                "would make every healthy device rejoin on each check",
            drop > guard
        )
        assertTrue(
            "a verified chat must be remembered so the DS is not asked on every " +
                "encrypt and decrypt",
            b.contains("gidVerified.add(chatId)")
        )
    }

    @Test
    fun `an unobtainable server gid fails closed`() {
        val b = body("private suspend fun ensureCurrentGid(")
        assertTrue(
            "a missing token must not be treated as agreement",
            b.contains("token() ?: return false")
        )
        // Pinned to the getMlsGroup line specifically: a generic
        // "}.getOrNull() ?: return false" also matches the unb64 line below, so
        // flipping THIS one to fail-open went undetected the first time.
        val fetch = b.lineSequence().firstOrNull { it.contains("getMlsGroup(") }
        assertTrue("expected the DS fetch in ensureCurrentGid", fetch != null)
        assertTrue(
            "an unreachable DS must not be treated as agreement: " + fetch,
            fetch!!.contains("?: return false")
        )
        assertFalse(
            "no branch of the gid check may fail open: " + b,
            b.contains("?: return true")
        )
        val h = body("suspend fun hasGroup(")
        assertTrue(
            "hasGroup must refuse the chat when the gid cannot be confirmed, rather " +
                "than falling back to the chat-derived id",
            h.contains("if (!ensureCurrentGid(chatId))")
        )
        assertTrue(
            "the failure must return false, not continue into loadGroup",
            h.substringAfter("!ensureCurrentGid(chatId)").substringBefore("liveGroups")
                .contains("return@withContext false")
        )
    }

    @Test
    fun `the positive cache can no longer latch adoption shut`() {
        val b = body("suspend fun serverHasGroup(")
        assertFalse(
            "serverGroups must not short-circuit before the gid comparison runs - " +
                "that latched the only adoption path shut for the whole process",
            b.contains("if (serverGroups.contains(chatId)) return@withContext true")
        )
        assertTrue(
            "serverHasGroup must delegate to the single adoption implementation",
            b.contains("ensureCurrentGid(chatId)")
        )
    }

    @Test
    fun `a failed apply re-opens the gid check`() {
        val b = body("private suspend fun applyBytes(")
        assertTrue(
            "a message that will not apply is exactly what a recreation looks like " +
                "from here; the next pass must re-ask the DS rather than stay latched",
            b.contains("gidVerified.remove(chatId)")
        )
    }

    @Test
    fun `phantom eviction runs before the invite and is gated on server evidence`() {
        val inv = body("suspend fun inviteMissingDevices(")
        val evict = inv.indexOf("evictPhantomMembers(")
        val roster = inv.indexOf("roster(chatId).toSet()")
        assertTrue("the invite path must evict phantoms", evict >= 0)
        assertTrue(
            "eviction must happen before the roster is read, or the phantom is still " +
                "treated as a member and skipped",
            evict < roster
        )

        val b = body("private suspend fun evictPhantomMembers(")
        // WHO is a phantom is a rule, and rules are tested by calling them:
        // MlsPhantomPolicyTest owns that. Asserting it here by quoting the
        // source is what let the defect through - the old guard asserted the
        // body mentioned `pendingDeviceIds`, which is precisely the bug, so it
        // passed because the bug was present. What is left below is wiring:
        // things with no return value to assert on.
        assertTrue(
            "the decision must come from the pure predicate, not be re-derived here",
            b.contains("MlsPolicy.phantomMembers(")
        )
        assertFalse(
            "coverage must be consulted only through the predicate; a second, " +
                "looser reading of the ledger here is how the two drift apart",
            b.contains("coverage.pendingDeviceIds.filter")
        )
        assertTrue(
            "unfetchable coverage must remove nobody",
            b.contains("}.getOrNull()")
        )
        assertTrue(
            "an empty result must remove nobody",
            b.contains("if (phantoms.isEmpty()) return")
        )
        assertTrue(
            "removal must go through the core, never a local roster edit",
            b.contains("c.removeMembers(")
        )
        assertTrue(
            "a rejected removal must be discarded, not left staged",
            b.contains("onCommitRejected(chatId)")
        )
    }
}
