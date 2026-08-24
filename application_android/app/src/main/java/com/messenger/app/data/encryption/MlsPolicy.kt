package com.messenger.app.data.encryption

/**
 * Deterministic MLS membership rules shared by [com.messenger.app.data.repository.MlsRepository].
 *
 * These are pure functions so the "who creates / who adds / when may we send"
 * decisions can be tested without BouncyCastle or a device. The Delivery
 * Service still cannot see the ratchet tree; clients apply these rules to
 * the roster they read locally plus the coverage the DS *can* report
 * (which devices have acked a Welcome).
 */
object MlsPolicy {

    /**
     * Creator / adder election: lowest UTF-8 `userId|deviceId` among [keys].
     * An empty candidate list fails open (better one extra 409 than a group
     * nobody creates).
     */
    fun isLowest(meKey: String, keys: List<String>): Boolean {
        if (meKey.isBlank()) return false
        val eligible = keys.filter { it.isNotBlank() }
        if (eligible.isEmpty()) return true
        return eligible.minOrNull() == meKey
    }

    /**
     * Only a current leaf may add, and only the lowest-ordered current leaf
     * does so. Everyone inviting at once is what forked epochs (two Adds
     * against the same epoch, one 409, the loser keeping a private tree).
     */
    fun isElectedAdder(meCred: String, roster: List<String>): Boolean {
        if (meCred.isBlank() || roster.isEmpty()) return false
        if (meCred !in roster) return false
        return roster.minOrNull() == meCred
    }

    /**
     * Leaves whose device the Delivery Service reports as having an OUTSTANDING
     * invitation, i.e. the phantoms: present in the tree, but not actually in
     * the group.
     *
     * The two halves of that judgement live in different places and neither is
     * sufficient alone. The DS holds the invitation ledger and decides what
     * "outstanding" means - the device's NEWEST Welcome is unconsumed AND the
     * group has already moved past it, so it is neither superseded by a later
     * join nor merely in flight. But the DS never parses a commit, so it cannot
     * see removals and cannot tell a current member from a removed one. Only
     * [roster] knows that. A phantom is the disagreement.
     *
     * Positive evidence only: an empty [pendingDeviceIds] returns nobody.
     * Absence of information must never justify evicting a member - and the
     * caller must treat coverage it could not fetch the same way, because a
     * device evicted here is re-invited, and a predicate that misfires on a
     * genuine member does not misfire once. It loops.
     */
    fun phantomMembers(
        roster: List<String>,
        meDev: String,
        pendingDeviceIds: Set<String>
    ): List<String> {
        val pending = pendingDeviceIds.filter { it.isNotBlank() && it != meDev }.toSet()
        if (pending.isEmpty()) return emptyList()
        // Credentials are "userId|deviceId"; the DS reports device ids, so the
        // device half is what identifies a leaf here.
        return roster.filter { cred ->
            val device = cred.substringAfter('|', "")
            device.isNotBlank() && device != meDev && device in pending
        }
    }

    /**
     * A Welcome can replace local state only when that state is demonstrably
     * older than the invitation that would replace it. Equality is important:
     * a current-epoch Welcome may simply be a successful join whose ack was
     * interrupted, and opening it again would consume its single-use
     * KeyPackage before OpenMLS reports that the group already exists.
     *
     * The repository establishes the same chat and active DS GID before
     * calling this predicate. Unknown epochs never authorize destruction.
     */
    fun shouldReplaceLocalGroupForWelcome(localEpoch: Long?, welcomeEpoch: Long): Boolean =
        localEpoch != null && localEpoch >= 0L && welcomeEpoch > localEpoch

    /** Live devices that cannot yet unprotect, excluding this install. */
    fun missingLiveDevices(
        meDev: String,
        liveDeviceIds: List<String>,
        acked: Set<String>
    ): List<String> = liveDeviceIds.filter { it.isNotBlank() && it != meDev && it !in acked }

    /**
     * Send MLS only when this device holds group state at the server's epoch
     * AND every other live device has acked a Welcome. Local `hasGroup` is
     * not enough: the adder's tree already contains a leaf for someone who
     * has not processed their Welcome yet, and encrypting then is ciphertext
     * they will never open.
     */
    fun readyToSend(
        hasGroup: Boolean,
        meDev: String,
        liveDeviceIds: List<String>,
        acked: Set<String>,
        localEpoch: Long,
        serverEpoch: Long
    ): Boolean {
        if (!hasGroup) return false
        if (localEpoch < 0L || localEpoch != serverEpoch) return false
        val live = liveDeviceIds.filter { it.isNotBlank() }
        if (live.isEmpty()) return false
        return missingLiveDevices(meDev, live, acked).isEmpty()
    }
}
