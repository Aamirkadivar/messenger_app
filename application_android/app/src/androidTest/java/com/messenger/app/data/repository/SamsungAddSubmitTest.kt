package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.model.MlsClaimKeyPackageRequest
import com.messenger.app.data.model.MlsCommitRequest
import com.messenger.app.data.model.MlsWelcomeItem
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
import java.security.MessageDigest

/**
 * PHASE R2/ADD - THE AUTHORIZED IRREVERSIBLE SUBMISSION (epoch 9 -> 10).
 *
 * Claim + stage + submit in ONE run so exactly one KeyPackage is consumed.
 * Every precondition is a hard gate; any mismatch aborts BEFORE submitMlsCommit.
 * Never launches MainActivity, never calls inviteMissingDevices/evictPhantomMembers.
 */
class SamsungAddSubmitTest {

    private companion object {
        const val TAG = "R2SUBMIT"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val S_USER = "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2"
        const val S_DEV = "4038fd60-85e0-44a8-ac8c-f3a3f770d9f2"
        const val S_CRED = "$S_USER|$S_DEV"
        /** Signature key of Samsung's former leaf, from the epoch-8 identity probe. */
        const val S_SIGKEY =
            "fbe2c3402ce83030602307496052e481580dc91cac42f6805280589038ee7a9c"
        const val EXPECTED_EPOCH = 9L
        const val CIPHER_SUITE = 1
        const val ADDER_DEV = "bd63c60a-fa1e-4689-a1b3-719139fb53f7"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).hex()

    @Test
    fun claimStageAndSubmitSamsungAdd() = runBlocking {
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

        p("=== R2/ADD SUBMIT: epoch 9 -> 10, Samsung only ===")

        val meDev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("adder device : $meDev")
        if (meDev != ADDER_DEV) { p("ABORT: wrong device"); return@runBlocking }

        // ---- GATE 1: server GID + epoch
        val g = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        if (g?.isSuccessful != true || g.body() == null) {
            p("ABORT: getMlsGroup failed (${g?.code()})"); return@runBlocking
        }
        val serverGid = Base64.decode(g.body()!!.groupIdB64, Base64.NO_WRAP)
        p("server epoch BEFORE : ${g.body()!!.epoch}")
        p("server GID          : ${serverGid.hex()}")
        if (g.body()!!.epoch != EXPECTED_EPOCH) { p("ABORT: server epoch != 9"); return@runBlocking }
        if (serverGid.hex() != EXPECTED_GID) { p("ABORT: GID mismatch"); return@runBlocking }
        p("GATE 1 GID + epoch 9 : OK")

        // ---- GATE 2: local state at epoch 9
        if (!repo.hasGroup(CHAT)) { p("ABORT: hasGroup false"); return@runBlocking }
        var local = repo.epoch(CHAT)
        if (local != EXPECTED_EPOCH) { repo.syncHandshakes(CHAT); local = repo.epoch(CHAT) }
        p("adder local epoch   : $local")
        if (local != EXPECTED_EPOCH) { p("ABORT: local epoch != 9"); return@runBlocking }
        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)

        val rosterBefore = repo.roster(CHAT)
        p("roster BEFORE (${rosterBefore.size}):")
        rosterBefore.forEach { p("    $it") }
        if (rosterBefore.contains(S_CRED)) { p("ABORT: Samsung already a leaf"); return@runBlocking }
        p("GATE 2 local epoch 9, Samsung absent : OK")

        // ---- GATE 3: claim exactly one fresh KeyPackage
        val claim = runCatching {
            api.claimMlsKeyPackage(tok(),
                MlsClaimKeyPackageRequest(userId = S_USER, deviceId = S_DEV, cipherSuite = CIPHER_SUITE))
        }.getOrNull()
        if (claim?.isSuccessful != true || claim.body() == null) {
            p("ABORT: claim failed (${claim?.code()})"); return@runBlocking
        }
        val cb = claim.body()!!
        if (cb.userId != S_USER || cb.deviceId != S_DEV) {
            p("ABORT: claim not for Samsung (${cb.userId}|${cb.deviceId})"); return@runBlocking
        }
        val kp = Base64.decode(cb.keyPackageB64, Base64.NO_WRAP)
        p("claimed for       : ${cb.userId}|${cb.deviceId}")
        p("KeyPackage bytes  : ${kp.size}")
        p("KeyPackage sha256 : ${sha256(kp)}")

        val c = repo.ensureClient()
        if (c == null) { p("ABORT: no client"); return@runBlocking }
        val sig = c.signatureKeyOf(kp)
        if (sig == null) { p("ABORT: no readable signature key"); return@runBlocking }
        p("KeyPackage sigkey : ${sig.hex()}")
        if (sig.hex() != S_SIGKEY) {
            p("ABORT: signature key does NOT match Samsung's former leaf ($S_SIGKEY)")
            return@runBlocking
        }
        if (c.memberSignatureKeys(gid).map { it.toList() }.contains(sig.toList())) {
            p("ABORT: signature key already in the group"); return@runBlocking
        }
        p("GATE 3 claim is Samsung, sigkey matches former leaf, absent from roster : OK")

        // ---- stage
        val staged = repo.stageAdd(CHAT, listOf(kp))
        if (staged == null) { p("ABORT: stageAdd null"); return@runBlocking }
        p("staged commit bytes  : ${staged.commit.size}")
        p("staged Welcome bytes : ${staged.welcome.size}")

        // ---- GATE 4: re-check server epoch IMMEDIATELY before submitting
        val g2 = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        if (g2?.isSuccessful != true || g2.body()?.epoch != EXPECTED_EPOCH) {
            p("ABORT: server epoch moved to ${g2?.body()?.epoch} - discarding staged Add")
            repo.onCommitRejected(CHAT)
            return@runBlocking
        }
        p("GATE 4 server still at epoch 9 immediately pre-submit : OK")

        // ---- SUBMIT (irreversible)
        p(">>> SUBMITTING expected_epoch=$EXPECTED_EPOCH welcomes=1(Samsung) <<<")
        val resp = runCatching {
            api.submitMlsCommit(tok(), CHAT, MlsCommitRequest(
                expectedEpoch = EXPECTED_EPOCH,
                commitB64 = b64(staged.commit),
                senderDeviceId = meDev,
                welcomes = listOf(MlsWelcomeItem(
                    userId = S_USER, deviceId = S_DEV, welcomeB64 = b64(staged.welcome)))
            ))
        }.getOrNull()

        if (resp?.isSuccessful != true) {
            p("SUBMIT FAILED http=${resp?.code()}")
            repo.onCommitRejected(CHAT)
            p("staged Add discarded; local epoch = ${repo.epoch(CHAT)}")
            p("RESULT: SUBMIT-REJECTED")
            return@runBlocking
        }
        p("HTTP ${resp.code()} OK; server reports epoch = ${resp.body()?.epoch}")

        // ---- merge + persist
        val newLocal = repo.onCommitAccepted(CHAT)
        p("local epoch AFTER merge+persist : $newLocal")

        val rosterAfter = repo.roster(CHAT)
        p("roster AFTER (${rosterAfter.size}):")
        rosterAfter.forEach { p("    $it") }
        val added = rosterAfter.filter { it !in rosterBefore }
        val removed = rosterBefore.filter { it !in rosterAfter }
        p("added   : $added")
        p("removed : $removed")
        p("RESULT: ADD-SUBMITTED-AND-MERGED")

        // generous flush window so the epoch-10 snapshot reaches disk
        delay(4000)
        p("flush window elapsed; durable epoch should be $newLocal")
    }
}
