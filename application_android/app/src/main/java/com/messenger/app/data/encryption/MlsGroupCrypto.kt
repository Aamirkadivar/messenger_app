package com.messenger.app.data.encryption

import android.util.Log
import java.security.MessageDigest
import java.util.LinkedHashMap
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.mls.TreeKEM.LifeTime
import org.bouncycastle.mls.codec.Capabilities
import org.bouncycastle.mls.codec.Extension
import org.bouncycastle.mls.codec.KeyPackage
import org.bouncycastle.mls.codec.MLSInputStream
import org.bouncycastle.mls.codec.MLSMessage
import org.bouncycastle.mls.codec.MLSOutputStream
import org.bouncycastle.mls.crypto.MlsCipherSuite
import org.bouncycastle.mls.TreeKEM.LeafIndex
import org.bouncycastle.mls.TreeKEM.LeafNode
import org.bouncycastle.mls.codec.Credential
import org.bouncycastle.mls.protocol.Group

/**
 * MLS (RFC 9420) group messaging, backed by BouncyCastle's implementation.
 *
 * This is a thin wrapper over `org.bouncycastle.mls` — TreeKEM, the key
 * schedule, and every cryptographic operation come from the library. Nothing
 * here is hand-rolled; that is the whole point of choosing MLS via a maintained
 * implementation rather than writing one (see docs/e2ee-protocol-v4.md for what
 * hand-rolling cost us on direct messages).
 *
 * What MLS buys over the existing Sender Keys scheme:
 *  - post-compromise security: the group heals after a member is compromised
 *  - O(log n) rekeying via TreeKEM instead of O(n) pairwise redistribution
 *  - forward secrecy per epoch, enforced by the key schedule
 *
 * Server role: the backend is an untrusted Delivery Service. It stores
 * KeyPackages and relays Commit/Welcome/application messages as opaque bytes.
 * It never runs TreeKEM and never sees group secrets.
 */
object MlsGroupCrypto {
    private const val TAG = "MlsGroupCrypto"

    /**
     * X25519 + AES-128-GCM + SHA-256 + Ed25519. The MLS mandatory-to-implement
     * suite, so every RFC 9420 implementation (BouncyCastle here, mlspp on
     * Windows) interoperates on it. Do not change without a migration plan:
     * the suite is baked into a group at creation.
     */
    const val SUITE_ID: Short = MlsCipherSuite.MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519

    fun suite(): MlsCipherSuite = MlsCipherSuite.getSuite(SUITE_ID)

    /** A member's long-lived MLS identity for one group membership. */
    class Identity(
        val suite: MlsCipherSuite,
        /** HPKE keypair for the leaf's encryption key. */
        val leafKeyPair: AsymmetricCipherKeyPair,
        /** Signature keypair authenticating this member. */
        val sigKeyPair: AsymmetricCipherKeyPair,
        val credential: ByteArray
    ) {
        val sigPrivateBytes: ByteArray get() = suite.serializeSignaturePrivateKey(sigKeyPair.private)

        fun leafNode(): LeafNode = LeafNode(
            suite,
            suite.hpke.serializePublicKey(leafKeyPair.public),
            suite.serializeSignaturePublicKey(sigKeyPair.public),
            Credential.forBasic(credential),
            Capabilities(),
            LifeTime(),
            ArrayList<Extension>(),
            sigPrivateBytes
        )
    }

    /** Creates a fresh MLS identity for [userId] (one per device per group). */
    fun newIdentity(userId: String): Identity {
        val s = suite()
        return Identity(
            suite = s,
            leafKeyPair = s.hpke.generatePrivateKey(),
            sigKeyPair = s.generateSignatureKeyPair(),
            credential = userId.toByteArray(Charsets.UTF_8)
        )
    }

    // ---- Persistence ----
    //
    // Neither BouncyCastle nor mlspp can serialize a live MLS group: `Group`
    // has no writeTo/Serializable, and mlspp's `State` is not TLS-serializable
    // either. So a restart cannot simply reload group state — it has to be
    // REBUILT. What *is* serializable is the material a rebuild needs: our
    // identity keys, the KeyPackage we published (with its init private key),
    // and the Welcome that admitted us. Replaying those plus the commits the
    // Delivery Service already stores reconstructs the same group.

