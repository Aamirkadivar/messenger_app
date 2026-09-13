package com.messenger.app.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.model.MlsGroupDto
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.MlsOwner
import com.messenger.app.security.TokenManager
import com.messenger.app.security.TokenManagerImpl
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * GATE 26 - MLS identity and group state must be owned by (account, device).
 *
 * Gate 26 proved these RED: MlsRepository's maps were keyed by chat id or by
 * KeyPackage hash, nothing cleared them, and AuthRepository never reached them -
 * so after a logout account B observed the group and epoch account A had built,
 * and would have committed to it under account A's credential.
 *
 * Two accounts on one phone can legitimately be in the same group and still need
 * entirely separate leaf and signature keys, so a shared chat id is exactly the
 * case that must stay isolated - not an excuse for sharing.
 *
 * Storage here is the REAL TokenManagerImpl, so the namespace under test is
 * production's. Only the identity is overridden, which is what lets one test act
 * as two accounts, or as one account on two devices.
 */
@RunWith(AndroidJUnit4::class)
class MlsAccountIsolationTest {

    private companion object {
        const val A = "aaaa9999-0000-0000-0000-00000000a999"
        const val B = "bbbb8888-0000-0000-0000-00000000b888"
        const val DEV_1 = "device-one"
        const val DEV_2 = "device-two"
        const val GROUP = "group-both-accounts-belong-to"
    }

    /**
     * Real storage, controllable identity.
     *
     * Delegating to TokenManagerImpl keeps the durable behaviour honest: the
     * keys, the Keystore wrapping and the mls2_ namespace are production's.
     */
    private class IdentityOverride(
        private val real: TokenManagerImpl,
        @Volatile var account: String?,
        @Volatile var device: String?
    ) : TokenManager by real {
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
        override suspend fun getOrCreateDeviceId(): Result<String> =
            Result.success(device.orEmpty())
        @Volatile var gateAccessToken = false
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun getAccessToken(): Result<String?> {
            if (gateAccessToken) {
                gateAccessToken = false
                entered.complete(Unit)
                release.await()
            }
            return Result.success("token")
        }
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var real: TokenManagerImpl
    private lateinit var auth: IdentityOverride
    private lateinit var api: ChatApiService
    private lateinit var mls: MlsRepository

    @Before
    fun setUp() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        real = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        auth = IdentityOverride(real, A, DEV_1)
        api = mockk(relaxed = true)
        // createGroup rolls its local state back if the server rejects it, so the
        // server has to accept for an account to establish anything at all.
        coEvery { api.createMlsGroup(any(), any()) } answers {
            Response.success(MlsGroupDto(chatId = GROUP, groupIdB64 = "", cipherSuite = 1))
        }
        mls = newRepository()
    }

    private fun newRepository() = MlsRepository(api, auth)

    private fun becomeOwner(account: String, device: String) {
        auth.account = account
        auth.device = device
    }

    // ---------------- R1: basic group isolation ----------------

