package com.messenger.app.ui.viewmodel

import com.messenger.app.security.MlsOwner
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The manual "Reset MLS Group" action.
 *
 * Recovery is destructive - it abandons the group's existing MLS tree and every
 * message sealed under it - so the action must do exactly what it says and
 * report honestly. These tests pin the wiring: the tap reaches the repository
 * with the right chat, and success and failure are both surfaced rather than
 * leaving the screen looking busy or silently pretending it worked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupInfoMlsRecoveryTest {

    private companion object {
        const val CHAT_ID = "28745790-8fcd-4c32-8fe5-0281644ce7bf"
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var chatRepository: ChatRepository
    private lateinit var viewModel: GroupInfoViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        chatRepository = mockk(relaxed = true)
        // A hand-written fake rather than a mock: mockk cannot stub suspend
        // functions returning Result, which is a value class - the stub hands
        // back a boxed Result where the caller expects the unboxed String.
        viewModel = GroupInfoViewModel(
            groupRepository = mockk(relaxed = true),
            avatarRepository = mockk(relaxed = true),
            chatRepository = chatRepository,
            mlsRepository = mockk(relaxed = true),
            tokenManager = FakeTokenManager(),
            // Real coordinator over in-memory ports: this test does not exercise rotation, but the
            // ViewModel now calls it after a membership change, so it must be a working instance
            // rather than a mock that silently swallows the call.
            historyRotation = run {
                val k = com.messenger.app.data.repository.HistoryKeyringRepository(
                    NoopKeyringVault(), NoopKeyringStore(),
                    com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }
                , HistoryArchiveFeature { true })
                com.messenger.app.data.repository.HistoryRotationCoordinator(
                    k, com.messenger.app.data.repository.RecoveryTestSupport.disabledLazy(k)
                , HistoryArchiveFeature { true })
            }
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // load() is deliberately not called: it pulls the whole group over the
    // network, and recovery takes the chat id directly from the screen.

    @Test
    fun `recovery reaches the repository for the open chat`() = runTest(dispatcher) {
        coEvery { chatRepository.recreateMlsV2Group(CHAT_ID) } returns true
        viewModel.recoverMlsGroup(CHAT_ID)
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.recreateMlsV2Group(CHAT_ID) }
    }

    @Test
    fun `success is reported to the user`() = runTest(dispatcher) {
        coEvery { chatRepository.recreateMlsV2Group(any()) } returns true
        viewModel.recoverMlsGroup(CHAT_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse("the screen must not stay busy", state.isBusy)
        assertNotNull("success must be visible, not silent", state.message)
        assertNull("success must not also raise an error", state.error)
    }

    @Test
    fun `success message warns that earlier messages stay unreadable`() = runTest(dispatcher) {
        coEvery { chatRepository.recreateMlsV2Group(any()) } returns true
        viewModel.recoverMlsGroup(CHAT_ID)
        advanceUntilIdle()

        val text = viewModel.state.value.message.orEmpty()
        assertTrue(
            "recovery discards readable history; the user must be told: $text",
            text.contains("unreadable", ignoreCase = true)
        )
    }

    @Test
    fun `failure is surfaced as an error, never as success`() = runTest(dispatcher) {
        coEvery { chatRepository.recreateMlsV2Group(any()) } returns false
        viewModel.recoverMlsGroup(CHAT_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isBusy)
        assertNotNull("a refused recovery must show why", state.error)
        assertNull("a failure must never be reported as success", state.message)
    }

    @Test
    fun `a thrown error is surfaced rather than crashing the screen`() = runTest(dispatcher) {
        coEvery { chatRepository.recreateMlsV2Group(any()) } throws IllegalStateException("boom")
        viewModel.recoverMlsGroup(CHAT_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isBusy)
        assertEquals("boom", state.error)
        assertNull(state.message)
    }

    @Test
    fun `recovery never runs unless it is asked for`() = runTest(dispatcher) {
        coEvery { chatRepository.recreateMlsV2Group(any()) } returns true

        // Constructing the ViewModel and letting it settle must not recreate a
        // group: the action is destructive, so it only ever happens on a tap.
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.recreateMlsV2Group(any()) }
    }
}

