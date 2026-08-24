package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.MlsClient
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * GATE 2-4, STEP 2 - runs on EMU-A (d22af910 / emulator-5558).
 *
 * Performs the external-commit recovery ENTIRELY IN MEMORY against a snapshot
 * restored from storage, and asserts the invariant: the four credentials and
 * four signature keys are byte-identical, and the recovered client reaches
 * epoch 11 while the server stays at 10.
 *
 * Never calls persist(), submitMlsCommit, or any network path. EMU-A's durable
 * snapshot is untouched - the host verifies its mtime and md5 either side.
 */
class ExternalJoinProofTest {

    private companion object {
        const val TAG = "EXTPROOF"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val EMUA_DEV = "d22af910-68c1-4cb6-88a6-c58148ca09d8"
        const val DURABLE_EPOCH = 8L
        const val SERVER_EPOCH = 10L
        const val IN = "groupinfo.txt"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private var failures = 0
    private fun ok(label: String, cond: Boolean) {
        p("  [${if (cond) "PASS" else "FAIL"}] $label")
        if (!cond) failures++
    }

    @Test
    fun externalCommitRecoversWithoutChangingMembership() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))

        p("=== GATE 2-4 STEP 2: external-commit recovery, IN MEMORY ONLY ===")
        val user = tm.getCurrentUserId().getOrNull().orEmpty()
        val dev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("identity : $user|$dev")
        if (dev != EMUA_DEV) { p("ABORT: wrong device, expected $EMUA_DEV"); return@runBlocking }

        // ---- transferred GroupInfo + the authoritative epoch-10 roster
        val f = File(ctx.getExternalFilesDir(null), IN)
        if (!f.exists()) { p("ABORT: ${f.absolutePath} missing"); return@runBlocking }
        val lines = f.readLines()
        val gi = Base64.decode(
            lines.first { it.startsWith("GI ") }.removePrefix("GI "), Base64.NO_WRAP
        )
        val srcEpoch = lines.first { it.startsWith("EPOCH ") }.removePrefix("EPOCH ").trim().toLong()
        val expectedLeaves = lines.filter { it.startsWith("LEAF ") }
            .map { it.removePrefix("LEAF ") }.sorted()
        p("GroupInfo bytes : ${gi.size}")
        p("source epoch    : $srcEpoch")
        p("expected leaves : ${expectedLeaves.size}")
        expectedLeaves.forEach { p("    $it") }

        ok("GroupInfo came from the server epoch", srcEpoch == SERVER_EPOCH)
        ok("exactly four leaves expected", expectedLeaves.size == 4)

        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)
        ok("local GID matches server GID", gid.hex() == EXPECTED_GID)

        // ---- restore EMU-A in memory; nothing here is ever written back
        val blob = Base64.decode(tm.loadMlsBundle("mls2_snapshot").getOrNull(), Base64.NO_WRAP)
        val c = MlsClient.restore(blob, user, dev)
        try {
            val stale = c.loadGroup(gid)
            p("EMU-A durable epoch : $stale")
            ok("EMU-A starts stranded at epoch 8", stale == DURABLE_EPOCH)

            // ---- external commit
            c.dropGroup(gid)
            val r = c.externalJoin(gi)
            p("external commit bytes : ${r.commit.size}")
            ok("external commit targets this group", r.groupId.hex() == EXPECTED_GID)

            val recovered = c.mergePending(gid)
            p("recovered in-memory epoch : $recovered")
            ok("recovered client reaches epoch 11", recovered == SERVER_EPOCH + 1)

            val roster = c.roster(gid)
            val keys = c.memberSignatureKeys(gid)
            val actualLeaves = roster.indices.map { "${roster[it]} ${keys[it].hex()}" }.sorted()
            p("recovered roster:")
            actualLeaves.forEach { p("    $it") }

            ok("still exactly four leaves", actualLeaves.size == 4)
            ok(
                "FOUR CREDENTIALS AND FOUR SIGNATURE KEYS BYTE-IDENTICAL",
                actualLeaves == expectedLeaves
            )
            ok("EMU-A is a member exactly once",
                roster.count { it == "$user|$dev" } == 1)
            ok("no leaf added", actualLeaves.all { expectedLeaves.contains(it) })
            ok("no leaf removed", expectedLeaves.all { actualLeaves.contains(it) })

            p(if (failures == 0) "RESULT: PROOF-PASSED" else "RESULT: PROOF-FAILED ($failures)")
        } finally {
            runCatching { c.close() }
            p("client closed; persist() never called, nothing submitted")
        }
    }
}
