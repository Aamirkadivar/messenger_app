package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.DoubleRatchet
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider

/**
 * GATE 25.3 - a stale operation cannot mutate memory another account already owns.
 *
 * The weaker property, which AuditRaceProofTest establishes, is that account B
 * never OBSERVES account A's material. That one can pass without any write-side
 * check at all: the read path heals first, so a leaked entry is wiped before
 * anything looks at it. It proves cleanup, not mutation isolation.
 *
 * The property here is the strong one:
 *
 *     stale A write -> holder already owned by B -> write refused
 *
 * Every test follows the same sequence: A starts an operation and suspends
 * before its mutation; logout clears A's memory; B signs in and performs a
 * LEGITIMATE operation that establishes a real value, so stateOwner is already B
 * and no heal can fire; A then resumes. The assertions check B's own value is
 * still there, unchanged - because "A's value is absent" is weaker than "B's
 * value survived".
 */
@RunWith(AndroidJUnit4::class)
class MemoryOwnershipRaceTest {

    private companion object {
        const val A = "7777aaaa-0000-0000-0000-00000000a777"
        const val B = "8888bbbb-0000-0000-0000-00000000b888"
        const val CHAT = "chat-both-accounts-can-open"
        const val GROUP = "group-both-accounts-are-in"
        const val TOKEN = "test-token"

        /**
         * Account A's pinned peer identity, deliberately NOT valid hex.
         *
         * That is the distinguisher: if A's stale write lands on B's memory, the
         * chat's peer identity becomes unusable and B can no longer seal. If B's
         * value survives, B seals normally. Without it, both accounts' values are
         * "some non-blank string" and an overwrite is invisible.
         */
        const val A_PIN_NOT_HEX = "ACCOUNT-A-PINNED-IDENTITY-NOT-HEX"
        val SERVER_PUB = "bb".repeat(32)
        val A_PEER_SENDER_KEY = "a1".repeat(32)

        /**
         * Real X25519 material. Arbitrary 32-byte strings are hex-valid but are
         * not a keypair, and the native ratchet does not survive being handed
         * one - it takes the instrumentation process down with it rather than
         * failing a test.
         */
        // by lazy, not eager: a companion initializer runs at class load, which
        // is before the application has initialised libsodium. Generating keys
        // there took the whole instrumentation process down.
        val MINE by lazy { requireNotNull(E2ECrypto.generateKeyPair()) }
        val PEER_A by lazy { requireNotNull(E2ECrypto.generateKeyPair()) }
        val PEER_B by lazy { requireNotNull(E2ECrypto.generateKeyPair()) }
        val PEER_PUB_A: String get() = PEER_A.publicHex
        val PEER_PUB_B: String get() = PEER_B.publicHex
    }

    private class GatedAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = A
        @Volatile var gateOn: String? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val peerKeyLoads = CopyOnWriteArrayList<String>()
        val ratchetLoads = CopyOnWriteArrayList<String>()
        /** Only family 1 needs A to hold a pin that disagrees with the server. */
        @Volatile var pinAccountA = false
        /** Family 3 gives both accounts the SAME peer, so see the note there. */
        @Volatile var forcedPeerPub: String? = null
        @Volatile var peerKeyForA: String? = null
        val deviceIdCalls = CopyOnWriteArrayList<String>()

