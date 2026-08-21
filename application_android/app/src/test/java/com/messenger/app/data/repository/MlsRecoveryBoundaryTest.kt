package com.messenger.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The boundary around destructive MLS recovery.
 *
 * `ensureClient()` refuses to build a client when the stored snapshot exists but
 * cannot be restored. That is what stops a corrupt snapshot silently becoming an
 * empty one - and it is deliberately absolute, so every normal path (startup,
 * sync, send, process) fails closed.
 *
 * `replaceClientForRecovery()` is the single exception: it builds a fresh client
 * without restoring, discarding whatever the device held. It is only correct
 * because of *where* it can be called from, so these tests assert the shape of
 * the code rather than its behaviour.
 *
 * This is a source-level guard, and textual by nature - there is no Kotlin AST
 * available here the way go/ast is on the backend. It is still the cheapest
 * defence that actually fails when the boundary is removed, which the mutation
 * tests for this file demonstrate.
 */
class MlsRecoveryBoundaryTest {

    private companion object {
        val SOURCE = File("src/main/java/com/messenger/app/data/repository/MlsV2Repository.kt")
        const val RECOVERY_FN = "replaceClientForRecovery"
        const val CALLER_FN = "recreateMlsGroup"
    }

    private fun source(): String {
        assertTrue(
            "${SOURCE.path} should exist (tests run from the :app module directory)",
            SOURCE.exists()
        )
        return SOURCE.readText()
    }

    /** Extracts a function body by brace matching from its declaration. */
    private fun functionBody(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        assertTrue("could not find $declaration", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("could not find the body of $declaration", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces reading $declaration")
    }

    @Test
    fun `recovery entry point is private`() {
        val src = source()
        assertTrue(
            "$RECOVERY_FN must stay private - it is the only way to discard local MLS state",
            src.contains("private suspend fun $RECOVERY_FN(")
        )
    }

    /**
     * The invariant is one *caller*, not one call site: the reset path invokes it
     * from two branches (no restorable client at all, and a client whose old group
     * could not be dropped). What must never happen is another function gaining
     * the ability to discard local MLS state.
     */
    @Test
    fun `recovery is only ever called from recreateMlsGroup`() {
        val src = source()
        val callerBody = functionBody(src, "suspend fun $CALLER_FN(")

        val totalCalls = Regex(Regex.escape("$RECOVERY_FN(")).findAll(src).count() - 1 // minus the declaration
        val callsInCaller = Regex(Regex.escape("$RECOVERY_FN(")).findAll(callerBody).count()

        assertTrue("$RECOVERY_FN must be called at least once", totalCalls >= 1)
        assertEquals(
            "every call to $RECOVERY_FN must live inside $CALLER_FN; destructive recovery " +
                "must not spread to any other path",
            totalCalls, callsInCaller
        )
    }

    @Test
    fun `the only caller is recreateMlsGroup`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        assertTrue(
            "the sole call to $RECOVERY_FN must live inside $CALLER_FN",
            caller.contains("$RECOVERY_FN(")
        )
    }

    /**
     * Mutation 2 guard. Rebuilding the client before the server has accepted a new
     * incarnation would discard local state on the strength of a request that may
     * still fail.
     */
    @Test
    fun `recovery happens only after the server request`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        val request = caller.indexOf("api.recreateMlsGroup(")
        val recovery = caller.indexOf("$RECOVERY_FN(")

        assertTrue("expected the recreate request inside $CALLER_FN", request >= 0)
        assertTrue("expected the recovery call inside $CALLER_FN", recovery >= 0)
        assertTrue(
            "$RECOVERY_FN must be called AFTER api.recreateMlsGroup - otherwise a failed " +
                "server call would still have destroyed local MLS state",
            recovery > request
        )
    }

    /**
     * Mutation 1 guard. ensureClient must never fall back to building a fresh
     * client: that is the exact behaviour that overwrote real MLS state with an
     * empty snapshot.
     */
    @Test
    fun `ensureClient never falls back to creating a client on restore failure`() {
        val src = source()
        val body = functionBody(src, "suspend fun ensureClient(")

        // The legitimate create is the "genuinely nothing stored" branch. Any
        // second one means a fallback has been introduced.
        val creates = Regex(Regex.escape("MlsClient.create(")).findAll(body).count()
        assertEquals(
            "ensureClient may construct a client only for the truly-absent-snapshot " +
                "case; a second MlsClient.create means a restore-failure fallback crept back in",
            1, creates
        )

        val restoreIdx = body.indexOf("MlsClient.restore(")
        assertTrue("expected ensureClient to attempt a restore", restoreIdx >= 0)
        val afterRestore = body.substring(restoreIdx)
        assertFalse(
            "the restore-failure branch must not create a client - it must return null " +
                "and leave the stored snapshot alone",
            afterRestore.contains("MlsClient.create(")
        )
    }

