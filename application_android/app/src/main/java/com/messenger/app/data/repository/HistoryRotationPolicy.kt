package com.messenger.app.data.repository

/**
 * The security events that rotate a chat's history root, and which chats each one touches.
 *
 * Deliberately NOT coupled to `Chat.KeyEpoch`. That epoch advances only on group membership changes
 * (never on device revocation or an E2EE reset), exists only for group chats, and its backend bump
 * is explicitly best-effort - "a missed epoch bump just means members redistribute their sender key
 * on the next send". Sender keys self-heal from a missed bump; a missed history rotation does not.
 * This trigger is therefore local, event-driven, and covers direct chats too.
 *
 * Pure and JVM-testable: it decides WHAT to rotate, never performs the rotation.
 */
sealed interface SecurityEvent {

    /**
     * Identity of this event for de-duplication.
     *
     * Two callbacks describing the same boundary share a key and rotate once. Two genuinely
     * different boundaries - removing Alice, then removing Bob - have different keys and rotate
     * twice, which is the behaviour that actually matters.
     */
    val dedupKey: String

    /** Members were added to a group; future messages must not use the pre-existing root. */
    data class GroupMembersAdded(val chatId: String, val memberIds: List<String>) : SecurityEvent {
        override val dedupKey: String = "add|$chatId|${memberIds.sorted().joinToString(",")}"
    }

    data class GroupMemberRemoved(val chatId: String, val memberId: String) : SecurityEvent {
        override val dedupKey: String = "remove|$chatId|$memberId"
    }

    data class GroupLeft(val chatId: String) : SecurityEvent {
        override val dedupKey: String = "leave|$chatId"
    }

    /**
     * The user accepted a changed peer identity key in a direct chat (`acceptPeerKeyChange`).
     *
     * This is the direct-chat equivalent of a membership change: from here on, a different key
     * decrypts. The fingerprint is part of the identity so accepting a second, different key later
     * is a distinct boundary.
     */
    data class DirectPeerIdentityAccepted(
        val chatId: String,
        val peerKeyFingerprint: String,
    ) : SecurityEvent {
        override val dedupKey: String = "peer|$chatId|$peerKeyFingerprint"
    }

    /**
     * One of this account's own devices was revoked.
     *
     * Affects every chat, because a revoked device may hold cached roots for any of them.
     */
    data class DeviceRevoked(val deviceId: String) : SecurityEvent {
        override val dedupKey: String = "revoke|$deviceId"
    }
}

/** Maps an event to the chats whose root must rotate. */
object HistoryRotationPolicy {

    /**
     * @param allChatIds every chat this device knows about locally. Only consulted for
     *   [SecurityEvent.DeviceRevoked]; every other event names its own chat, so a stale or empty
     *   chat list cannot cause a chat-scoped rotation to be missed.
     */
    fun affectedChats(event: SecurityEvent, allChatIds: Collection<String>): Set<String> =
        when (event) {
            is SecurityEvent.GroupMembersAdded -> setOfNotBlank(event.chatId)
            is SecurityEvent.GroupMemberRemoved -> setOfNotBlank(event.chatId)
            is SecurityEvent.GroupLeft -> setOfNotBlank(event.chatId)
            is SecurityEvent.DirectPeerIdentityAccepted -> setOfNotBlank(event.chatId)
            // A revoked device could hold roots for any chat, so all of them rotate.
            is SecurityEvent.DeviceRevoked -> allChatIds.filter { it.isNotBlank() }.toSet()
        }

    private fun setOfNotBlank(chatId: String): Set<String> =
        if (chatId.isBlank()) emptySet() else setOf(chatId)
}
