package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 4 PHASE 2 - the feature flag governs the network too, JVM.
 *
 * With archiving OFF nothing may leave or enter the device: no seal, no upload,
 * no download. The API and TokenManager are relaxed mocks, and the assertion is
 * that they are never touched at all - a stronger statement than "the call
 * returned empty".
 */
class ArchiveSyncFeatureFlagTest {

    private class MemoryStore : HistoryKeyringStore {
        private var blob: ByteArray? = null
        private var cache: ByteArray? = null
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() = Result.success(Unit).also { cache = null }

        private var generation: Long? = null
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    private class PassthroughVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x7F) + plaintext.copyOf())
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte()) {
                Result.success(sealed.copyOfRange(1, sealed.size))
            } else Result.failure(IllegalStateException("not sealed"))
    }

    private class CountingCipher : ArchiveCipher {
        var sealCalls = 0
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray): ByteArray {
            sealCalls++
            return "sealed".toByteArray()
        }
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? = null
    }

    private companion object {
        const val USER = "user-1"
        const val CHAT = "chat-1"
        const val MSG = "msg-1"
        const val TEXT = "phase 2 canary"
    }

    private fun sync(
        enabled: Boolean,
        api: ChatApiService = mockk(relaxed = true),
        tokens: TokenManager = mockk(relaxed = true),
        cipher: CountingCipher = CountingCipher(),
    ): Triple<ArchiveSync, ChatApiService, CountingCipher> {
        val keyring = HistoryKeyringRepository(PassthroughVault(), MemoryStore(), com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val archiver = MessageArchiver(keyring, cipher, HistoryArchiveFeature { enabled })
        return Triple(
            ArchiveSync(archiver, api, tokens, HistoryArchiveFeature { enabled },
                RecoveryTestSupport.disabledLazy(keyring)),
            api,
            cipher,
        )
    }

    // ------------------------------------------------------------------ OFF

    @Test
    fun theProductionDefaultIsOff() {
        assertTrue(!HistoryArchiveFeature.DEFAULT_ENABLED)
        assertTrue(!HistoryArchiveFeature.Default.isEnabled())
    }

    @Test
    fun offMeansNoSealAndNoUpload() = runBlocking {
        val (s, api, cipher) = sync(enabled = false)
        assertNull(s.sealAndUpload(USER, CHAT, MSG, TEXT))
        assertEquals("the cipher must not run", 0, cipher.sealCalls)
        verify { api wasNot Called }
    }

    @Test
    fun offMeansNoDownload() = runBlocking {
        val (s, api, _) = sync(enabled = false)
        assertTrue(s.downloadFor(CHAT).records.isEmpty())
        verify { api wasNot Called }
    }

    @Test
    fun offMeansNoTokenIsEvenRead() = runBlocking {
        val tokens: TokenManager = mockk(relaxed = true)
        val (s, _, _) = sync(enabled = false, tokens = tokens)
        s.sealAndUpload(USER, CHAT, MSG, TEXT)
        s.downloadFor(CHAT)
        verify { tokens wasNot Called }
    }

    // ------------------------------------------------------------------ ON

    /**
     * With the flag ON the local seal happens even if the network is unusable,
     * and the sealed archive is still returned so the caller records it locally.
     * That is what makes a failed upload retryable by a later refresh instead of
     * losing history the device can already read.
     */
    @Test
    fun onSealsLocallyEvenWhenUploadCannotProceed() = runBlocking {
        // Relaxed TokenManager returns a boxed default for getAccessToken(), so
        // the upload leg cannot proceed - exactly the "no usable token" path.
        val (s, _, cipher) = sync(enabled = true)
        val sealed = s.sealAndUpload(USER, CHAT, MSG, TEXT)
        assertTrue("the local seal must still have happened", cipher.sealCalls > 0)
        assertTrue("and its result must be returned for local storage", sealed != null)
        assertEquals(1, sealed!!.rootVersion)
    }

    @Test
    fun onWithNoUsableTokenDownloadsNothingAndDoesNotThrow() = runBlocking {
        val (s, _, _) = sync(enabled = true)
        assertTrue(s.downloadFor(CHAT).records.isEmpty())
    }

    @Test
    fun blankChatIdIsRefusedRegardlessOfFlag() = runBlocking {
        val (s, api, _) = sync(enabled = true)
        assertTrue(s.downloadFor("").records.isEmpty())
        verify { api wasNot Called }
    }
}
