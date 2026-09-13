package com.messenger.app.security

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The MLS snapshot read path.
 *
 * This is where the group-loss bug lived. `loadMlsBundle` returned
 * `unwrapSecret(stored) ?: stored`, so a failed Keystore unwrap handed back the
 * still-wrapped ciphertext as though it were plaintext. MLS restore then failed
 * its magic check, the caller concluded the device had no MLS state, and the
 * next persist replaced a complete whole-store snapshot with an empty one -
 * destroying every group the device belonged to, irrecoverably.
 *
 * The invariant these tests pin: "stored but unreadable" must never be
 * reported as "absent", and the stored blob must survive the attempt.
 */
class MlsBundleStorageTest {

    private companion object {
        const val OWNER = "mls-bundle-storage-test-account"
        val MLS_OWNER = MlsOwner(OWNER, "test-device")
        val OWNER_A = MlsOwner("account-a", "test-device")
        val OWNER_B = MlsOwner("account-b", "test-device")
        const val KEY = "mls2_snapshot"
        /** The owner-scoped slot. The pre-ownership name was "mls1_mls2_snapshot". */
        val PREF = "mls2_" + MLS_OWNER.tag + "_" + KEY
        const val LEGACY_PREF = "mls1_mls2_snapshot"
        const val WRAP_PREFIX = "ks1:"
        const val SNAPSHOT = "MLS1-base64-snapshot-payload"
    }

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var keyStore: FakeKeyStoreManager
    private lateinit var tokenManager: TokenManagerImpl

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        keyStore = FakeKeyStoreManager()
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        tokenManager = TokenManagerImpl(context, keyStore)
    }

    @Test
    fun `absent bundle reads as null`() = runBlocking {
        val result = tokenManager.loadMlsBundle(MLS_OWNER, KEY)

        assertTrue("absence is not an error", result.isSuccess)
        assertNull(result.getOrNull())
        assertFalse(tokenManager.hasMlsBundle(MLS_OWNER, KEY).getOrThrow())
    }

    @Test
    fun `stored bundle round-trips`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT)

        assertEquals(SNAPSHOT, tokenManager.loadMlsBundle(MLS_OWNER, KEY).getOrNull())
        assertTrue(tokenManager.hasMlsBundle(MLS_OWNER, KEY).getOrThrow())
    }

    @Test
    fun `stored bundle is written wrapped, not in the clear`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT)

        val raw = prefs.getString(PREF, null)
        assertTrue("must be Keystore-wrapped", raw!!.startsWith(WRAP_PREFIX))
        assertFalse("the snapshot must not sit in the clear", raw.contains(SNAPSHOT))
    }

    /** The core regression. */
    @Test
    fun `unreadable bundle fails instead of reporting absence`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT)
        keyStore.failDecrypt = true

        val result = tokenManager.loadMlsBundle(MLS_OWNER, KEY)

        assertTrue(
            "a stored-but-unreadable bundle must be a failure - reporting absence " +
                "is what let an empty client overwrite real MLS state",
            result.isFailure
        )
    }

    @Test
    fun `unreadable bundle never returns the ciphertext as plaintext`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT)
        keyStore.failDecrypt = true

        val value = tokenManager.loadMlsBundle(MLS_OWNER, KEY).getOrNull()

        assertNull("no value may be produced when the unwrap failed", value)
        val stored = prefs.getString(PREF, null)!!
        assertFalse(
            "the wrapped blob must never be handed back as though it were plaintext",
            value == stored
        )
    }

    @Test
    fun `failed read leaves the stored bundle intact`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT)
        val before = prefs.getString(PREF, null)

        keyStore.failDecrypt = true
        tokenManager.loadMlsBundle(MLS_OWNER, KEY)

        assertEquals("recovery depends on the blob surviving", before, prefs.getString(PREF, null))
        keyStore.failDecrypt = false
        assertEquals("and on it still being readable once the Keystore recovers",
            SNAPSHOT, tokenManager.loadMlsBundle(MLS_OWNER, KEY).getOrNull())
    }

    @Test
    fun `presence is reported even when the bundle cannot be unwrapped`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT)
        keyStore.failDecrypt = true

        assertTrue(
            "presence must not depend on readability - that is how a caller tells " +
                "'no state yet' from 'state exists but is unreadable'",
            tokenManager.hasMlsBundle(MLS_OWNER, KEY).getOrThrow()
        )
    }

    @Test
    fun `legacy account-less bundle is quarantined, never adopted`() = runBlocking {
        // Written before MLS state had an owner: keyed "mls1_", no account and no
        // device. Its owner cannot be established now, and login order is not
        // provenance - so no owner may claim it, and it must be left as found.
        prefs.seed(LEGACY_PREF, SNAPSHOT)

        for (owner in listOf(MLS_OWNER, OWNER_A, OWNER_B)) {
            val result = tokenManager.loadMlsBundle(owner, KEY)
            assertTrue("absence is not an error", result.isSuccess)
            assertNull(
                "PROVEN BROKEN: ${owner.accountId} adopted legacy account-less MLS state",
                result.getOrNull()
            )
            assertFalse(tokenManager.hasMlsBundle(owner, KEY).getOrThrow())
        }
        assertEquals(
            "the legacy blob must survive untouched, not be migrated",
            SNAPSHOT, prefs.getString(LEGACY_PREF, null)
        )
    }
    // ---------------------------------------------- account-switch lifecycle
    //
    // clearTokens used to remove only the auth keys, leaving mls1_* in place. A
    // second account signing in on the same install then restored the FIRST
    // account's MLS store and published KeyPackages carrying its credential and
    // signature key. Two accounts, one MLS identity - which MLS refuses to admit
    // to the same group, and which broke a live JOIN.

    @Test
    fun `logout keeps an owner's MLS state and still hides it from everyone else`() =
        runBlocking {
            tokenManager.saveMlsBundle(OWNER_A, KEY, SNAPSHOT).getOrThrow()
            tokenManager.saveMlsBundle(OWNER_A, "mls2_gid_chat-1", "gid-bytes").getOrThrow()
            assertTrue(tokenManager.hasMlsBundle(OWNER_A, KEY).getOrThrow())

            tokenManager.clearTokens().getOrThrow()

            // The namespace is the boundary, so the wipe is no longer what keeps
            // accounts apart - and deleting A's store on any logout would cost A
            // its groups for no security gain.
            assertEquals(
                "an owner's own MLS state must survive its logout",
                SNAPSHOT, tokenManager.loadMlsBundle(OWNER_A, KEY).getOrThrow()
            )
            assertFalse(
                "PROVEN BROKEN: another owner can see it",
                tokenManager.hasMlsBundle(OWNER_B, KEY).getOrThrow()
            )
            assertTrue(
                "the legacy account-less prefix is still cleared",
                prefs.all.keys.none { it.startsWith("mls1_") }
            )
        }

    @Test
    fun `logout clears the auth session`() = runBlocking {
        tokenManager.saveCurrentUserId("account-a").getOrThrow()
        tokenManager.saveAccessToken("token-a").getOrThrow()

        tokenManager.clearTokens().getOrThrow()

        assertNull(tokenManager.getCurrentUserId().getOrThrow())
        assertNull(tokenManager.getAccessToken().getOrThrow())
    }

    @Test
    fun `logout leaves unrelated application data alone`() = runBlocking {
        tokenManager.saveMlsBundle(MLS_OWNER, KEY, SNAPSHOT).getOrThrow()
        tokenManager.saveGroupSenderKey(OWNER, "chat-1", "3:deadbeef").getOrThrow()
        tokenManager.saveKnownPublicKey(OWNER, "chat-1", "cafebabe").getOrThrow()

        tokenManager.clearTokens().getOrThrow()

        assertEquals(
            "sender keys are not MLS state and must survive",
            "3:deadbeef", tokenManager.getGroupSenderKey(OWNER, "chat-1").getOrThrow()
        )
        assertEquals(
            "cached peer keys must survive",
            "cafebabe", tokenManager.getKnownPublicKey(OWNER, "chat-1").getOrThrow()
        )
    }

    @Test
    fun `a second account cannot restore the first account's MLS store`() = runBlocking {
        // Account A signs in and builds MLS state.
        tokenManager.saveCurrentUserId("account-a").getOrThrow()
        tokenManager.saveMlsBundle(OWNER_A, KEY, SNAPSHOT).getOrThrow()

        // A signs out; B signs in on the same install.
        tokenManager.clearTokens().getOrThrow()
        tokenManager.saveCurrentUserId("account-b").getOrThrow()

        assertFalse(
            "B must not find A's snapshot; restoring it is how B came to publish " +
                "packages under A's MLS identity",
            tokenManager.hasMlsBundle(OWNER_B, KEY).getOrThrow()
        )
        assertNull(tokenManager.loadMlsBundle(OWNER_B, KEY).getOrThrow())
        assertEquals(
            "...and A's own store is still A's",
            SNAPSHOT, tokenManager.loadMlsBundle(OWNER_A, KEY).getOrThrow()
        )
        assertEquals("account-b", tokenManager.getCurrentUserId().getOrThrow())
    }

    @Test
    fun `switching A to B and back gives each account only its own MLS store`() =
        runBlocking {
            tokenManager.saveCurrentUserId("account-a").getOrThrow()
            tokenManager.saveMlsBundle(OWNER_A, KEY, "$SNAPSHOT-a").getOrThrow()
            tokenManager.clearTokens().getOrThrow()

            tokenManager.saveCurrentUserId("account-b").getOrThrow()
            assertFalse(
                "B must start with no inherited MLS store",
                tokenManager.hasMlsBundle(OWNER_B, KEY).getOrThrow()
            )
            tokenManager.saveMlsBundle(OWNER_B, KEY, "$SNAPSHOT-b").getOrThrow()
            tokenManager.clearTokens().getOrThrow()

            // Back to A. Its own store is intact, and B's is untouched - isolation
            // that works by destroying the outgoing account's data is not isolation.
            tokenManager.saveCurrentUserId("account-a").getOrThrow()
            assertEquals(
                "$SNAPSHOT-a", tokenManager.loadMlsBundle(OWNER_A, KEY).getOrThrow()
            )
            assertEquals(
                "$SNAPSHOT-b", tokenManager.loadMlsBundle(OWNER_B, KEY).getOrThrow()
            )
            assertTrue(prefs.all.keys.none { it.startsWith("mls1_") })
        }

}

