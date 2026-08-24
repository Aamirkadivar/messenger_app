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
 * GATE 2-4, STEP 1 - runs on the HEALTHY member (bd63c60a / emulator-5556).
 *
 * Exports GroupInfo plus the authoritative epoch-10 roster and signature-key
 * set, and writes them to a file the host pulls. Nothing is uploaded: the
 * transfer is host-mediated so the server's group_info_epoch stays 0.
 *
 * Read-only: restores the snapshot in memory, never calls persist().
 */
class ExportGroupInfoTest {

    private companion object {
        const val TAG = "GIEXPORT"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val ADDER_DEV = "bd63c60a-fa1e-4689-a1b3-719139fb53f7"
        const val EXPECTED_EPOCH = 10L
        const val OUT = "groupinfo.txt"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun exportGroupInfoAndRoster() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))

        p("=== GATE 2-4 STEP 1: export GroupInfo (read-only) ===")
        val user = tm.getCurrentUserId().getOrNull().orEmpty()
        val dev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("identity : $user|$dev")
        if (dev != ADDER_DEV) { p("ABORT: wrong device, expected $ADDER_DEV"); return@runBlocking }

        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)
        if (gid.hex() != EXPECTED_GID) { p("ABORT: GID mismatch"); return@runBlocking }

        val blob = Base64.decode(tm.loadMlsBundle("mls2_snapshot").getOrNull(), Base64.NO_WRAP)
        val c = MlsClient.restore(blob, user, dev)
        try {
            val epoch = c.loadGroup(gid)
            p("local epoch : $epoch")
            if (epoch != EXPECTED_EPOCH) { p("ABORT: epoch != 10"); return@runBlocking }

            val roster = c.roster(gid)
            val keys = c.memberSignatureKeys(gid)
            if (roster.size != 4 || keys.size != 4) {
                p("ABORT: expected 4 leaves, saw ${roster.size}/${keys.size}"); return@runBlocking
            }

            val gi = c.exportGroupInfo(gid)
            p("GroupInfo bytes : ${gi.size}")

            val lines = mutableListOf<String>()
            lines += "GI " + Base64.encodeToString(gi, Base64.NO_WRAP)
            lines += "EPOCH $epoch"
            roster.indices
                .map { "${roster[it]} ${keys[it].hex()}" }
                .sorted()
                .forEach { lines += "LEAF $it" }

            val out = File(ctx.getExternalFilesDir(null), OUT)
            out.writeText(lines.joinToString("\n"))
            p("wrote ${out.absolutePath} (${out.length()} bytes)")

            p("epoch-10 roster:")
            roster.indices.map { "${roster[it]} ${keys[it].hex()}" }.sorted()
                .forEach { p("    $it") }
            p("RESULT: EXPORT-OK")
        } finally {
            runCatching { c.close() }
            p("client closed; no persist() called")
        }
    }
}
