package com.messenger.app.ui.viewmodel

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
            tokenManager = FakeTokenManager()
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
    override suspend fun getAccessTokenExpiresAt(): Result<Long?> = Result.success(null)
    override suspend fun saveCurrentUserId(userId: String) = Result.success(Unit)
    override suspend fun getCurrentUserId(): Result<String?> = Result.success("me")
    override suspend fun saveE2EEKeys(userId: String, publicHex: String, privateHex: String) = Result.success(Unit)
    override suspend fun getE2EEPrivateKey(userId: String): Result<String?> = Result.success(null)
    override suspend fun getE2EEPublicKey(userId: String): Result<String?> = Result.success(null)
    override suspend fun saveGroupSenderKey(chatId: String, versionAndKey: String) = Result.success(Unit)
    override suspend fun getGroupSenderKey(chatId: String): Result<String?> = Result.success(null)
    override suspend fun loadGroupSenderKeys(chatId: String) = Result.success(emptyMap<Int, String>())
    override suspend fun savePeerSenderKey(chatId: String, senderId: String, version: Int, keyHex: String) = Result.success(Unit)
    override suspend fun loadPeerSenderKeys(chatId: String) = Result.success(emptyMap<String, String>())
    override suspend fun saveKnownPublicKey(chatId: String, publicKeyHex: String) = Result.success(Unit)
    override suspend fun getKnownPublicKey(chatId: String): Result<String?> = Result.success(null)
    override suspend fun savePendingPublicKey(chatId: String, publicKeyHex: String) = Result.success(Unit)
    override suspend fun getPendingPublicKey(chatId: String): Result<String?> = Result.success(null)
    override suspend fun clearPendingPublicKey(chatId: String) = Result.success(Unit)
    override suspend fun deleteDirectRatchet(chatId: String) = Result.success(Unit)
    override suspend fun markSafetyVerified(chatId: String, pubHex: String) = Result.success(Unit)
    override suspend fun clearSafetyVerified(chatId: String) = Result.success(Unit)
    override suspend fun isSafetyVerified(chatId: String) = Result.success(false)
    override suspend fun getOrCreateDeviceId() = Result.success("test-device")
    override suspend fun exportVaultSenderKeys() = Result.success(emptyMap<String, String>())
    override suspend fun exportVaultPeerPubs() = Result.success(emptyMap<String, String>())
    override suspend fun restoreVaultSenderKeys(keys: Map<String, String>) = Result.success(Unit)
    override suspend fun restoreVaultPeerPubs(pubs: Map<String, String>) = Result.success(Unit)
    override suspend fun exportVaultPeerSenderKeys() = Result.success(emptyMap<String, String>())
    override suspend fun restoreVaultPeerSenderKeys(keys: Map<String, String>) = Result.success(Unit)
    override suspend fun saveMlsBundle(key: String, json: String) = Result.success(Unit)
    override suspend fun loadMlsBundle(key: String): Result<String?> = Result.success(null)
    override suspend fun hasMlsBundle(key: String) = Result.success(false)
    override suspend fun listMlsBundles(prefix: String) = Result.success(emptyMap<String, String>())
    override suspend fun deleteMlsBundle(key: String) = Result.success(Unit)
    override suspend fun saveDirectRatchet(chatId: String, json: String) = Result.success(Unit)
    override suspend fun loadDirectRatchet(chatId: String): Result<String?> = Result.success(null)
    override suspend fun exportVaultDirectRatchets() = Result.success(emptyMap<String, String>())
    override suspend fun restoreVaultDirectRatchets(sessions: Map<String, String>) = Result.success(Unit)
    override suspend fun clearTokens() = Result.success(Unit)
    override suspend fun isAccessTokenExpired() = Result.success(false)
    override fun isAuthenticated() = true
}
