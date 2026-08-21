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