/** In-memory KeyStoreManager; wrapping is a reversible prefix. */
private class FakeKeyStoreManager : KeyStoreManager {
    var failDecrypt = false
    var failEncrypt = false
    private val aliases = mutableSetOf<String>()

    override suspend fun generateEncryptionKey(alias: String): Result<Unit> {
        aliases.add(alias); return Result.success(Unit)
    }

    override suspend fun generateAuthKey(): Result<Unit> = Result.success(Unit)

    // Reversible, but deliberately not plaintext-preserving: a fake that embedded
    // the input verbatim would pass a "not stored in the clear" assertion for the
    // wrong reason.
    override suspend fun encryptData(plaintext: String, alias: String): Result<String> {
        if (failEncrypt) return Result.failure(IllegalStateException("keystore unavailable"))
        aliases.add(alias)
        return Result.success("enc(" + plaintext.reversed() + ")")
    }

    override suspend fun decryptData(encryptedData: String, alias: String): Result<String> {
        if (failDecrypt) return Result.failure(IllegalStateException("key invalidated"))
        if (!encryptedData.startsWith("enc(") || !encryptedData.endsWith(")")) {
            return Result.failure(IllegalArgumentException("not wrapped by this fake"))
        }
        return Result.success(encryptedData.removePrefix("enc(").removeSuffix(")").reversed())
    }

    override suspend fun containsKey(alias: String): Result<Boolean> = Result.success(aliases.contains(alias))
    override suspend fun deleteKey(alias: String): Result<Unit> {
        aliases.remove(alias); return Result.success(Unit)
    }

    override fun getPublicKey(alias: String): java.security.PublicKey? = null
    override fun getPrivateKey(alias: String): java.security.PrivateKey? = null
}

/** Minimal in-memory SharedPreferences - more robust here than mocking the editor chain. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    fun seed(key: String, value: String) {
        values[key] = value
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    private class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { staged[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { removed.add(key) }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply(); return true
        }

        override fun apply() {
            if (clearAll) target.clear()
            removed.forEach { target.remove(it) }
            target.putAll(staged)
            staged.clear(); removed.clear(); clearAll = false
        }
    }
}