        private suspend fun gate(name: String) {
            if (gateOn == name) {
                gateOn = null
                entered.complete(Unit)
                release.await()
            }
        }

        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)

        /** A has a pin that disagrees with the server; B has none. */
        override suspend fun getKnownPublicKey(owner: String, chatId: String): Result<String?> {
            gate("getKnownPublicKey")
            return Result.success(if (pinAccountA && owner == A) A_PIN_NOT_HEX else null)
        }

        override suspend fun loadPeerSenderKeys(owner: String, chatId: String): Result<Map<String, String>> {
            gate("loadPeerSenderKeys")
            peerKeyLoads += owner
            return Result.success(
                if (owner == A) mapOf("someone|1" to (peerKeyForA ?: A_PEER_SENDER_KEY))
                else emptyMap()
            )
        }

        /** Account A holds none, so its send has to GENERATE one. */
        @Volatile var emptyOwnKeysForA = false

        override suspend fun saveGroupSenderKey(owner: String, chatId: String, versionAndKey: String): Result<Unit> {
            gate("saveGroupSenderKey")
            return Result.success(Unit)
        }

        override suspend fun loadGroupSenderKeys(owner: String, chatId: String): Result<Map<Int, String>> {
            gate("loadGroupSenderKeys")
            if (emptyOwnKeysForA && owner == A) return Result.success(emptyMap())
            // A holds a much higher version. ensureGroupSenderKeyReady always
            // selects the highest, so if A's install lands on B's memory the
            // version B seals under changes - visible in SealedBytes.keyVersion.
            return Result.success(
                if (owner == A) mapOf(99 to A_PEER_SENDER_KEY) else mapOf(1 to "b1".repeat(32))
            )
        }

        /**
         * Never returns a stored session: a hand-written one parses into a state
         * the native crypto rejects, and that crashes the process rather than
         * failing a test. Sessions here are always derived fresh, which is enough
         * - the two accounts derive DIFFERENT ones, because their peer identities
         * differ, and that difference is what the ratchet test observes.
         */
        /** A real v3 state when asked for one; v4 always derives fresh. */
        @Volatile var v3SessionJson: String? = null

        override suspend fun loadDirectRatchet(owner: String, chatId: String): Result<String?> {
            gate("loadDirectRatchet")
            ratchetLoads += owner
            return Result.success(if (chatId.startsWith("v4:")) null else v3SessionJson)
        }

        override suspend fun getPendingPublicKey(owner: String, chatId: String): Result<String?> {
            gate("getPendingPublicKey")
            // Not valid hex, on purpose: PEER_PUB_A would be perfectly usable by
            // account B, so an overwrite with it would be invisible. This makes
            // "B's identity was replaced" observable as "B can no longer seal".
            return Result.success(if (owner == A) A_PIN_NOT_HEX else null)
        }

        override suspend fun getE2EEPrivateKey(userId: String): Result<String?> {
            gate("getE2EEPrivateKey")
            return Result.success(MINE.privateHex)
        }

        override suspend fun getE2EEPublicKey(userId: String): Result<String?> =
            Result.success(MINE.publicHex)

        override suspend fun getOrCreateDeviceId(): Result<String> {
            deviceIdCalls += account.orEmpty()
            return Result.success("dev")
        }
        override suspend fun getAccessToken(): Result<String?> = Result.success(null)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: GatedAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var api: ChatApiService
    private lateinit var groups: GroupRepository
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        for (id in listOf(A, B)) ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        auth = GatedAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        api = mockk(relaxed = true)
        groups = mockk(relaxed = true)
        coEvery { groups.getGroupInfo(any(), any()) } returns
            Result.success(GroupDto(id = GROUP, keyEpoch = 1))
        serveChatList("direct")
        repo = ChatRepository(
            chatApiService = api,
            messageDao = ScopedMessageDao(holder),
            conversationDao = ScopedConversationDao(holder),
            cachedChatDao = ScopedCachedChatDao(holder),
            webSocketManager = mockk<WebSocketManager>(relaxed = true),
            tokenManager = auth,
            groupRepository = groups,
            json = Json { ignoreUnknownKeys = true }
        )
    }

    private fun serveChatList(type: String) {
        coEvery { api.getChats(any()) } answers { Response.success(
            ChatsListResponse(
                data = listOf(
                    ChatListItemDto(
                        id = CHAT, type = type,
                        otherUser = ChatListOtherUserDto(
                            id = "peer", email = "p@e.test", username = "p",
                            publicKey = auth.forcedPeerPub
                                ?: if (auth.account == A) PEER_PUB_A else PEER_PUB_B
                        )
                    )
                )
            )
        ) }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        holder.deactivate()
        for (id in listOf(A, B)) ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
    }

    /**
     * Steps 1-3: A starts an operation and parks before its memory mutation.
     *
     * An extension on the CALLER's scope on purpose. coroutineScope waits for
     * its children, so building the job inside one deadlocks against the very
     * coroutine this parks.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.parkAccountAInside(
        gate: String,
        start: suspend () -> Unit
    ): Job {
        auth.account = A
        auth.gateOn = gate
        val job = launch(Dispatchers.IO) { runCatching { start() } }
        val reached = runCatching { withTimeout(15_000) { auth.entered.await() } }.isSuccess
        if (!reached) {
            auth.release.complete(Unit)
            job.join()
            error("gate '$gate' was never reached - this test would prove nothing")
        }
        return job
    }

    /** Steps 4-5: logout clears A's memory, then B signs in. */
    private suspend fun logoutAndSignInB() {
        auth.account = null
        repo.clearAccountScopedState()
        auth.account = B
    }

    // ================= family 1: peer identity / pending key / notice =================

    @Test
    fun staleAcannotMutateBownedPeerIdentityState() = runBlocking {
        auth.pinAccountA = true
        val job = parkAccountAInside("getKnownPublicKey") { repo.getChats(TOKEN) }
        logoutAndSignInB()

        // Step 6: B's own legitimate operation establishes its peer identity.
        runCatching { repo.getChats(TOKEN) }
        // Step 7: the holder is genuinely B's - B can seal with its own identity.
        assertTrue(
            "precondition: B established its own peer identity and owns this memory",
            repo.encryptFor(CHAT, "hello").encrypted
        )
        assertFalse("precondition: B has no pending key of its own",
            repo.hasPendingPeerKeyChange(CHAT))

        // Steps 8-9: A resumes and must be refused at the write boundary.
        auth.release.complete(Unit)
        job.join()

        // Step 10: B's value survived, byte-for-byte - not merely "A's is absent".
        assertTrue(
            "PROVEN BROKEN: account A's stale write replaced the peer identity " +
                "that account B legitimately owned. B can no longer seal for this " +
                "chat, because chatOtherPub now holds account A's pinned value.",
            repo.encryptFor(CHAT, "hello").encrypted
        )
        // Step 11: none of A's material is present.
        assertFalse(
            "PROVEN BROKEN: account A's pending peer key landed on B-owned memory",
            repo.hasPendingPeerKeyChange(CHAT)
        )
        assertFalse(
            "PROVEN BROKEN: account A's security notice landed on B-owned memory",
            repo.takePendingSecurityNotice(CHAT)
        )
    }

    // ================= family 2: Sender Key state =================

    /**
     * Peers' Sender Keys. ensurePeerSenderKeysLoaded runs outside any mutex, so
     * unlike the two families below this interleaving really is reachable: a
     * stale load can resume while account B already owns the maps.
     *
     * The assertion is the security consequence, not a reload count - account B
     * must not be able to open a group message encrypted under the key account
     * A collected.
     */
    @Test
    fun staleAcannotMutateBownedPeerSenderKeyState() = runBlocking {
        val keyHex = requireNotNull(E2ECrypto.secretBoxGenerateKey())
        auth.peerKeyForA = keyHex
        val sealed = requireNotNull(
            E2ECrypto.secretBoxEncryptBytes(E2ECrypto.wrapEnvelope("secret".toByteArray()), keyHex)
        )
        val ciphertext = E2ECrypto.toHex(sealed)

        val job = parkAccountAInside("loadPeerSenderKeys") {
            repo.fetchGroupSenderKeys(TOKEN, CHAT)
        }
        logoutAndSignInB()

        // B legitimately loads its own (empty) peer key set and owns the maps.
        runCatching { repo.fetchGroupSenderKeys(TOKEN, CHAT) }
        assertTrue("precondition: B loaded its own peer keys", auth.peerKeyLoads.contains(B))
        assertNotEquals(
            "precondition: B cannot read this message before A resumes",
            "secret",
            repo.decryptFor(CHAT, ciphertext, true, "someone", 1, chatType = "group")
        )

        auth.release.complete(Unit)
        job.join()

        assertNotEquals(
            "PROVEN BROKEN: account A's stale peer Sender Key install landed on " +
                "maps account B already owned, and B recovered the PLAINTEXT of a " +
                "group message that only account A had the key for.",
            "secret",
            repo.decryptFor(CHAT, ciphertext, true, "someone", 1, chatType = "group")
        )
    }

    // ================= family 3: direct ratchet state =================

    /**
     * Ratchet and own-Sender-Key state are guarded by drMutex / groupKeyMutex,
     * and the stale operation parks while HOLDING one. Account B therefore
     * cannot mutate that state while A is suspended - it blocks on the lock -
     * so the concurrent "B owns it, then A's write lands" interleaving does not
     * exist for these two families.
     *
     * What IS reachable is the sequential form: A resumes, installs, releases
     * the lock, and B then finds the entry already sitting there. B claims the
     * holder first (through a path that takes no lock), so no heal can fire to
     * tidy it away - which is exactly what makes the install observable.
     */
    @Test
    fun aStaleRatchetInstallIsNotLeftForTheNextAccount() = runBlocking {
        auth.forcedPeerPub = PEER_PUB_A      // same peer, so no identity check masks it
        val job = parkAccountAInside("loadDirectRatchet") {
            repo.getChats(TOKEN)
            repo.encryptFor(CHAT, "from-A")
        }
        logoutAndSignInB()

        // B claims the holder without touching drMutex, which A still holds.
        runCatching { repo.getChats(TOKEN) }
        assertFalse("precondition: B owns the holder", repo.hasPendingPeerKeyChange(CHAT))

        auth.release.complete(Unit)
        job.join()

        // Only now does B take drMutex - A has released it.
        auth.ratchetLoads.clear()
        repo.encryptFor(CHAT, "from-B")
        assertTrue(
            "PROVEN BROKEN: account B reused the ratchet session account A's " +
                "stale operation installed - B never loaded one of its own, so it " +
                "is advancing account A's ratchet (loads: ${auth.ratchetLoads})",
            auth.ratchetLoads.contains(B)
        )
    }

    @Test
    fun aStaleOwnSenderKeyInstallIsNotLeftForTheNextAccount() = runBlocking {
        serveChatList("group")
        val job = parkAccountAInside("loadGroupSenderKeys") {
            repo.getChats(TOKEN)
            repo.encryptBytesFor(TOKEN, CHAT, "x".toByteArray())
        }
        logoutAndSignInB()

        runCatching { repo.getChats(TOKEN) }
        assertFalse("precondition: B owns the holder", repo.hasPendingPeerKeyChange(CHAT))

        auth.release.complete(Unit)
        job.join()

        // B now takes groupKeyMutex, free again, and picks a Sender Key. The
        // fake gives account A version 99 and account B version 1, and the
        // highest version always wins - so A's leftover is directly visible.
        val sealed = repo.encryptBytesFor(TOKEN, CHAT, "hello".toByteArray())
        assertEquals(
            "PROVEN BROKEN: account B sealed group traffic under the Sender Key " +
                "account A's stale operation installed. B's own key is version 1; " +
                "account A's is 99.",
            1, sealed?.keyVersion
        )
    }

    /**
     * M-V10 lives on the v3 loadRatchet install, not the v4 one, and only fires
     * when a STORED session comes back - so this drives a legacy v3 row with a
     * real DoubleRatchet state behind it.
     */
    @Test
    fun aStaleV3RatchetInstallIsNotLeftForTheNextAccount() = runBlocking {
        auth.forcedPeerPub = PEER_PUB_A
        auth.v3SessionJson = requireNotNull(
            DoubleRatchet.initAlice(requireNotNull(E2ECrypto.fromHex(PEER_PUB_A)))
        ).toJson()
        // A fan-out addressed to this device, so openDirectV3 reaches the ratchet.
        val payload = E2ECrypto.toHex(
            requireNotNull(E2ECrypto.wrapFanout(listOf(E2ECrypto.FanoutPart("dev", ByteArray(80) { 7 }))))
        )

        val job = parkAccountAInside("loadDirectRatchet") {
            repo.getChats(TOKEN)
            repo.decryptFor(
                CHAT, payload, encrypted = true, senderId = "peer",
                keyVersion = 0, encryptionVersion = 3, chatType = "direct"
            )
        }
        logoutAndSignInB()

        runCatching { repo.getChats(TOKEN) }
        assertFalse("precondition: B owns the holder", repo.hasPendingPeerKeyChange(CHAT))

        auth.release.complete(Unit)
        job.join()

        auth.ratchetLoads.clear()
        repo.decryptFor(
            CHAT, payload, encrypted = true, senderId = "peer",
            keyVersion = 0, encryptionVersion = 3, chatType = "direct"
        )
        assertTrue(
            "PROVEN BROKEN: account B reused the v3 ratchet session account A's " +
                "stale operation installed - B never loaded one of its own " +
                "(loads: ${auth.ratchetLoads})",
            auth.ratchetLoads.contains(B)
        )
    }

    /**
     * M-V14 is on the install of a NEWLY GENERATED Sender Key, after it is
     * persisted - not on the reload path. Account A therefore has to hold no key
     * at all, so its send generates one, and it parks on the persist step which
     * sits immediately before the install.
     */
    @Test
    fun aStaleGeneratedSenderKeyIsNotLeftForTheNextAccount() = runBlocking {
        serveChatList("group")
        auth.emptyOwnKeysForA = true
        // A's group is far ahead, so the key A generates outranks B's version 1
        // and would be selected if it leaked.
        coEvery { groups.getGroupInfo(any(), any()) } answers {
            Result.success(GroupDto(id = CHAT, keyEpoch = if (auth.account == A) 99 else 1))
        }

        val job = parkAccountAInside("saveGroupSenderKey") {
            repo.getChats(TOKEN)
            repo.encryptBytesFor(TOKEN, CHAT, "x".toByteArray())
        }
        logoutAndSignInB()

        runCatching { repo.getChats(TOKEN) }
        assertFalse("precondition: B owns the holder", repo.hasPendingPeerKeyChange(CHAT))

        auth.release.complete(Unit)
        job.join()

        val sealed = repo.encryptBytesFor(TOKEN, CHAT, "hello".toByteArray())
        assertEquals(
            "PROVEN BROKEN: account B sealed group traffic under the Sender Key " +
                "account A generated. B's own key is version 1; account A's is 99, " +
                "and the highest version always wins.",
            1, sealed?.keyVersion
        )
    }

    // ================= family 4: chat metadata =================

    @Test
    fun staleAcannotMutateBownedChatMetadata() = runBlocking {
        val job = parkAccountAInside("getKnownPublicKey") { repo.getChats(TOKEN) }
        logoutAndSignInB()

        // B legitimately learns this chat as a GROUP.
        serveChatList("group")
        runCatching { repo.getChats(TOKEN) }
        assertEquals("precondition: B owns this chat's metadata", "group", repo.chatTypeFor(CHAT))

        auth.release.complete(Unit)
        job.join()

        assertEquals(
            "PROVEN BROKEN: account A's stale chat list rewrote metadata that " +
                "account B owned - the chat flipped back to A's view of its type, " +
                "which decides which crypto path every later message takes.",
            "group", repo.chatTypeFor(CHAT)
        )
    }

    /**
     * A stale READ, after a suspension, must be refused - not answered from the
     * memory of whoever owns it now.
     *
     * decryptToBytes resolves the account's private key (suspending), then reads
     * chatOtherPub. Parking in that gap leaves account A reading a peer identity
     * that belongs to account B. The observable is whether A's decrypt proceeds
     * at all: with no peer identity of its own it must stop, so it never reaches
     * the fan-out that asks for this device's id.
     */
    @Test
    fun aStaleReadAfterSuspensionIsRefusedNotAnsweredFromBsMemory() = runBlocking {
        val payload = E2ECrypto.toHex("not-really-a-fanout".toByteArray())
        val job = parkAccountAInside("getE2EEPrivateKey") {
            repo.decryptFor(
                CHAT, payload, encrypted = true, senderId = "peer",
                keyVersion = 0, encryptionVersion = 4, chatType = "direct"
            )
        }
        logoutAndSignInB()

        runCatching { repo.getChats(TOKEN) }
        assertFalse("precondition: B owns the holder", repo.hasPendingPeerKeyChange(CHAT))

        auth.deviceIdCalls.clear()
        auth.release.complete(Unit)
        job.join()

        assertTrue(
            "PROVEN BROKEN: account A's stale read was answered with account B's " +
                "peer identity, so A carried on and tried to open the message " +
                "against B's identity (device-id lookups: ${auth.deviceIdCalls})",
            auth.deviceIdCalls.isEmpty()
        )
    }

    // ================= reader isolation (no logout in between) =================

    @Test
    fun theNonSuspendReadersRefuseAnotherAccountsState() = runBlocking {
        auth.pinAccountA = true
        auth.account = A
        runCatching { repo.getChats(TOKEN) }
        assertTrue("precondition: A recorded a pending peer key", repo.hasPendingPeerKeyChange(CHAT))

        auth.account = B      // a switch with no logout and no explicit clear

        assertFalse(
            "PROVEN BROKEN: account B sees account A's pending peer key. These " +
                "were plain getters, which is how B came to observe - and consume " +
                "- a warning raised in account A's session.",
            repo.hasPendingPeerKeyChange(CHAT)
        )
        assertFalse(
            "PROVEN BROKEN: account B consumed account A's security notice",
            repo.takePendingSecurityNotice(CHAT)
        )
    }

    /**
     * The notice reader on its own.
     *
     * Asking hasPendingPeerKeyChange first hides a broken notice reader: that
     * call heals, which wipes the notice before the second reader runs. Each
     * reader has to be the FIRST account-scoped call after the switch to be
     * tested at all.
     */
    @Test
    fun theSecurityNoticeReaderRefusesAnotherAccountsStateOnItsOwn() = runBlocking {
        auth.pinAccountA = true
        auth.account = A
        runCatching { repo.getChats(TOKEN) }
        assertTrue("precondition: A raised a security notice for this chat",
            repo.hasPendingPeerKeyChange(CHAT))

        auth.account = B

        assertFalse(
            "PROVEN BROKEN: account B consumed a peer-key-change warning raised " +
                "in account A's session - and consumed it, so account A never " +
                "sees its own warning again.",
            repo.takePendingSecurityNotice(CHAT)
        )
    }

    /**
     * M-V8's window: the account changes between reading the pending key and
     * pinning it. Pinning is the most trust-relevant act in this class, so a
     * stale one must be refused rather than applied for whoever is current.
     */
    @Test
    fun aStalePeerKeyAcceptanceCannotPinOnBownedMemory() = runBlocking {
        val job = parkAccountAInside("getPendingPublicKey") { repo.acceptPeerKeyChange(CHAT) }
        logoutAndSignInB()

        runCatching { repo.getChats(TOKEN) }
        // Deliberately NOT sealing here. A send would cache a ratchet session,
        // and loadRatchetV4's identity check passes vacuously when the peer key
        // is unparseable - so a later send would reuse that cached session and
        // succeed even if chatOtherPub had been overwritten, hiding the defect.
        assertFalse("precondition: B owns the holder and has no pending key",
            repo.hasPendingPeerKeyChange(CHAT))

        auth.release.complete(Unit)
        job.join()

        assertTrue(
            "PROVEN BROKEN: account A's stale acceptance pinned a peer identity " +
                "on memory account B owned. B's own identity for this chat was " +
                "replaced by the one account A was in the middle of accepting.",
            repo.encryptFor(CHAT, "hi").encrypted
        )
        assertFalse(
            "PROVEN BROKEN: account A's pending-key bookkeeping landed on B's memory",
            repo.hasPendingPeerKeyChange(CHAT)
        )
    }

    @Test
    fun accountBCannotAcceptAccountAsPendingPeerKey() = runBlocking {
        auth.pinAccountA = true
        auth.account = A
        runCatching { repo.getChats(TOKEN) }
        assertTrue("precondition: A has a pending peer key", repo.hasPendingPeerKeyChange(CHAT))

        auth.account = B
        repo.acceptPeerKeyChange(CHAT)

        assertFalse(
            "PROVEN BROKEN: account B pinned the peer identity account A had " +
                "pending. Accepting a key change is the most trust-relevant act " +
                "here and must never happen on another account's behalf.",
            repo.hasPendingPeerKeyChange(CHAT)
        )
    }

    /**
     * A stale READ must be refused, not answered from whoever owns memory now.
     * Handing B's peer identity to an operation belonging to A would seal A's
     * message under B's peer key.
     */
    @Test
    fun aStaleReadIsRefusedRatherThanAnsweredWithTheOtherAccountsData() = runBlocking {
        auth.account = B
        runCatching { repo.getChats(TOKEN) }
        assertTrue("precondition: B can seal with its own identity",
            repo.encryptFor(CHAT, "hi").encrypted)

        auth.account = A
        // A owns nothing here: its own pin is not usable, and B's must not be
        // substituted for it.
        assertFalse(
            "PROVEN BROKEN: account A sealed a message using account B's peer " +
                "identity. A read issued under one account must never be answered " +
                "from another account's memory.",
            repo.encryptFor(CHAT, "hi").encrypted
        )
    }
}
