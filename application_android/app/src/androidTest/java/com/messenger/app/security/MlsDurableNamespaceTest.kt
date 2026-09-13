package com.messenger.app.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 26 - the durable MLS namespace itself.
 *
 * MlsAccountIsolationTest drives MlsRepository, which reaches storage through
 * restoreGroups()/listMlsBundles. That leaves the single-key read path and the
 * listing's owner scoping untested, and a legacy fallback hiding in either would
 * go unnoticed - so these exercise the TokenManager MLS API directly.
 *
 * Real TokenManagerImpl, real prefs file.
 */
@RunWith(AndroidJUnit4::class)
class MlsDurableNamespaceTest {

    private companion object {
        val OWNER_A = MlsOwner("account-a", "device-1")
        val OWNER_B = MlsOwner("account-b", "device-1")
        val OWNER_A_DEV2 = MlsOwner("account-a", "device-2")
        const val KEY = "group/shared-chat"
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tm: TokenManagerImpl

    @Before
    fun setUp() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        tm = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    private fun plantLegacy() {
        ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE).edit()
            .putString("mls1_$KEY", "legacy-bundle")
            .putString("mls1_kp/deadbeef", "legacy-keypackage")
            .putString("mls1_mls2_snapshot", "legacy-v2-snapshot")
            .commit()
    }

    // ---- single-key read path ----

    @Test
    fun oneOwnerCannotLoadAnothersMlsBundle() = runBlocking {
        tm.saveMlsBundle(OWNER_A, KEY, "account A's group bundle")
        assertEquals(
            "precondition: A reads back its own bundle",
            "account A's group bundle", tm.loadMlsBundle(OWNER_A, KEY).getOrNull()
        )

        assertNull(
            "PROVEN BROKEN: account B loaded account A's MLS group bundle - the " +
                "identity and Welcome that rebuild A's leaf in that group.",
            tm.loadMlsBundle(OWNER_B, KEY).getOrNull()
        )
        assertNull(
            "PROVEN BROKEN: the same account on another device loaded device 1's " +
                "MLS bundle. Each device is its own leaf.",
            tm.loadMlsBundle(OWNER_A_DEV2, KEY).getOrNull()
        )
        assertFalse(
            "PROVEN BROKEN: hasMlsBundle answered for the wrong owner",
            tm.hasMlsBundle(OWNER_B, KEY).getOrDefault(false)
        )
    }

    /** M-M11 / M-M12: no fallback to, and no adoption of, the legacy key. */
    @Test
    fun anEmptySlotDoesNotFallBackToLegacyMlsState() = runBlocking {
        plantLegacy()

        for (owner in listOf(OWNER_A, OWNER_B, OWNER_A_DEV2, OWNER_A)) {
            assertNull(
                "PROVEN BROKEN: ${owner.accountId}/${owner.deviceId} read legacy " +
                    "account-less MLS state. Its owner cannot be established, and " +
                    "login order is not provenance.",
                tm.loadMlsBundle(owner, KEY).getOrNull()
            )
            assertFalse(
                "PROVEN BROKEN: hasMlsBundle reported the legacy entry",
                tm.hasMlsBundle(owner, KEY).getOrDefault(false)
            )
        }

        val prefs = ctx.getSharedPreferences("messenger_secure_prefs", Context.MODE_PRIVATE)
        assertEquals(
            "the legacy entry must be left exactly as found - quarantined, never migrated",
            "legacy-bundle", prefs.getString("mls1_$KEY", null)
        )
        assertTrue(
            "PROVEN BROKEN: reading created a scoped copy of unattributable legacy state",
            prefs.all.keys.none { it.startsWith("mls2_") }
        )
    }

    // ---- listing path ----

    /** M-M13: a listing must enumerate only the asking owner's bundles. */
    @Test
    fun listingIsScopedToTheAskingOwner() = runBlocking {
        tm.saveMlsBundle(OWNER_A, "group/one", "A-one")
        tm.saveMlsBundle(OWNER_A, "group/two", "A-two")
        tm.saveMlsBundle(OWNER_B, "group/three", "B-three")
        plantLegacy()

        val seenByA = tm.listMlsBundles(OWNER_A, "group/").getOrDefault(emptyMap())
        val seenByB = tm.listMlsBundles(OWNER_B, "group/").getOrDefault(emptyMap())

        assertEquals(
            "account A must see exactly its own two bundles (saw $seenByA)",
            setOf("group/one", "group/two"), seenByA.keys
        )
        assertEquals(
            "PROVEN BROKEN: account B's listing returned bundles belonging to " +
                "another owner (saw $seenByB)",
            setOf("group/three"), seenByB.keys
        )
        assertTrue(
            "PROVEN BROKEN: a listing surfaced legacy account-less MLS state",
            (seenByA.values + seenByB.values).none { it.contains("legacy") }
        )
    }

    @Test
    fun deletingOneOwnersBundleLeavesAnothersIntact() = runBlocking {
        tm.saveMlsBundle(OWNER_A, KEY, "A's bundle")
        tm.saveMlsBundle(OWNER_B, KEY, "B's bundle")

        tm.deleteMlsBundle(OWNER_A, KEY)

        assertNull("A's bundle should be gone", tm.loadMlsBundle(OWNER_A, KEY).getOrNull())
        assertEquals(
            "PROVEN BROKEN: deleting one owner's MLS bundle removed another's",
            "B's bundle", tm.loadMlsBundle(OWNER_B, KEY).getOrNull()
        )
    }

    /** The v2 whole-store snapshot is one image PER OWNER. */
    @Test
    fun theV2SnapshotIsPerOwner() = runBlocking {
        tm.saveMlsBundle(OWNER_A, "mls2_snapshot", "A's whole-store image")
        tm.saveMlsBundle(OWNER_B, "mls2_snapshot", "B's whole-store image")

        assertEquals(
            "PROVEN BROKEN: one account's MLS v2 snapshot overwrote another's. " +
                "The snapshot is a whole-store image, so sharing it hands over " +
                "every group and every leaf key at once.",
            "A's whole-store image", tm.loadMlsBundle(OWNER_A, "mls2_snapshot").getOrNull()
        )
        assertEquals(
            "B's snapshot must be its own",
            "B's whole-store image", tm.loadMlsBundle(OWNER_B, "mls2_snapshot").getOrNull()
        )
    }
}