    /**
     * The first durable write must stay tied to the accepted-commit path, so a
     * failure between recovery and acceptance leaves the old snapshot intact and
     * the recovery retryable.
     */
    @Test
    fun `recovery does not persist the fresh client`() {
        val src = source()
        val body = functionBody(src, "private suspend fun $RECOVERY_FN(")

        assertFalse(
            "$RECOVERY_FN must not persist: writing the fresh snapshot here would " +
                "destroy the corrupt one before the new group is real",
            body.contains("persist(")
        )
        assertFalse(
            "$RECOVERY_FN must not write the MLS bundle directly either",
            body.contains("saveMlsBundle(")
        )
    }

    /**
     * A fresh client is a member of nothing. Leaving stale ids in liveGroups would
     * let a later send address a group the new client does not have.
     */
    @Test
    fun `recovery clears in-memory membership`() {
        val src = source()
        val body = functionBody(src, "private suspend fun $RECOVERY_FN(")

        assertTrue(
            "$RECOVERY_FN must clear liveGroups so in-memory membership matches the " +
                "fresh client",
            body.contains("liveGroups.clear()")
        )
    }

    /**
     * forgetLocal persists. Calling it from the reset path would write a snapshot
     * before the new group's commit is accepted, so a later failure would leave
     * the device with neither the old group nor the new one.
     */
    @Test
    fun `reset path never calls forgetLocal`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        assertFalse(
            "$CALLER_FN must not call forgetLocal - it persists, and the first durable " +
                "write has to stay on the accepted-commit path",
            Regex("""(?<!NOT )forgetLocal\(""").containsMatchIn(caller.replace("NOT forgetLocal()", ""))
        )
    }

    /** No durable write may happen anywhere in the reset path. */
    @Test
    fun `reset path performs no snapshot write`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        assertFalse(
            "$CALLER_FN must not persist; the snapshot changes only once a commit is accepted",
            caller.contains("persist(")
        )
        assertFalse(
            "$CALLER_FN must not write the MLS bundle directly either",
            caller.contains("saveMlsBundle(")
        )
    }

    /**
     * The old group has to be addressed while its gid is still active. Switching
     * first would leave the abandoned group unreachable and undeleted.
     */
    @Test
    fun `old gid is captured before the active gid is switched`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        val capture = caller.indexOf("val oldGid")
        val drop = caller.indexOf("dropGroup(oldGid)")
        val switch = caller.indexOf("setActiveGid(")

        assertTrue("expected the old gid to be captured in $CALLER_FN", capture >= 0)
        assertTrue("expected dropGroup(oldGid) in $CALLER_FN", drop >= 0)
        assertTrue("expected setActiveGid in $CALLER_FN", switch >= 0)

        assertTrue("the old gid must be captured before it is switched away", capture < switch)
        assertTrue(
            "the abandoned group must be dropped while its gid is still active",
            drop < switch
        )
    }

    /** The new group must be built on the new gid, after the switch. */
    @Test
    fun `new group is created after the active gid is switched`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        val switch = caller.indexOf("setActiveGid(")
        val create = caller.indexOf("createGroupWithClient(")

        assertTrue("expected createGroupWithClient in $CALLER_FN", create >= 0)
        assertTrue(
            "createGroupWithClient must run after setActiveGid, or it would rebuild the " +
                "group under the abandoned gid",
            create > switch
        )
    }

    /**
     * A client whose abandoned group could not be removed is half-modified and
     * must not be carried forward; the explicit reset authorizes replacing it.
     */
    @Test
    fun `dropGroup failure falls back to the recovery-only client`() {
        val src = source()
        val caller = functionBody(src, "suspend fun $CALLER_FN(")

        val drop = caller.indexOf("dropGroup(oldGid)")
        assertTrue("expected dropGroup(oldGid) in $CALLER_FN", drop >= 0)

        val after = caller.substring(drop)
        assertTrue(
            "the dropGroup failure branch must fall back to $RECOVERY_FN rather than " +
                "continuing with a half-modified client",
            after.contains("$RECOVERY_FN(")
        )
        // Presence of the fallback is not enough: the branch has to actually be
        // reachable. A widened condition (`if (dropped || true)`) would leave the
        // fallback in the source while making it dead code, so the condition is
        // pinned exactly. Brittle on purpose - this is a security boundary.
        assertTrue(
            "the drop result must gate the branch exactly - found no `if (dropped) {`, " +
                "which means the failure path may have been made unreachable",
            Regex("""if\s*\(\s*dropped\s*\)\s*\{""").containsMatchIn(after)
        )
        assertFalse(
            "the drop condition must not be widened; that turns the fallback into dead code",
            Regex("""if\s*\(\s*dropped\s*(\|\||&&)""").containsMatchIn(after)
        )
    }
}
