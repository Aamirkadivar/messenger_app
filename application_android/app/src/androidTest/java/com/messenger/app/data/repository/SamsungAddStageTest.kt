package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.model.MlsClaimKeyPackageRequest
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
 * PHASE R2/ADD - PRE-FLIGHT, SYNC AND STAGING ONLY. Adder: bd63c60a.
 *
 * Uses the production TokenRefreshAuthenticator so an expired access token is
 * renewed exactly as the app would, instead of failing with 401.
 *
 * DOES NOT call submitMlsCommit. Does not launch MainActivity, does not call
 * inviteMissingDevices or evictPhantomMembers, does not touch Samsung.
 */
class SamsungAddStageTest {

    private companion object {
        const val TAG = "R2ADD"
        const val CHAT = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
        const val EXPECTED_GID =
            "ad4089af76b3b6a8c47452a774bb0e5851ad2872cdb2c6d5bc70ed7e93968d85"
        const val S_USER = "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2"
        const val S_DEV = "4038fd60-85e0-44a8-ac8c-f3a3f770d9f2"
        const val S_CRED = "$S_USER|$S_DEV"
        const val EXPECTED_EPOCH = 9L
        const val CIPHER_SUITE = 1
        const val ADDER_DEV = "bd63c60a-fa1e-4689-a1b3-719139fb53f7"
    }