/**
 * Minimal TokenManager for the ViewModel under test. Only the values load()
 * reads matter; everything else returns an empty success.
 */
private class FakeTokenManager : TokenManager {
    override suspend fun saveAccessToken(token: String) = Result.success(Unit)
    override suspend fun getAccessToken(): Result<String?> = Result.success("test-token")
    override suspend fun saveRefreshToken(token: String) = Result.success(Unit)
    override suspend fun getRefreshToken(): Result<String?> = Result.success(null)
    override suspend fun saveAccessTokenExpiresAt(expiresAt: Long) = Result.success(Unit)
    // Gate 17 mechanism migration: the interface gained pending-refresh
    // persistence. This fake only needs to satisfy it; no assertion here depends
    // on refresh behaviour.
    override suspend fun savePendingRefresh(userId: String, refreshToken: String, requestId: String) = Result.success(Unit)
    override suspend fun getPendingRefresh(): Result<com.messenger.app.security.PendingRefresh?> = Result.success(null)
    override suspend fun clearPendingRefresh() = Result.success(Unit)
    override suspend fun installRefreshedTokens(accessToken: String, refreshToken: String, expiresAt: Long) = Result.success(Unit)
    override suspend fun getAccessTokenExpiresAt(): Result<Long?> = Result.success(null)
    override suspend fun saveCurrentUserId(userId: String) = Result.success(Unit)
    override suspend fun getCurrentUserId(): Result<String?> = Result.success("me")
    override suspend fun saveE2EEKeys(userId: String, publicHex: String, privateHex: String) = Result.success(Unit)
    override suspend fun getE2EEPrivateKey(userId: String): Result<String?> = Result.success(null)
    override suspend fun getE2EEPublicKey(userId: String): Result<String?> = Result.success(null)
    // Phase 67 added the K_device slot to the interface. This fake only needs to
    // satisfy it; nothing here enrols a device.
    override suspend fun saveDeviceKeys(userId: String, publicHex: String, privateHex: String) = Result.success(Unit)
    override suspend fun getDeviceKeyPrivate(userId: String): Result<String?> = Result.success(null)
    override suspend fun getDeviceKeyPublic(userId: String): Result<String?> = Result.success(null)
    override suspend fun saveGroupSenderKey(owner: String, chatId: String, versionAndKey: String) = Result.success(Unit)
    override suspend fun getGroupSenderKey(owner: String, chatId: String): Result<String?> = Result.success(null)
    override suspend fun loadGroupSenderKeys(owner: String, chatId: String) = Result.success(emptyMap<Int, String>())
    override suspend fun savePeerSenderKey(owner: String, chatId: String, senderId: String, version: Int, keyHex: String) = Result.success(Unit)
    override suspend fun loadPeerSenderKeys(owner: String, chatId: String) = Result.success(emptyMap<String, String>())
    override suspend fun saveKnownPublicKey(owner: String, chatId: String, publicKeyHex: String) = Result.success(Unit)
    override suspend fun getKnownPublicKey(owner: String, chatId: String): Result<String?> = Result.success(null)
    override suspend fun savePendingPublicKey(owner: String, chatId: String, publicKeyHex: String) = Result.success(Unit)
    override suspend fun getPendingPublicKey(owner: String, chatId: String): Result<String?> = Result.success(null)
    override suspend fun clearPendingPublicKey(owner: String, chatId: String) = Result.success(Unit)
    override suspend fun deleteDirectRatchet(owner: String, chatId: String) = Result.success(Unit)
    override suspend fun markSafetyVerified(owner: String, chatId: String, pubHex: String) = Result.success(Unit)
    override suspend fun clearSafetyVerified(owner: String, chatId: String) = Result.success(Unit)
    override suspend fun isSafetyVerified(owner: String, chatId: String) = Result.success(false)
    override suspend fun getOrCreateDeviceId() = Result.success("test-device")
    override suspend fun exportVaultSenderKeys(owner: String) = Result.success(emptyMap<String, String>())
    override suspend fun exportVaultPeerPubs(owner: String) = Result.success(emptyMap<String, String>())
    override suspend fun restoreVaultSenderKeys(owner: String, keys: Map<String, String>) = Result.success(Unit)
    override suspend fun restoreVaultPeerPubs(owner: String, pubs: Map<String, String>) = Result.success(Unit)
    override suspend fun exportVaultPeerSenderKeys(owner: String) = Result.success(emptyMap<String, String>())
    override suspend fun restoreVaultPeerSenderKeys(owner: String, keys: Map<String, String>) = Result.success(Unit)
    override suspend fun saveMlsBundle(owner: MlsOwner, key: String, json: String) = Result.success(Unit)
    override suspend fun loadMlsBundle(owner: MlsOwner, key: String): Result<String?> = Result.success(null)
    override suspend fun hasMlsBundle(owner: MlsOwner, key: String) = Result.success(false)
    override suspend fun listMlsBundles(owner: MlsOwner, prefix: String) = Result.success(emptyMap<String, String>())
    override suspend fun deleteMlsBundle(owner: MlsOwner, key: String) = Result.success(Unit)
    override suspend fun saveDirectRatchet(owner: String, chatId: String, json: String) = Result.success(Unit)
    override suspend fun loadDirectRatchet(owner: String, chatId: String): Result<String?> = Result.success(null)
    override suspend fun exportVaultDirectRatchets(owner: String) = Result.success(emptyMap<String, String>())
    override suspend fun restoreVaultDirectRatchets(owner: String, sessions: Map<String, String>) = Result.success(Unit)
    override suspend fun clearTokens() = Result.success(Unit)
    override suspend fun isAccessTokenExpired() = Result.success(false)
    override fun isAuthenticated() = true
}

