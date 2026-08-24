package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.model.MlsCommitRequest
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
 * PHASE R2 (REMOVE ONLY) - the single authorized irreversible operation.
 *
 * Submits exactly the Samsung-only Remove commit validated in R1:
 * epoch 8 -> 9, no Welcomes, one credential.
 *
 * Every precondition is a hard gate: any mismatch aborts BEFORE submitMlsCommit.
 * Does NOT claim a KeyPackage, stage an Add, submit an Add, or call
 * inviteMissingDevices.
 */
class SamsungRemoveSubmitTest {

    private companion object {
        const val TAG = "R2REMOVE"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val TARGET =
            "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2|4038fd60-85e0-44a8-ac8c-f3a3f770d9f2"
        const val SENDER_DEVICE = "d22af910-68c1-4cb6-88a6-c58148ca09d8"
        const val EXPECTED_EPOCH = 8L
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    @Test
    fun submitSamsungRemove() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))

        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val api: ChatApiService = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChatApiService::class.java)

        val repo = MlsV2Repository(api, tm)

        p("=== R2 REMOVE: epoch 8 -> 9, Samsung only ===")

        val token = tm.getAccessToken().getOrNull()
        if (token.isNullOrBlank()) { p("ABORT: no access token"); return@runBlocking }
        val deviceId = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("sender device : $deviceId")
        if (deviceId != SENDER_DEVICE) { p("ABORT: sender device mismatch"); return@runBlocking }

        // ---- GATE 1: live server still exactly at epoch 8 with the same GID
        val gResp = runCatching { api.getMlsGroup("Bearer $token", CHAT) }.getOrNull()
        if (gResp == null || !gResp.isSuccessful) {
            p("ABORT: getMlsGroup failed (${gResp?.code()})"); return@runBlocking
        }
        val dto = gResp.body()
        if (dto == null) { p("ABORT: empty group body"); return@runBlocking }
        val serverGid = Base64.decode(dto.groupIdB64, Base64.NO_WRAP)
        p("server epoch  : ${dto.epoch}")
        p("server GID    : ${serverGid.hex()}")
        if (dto.epoch != EXPECTED_EPOCH) { p("ABORT: server epoch != 8"); return@runBlocking }
        if (serverGid.hex() != EXPECTED_GID) { p("ABORT: server GID mismatch"); return@runBlocking }
        p("GATE 1 server epoch 8 + GID match : OK")

        // ---- GATE 2: local state agrees (this also populates the repo's active GID)
        if (!repo.hasGroup(CHAT)) { p("ABORT: hasGroup false"); return@runBlocking }
        val localEpoch = repo.epoch(CHAT)
        p("local epoch   : $localEpoch")
        if (localEpoch != EXPECTED_EPOCH) { p("ABORT: local epoch != 8"); return@runBlocking }

        val gidB64 = tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull()
        if (gidB64.isNullOrBlank()) { p("ABORT: no persisted GID"); return@runBlocking }
        val gid = Base64.decode(gidB64, Base64.NO_WRAP)
        if (gid.hex() != EXPECTED_GID) { p("ABORT: local GID mismatch"); return@runBlocking }
        p("GATE 2 local epoch 8 + GID match  : OK")

        // ---- GATE 3: roster contains the target exactly once
        val rosterBefore = repo.roster(CHAT)
        p("roster BEFORE (${rosterBefore.size}):")
        rosterBefore.forEach { p("    $it") }
        if (rosterBefore.count { it == TARGET } != 1) {
            p("ABORT: target not present exactly once"); return@runBlocking
        }
        p("GATE 3 target present exactly once: OK")

        // ---- stage
        val c = repo.ensureClient()
        if (c == null) { p("ABORT: no client"); return@runBlocking }
        val commit = try {
            c.removeMembers(gid, listOf(TARGET))
        } catch (e: Throwable) { p("ABORT: removeMembers failed: ${e.message}"); return@runBlocking }
        p("staged commit bytes : ${commit.size}")

        // ---- SUBMIT (irreversible)
        p(">>> SUBMITTING expected_epoch=$EXPECTED_EPOCH welcomes=0 <<<")
        val resp = runCatching {
            api.submitMlsCommit(
                "Bearer $token", CHAT,
                MlsCommitRequest(
                    expectedEpoch = EXPECTED_EPOCH,
                    commitB64 = b64(commit),
                    senderDeviceId = deviceId,
                    welcomes = emptyList()
                )
            )
        }.getOrNull()

        if (resp == null || !resp.isSuccessful) {
            p("SUBMIT FAILED http=${resp?.code()} body=${resp?.errorBody()?.string()}")
            repo.onCommitRejected(CHAT)
            p("staged commit discarded; local epoch = ${repo.epoch(CHAT)}")
            p("RESULT: SUBMIT-REJECTED")
            return@runBlocking
        }
        p("HTTP ${resp.code()} OK; server reports epoch = ${resp.body()?.epoch}")

        // ---- merge + persist locally
        val newLocal = repo.onCommitAccepted(CHAT)
        p("local epoch AFTER merge+persist : $newLocal")

        val rosterAfter = repo.roster(CHAT)
        p("roster AFTER (${rosterAfter.size}):")
        rosterAfter.forEach { p("    $it") }
        p("target still present : ${rosterAfter.contains(TARGET)}")
        val removed = rosterBefore.filter { it !in rosterAfter }
        val added = rosterAfter.filter { it !in rosterBefore }
        p("removed credentials : $removed")
        p("added credentials   : $added")

        p("RESULT: REMOVE-SUBMITTED-AND-MERGED")
    }
}
