package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.remote.TokenRefreshAuthenticator
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * PHASE R3 - EMU-A REMOVE PRE-FLIGHT. STAGING ONLY, THEN DISCARD.
 *
 * Runs on the healthy adder bd63c60a. Stages a Remove for exactly
 * 07a6f67a...|d22af910... against epoch 10, inspects it, then clears the
 * pending commit.
 *
 * NO submitMlsCommit. NO KeyPackage claim. NO merge. NO persist.
 * The only HTTP is read-only verification (GET group).
 */
class EmuARemovePreflightTest {

    private companion object {
        const val TAG = "R3PRE"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val TARGET =
            "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2|d22af910-68c1-4cb6-88a6-c58148ca09d8"
        const val SAMSUNG =
            "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2|4038fd60-85e0-44a8-ac8c-f3a3f770d9f2"
        const val ADDER_DEV = "bd63c60a-fa1e-4689-a1b3-719139fb53f7"
        const val EXPECTED_EPOCH = 10L
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun stageEmuARemoveThenDiscard() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val ct = "application/json".toMediaType()

        val authApi = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient()).addConverterFactory(json.asConverterFactory(ct))
            .build().create(AuthApiService::class.java)
        val client = OkHttpClient.Builder()
            .authenticator(TokenRefreshAuthenticator(tm) { authApi }).build()
        val api: ChatApiService = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL)
            .client(client).addConverterFactory(json.asConverterFactory(ct))
            .build().create(ChatApiService::class.java)

        val repo = MlsV2Repository(api, tm)
        suspend fun tok() = "Bearer " + tm.getAccessToken().getOrNull().orEmpty()

        p("=== R3: EMU-A Remove PRE-FLIGHT (stage then discard, NO SUBMIT) ===")

        val meDev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("adder device : $meDev")
        if (meDev != ADDER_DEV) { p("ABORT: wrong device"); return@runBlocking }
        p("GATE adder identity : OK")

        // ---- GATE 1: server GID + epoch 10 (read-only)
        val g = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        if (g?.isSuccessful != true || g.body() == null) {
            p("ABORT: getMlsGroup failed (${g?.code()})"); return@runBlocking
        }
        val serverGid = Base64.decode(g.body()!!.groupIdB64, Base64.NO_WRAP)
        p("server epoch : ${g.body()!!.epoch}")
        p("server GID   : ${serverGid.hex()}")
        if (g.body()!!.epoch != EXPECTED_EPOCH) { p("ABORT: server epoch != 10"); return@runBlocking }
        if (serverGid.hex() != EXPECTED_GID) { p("ABORT: GID mismatch"); return@runBlocking }
        p("GATE 1 server GID + epoch 10 : OK")

        // ---- GATE 2: local state at epoch 10
        if (!repo.hasGroup(CHAT)) { p("ABORT: hasGroup false"); return@runBlocking }
        val localEpoch = repo.epoch(CHAT)
        p("adder local epoch : $localEpoch")
        if (localEpoch != EXPECTED_EPOCH) { p("ABORT: local epoch != 10"); return@runBlocking }
        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)
        if (gid.hex() != EXPECTED_GID) { p("ABORT: local GID mismatch"); return@runBlocking }
        p("GATE 2 local epoch 10 + GID : OK")

        // ---- GATE 3: target present exactly once; Samsung present and NOT the target
        val rosterBefore = repo.roster(CHAT)
        p("roster BEFORE (${rosterBefore.size}):")
        rosterBefore.forEach { p("    $it") }
        val hits = rosterBefore.count { it == TARGET }
        p("target credential  : $TARGET")
        p("target occurrences : $hits")
        if (hits != 1) { p("ABORT: target not present exactly once"); return@runBlocking }
        if (!rosterBefore.contains(SAMSUNG)) {
            p("ABORT: Samsung missing from roster - unexpected state"); return@runBlocking
        }
        p("Samsung present and is NOT the target : ${TARGET != SAMSUNG}")
        p("GATE 3 target present exactly once : OK")

        // ---- stage (not submitted, not merged, not persisted)
        val c = repo.ensureClient()
        if (c == null) { p("ABORT: no client"); return@runBlocking }
        val commit = try {
            c.removeMembers(gid, listOf(TARGET))
        } catch (e: Throwable) {
            p("removeMembers FAILED: ${e.message}")
            p("RESULT: STOP - EMU-A is NOT removable by that credential")
            return@runBlocking
        }
        p("removeMembers        : SUCCESS")
        p("staged commit bytes  : ${commit.size}")
        p("credentials passed   : 1")
        p("expected epoch for R4 submission : $localEpoch")

        val rosterAfterStage = repo.roster(CHAT)
        p("local epoch AFTER STAGE : ${repo.epoch(CHAT)}")
        p("roster unchanged by staging (pending, not merged): " +
            "${rosterAfterStage == rosterBefore}")
        p("intended delta on merge : REMOVE $TARGET")
        p("other credentials affected : NONE")

        // ---- discard without persistence
        c.clearPending(gid)
        val epochAfterClear = repo.epoch(CHAT)
        val rosterAfterClear = repo.roster(CHAT)
        p("clearPending         : done (no persist called)")
        p("local epoch AFTER CLEAR : $epochAfterClear")
        p("roster identical to start : ${rosterAfterClear == rosterBefore}")
        p("epoch still exactly 10    : ${epochAfterClear == EXPECTED_EPOCH}")
        p("RESULT: STAGED-OK-DISCARDED")
    }
}
