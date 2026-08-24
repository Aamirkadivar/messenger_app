package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.model.MlsCommitRequest
import com.messenger.app.data.remote.TokenRefreshAuthenticator
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/**
 * THE AUTHORIZED IRREVERSIBLE OPERATION - EMU-A external commit, epoch 10 -> 11.
 *
 * Four members before, four members after; same four signature keys. No Add, no
 * Remove semantics, no KeyPackage, no Welcome, no GroupInfo upload.
 *
 * On 2xx: merge + persist through the production onCommitAccepted path, then a
 * genuine flush window - `saveMlsBundle` uses apply(), which reports success
 * before the write lands, and that is exactly what stranded this device at
 * epoch 9. Durability is verified afterwards from a SEPARATE process.
 *
 * On non-2xx: stop. No retry, no clearPending (it cannot rescue an external
 * commit), no persist - so the durable epoch-8 snapshot survives untouched.
 */
class ExternalCommitSubmitTest {

    private companion object {
        const val TAG = "EXTSUBMIT"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val EMUA_DEV = "d22af910-68c1-4cb6-88a6-c58148ca09d8"
        const val EXPECTED_EPOCH = 10L
        const val IN = "groupinfo.txt"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    @Test
    fun submitExternalCommit() = runBlocking {
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

        p("=== EXTERNAL COMMIT SUBMIT: epoch 10 -> 11 ===")
        val user = tm.getCurrentUserId().getOrNull().orEmpty()
        val dev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("identity : $user|$dev")
        if (dev != EMUA_DEV) { p("ABORT: wrong device"); return@runBlocking }

        // ---- transferred GroupInfo + authoritative epoch-10 leaf set
        val f = File(ctx.getExternalFilesDir(null), IN)
        if (!f.exists()) { p("ABORT: groupinfo missing"); return@runBlocking }
        val lines = f.readLines()
        val gi = Base64.decode(
            lines.first { it.startsWith("GI ") }.removePrefix("GI "), Base64.NO_WRAP)
        val srcEpoch = lines.first { it.startsWith("EPOCH ") }
            .removePrefix("EPOCH ").trim().toLong()
        val expectedLeaves = lines.filter { it.startsWith("LEAF ") }
            .map { it.removePrefix("LEAF ") }.sorted()
        p("GroupInfo bytes  : ${gi.size}  sourceEpoch=$srcEpoch  leaves=${expectedLeaves.size}")
        if (srcEpoch != EXPECTED_EPOCH || expectedLeaves.size != 4) {
            p("ABORT: GroupInfo is not a 4-leaf epoch-10 export"); return@runBlocking
        }

        // ---- GATE 1: server epoch + GID
        val g = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        if (g?.isSuccessful != true || g.body() == null) {
            p("ABORT: getMlsGroup failed (${g?.code()})"); return@runBlocking
        }
        val serverGid = Base64.decode(g.body()!!.groupIdB64, Base64.NO_WRAP)
        p("server epoch : ${g.body()!!.epoch}")
        p("server GID   : ${serverGid.hex()}")
        if (g.body()!!.epoch != EXPECTED_EPOCH) { p("ABORT: server epoch != 10"); return@runBlocking }
        if (serverGid.hex() != EXPECTED_GID) { p("ABORT: GID mismatch"); return@runBlocking }
        p("GATE 1 epoch 10 + GID : OK")

        // ---- GATE 2: no intervening handshake beyond epoch 10
        val hs = runCatching {
            api.getMlsHandshakes(tok(), CHAT, EXPECTED_EPOCH).body()?.handshakes.orEmpty()
        }.getOrNull()
        if (hs == null) { p("ABORT: handshake check failed"); return@runBlocking }
        p("handshakes beyond epoch 10 : ${hs.size}")
        if (hs.isNotEmpty()) { p("ABORT: someone committed since epoch 10"); return@runBlocking }
        p("GATE 2 no intervening handshake : OK")

        // ---- GATE 3: local state, and the four leaves we must preserve
        if (!repo.hasGroup(CHAT)) { p("ABORT: hasGroup false"); return@runBlocking }
        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)
        if (gid.hex() != EXPECTED_GID) { p("ABORT: local GID mismatch"); return@runBlocking }
        val c = repo.ensureClient()
        if (c == null) { p("ABORT: no client"); return@runBlocking }
        val durable = runCatching { c.loadGroup(gid) }.getOrNull()
        p("EMU-A durable epoch : $durable")
        p("GATE 3 local GID : OK")

        // ---- build the external commit
        runCatching { c.dropGroup(gid) }
        val r = runCatching { c.externalJoin(gi) }.getOrElse {
            p("ABORT: externalJoin failed: ${it.message}"); return@runBlocking
        }
        p("external commit bytes : ${r.commit.size}")
        if (r.groupId.hex() != EXPECTED_GID) { p("ABORT: commit targets another group"); return@runBlocking }

        // ---- GATE 4: re-check the epoch immediately before submitting
        val g2 = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        if (g2?.isSuccessful != true || g2.body()?.epoch != EXPECTED_EPOCH) {
            p("ABORT: server moved to ${g2?.body()?.epoch}; discarding, NOT submitting")
            return@runBlocking
        }
        p("GATE 4 still epoch 10 immediately pre-submit : OK")

        // ---- SUBMIT (irreversible)
        p(">>> SUBMITTING external commit expected_epoch=$EXPECTED_EPOCH welcomes=0 <<<")
        val resp = runCatching {
            api.submitMlsCommit(tok(), CHAT, MlsCommitRequest(
                expectedEpoch = EXPECTED_EPOCH,
                commitB64 = b64(r.commit),
                senderDeviceId = dev,
                welcomes = emptyList()
            ))
        }.getOrNull()

        if (resp?.isSuccessful != true) {
            p("SUBMIT REJECTED http=${resp?.code()}")
            p("NOT retrying, NOT clearing, NOT persisting - durable epoch-8 snapshot survives")
            p("RESULT: SUBMIT-REJECTED")
            return@runBlocking
        }
        p("HTTP ${resp.code()} OK; server reports epoch = ${resp.body()?.epoch}")

        // ---- merge + persist via the production path
        val newEpoch = repo.onCommitAccepted(CHAT)
        p("local epoch after merge+persist : $newEpoch")

        val roster = c.roster(gid)
        val keys = c.memberSignatureKeys(gid)
        val actual = roster.indices.map { "${roster[it]} ${keys[it].hex()}" }.sorted()
        p("roster after (${actual.size}):")
        actual.forEach { p("    $it") }
        p("FOUR CREDENTIALS + FOUR KEYS BYTE-IDENTICAL : ${actual == expectedLeaves}")

        // ---- genuine flush window: apply() reports success before the write lands
        p("holding flush window...")
        delay(8000)
        p("flush window elapsed; durability to be confirmed from a fresh process")
        p("RESULT: EXTERNAL-COMMIT-SUBMITTED")
    }
}
