package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.MlsClient
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * READ-ONLY identity probe.
 *
 * Restores this device's stored MLS snapshot IN MEMORY and reports what
 * identity it actually holds, by matching the signature public key recorded in
 * the snapshot header against the group's leaf signature keys.
 *
 * Makes NO network call, claims nothing, stages nothing, persists nothing.
 * MlsClient.restore + loadGroup + roster are all read paths; the snapshot is
 * never rewritten because persist() is never invoked.
 */
class Emu5556IdentityProbeTest {

    private companion object {
        const val TAG = "IDPROBE"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val SERVER_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val CANDIDATE_DEV = "bd63c60a-fa1e-4689-a1b3-719139fb53f7"
        const val SNAPSHOT_KEY = "mls2_snapshot"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun probeStoredIdentity() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))

        p("=== READ-ONLY MLS IDENTITY PROBE ===")

        val prefsUser = tm.getCurrentUserId().getOrNull().orEmpty()
        val prefsDev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("prefs current_user_id : $prefsUser")
        p("prefs e2ee_device_id  : $prefsDev")

        // ---- stored active GID for this chat
        val gidB64 = tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull()
        if (gidB64.isNullOrBlank()) { p("RESULT: no stored active GID for this chat"); return@runBlocking }
        val gid = Base64.decode(gidB64, Base64.NO_WRAP)
        p("stored local GID      : ${gid.hex()}")
        p("server GID            : $SERVER_GID")
        p("GID matches server    : ${gid.hex() == SERVER_GID}")

        // ---- stored snapshot
        val blobB64 = tm.loadMlsBundle(SNAPSHOT_KEY).getOrNull()
        if (blobB64.isNullOrBlank()) { p("RESULT: no MLS snapshot stored"); return@runBlocking }
        val blob = Base64.decode(blobB64, Base64.NO_WRAP)
        p("snapshot bytes        : ${blob.size}")

        // Snapshot layout: u32 BE len(pubkey) || pubkey || storage-blob
        if (blob.size < 4) { p("RESULT: snapshot too short"); return@runBlocking }
        val klen = ((blob[0].toInt() and 0xFF) shl 24) or
            ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or
            (blob[3].toInt() and 0xFF)
        if (klen <= 0 || blob.size < 4 + klen) { p("RESULT: snapshot header unreadable"); return@runBlocking }
        val ownSigKey = blob.copyOfRange(4, 4 + klen)
        p("snapshot signature key: ${ownSigKey.hex()}")

        // ---- restore in memory only (no persist anywhere in this test)
        val c = try {
            MlsClient.restore(blob, prefsUser, prefsDev)
        } catch (e: Throwable) {
            p("RESULT: restore FAILED: ${e.message}"); return@runBlocking
        }

        try {
            val epoch = try {
                c.loadGroup(gid)
            } catch (e: Throwable) {
                p("loadGroup failed: ${e.message}")
                p("RESULT: snapshot holds no group for this GID")
                return@runBlocking
            }
            p("local epoch           : $epoch")

            val roster = c.roster(gid)
            val keys = c.memberSignatureKeys(gid)
            p("roster (${roster.size} leaves):")
            roster.forEachIndexed { i, cred ->
                val k = keys.getOrNull(i)
                val mine = k != null && k.contentEquals(ownSigKey)
                p("    ${if (mine) "*" else " "} $cred")
                if (k != null) p("        sigkey=${k.hex()}")
            }

            val myIndex = keys.indexOfFirst { it.contentEquals(ownSigKey) }
            if (myIndex >= 0) {
                p("STORED IDENTITY (own leaf) : ${roster[myIndex]}")
                p("is the bd63c60a adder      : ${roster[myIndex].endsWith("|$CANDIDATE_DEV")}")
            } else {
                p("STORED IDENTITY (own leaf) : NOT A MEMBER - this snapshot's signature")
                p("                             key matches no leaf in the group")
            }
            p("prefs device vs stored leaf consistent : " +
                "${myIndex >= 0 && roster[myIndex].endsWith("|$prefsDev")}")
            p("RESULT: PROBE-COMPLETE")
        } finally {
            runCatching { c.close() }
            p("client closed; snapshot never rewritten")
        }
    }
}