    private fun p(msg: String) = Log.i(TAG, msg)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).hex()

    @Test
    fun preflightSyncClaimAndStageAdd() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val ct = "application/json".toMediaType()

        // Bare client for the refresh endpoint itself (breaks the dependency cycle)
        val authApi = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory(ct))
            .build()
            .create(AuthApiService::class.java)

        val client = OkHttpClient.Builder()
            .authenticator(TokenRefreshAuthenticator(tm) { authApi })
            .build()

        val api: ChatApiService = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(ct))
            .build()
            .create(ChatApiService::class.java)

        val repo = MlsV2Repository(api, tm)
        suspend fun tok() = "Bearer " + tm.getAccessToken().getOrNull().orEmpty()

        p("=== R2/ADD pre-flight + sync + staging (NO SUBMIT) ===")

        val meDev = tm.getOrCreateDeviceId().getOrNull().orEmpty()
        p("this device : $meDev")
        if (meDev != ADDER_DEV) {
            p("ABORT: wrong device. This phase is authorized for $ADDER_DEV only")
            return@runBlocking
        }
        p("GATE adder identity : OK")

        // ---- 1. read-only server pre-flight
        val g = runCatching { api.getMlsGroup(tok(), CHAT) }.getOrNull()
        if (g == null || !g.isSuccessful || g.body() == null) {
            p("ABORT: getMlsGroup failed (${g?.code()})"); return@runBlocking
        }
        val dto = g.body()!!
        val serverGid = Base64.decode(dto.groupIdB64, Base64.NO_WRAP)
        p("server epoch BEFORE : ${dto.epoch}")
        p("server GID          : ${serverGid.hex()}")
        if (dto.epoch != EXPECTED_EPOCH) { p("ABORT: server epoch != 9"); return@runBlocking }
        if (serverGid.hex() != EXPECTED_GID) { p("ABORT: GID mismatch"); return@runBlocking }

        val cov = runCatching { api.getMlsCoverage(tok(), CHAT).body() }.getOrNull()
        if (cov == null) { p("ABORT: coverage unreadable"); return@runBlocking }
        p("coverage pending    : ${cov.pendingDeviceIds}")
        if (!cov.pendingDeviceIds.contains(S_DEV)) {
            p("ABORT: Samsung is not classified pending"); return@runBlocking
        }
        p("GATE server epoch 9 + GID + Samsung pending : OK")

        // ---- 2. sync the adder from epoch 8 to epoch 9
        if (!repo.hasGroup(CHAT)) { p("ABORT: hasGroup false"); return@runBlocking }
        val localBefore = repo.epoch(CHAT)
        p("adder local epoch BEFORE sync : $localBefore")
        if (localBefore != 8L && localBefore != EXPECTED_EPOCH) {
            p("ABORT: unexpected local epoch $localBefore"); return@runBlocking
        }
        if (localBefore != EXPECTED_EPOCH) repo.syncHandshakes(CHAT)
        val localAfter = repo.epoch(CHAT)
        p("adder local epoch AFTER sync  : $localAfter")
        if (localAfter != EXPECTED_EPOCH) { p("ABORT: local epoch != 9 after sync"); return@runBlocking }

        val gid = Base64.decode(tm.loadMlsBundle("mls2_gid_$CHAT").getOrNull(), Base64.NO_WRAP)

        // ---- 3. Samsung must be absent from the local epoch-9 roster
        val rosterBefore = repo.roster(CHAT)
        p("roster @9 (${rosterBefore.size}):")
        rosterBefore.forEach { p("    $it") }
        if (rosterBefore.contains(S_CRED)) { p("ABORT: Samsung still a leaf"); return@runBlocking }
        p("GATE Samsung absent from epoch-9 roster : OK")

        // ---- 4. claim exactly ONE KeyPackage for Samsung
        val claim = runCatching {
            api.claimMlsKeyPackage(
                tok(),
                MlsClaimKeyPackageRequest(userId = S_USER, deviceId = S_DEV, cipherSuite = CIPHER_SUITE)
            )
        }.getOrNull()
        if (claim == null || !claim.isSuccessful || claim.body() == null) {
            p("ABORT: claim failed (${claim?.code()})"); return@runBlocking
        }
        val cb = claim.body()!!
        p("claim user_id   : ${cb.userId}")
        p("claim device_id : ${cb.deviceId}")
        p("claim suite     : ${cb.cipherSuite}")
        if (cb.userId != S_USER || cb.deviceId != S_DEV) {
            p("ABORT: claim is not for Samsung"); return@runBlocking
        }
        val kp = Base64.decode(cb.keyPackageB64, Base64.NO_WRAP)
        p("KeyPackage bytes: ${kp.size}")
        p("KeyPackage sha256 (server key_package_hash): ${sha256(kp)}")
        p("GATE claim belongs to exact Samsung user/device : OK")

        // ---- 5. vet the KeyPackage
        val c = repo.ensureClient()
        if (c == null) { p("ABORT: no client"); return@runBlocking }
        val sigKey = c.signatureKeyOf(kp)
        if (sigKey == null) { p("ABORT: KeyPackage has no readable signature key"); return@runBlocking }
        p("KeyPackage signature key : ${sigKey.hex()}")
        if (c.memberSignatureKeys(gid).map { it.toList() }.contains(sigKey.toList())) {
            p("ABORT: that signature key is ALREADY in the group"); return@runBlocking
        }
        p("GATE signature key valid and not already a member : OK")

        // ---- 6. stage the Add (ONE KeyPackage, NOT submitted)
        val staged = repo.stageAdd(CHAT, listOf(kp))
        if (staged == null) { p("ABORT: stageAdd returned null"); return@runBlocking }
        p("staged commit bytes  : ${staged.commit.size}")
        p("staged Welcome bytes : ${staged.welcome.size}")
        p("Welcome recipient    : $S_CRED (single KeyPackage staged)")

        p("expected epoch for submission : ${repo.epoch(CHAT)}")

        val rosterAfterStage = repo.roster(CHAT)
        p("roster AFTER STAGE (${rosterAfterStage.size}) - unchanged until merge: " +
            "${rosterAfterStage == rosterBefore}")
        p("intended delta on merge : +$S_CRED")
        p("credentials removed     : none")
        p("other devices targeted  : none (1 KeyPackage staged, 1 claim made)")

        p("NO submitMlsCommit CALLED")
        p("RESULT: ADD-STAGED-NOT-SUBMITTED")

        delay(2500)
        p("flush window elapsed")
    }
}
