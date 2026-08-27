package com.messenger.app.data.encryption.history

/**
 * Union of two keyrings, for recovery import.
 *
 * A recovered keyring is ADDITIVE. It may contribute entries this device does
 * not have; it may never delete, replace, or downgrade one it does. The keyring
 * has always been append-only and identified by `(chatId, rootVersion)`, and
 * recovery does not get to change that.
 *
 * The conflict rule is the security-relevant part. Two entries sharing
 * `(chatId, rootVersion)` but carrying DIFFERENT root material cannot both be
 * right: one of them cannot open the archives recorded against that version. That
 * is a real inconsistency - a tampered blob, a restored-from-elsewhere keyring,
 * or a genuine bug - and "last write wins" would silently pick one and orphan the
 * archives sealed under the other. So it fails closed instead.
 */
object HistoryKeyringMerge {

    /**
     * @param local this device's authoritative keyring; always wins on identity.
     * @param remote the recovered keyring.
     */
    fun union(local: HistoryKeyring, remote: HistoryKeyring): Result<HistoryKeyring> {
        val byIdentity = LinkedHashMap<Pair<String, Int>, HistoryRootEntry>()
        for (e in local.entries) {
            byIdentity[e.chatId to e.rootVersion] = e
        }

        for (e in remote.entries) {
            val key = e.chatId to e.rootVersion
            val existing = byIdentity[key]
            if (existing == null) {
                byIdentity[key] = e
                continue
            }
            // Identical material is simply the same entry seen twice - the normal
            // case when a device uploads, then later recovers its own keyring.
            if (existing.root.contentEquals(e.root)) continue

            // Different material under one identity. Refuse rather than choose.
            return Result.failure(
                IllegalStateException(
                    "history keyring conflict: two different roots claim version " +
                        "${e.rootVersion} for the same chat; refusing to choose between them"
                )
            )
        }

        return HistoryKeyring.of(byIdentity.values.toList())
    }
}