    /** Serializes an identity's private material. Never leaves the device. */
    fun encodeIdentity(id: Identity): String {
        val o = org.json.JSONObject()
        o.put("v", 1)
        o.put("sig_priv", E2ECrypto.toHex(id.sigPrivateBytes))
        o.put("leaf_priv", E2ECrypto.toHex(id.suite.hpke.serializePrivateKey(id.leafKeyPair.private)))
        o.put("leaf_pub", E2ECrypto.toHex(id.suite.hpke.serializePublicKey(id.leafKeyPair.public)))
        o.put("cred", E2ECrypto.toHex(id.credential))
        return o.toString()
    }

    fun decodeIdentity(json: String): Identity? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optInt("v") != 1) return null
        val s = suite()
        val leafPriv = E2ECrypto.fromHex(o.optString("leaf_priv")) ?: return null
        val leafPub = E2ECrypto.fromHex(o.optString("leaf_pub")) ?: return null
        val sigPriv = E2ECrypto.fromHex(o.optString("sig_priv")) ?: return null
        Identity(
            suite = s,
            leafKeyPair = s.hpke.deserializePrivateKey(leafPriv, leafPub),
            sigKeyPair = s.deserializeSignaturePrivateKey(sigPriv),
            credential = E2ECrypto.fromHex(o.optString("cred")) ?: ByteArray(0)
        )
    }.getOrNull()

    /** Serializes a published KeyPackage plus the init key that opens its Welcome. */
    fun encodePublishedKeyPackage(p: PublishedKeyPackage): String {
        val o = org.json.JSONObject()
        o.put("v", 1)
        o.put("kp", E2ECrypto.toHex(encodeKeyPackage(p.keyPackage)))
        o.put("init_priv", E2ECrypto.toHex(p.suite.hpke.serializePrivateKey(p.initKeyPair.private)))
        o.put("init_pub", E2ECrypto.toHex(p.suite.hpke.serializePublicKey(p.initKeyPair.public)))
        return o.toString()
    }

    fun decodePublishedKeyPackage(json: String): PublishedKeyPackage? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optInt("v") != 1) return null
        val s = suite()
        val kp = decodeKeyPackage(E2ECrypto.fromHex(o.optString("kp")) ?: return null)
        val initPriv = E2ECrypto.fromHex(o.optString("init_priv")) ?: return null
        val initPub = E2ECrypto.fromHex(o.optString("init_pub")) ?: return null
        PublishedKeyPackage(kp, s.hpke.deserializePrivateKey(initPriv, initPub), s)
    }.getOrNull()

    /**
     * A published KeyPackage plus the init private key needed to open a Welcome
     * addressed to it. Only [keyPackage] leaves the device; [initPrivate] stays
     * local (it is what decrypts the group secrets on join).
     */
    class PublishedKeyPackage(
        val keyPackage: KeyPackage,
        val initKeyPair: AsymmetricCipherKeyPair,
        val suite: MlsCipherSuite
    ) {
        val initPrivate: ByteArray get() = suite.hpke.serializePrivateKey(initKeyPair.private)
    }

    /**
     * Builds a KeyPackage this member publishes so others can add them to a
     * group. Uploaded to the Delivery Service as opaque bytes; it contains only
     * public material.
     *
     * The init key must be distinct from the leaf key: the Welcome's group
     * secrets are HPKE-sealed to the init key, and the caller must keep the
     * private half to join.
     */
    fun newKeyPackage(id: Identity): PublishedKeyPackage {
        val initKeyPair = id.suite.hpke.generatePrivateKey()
        val leaf = id.leafNode()
        val kp = KeyPackage(
            id.suite,
            id.suite.hpke.serializePublicKey(initKeyPair.public),
            leaf,
            ArrayList<Extension>(),
            id.sigPrivateBytes
        )
        return PublishedKeyPackage(kp, initKeyPair, id.suite)
    }

    /** Creates a new group with this member as the only participant. */
    fun createGroup(groupId: ByteArray, id: Identity): Group {
        val leaf = id.leafNode()
        return Group(
            groupId,
            id.suite,
            id.leafKeyPair,
            id.sigPrivateBytes,
            leaf.copy(leaf.encryptionKey),
            ArrayList<Extension>()
        )
    }

    /**
     * Adds [keyPackage] to [group]: proposes the add, then commits it.
     * Returns the new group state plus the commit and welcome messages the
     * caller must relay (commit → existing members, welcome → the joiner).
     */
    class AddResult(val group: Group, val commit: ByteArray, val welcome: ByteArray)

    /**
     * Proposes [keyPackage] and commits it.
     *
     * BC returns the commit as `GroupWithMessage.message` and attaches the
     * Welcome for members added in that commit to `message.welcome` (a public
     * field, not a return value). Serializing the message with the welcome
     * still attached would hand every existing member the joiner's secrets, so
     * the welcome is split out and the commit copy is cleared — the Delivery
     * Service relays the commit to members and the welcome only to the joiner.
     *
     * `inlineTree` is required: the joiner has no other way to learn the
     * ratchet tree, and our Delivery Service does not serve one.
     */
    fun addMember(group: Group, keyPackage: KeyPackage): AddResult {
        val opts = Group.MessageOptions()
        val addProposal = group.add(keyPackage, opts)
        // Cache the proposal so the commit below includes it.
        group.handle(encode(addProposal), null)

        // Always emit an UpdatePath. BouncyCastle rejects a path-less Add-only
        // commit ("Path required but not present"), which left the joiner at
        // the previous epoch while we encrypted at the new one.
        val commitOpts = Group.CommitOptions(
            ArrayList(), /* inlineTree = */ true, /* forcePath = */ true, null
        )
        val committed = group.commit(
            org.bouncycastle.mls.crypto.Secret(randomBytes(32)),
            commitOpts,
            opts,
            // Must not be null: BC dereferences paramID. A member-initiated
            // add/remove is a NORMAL commit (as opposed to external/restart).
            Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS)
        )

        val commitMsg = committed.message
        val welcomeMsg = commitMsg.welcome
            ?: throw IllegalStateException("commit produced no welcome for the added member")

        // Wrap the welcome as its own MLSMessage for the joiner.
        val welcomeWire = MLSMessage(org.bouncycastle.mls.codec.WireFormat.mls_welcome)
        welcomeWire.welcome = welcomeMsg
        val welcomeBytes = encode(welcomeWire)

        // Never ship the welcome to existing members.
        commitMsg.welcome = null
        return AddResult(committed.group, encode(commitMsg), welcomeBytes)
    }

    /**
     * Joins a group from a Welcome relayed by the Delivery Service.
     * [published] must be the same KeyPackage that was added — its init private
     * key is what opens the group secrets.
     */
    fun joinFromWelcome(published: PublishedKeyPackage, id: Identity, welcomeBytes: ByteArray): Group {
        val msg = decode(welcomeBytes)
        val welcome = msg.welcome
            ?: throw IllegalArgumentException("payload is not an MLS welcome")
        return Group(
            published.initPrivate,
            id.leafKeyPair,
            id.sigPrivateBytes,
            published.keyPackage,
            welcome,
            null, // ratchet tree travels inline in the welcome (inlineTree=true)
            HashMap(),
            HashMap()
        )
    }

    /**
     * Applies a commit relayed from another member.
     * Null means the commit did not apply — callers must stop handshake
     * replay rather than skip the epoch (a gap forks the group).
     */
    fun applyCommit(group: Group, commitBytes: ByteArray): Group? {
        return try {
            group.handle(commitBytes, null).also {
                if (it == null) Log.w(TAG, "applyCommit: handle returned null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyCommit failed: ${e.message}")
            null
        }
    }

    /**
     * Publishes the current GroupInfo so a member who lost local state can
     * rejoin by external commit. `inlineTree = true` embeds the ratchet tree,
     * which the rejoiner needs and our Delivery Service does not serve
     * separately.
     */
    fun exportGroupInfo(group: Group): ByteArray = encode(group.getGroupInfo(true))

    /**
     * Rejoins a group we can no longer rebuild locally (RFC 9420 §12.4.3.2).
     *
     * This is the recovery path for a group we *created*: no Welcome is ever
     * addressed to the creator, and neither BouncyCastle nor mlspp can
     * serialize group state, so replay cannot reconstruct it. An external
     * commit re-adds this device using only the public GroupInfo.
     *
     * It is not free: the returned commit advances the group an epoch and must
     * be accepted by the Delivery Service, so it can lose an epoch race and
     * need retrying against fresher GroupInfo. It also cannot recover messages
     * from before the rejoin — those keys are gone with the old state.
     */
    class ExternalJoinResult(val group: Group, val commit: ByteArray)

    fun externalJoin(
        published: PublishedKeyPackage,
        id: Identity,
        groupInfoBytes: ByteArray
    ): ExternalJoinResult {
        val msg = decode(groupInfoBytes)
        val groupInfo = msg.groupInfo
            ?: throw IllegalArgumentException("payload is not an MLS GroupInfo")
        val joined = Group.externalJoin(
            org.bouncycastle.mls.crypto.Secret(randomBytes(32)),
            id.sigKeyPair,
            published.keyPackage,
            groupInfo,
            null, // ratchet tree is inline in the GroupInfo
            Group.MessageOptions(),
            null, // no prior appearance to evict
            HashMap()
        )
        return ExternalJoinResult(joined.group, encode(joined.message))
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }


    /**
     * Credential strings of every current leaf ("userId|deviceId").
     *
     * The ratchet tree is the only trustworthy answer to "is this device
     * already a member?". A locally persisted "invited" set outlives server
     * resets and re-joins, so a device that legitimately needs adding again
     * looks already-invited and waits for a Welcome forever.
     *
     * BouncyCastle leaves LeafNode.credential package-private with no getter,
     * so the field is read reflectively. On any failure this returns an empty
     * list, which makes the caller fall back to inviting — a duplicate add
     * costs an epoch, while a missed add deadlocks the device.
     */
    fun memberIdentities(group: Group): List<String> = runCatching {
        val tree = group.tree
        val leafCount = tree.size.leafCount().toInt()
        val credField = LeafNode::class.java.getDeclaredField("credential")
            .apply { isAccessible = true }
        (0 until leafCount).mapNotNull { i ->
            val leaf = tree.getLeafNode(LeafIndex(i)) ?: return@mapNotNull null
            val cred = credField.get(leaf) as? Credential ?: return@mapNotNull null
            String(cred.identity, Charsets.UTF_8)
        }
    }.getOrElse {
        android.util.Log.w("MlsGroupCrypto", "roster read failed: ${it.message}")
        emptyList()
    }

    /**
     * MLS application keys are single-use: [Group.unprotect] deletes the
     * generation it just opened, and a sender cannot reopen its own ciphertext
     * at all. The UI decrypts the same blob several times (store, paint,
     * chat-list preview), so without a cache every MLS bubble becomes
     * "Encrypted message" after the first successful open — including messages
     * this account sent from another device.
     */
    private const val OPEN_CACHE_LIMIT = 256
    private val openCacheLock = Any()
    private val openCache = object : LinkedHashMap<String, ByteArray>(OPEN_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > OPEN_CACHE_LIMIT
    }

    private fun openCacheKey(payload: ByteArray): String =
        E2ECrypto.toHex(MessageDigest.getInstance("SHA-256").digest(payload))

    private fun rememberOpen(ciphertext: ByteArray, plaintext: ByteArray) {
        synchronized(openCacheLock) { openCache[openCacheKey(ciphertext)] = plaintext }
    }

    private fun lookupOpen(ciphertext: ByteArray): ByteArray? =
        synchronized(openCacheLock) { openCache[openCacheKey(ciphertext)] }

    /** Encrypts an application message for the group. */
    fun protect(group: Group, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val sealed = encode(group.protect(aad, plaintext, 0))
        rememberOpen(sealed, plaintext)
        return sealed
    }

    /** Decrypts an application message. Returns null if it does not open. */
    fun unprotect(group: Group, payload: ByteArray): ByteArray? {
        lookupOpen(payload)?.let { return it }
        return try {
            val msg = decode(payload)
            group.unprotect(msg)?.getOrNull(1)?.also { rememberOpen(payload, it) }
        } catch (e: Exception) {
            Log.w(TAG, "unprotect failed: ${e.message}")
            null
        }
    }

    fun encode(o: MLSOutputStream.Writable): ByteArray = MLSOutputStream.encode(o)

    fun decode(b: ByteArray): MLSMessage =
        MLSInputStream.decode(b, MLSMessage::class.java) as MLSMessage

    fun encodeKeyPackage(kp: KeyPackage): ByteArray = MLSOutputStream.encode(kp)

    fun decodeKeyPackage(b: ByteArray): KeyPackage =
        MLSInputStream.decode(b, KeyPackage::class.java) as KeyPackage
}
