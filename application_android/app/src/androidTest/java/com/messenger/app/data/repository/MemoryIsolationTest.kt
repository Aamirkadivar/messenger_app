package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.E2ECrypto
import com.messenger.app.data.local.AccountCacheHolder
import com.messenger.app.data.local.AccountCacheNamespace
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.model.ChatListItemDto
import com.messenger.app.data.model.ChatListOtherUserDto
import com.messenger.app.data.model.ChatsListResponse
import com.messenger.app.data.model.GroupDto
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider

/**
 * GATE 24.1 - account isolation of ChatRepository's in-memory state.
 *
 * The maps are private, so these assertions observe the DURABLE SOURCE instead:
 * if in-memory state was correctly discarded, the next account must reload from
 * its own store, and that reload is recordable. If state leaked across the
 * switch, the reload never happens - which is precisely the signal.
 *
 * Where a stale entry would not merely be *held* but actually *used*, the test
 * asserts the security consequence directly: account B must not be able to read
 * account A's ciphertext.
 */
@RunWith(AndroidJUnit4::class)
class MemoryIsolationTest {

    private companion object {
        const val ACCOUNT_A = "9999aaaa-0000-0000-0000-0000000099aa"
        const val ACCOUNT_B = "0000bbbb-0000-0000-0000-0000000000bb"
        const val GROUP = "group-both-accounts-are-in"
        const val DIRECT = "direct-chat-with-the-same-peer"
        const val PEER = "cccc0000-0000-0000-0000-0000000000cc"
        const val TOKEN = "test-token"

        // Real 32-byte hex material: the ratchet refuses to initialise a session
        // from anything else, and this test needs a session to actually exist.
        const val PEER_PUB = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
        val PUB = mapOf(
            ACCOUNT_A to "abababababababababababababababababababababababababababababababab",
            ACCOUNT_B to "bcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbc"
        )
        val PRIV = mapOf(
            ACCOUNT_A to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ACCOUNT_B to "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
    }

    private class RecordingAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = ACCOUNT_A
        val peerKeyLoads = CopyOnWriteArrayList<String>()
        val privateKeyLookups = CopyOnWriteArrayList<String>()

        /** Reloads of this account's OWN Sender Keys, tagged with who asked. */
        val ownKeyLoads = CopyOnWriteArrayList<String>()
        val senderKeySaves = CopyOnWriteArrayList<String>()
        @Volatile var failSenderKeySave = false

        /** Reloads of direct-chat ratchet state, tagged with who asked. */
        val ratchetLoads = CopyOnWriteArrayList<String>()

        /** Durable peer Sender Keys, per account: account -> ("sender|ver" -> keyHex). */
        @Volatile var peerKeyStore: Map<String, Map<String, String>> = emptyMap()

        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)

        override suspend fun getE2EEPrivateKey(userId: String): Result<String?> {
            privateKeyLookups += userId
            return Result.success(PRIV[userId] ?: "00".repeat(32))
        }

        override suspend fun getE2EEPublicKey(userId: String): Result<String?> =
            Result.success(PUB[userId] ?: "11".repeat(32))

        override suspend fun loadPeerSenderKeys(owner: String, chatId: String): Result<Map<String, String>> {
            // Tagged with the account in effect at load time, so the recording
            // shows WHO reloaded, not merely that something did.
            peerKeyLoads += "${account.orEmpty()}|$chatId"
            return Result.success(peerKeyStore[account.orEmpty()].orEmpty())
        }

        override suspend fun loadGroupSenderKeys(owner: String, chatId: String): Result<Map<Int, String>> {
            ownKeyLoads += "${account.orEmpty()}|$chatId"
            return Result.success(emptyMap())
        }

        override suspend fun saveGroupSenderKey(owner: String, chatId: String, versionAndKey: String): Result<Unit> {
            senderKeySaves += "${account.orEmpty()}|$chatId"
            return if (failSenderKeySave) Result.failure(IllegalStateException("keystore unavailable"))
            else Result.success(Unit)
        }

        override suspend fun loadDirectRatchet(owner: String, chatId: String): Result<String?> {
            ratchetLoads += "${account.orEmpty()}|$chatId"
            return Result.success(null)
        }

        /** Nothing pinned yet, so the server's key is adopted and chatOtherPub set. */
        override suspend fun getKnownPublicKey(owner: String, chatId: String): Result<String?> =
            Result.success(null)