    @Test
    fun accountBMustNotObserveAccountAsMlsGroup() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }
        assertTrue("precondition: A established an MLS group", mls.hasGroup(GROUP))

        becomeOwner(B, DEV_1)

        assertFalse(
            "PROVEN BROKEN: account B sees account A's MLS group. B would be " +
                "routed into a group whose leaf and signature keys belong to A.",
            mls.hasGroup(GROUP)
        )
    }

    @Test
    fun accountBMustNotInheritAccountAsGroupEpoch() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }
        assertTrue("precondition: A's group has an epoch", mls.epochOf(GROUP) >= 0L)

        becomeOwner(B, DEV_1)

        assertEquals(
            "PROVEN BROKEN: account B inherited account A's MLS epoch. Committing " +
                "from that state advances A's ratchet tree under A's credential.",
            -1L, mls.epochOf(GROUP)
        )
    }

    // ---------------- R2: same chatId, different accounts ----------------

    @Test
    fun twoAccountsUsingTheSameChatIdKeepDistinctMlsState() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }
        val aEpoch = mls.epochOf(GROUP)

        becomeOwner(B, DEV_1)
        assertFalse("B must start with nothing for this chat", mls.hasGroup(GROUP))
        runCatching { mls.createGroup(GROUP) }
        assertTrue("B established its own group for the same chat", mls.hasGroup(GROUP))

        becomeOwner(A, DEV_1)
        assertTrue("A still has its own group", mls.hasGroup(GROUP))
        assertEquals(
            "PROVEN BROKEN: account B's group replaced account A's for the same chat id",
            aEpoch, mls.epochOf(GROUP)
        )
    }

    // ---------------- R3: same account, different devices ----------------

    @Test
    fun oneAccountOnTwoDevicesKeepsDistinctMlsIdentity() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }
        assertTrue("precondition: device 1 established a group", mls.hasGroup(GROUP))

        becomeOwner(A, DEV_2)

        assertFalse(
            "PROVEN BROKEN: the same account on a SECOND device inherited the " +
                "first device's MLS group. Each device is a separate leaf and " +
                "needs its own signature key; sharing one makes two devices " +
                "present a single MLS identity.",
            mls.hasGroup(GROUP)
        )
    }

    // ---------------- R6/R7: durable restart + round trip ----------------

    @Test
    fun aFreshRepositoryReconstructsOnlyTheOwnersState() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }

        // A brand-new repository over the same durable store: the closest this
        // harness gets to a process restart.
        val fresh = newRepository()
        becomeOwner(B, DEV_1)
        runCatching { fresh.restoreGroups() }
        assertFalse(
            "PROVEN BROKEN: a cold repository handed account B account A's MLS group",
            fresh.hasGroup(GROUP)
        )

        becomeOwner(A, DEV_1)
        runCatching { fresh.restoreGroups() }
        becomeOwner(B, DEV_1)
        assertFalse(
            "PROVEN BROKEN: account B observed state restored for account A",
            fresh.hasGroup(GROUP)
        )
    }

    // ---------------- R8: logout ordering must not matter ----------------

    @Test
    fun isolationHoldsWithoutAnyLogoutOrReset() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }
        assertTrue(mls.hasGroup(GROUP))

        // No logout, no clearTokens, no reset hook: ownership alone must hold.
        becomeOwner(B, DEV_1)

        assertFalse(
            "PROVEN BROKEN: isolation depended on a logout running first. The " +
                "namespace is the boundary; a reset hook is only hygiene.",
            mls.hasGroup(GROUP)
        )
    }

    // ---------------- R10: legacy quarantine ----------------

    @Test
    fun legacyAccountlessMlsStateIsNeverAdopted() = runBlocking {
        // Exactly the shapes a pre-Gate-26 install already holds.
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE).edit()
            .putString("mls1_group/$GROUP", "legacy-group-bundle")
            .putString("mls1_kp/deadbeef", "legacy-keypackage")
            .putString("mls1_invited/$GROUP", "legacy-invited")
            .putString("mls1_mls2_snapshot", "legacy-v2-snapshot")
            .putString("mls1_mls2_gid_$GROUP", "legacy-gid")
            .commit()

        for ((account, device) in listOf(A to DEV_1, B to DEV_1, A to DEV_2, A to DEV_1)) {
            becomeOwner(account, device)
            val fresh = newRepository()
            runCatching { fresh.restoreGroups() }
            assertFalse(
                "PROVEN BROKEN: $account/$device adopted legacy account-less MLS " +
                    "state. Its owner cannot be established, and login order is " +
                    "not provenance.",
                fresh.hasGroup(GROUP)
            )
        }

        // ...and the legacy entries are left exactly as found, not migrated.
        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        assertEquals(
            "legacy state must be quarantined, not consumed",
            "legacy-group-bundle", prefs.getString("mls1_group/$GROUP", null)
        )
    }

    /**
     * A stale continuation must persist under the owner it STARTED as.
     *
     * createGroup captures its owner, then suspends on the access token before
     * it writes the group bundle. Re-resolving "who is signed in" at that write
     * would file account A's leaf and Welcome under account B - the Gate 22.1
     * rule, applied to MLS.
     */
    @Test
    fun aStaleGroupBundleIsPersistedUnderTheOwnerItStartedAs() = runBlocking {
        becomeOwner(A, DEV_1)
        auth.gateAccessToken = true
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { mls.createGroup(GROUP) }
        }
        kotlinx.coroutines.withTimeout(15_000) { auth.entered.await() }

        // The account changes while the operation is suspended.
        becomeOwner(B, DEV_1)
        auth.release.complete(Unit)
        job.join()

        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        val tagA = MlsOwner(A, DEV_1).tag
        val tagB = MlsOwner(B, DEV_1).tag
        val keys = prefs.all.keys.filter { it.startsWith("mls2_") }
        assertTrue(
            "PROVEN BROKEN: account A's MLS group bundle was filed under account " +
                "B because B was signed in when the write landed (keys: $keys)",
            keys.none { it.startsWith("mls2_${tagB}_") }
        )
        assertTrue(
            "the bundle must still reach its own owner's namespace (keys: $keys)",
            keys.any { it.startsWith("mls2_${tagA}_") }
        )
    }

    /** No new MLS write may land outside the owner namespace. */
    @Test
    fun everyMlsWriteLandsInTheOwnerNamespace() = runBlocking {
        becomeOwner(A, DEV_1)
        runCatching { mls.createGroup(GROUP) }

        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        val stray = prefs.all.keys.filter { it.startsWith("mls1_") }
        assertTrue(
            "PROVEN BROKEN: MLS state was written to the legacy account-less " +
                "namespace: $stray",
            stray.isEmpty()
        )
        val owned = prefs.all.keys.filter { it.startsWith("mls2_") }
        assertTrue("expected owner-scoped MLS keys to have been written", owned.isNotEmpty())
        val tag = MlsOwner(A, DEV_1).tag
        assertTrue(
            "MLS keys must carry this owner's tag: $owned",
            owned.all { it.startsWith("mls2_${tag}_") }
        )
    }
}
