package com.messenger.app.data.repository

import com.messenger.app.data.model.ArchiveDto
import java.util.Base64

/**
 * The gate every server-supplied archive must pass before it can reach Room.
 *
 * Pure and free of Android dependencies on purpose: this is the security-relevant
 * half of [ArchiveSync], and keeping it separable is what lets it be tested
 * directly rather than through a mocked Retrofit interface.
 *
 * Nothing here trusts the server. In particular the record's own `chat_id` is
 * checked against the chat actually being synced, so a wrong or hostile server
 * cannot bind an archive to a different conversation than the one the client
 * asked about.
 *
 * Passing this gate means the record is WELL-FORMED. It does not mean the
 * ciphertext is genuine - only opening it under the right root and context can
 * establish that, and that happens later in MessageArchiver.open.
 */
object ArchiveValidation {

    fun validate(dto: ArchiveDto, expectedChatId: String): RemoteArchive? {
        if (dto.messageId.isBlank() || dto.chatId.isBlank()) return null
        if (dto.chatId != expectedChatId) return null
        // Root and protocol versions start at 1. Version 0 is the local encoding
        // of "no archive", so the server may never assert it.
        if (dto.rootVersion < 1 || dto.protocolVersion < 1) return null
        if (dto.ciphertextB64.isBlank()) return null
        val decoded = runCatching { Base64.getDecoder().decode(dto.ciphertextB64) }.getOrNull()
        if (decoded == null || decoded.isEmpty()) return null

        return RemoteArchive(
            messageId = dto.messageId,
            chatId = dto.chatId,
            rootVersion = dto.rootVersion,
            protocolVersion = dto.protocolVersion,
            ciphertextB64 = dto.ciphertextB64,
            createdAt = dto.createdAt,
        )
    }
}