/** Minimal in-memory keyring ports so the ViewModel under test can be constructed. */
private class NoopKeyringVault : com.messenger.app.data.encryption.history.HistoryKeyringVault {
    override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
        Result.success(byteArrayOf(0x7F) + plaintext.copyOf())
    override suspend fun openHistoryKeyring(sealed: ByteArray) =
        if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte()) {
            Result.success(sealed.copyOfRange(1, sealed.size))
        } else Result.failure(IllegalStateException("not sealed"))
}

private class NoopKeyringStore : com.messenger.app.data.encryption.history.HistoryKeyringStore {
    private var blob: ByteArray? = null
    private var cache: ByteArray? = null
    override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray) =
        Result.success(Unit).also { blob = sealed.copyOf() }
    override suspend fun loadHistoryKeyring(owner: String) = Result.success(blob?.copyOf())
    override suspend fun deleteHistoryKeyring(owner: String) = Result.success(Unit).also { blob = null }
    override suspend fun saveHistoryKeyringCache(owner: String, plain: ByteArray) =
        Result.success(Unit).also { cache = plain.copyOf() }
    override suspend fun loadHistoryKeyringCache(owner: String) = Result.success(cache?.copyOf())
    override suspend fun deleteHistoryKeyringCache(owner: String) = Result.success(Unit).also { cache = null }
    private var generation: Long? = null
    override suspend fun saveHistoryKeyringGeneration(owner: String, generation: Long) =
        Result.success(Unit).also { this.generation = generation }
    override suspend fun loadHistoryKeyringGeneration(owner: String) = Result.success(generation)
    override suspend fun deleteHistoryKeyringGeneration(owner: String) =
        Result.success(Unit).also { generation = null }
}
