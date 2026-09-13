package com.messenger.app.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.MlsCore
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.MlsOwner
import com.messenger.app.security.TokenManager
import com.messenger.app.security.TokenManagerImpl
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 26.2 - the MLS v2 whole-store snapshot belongs to one (account, device).
 *
 * The snapshot is an image of the ENTIRE MLS store, rewritten after every
 * state-changing call, and it lived under a single constant key. Two accounts on
 * one device therefore overwrote and could restore each other's - every group and
 * every leaf key at once. Gate 26.1 stopped on exactly this: v2 shared the
 * account-less path v1 was being repaired away from.
 *
 * This drives the REAL MlsV2Repository against the REAL TokenManagerImpl, so the
 * assertions cover v2's own persistence call sites rather than the storage API
 * beneath them. That needs libmls_core.so, which mls-core/build-android.sh
 * produces into app/src/main/jniLibs; without it the repository cannot build a
 * client and never reaches a snapshot write at all.
 */
@RunWith(AndroidJUnit4::class)
class MlsV2SnapshotOwnershipTest {

    private companion object {
        const val A = "v2aaaa-0000-0000-0000-00000000aaaa"
        const val B = "v2bbbb-0000-0000-0000-00000000bbbb"
        const val DEV = "device-one"
        const val DEV_2 = "device-two"
        const val SNAPSHOT_KEY = "mls2_snapshot"
    }

    private class IdentityOverride(
        private val real: TokenManagerImpl,
        @Volatile var account: String?,
        @Volatile var device: String?
    ) : TokenManager by real {
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
        override suspend fun getOrCreateDeviceId(): Result<String> =
            Result.success(device.orEmpty())
        override suspend fun getAccessToken(): Result<String?> = Result.success("token")
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var real: TokenManagerImpl
    private lateinit var auth: IdentityOverride
    private lateinit var mls: MlsV2Repository

    @Before
    fun setUp() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        real = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        auth = IdentityOverride(real, A, DEV)
        mls = MlsV2Repository(mockk<ChatApiService>(relaxed = true), auth)
    }

    private fun become(account: String, device: String) {
        auth.account = account
        auth.device = device
    }

    /** Reads a snapshot the way production does: through the owner-scoped API. */
    private suspend fun snapshotOf(owner: MlsOwner): String? =
        real.loadMlsBundle(owner, SNAPSHOT_KEY).getOrNull()

    /** Builds the client and persists the store, which is what writes a snapshot. */
    private suspend fun establishStateAs(account: String, device: String) {
        become(account, device)
        mls.keyPackage()
    }

    @Test
    fun oneOwnersSnapshotIsNeitherReadableNorOverwritableByAnother() = runBlocking {
        assumeTrue(
            "libmls_core.so absent - build it with mls-core/build-android.sh: " +
                MlsCore.lastError,
            MlsCore.isAvailable
        )

        establishStateAs(A, DEV)
        val aSnapshot = snapshotOf(MlsOwner(A, DEV))
        assumeTrue("MLS v2 persisted no snapshot for A; nothing to isolate", aSnapshot != null)

        // Account B signs in on the same device and builds its own MLS store.
        establishStateAs(B, DEV)
        val bSnapshot = snapshotOf(MlsOwner(B, DEV))

        assertTrue("precondition: B established a snapshot of its own", bSnapshot != null)
        assertNotEquals(
            "PROVEN BROKEN: account B's whole-store snapshot is account A's. That " +
                "is every group and every leaf private key at once.",
            aSnapshot, bSnapshot
        )

        // A's own image survives B's arrival, byte for byte.
        assertEquals(
            "PROVEN BROKEN: account B's MLS v2 persistence overwrote account A's " +
                "whole-store snapshot - A loses every group it belonged to.",
            aSnapshot, snapshotOf(MlsOwner(A, DEV))
        )

        // ...and neither can address the other's slot, by load or by listing.
        val bList = real.listMlsBundles(MlsOwner(B, DEV), SNAPSHOT_KEY).getOrDefault(emptyMap())
        assertTrue(
            "PROVEN BROKEN: account B's listing surfaced account A's snapshot ($bList)",
            bList.values.none { it == aSnapshot }
        )
        assertNull(
            "PROVEN BROKEN: the v2 snapshot was written to the legacy account-less key",
            ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
                .getString("mls1_mls2_snapshot", null)
        )
    }

    @Test
    fun theSameAccountOnAnotherDeviceGetsItsOwnSnapshot() = runBlocking {
        assumeTrue("libmls_core.so absent: " + MlsCore.lastError, MlsCore.isAvailable)

        establishStateAs(A, DEV)
        val deviceOne = snapshotOf(MlsOwner(A, DEV))
        assumeTrue("no snapshot for device one", deviceOne != null)

        establishStateAs(A, DEV_2)

        assertNotEquals(
            "PROVEN BROKEN: the same account on a second device shares one MLS " +
                "store image. Each device is its own leaf with its own signature " +
                "key, so one image cannot describe both.",
            deviceOne, snapshotOf(MlsOwner(A, DEV_2))
        )
        assertEquals(
            "device one's snapshot must survive device two writing its own",
            deviceOne, snapshotOf(MlsOwner(A, DEV))
        )
    }
}
