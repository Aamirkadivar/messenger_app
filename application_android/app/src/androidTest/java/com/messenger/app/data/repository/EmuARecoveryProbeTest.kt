package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.encryption.MlsClient
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
 * EMU-A RECOVERY FEASIBILITY PROBE - STRICTLY READ-ONLY.
 *
 * Restores the stored snapshot IN MEMORY, fetches commit material with
 * read-only GETs, and attempts to apply it to the in-memory client only, to
 * establish exactly why epoch 9 cannot be replayed by its own author.
 *
 * persist() is NEVER called. MlsV2Repository is used only for its read paths;
 * syncHandshakes is deliberately NOT used because it persists on success.
 */
class EmuARecoveryProbeTest {

    private companion object {
        const val TAG = "EMUAPROBE"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val EMUA_DEV = "d22af910-68c1-4cb6-88a6-c58148ca09d8"
        const val SNAPSHOT_KEY = "mls2_snapshot"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun entries(snap: ByteArray): Int {
        val klen = ((snap[0].toInt() and 0xFF) shl 24) or ((snap[1].toInt() and 0xFF) shl 16) or
            ((snap[2].toInt() and 0xFF) shl 8) or (snap[3].toInt() and 0xFF)
        val s = snap.copyOfRange(4 + klen, snap.size)
        var n = 0L
        for (i in 4 until 12) n = (n shl 8) or (s[i].toLong() and 0xFF)
        return n.toInt()
    }

    @Test
    fun probeRecoveryFeasibility() = runBlocking {
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
        suspend fun tok() = "Bearer " + tm.getAccessToken().getOrNull().orEmpty()

        p("=== EMU-A RECOVERY FEASIBILITY PROBE (READ-ONLY) ===")

        // ---- 1. identity
        val user = tm.getCurrentUserId().getOrNull().orEmpty()
        val dev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("identity : $user|$dev")
        if (dev != EMUA_DEV) { p("ABORT: not EMU-A"); return@runBlocking }

        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)
        p("local GID       : ${gid.hex()}")
        p("GID matches srv : ${gid.hex() == EXPECTED_GID}")

        val blob = Base64.decode(tm.loadMlsBundle(SNAPSHOT_KEY).getOrNull(), Base64.NO_WRAP)
        p("snapshot bytes  : ${blob.size}")
        p("storage entries : ${entries(blob)}")

        // ---- 2. what epoch does the DURABLE snapshot hold?
        val c = MlsClient.restore(blob, user, dev)
        val epochBefore = try { c.loadGroup(gid) } catch (e: Throwable) {
            p("ABORT: loadGroup failed: ${e.message}"); return@runBlocking
        }
        p("DURABLE local epoch : $epochBefore")
        val roster = c.roster(gid)
        p("local roster (${roster.size}):")
        roster.forEach { p("    $it") }

        // ---- 3. what commit material is available (read-only GET)?
        val srv = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        p("server epoch    : ${srv?.body()?.epoch}")

        val hs = runCatching {
            api.getMlsHandshakes(tok(), CHAT, epochBefore).body()?.handshakes.orEmpty()
        }.getOrDefault(emptyList())
        p("handshakes available since epoch $epochBefore : ${hs.size}")
        hs.forEach {
            p("    epoch=${it.epoch} kind=${it.kind} sender=${it.senderDeviceId.take(8)} " +
                "bytes=${Base64.decode(it.payloadB64, Base64.NO_WRAP).size} " +
                "${if (it.senderDeviceId == EMUA_DEV) "<- AUTHORED BY EMU-A" else ""}")
        }

        // ---- 4. attempt to apply epoch 9 (in memory only, never persisted)
        val ep9 = hs.firstOrNull { it.epoch == 9L }
        if (ep9 == null) { p("epoch-9 commit not available"); }
        else {
            val r = runCatching { c.process(gid, Base64.decode(ep9.payloadB64, Base64.NO_WRAP)) }
            p("apply epoch 9 -> ${if (r.isSuccess) "OK" else "FAIL: ${r.exceptionOrNull()?.message}"}")
            p("local epoch after ep9 attempt : ${runCatching { c.epoch(gid) }.getOrNull()}")
        }

        // ---- 5. attempt epoch 10 on a FRESH restore (isolate from the ep9 attempt)
        val ep10 = hs.firstOrNull { it.epoch == 10L }
        if (ep10 == null) { p("epoch-10 commit not available") }
        else {
            val c2 = MlsClient.restore(blob, user, dev)
            c2.loadGroup(gid)
            val r = runCatching { c2.process(gid, Base64.decode(ep10.payloadB64, Base64.NO_WRAP)) }
            p("apply epoch 10 directly at epoch $epochBefore -> " +
                "${if (r.isSuccess) "OK" else "FAIL: ${r.exceptionOrNull()?.message}"}")
            p("local epoch after ep10 attempt : ${runCatching { c2.epoch(gid) }.getOrNull()}")
            runCatching { c2.close() }
        }

        // ---- 6. is there a pending commit left in the durable snapshot?
        val c3 = MlsClient.restore(blob, user, dev)
        c3.loadGroup(gid)
        val clr = runCatching { c3.clearPending(gid) }
        p("clearPending on durable state -> ${if (clr.isSuccess) "no-op/ok" else "FAIL: ${clr.exceptionOrNull()?.message}"}")
        p("epoch still : ${runCatching { c3.epoch(gid) }.getOrNull()} (any pending epoch-9 commit would be visible as staged state)")
        runCatching { c3.close() }

        runCatching { c.close() }
        p("NO persist() CALLED ANYWHERE IN THIS PROBE")
        p("RESULT: PROBE-COMPLETE")
    }
}
