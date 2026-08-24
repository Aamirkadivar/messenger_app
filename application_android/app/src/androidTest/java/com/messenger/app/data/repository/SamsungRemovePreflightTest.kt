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
 * PHASE R1 PRE-FLIGHT - local staging only.
 *
 * Stages a Remove for exactly one credential against this device's REAL
 * persisted MLS state, inspects it, then discards it.
 *
 * Deliberately avoids MlsV2Repository entirely: every helper there that could
 * reach the network (hasGroup -> ensureCurrentGid -> api.getMlsGroup) is off
 * limits for this phase. This restores a SEPARATE MlsClient handle from the
 * stored snapshot via the production MlsClient.restore API, so the app's own
 * client is never constructed and nothing is ever persisted.
 *
 * Performs NO HTTP request, NO submitMlsCommit, NO mergePending, NO persist.
 */
class SamsungRemovePreflightTest {

    private companion object {
        const val TAG = "PREFLIGHT"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val TARGET =
            "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2|4038fd60-85e0-44a8-ac8c-f3a3f770d9f2"
        const val SNAPSHOT_KEY = "mls2_snapshot"
    }

    private fun p(msg: String) = Log.i(TAG, msg)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun stageSamsungRemoveThenDiscard() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))

        p("=== R1 PRE-FLIGHT: local staging only ===")

        val userId = tm.getCurrentUserId().getOrNull().orEmpty()
        val deviceId = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("identity        : $userId|$deviceId")
        if (userId.isBlank() || deviceId.isBlank()) {
            p("RESULT: ABORT - no identity on this device"); return@runBlocking
        }

        // ---- precondition: local active GID
        val gidB64 = tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull()
        if (gidB64.isNullOrBlank()) {
            p("RESULT: ABORT - no persisted active GID for $CHAT"); return@runBlocking
        }
        val gid = Base64.decode(gidB64, Base64.NO_WRAP)
        p("local GID       : ${gid.hex()}")
        p("expected GID    : $EXPECTED_GID")
        if (gid.hex() != EXPECTED_GID) {
            p("RESULT: ABORT - local GID does not match the server GID"); return@runBlocking
        }
        p("GID match       : YES")

        // ---- restore a SEPARATE client from the real snapshot
        val blobB64 = tm.loadMlsBundle(SNAPSHOT_KEY).getOrNull()
        if (blobB64.isNullOrBlank()) {
            p("RESULT: ABORT - no MLS snapshot stored"); return@runBlocking
        }
        val c = MlsClient.restore(Base64.decode(blobB64, Base64.NO_WRAP), userId, deviceId)

        try {
            // ---- precondition: local epoch
            val epochBefore = try {
                c.loadGroup(gid)
            } catch (e: Throwable) {
                p("RESULT: ABORT - loadGroup failed: ${e.message}"); return@runBlocking
            }
            p("local epoch BEFORE : $epochBefore")
            if (epochBefore != 8L) {
                p("RESULT: ABORT - local epoch is not 8"); return@runBlocking
            }

            // ---- precondition: target present exactly once
            val rosterBefore = c.roster(gid)
            p("roster BEFORE (${rosterBefore.size} leaves):")
            rosterBefore.forEach { p("    $it") }
            val hits = rosterBefore.count { it == TARGET }
            p("target credential   : $TARGET")
            p("target occurrences  : $hits")
            if (hits != 1) {
                p("RESULT: STOP - target is not present exactly once (found $hits)")
                return@runBlocking
            }

            // ---- stage the Remove (NOT submitted, NOT merged, NOT persisted)
            val commit = try {
                c.removeMembers(gid, listOf(TARGET))
            } catch (e: Throwable) {
                p("removeMembers FAILED: ${e.message}")
                p("RESULT: STOP - Samsung is NOT removable by that credential")
                return@runBlocking
            }
            p("removeMembers       : SUCCESS")
            p("staged commit bytes : ${commit.size}")
            p("credentials passed  : 1")
            p("staged commit expected epoch (what R2 would submit): $epochBefore")

            val rosterAfterStage = c.roster(gid)
            val epochAfterStage = c.epoch(gid)
            p("local epoch AFTER STAGE : $epochAfterStage")
            p("roster AFTER STAGE size : ${rosterAfterStage.size}")
            p("roster unchanged by staging (expected - pending, not merged): " +
                "${rosterAfterStage == rosterBefore}")
            p("intended delta on merge : REMOVE $TARGET")
            p("other credentials affected: NONE (single credential passed, " +
                "single matching leaf)")

            // ---- discard, without persistence
            c.clearPending(gid)
            val epochAfterClear = c.epoch(gid)
            val rosterAfterClear = c.roster(gid)
            p("clearPending        : done (no persist called)")
            p("local epoch AFTER CLEAR : $epochAfterClear")
            p("roster AFTER CLEAR size : ${rosterAfterClear.size}")
            p("roster identical to start: ${rosterAfterClear == rosterBefore}")
            p("epoch still exactly 8   : ${epochAfterClear == 8L}")

            p("RESULT: STAGED-OK-DISCARDED")
        } finally {
            runCatching { c.close() }
            p("client closed; snapshot never rewritten")
        }
    }
}