        // Result<String> is a value class over a non-null String, and a relaxed
        // mock answers it with a proxy that cannot be cast - so the direct-send
        // path dies with a ClassCastException before it reaches the ratchet.
        // These two are on that path and have to be real.
        override suspend fun getOrCreateDeviceId(): Result<String> =
            Result.success("device-of-this-install")

        override suspend fun getAccessToken(): Result<String?> = Result.success(null)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: RecordingAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var api: ChatApiService
    private lateinit var groups: GroupRepository
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
        auth = RecordingAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        api = mockk(relaxed = true)
        groups = mockk(relaxed = true)
        // A real group with no members: enough to reach key generation and the
        // persistence step without any distribution side effects.
        coEvery { groups.getGroupInfo(any(), any()) } returns
            Result.success(GroupDto(id = GROUP, keyEpoch = 1))
        repo = newRepository()
    }

    private fun newRepository() = ChatRepository(
        chatApiService = api,
        messageDao = ScopedMessageDao(holder),
        conversationDao = ScopedConversationDao(holder),
        cachedChatDao = ScopedCachedChatDao(holder),
        webSocketManager = mockk<WebSocketManager>(relaxed = true),
        tokenManager = auth,
        groupRepository = groups,
        json = Json { ignoreUnknownKeys = true }
    )

    /** The server says GROUP is a group chat. Drives the real learnChatMeta path. */
    private fun serveGroupChatList() {
        coEvery { api.getChats(any()) } returns Response.success(
            ChatsListResponse(data = listOf(ChatListItemDto(id = GROUP, type = "group")))
        )
    }

    /** A direct chat with a known peer identity, so chatOtherPub gets populated. */
    private fun serveDirectChatList() {
        coEvery { api.getChats(any()) } returns Response.success(
            ChatsListResponse(
                data = listOf(
                    ChatListItemDto(
                        id = DIRECT,
                        type = "direct",
                        otherUser = ChatListOtherUserDto(
                            id = PEER,
                            email = "peer@example.test",
                            username = "peer",
                            publicKey = PEER_PUB
                        )
                    )
                )
            )
        )
    }

    /** Signs A out and B in, the way the app does. */
    private suspend fun switchToB() {
        auth.account = null
        holder.deactivate()
        auth.account = ACCOUNT_B
    }

    @After
    fun tearDown(): Unit = runBlocking {
        holder.deactivate()
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
    }

    /**
     * F: peer Sender Keys, and the peerKeysLoaded pairing.
     *
     * A loads peers' keys for a group. After the switch, B must load them for
     * ITSELF. If the cache survived - or if the keys were cleared but the
     * reload guard was not - B never reloads, and B would be reading A's copies.
     */
    @Test
    fun peerSenderKeysAreReloadedByTheNewAccount() = runBlocking {
        auth.account = ACCOUNT_A
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }
        assertTrue(
            "precondition: A loaded peer sender keys",
            auth.peerKeyLoads.any { it.startsWith(ACCOUNT_A) }
        )

        switchToB()

        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }

        assertTrue(
            "PROVEN BROKEN: account B never reloaded peer sender keys, so it was " +
                "reading account A's in-memory copies. Either groupOtherKeys " +
                "survived the switch, or it was cleared without clearing the " +
                "peerKeysLoaded guard that decides whether a reload happens at all. " +
                "Loads seen: ${auth.peerKeyLoads}",
            auth.peerKeyLoads.any { it.startsWith(ACCOUNT_B) }
        )
    }

    /**
     * F2: a retained peer Sender Key would not merely sit in memory - it would
     * be USED.
     *
     * ensurePeerSenderKeysLoaded only ever ADDS entries to groupOtherKeys; it
     * never removes ones that are already there. So B reloading its own (empty)
     * set does not displace A's entry, and groupSenderKeyFor would hand B the
     * key that decrypts A's group history. This asserts the consequence rather
     * than the reload: B must not be able to read the message.
     */
    @Test
    fun aPeerSenderKeyLeftBehindByACannotDecryptForB() = runBlocking {
        val keyHex = requireNotNull(E2ECrypto.secretBoxGenerateKey())
        val sealed = requireNotNull(
            E2ECrypto.secretBoxEncryptBytes(E2ECrypto.wrapEnvelope("hello".toByteArray()), keyHex)
        )
        val content = E2ECrypto.toHex(sealed)
        // Only A's durable store holds the peer's key. B's is empty.
        auth.peerKeyStore = mapOf(ACCOUNT_A to mapOf("$PEER|1" to keyHex))

        auth.account = ACCOUNT_A
        assertEquals(
            "precondition: A can read the group message with the peer's Sender Key",
            "hello",
            repo.decryptFor(GROUP, content, true, PEER, keyVersion = 1, chatType = "group")
        )

        switchToB()

        assertNotEquals(
            "PROVEN BROKEN: account B decrypted a group message addressed to " +
                "account A, using a peer Sender Key that account A left behind in " +
                "groupOtherKeys. B's own store never held that key.",
            "hello",
            repo.decryptFor(GROUP, content, true, PEER, keyVersion = 1, chatType = "group")
        )
    }

    /**
     * D: this account's OWN Sender Keys.
     *
     * ensureGroupSenderKeyReady reloads from durable storage only when the
     * in-memory entry is empty. If A's entry survives the switch, that reload
     * never happens and B encrypts group traffic under ACCOUNT A'S KEY - the
     * most severe form of this leak, since the ciphertext is then readable by
     * A's group peers and unreadable by B's.
     */
    @Test
    fun ownSenderKeysAreRebuiltByTheNewAccount() = runBlocking {
        serveGroupChatList()

        auth.account = ACCOUNT_A
        runCatching { repo.getChats(TOKEN) }
        runCatching { repo.encryptBytesFor(TOKEN, GROUP, "hello".toByteArray()) }
        assertTrue(
            "precondition: A consulted its own stored Sender Keys (saw ${auth.ownKeyLoads})",
            auth.ownKeyLoads.any { it.startsWith(ACCOUNT_A) }
        )

        switchToB()
        auth.ownKeyLoads.clear()

        // B re-learns the chat type through the same production path, then sends.
        runCatching { repo.getChats(TOKEN) }
        runCatching { repo.encryptBytesFor(TOKEN, GROUP, "hello".toByteArray()) }

        assertTrue(
            "PROVEN BROKEN: account B never reloaded its own Sender Keys, which " +
                "means mySenderKeys still held account A's key and B encrypted " +
                "group traffic under it (loads seen: ${auth.ownKeyLoads})",
            auth.ownKeyLoads.any { it.startsWith(ACCOUNT_B) }
        )
    }

    /**
     * E: direct-chat ratchet state.
     *
     * loadRatchetV4 short-circuits on the in-memory session before it ever
     * consults durable storage. If A's session survives the switch, B never
     * loads its own and would advance ACCOUNT A'S RATCHET - deriving message
     * keys from A's root key for traffic B is sending as itself.
     */
    @Test
    fun directRatchetStateIsRebuiltByTheNewAccount() = runBlocking {
        serveDirectChatList()

        auth.account = ACCOUNT_A
        val chats = runCatching { repo.getChats(TOKEN) }
        val enc = runCatching { repo.encryptBytesFor(TOKEN, DIRECT, "hi".toByteArray()) }
        assertTrue(
            "precondition: A established a ratchet session " +
                "(loads=${auth.ratchetLoads} chats=$chats enc=$enc)",
            auth.ratchetLoads.any { it.startsWith(ACCOUNT_A) }
        )

        switchToB()
        auth.ratchetLoads.clear()

        runCatching { repo.getChats(TOKEN) }
        runCatching { repo.encryptBytesFor(TOKEN, DIRECT, "hi".toByteArray()) }

        assertTrue(
            "PROVEN BROKEN: account B never loaded its own ratchet state, so the " +
                "in-memory session still belonged to account A and B's outgoing " +
                "direct messages advanced A's ratchet (loads seen: ${auth.ratchetLoads})",
            auth.ratchetLoads.any { it.startsWith(ACCOUNT_B) }
        )
    }

    /** A: the private key is always the signed-in account's. */
    @Test
    fun eachAccountUsesItsOwnPrivateKey() = runBlocking {
        auth.account = ACCOUNT_A
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }
        auth.privateKeyLookups.clear()

        auth.account = ACCOUNT_B
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }

        assertTrue(
            "B must resolve its OWN private key (saw ${auth.privateKeyLookups})",
            auth.privateKeyLookups.contains(ACCOUNT_B)
        )
        assertTrue(
            "A's key must not be consulted while B is signed in (saw ${auth.privateKeyLookups})",
            !auth.privateKeyLookups.contains(ACCOUNT_A)
        )
    }

    /** G: a late callback owned by A must not populate B's memory. */
    @Test
    fun aLateOperationOwnedByAIsRefusedOnceBIsActive() = runBlocking {
        auth.account = ACCOUNT_A
        runCatching { repo.sendMessage(TOKEN, GROUP, "group", "hello") }
        assertEquals("precondition: A learned the chat type", "group", repo.chatTypeFor(GROUP))

        auth.account = ACCOUNT_B

        // B must not see A's metadata, and A's own view must not be resurrected.
        assertEquals(
            "PROVEN BROKEN: B inherited A's chat metadata",
            "direct", repo.chatTypeFor(GROUP)
        )
    }

    /**
     * G2: an operation that OUTLIVES its account.
     *
     * The previous test switches accounts between operations, so the heal alone
     * is enough to protect it. Here the switch happens while the request is in
     * flight: the owner captured at operation start no longer matches the active
     * account when the reply applies its metadata. Only the owner check can
     * catch this, and the reply must be REFUSED - never re-attributed to whoever
     * happens to be signed in when it lands.
     */
    @Test
    fun aChatListReplyThatOutlivesItsAccountCannotPopulateTheNewOne() = runBlocking {
        val midFlight = newRepository()
        coEvery { api.getChats(any()) } answers {
            auth.account = ACCOUNT_B          // the switch happens DURING the call
            Response.success(
                ChatsListResponse(data = listOf(ChatListItemDto(id = GROUP, type = "group")))
            )
        }

        auth.account = ACCOUNT_A
        runCatching { midFlight.getChats(TOKEN) }

        assertEquals("precondition: the switch happened during the call", ACCOUNT_B, auth.account)
        assertEquals(
            "PROVEN BROKEN: a chat list fetched as account A populated account B's " +
                "in-memory metadata. The owner captured at operation start must be " +
                "compared against the active account before the mutation is applied.",
            "direct", midFlight.chatTypeFor(GROUP)
        )
    }

    /**
     * K: a Sender Key that could not be persisted must not become usable.
     *
     * Installing it in memory only would encrypt group traffic under a key that
     * ceases to exist the moment memory is cleared on the next logout - and this
     * gate is precisely what makes that clearing aggressive. The ciphertext
     * would then be unrecoverable even by its own sender.
     */
    @Test
    fun aSenderKeyThatFailsToPersistIsNotInstalled() = runBlocking {
        serveGroupChatList()
        auth.account = ACCOUNT_A
        runCatching { repo.getChats(TOKEN) }

        auth.failSenderKeySave = true
        val sealed = repo.encryptBytesFor(TOKEN, GROUP, "hello".toByteArray())

        assertTrue(
            "precondition: the key was generated and a save was attempted " +
                "(saves seen: ${auth.senderKeySaves})",
            auth.senderKeySaves.any { it.startsWith(ACCOUNT_A) }
        )
        assertNull(
            "PROVEN BROKEN: a Sender Key that could not be persisted was still " +
                "installed and used to encrypt. It must fail closed instead: the " +
                "key is gone after the next logout, and the ciphertext with it.",
            sealed
        )
    }

    /** I: A -> B -> A. A's state is rebuilt from durable storage, not memory. */
    @Test
    fun returningToAccountAReloadsFromDurableStorage() = runBlocking {
        auth.account = ACCOUNT_A
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }

        auth.account = ACCOUNT_B
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }

        auth.peerKeyLoads.clear()
        auth.account = ACCOUNT_A
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP) }

        assertTrue(
            "returning to A must rebuild A's peer keys from durable storage " +
                "rather than relying on state that happened to survive in memory " +
                "(loads seen: ${auth.peerKeyLoads})",
            auth.peerKeyLoads.any { it.startsWith(ACCOUNT_A) }
        )
    }

    /** H: logging out with nobody signed in must leave nothing readable. */
    @Test
    fun noAccountMeansNoReadableState() = runBlocking {
        auth.account = ACCOUNT_A
        runCatching { repo.sendMessage(TOKEN, GROUP, "group", "hello") }
        assertEquals("group", repo.chatTypeFor(GROUP))

        auth.account = null

        assertEquals(
            "PROVEN BROKEN: state stayed readable with no account signed in",
            "direct", repo.chatTypeFor(GROUP)
        )
    }
}
