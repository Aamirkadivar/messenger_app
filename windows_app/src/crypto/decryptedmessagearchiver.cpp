#include "decryptedmessagearchiver.h"

QString DecryptedMessageArchiver::failedDecryptPlaceholder() {
    // The exact bytes ChatService::decryptMessage returns when it cannot open a message:
    // U+1F512 LOCK, a space, then "Encrypted message".
    return QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message");
}

bool DecryptedMessageArchiver::isArchivableDecryption(bool encrypted,
                                                      bool hasFile,
                                                      const QString& display) {
    // Same four conditions ChatService uses to decide whether `display` may be cached as plaintext.
    //
    //  encrypted   - a cleartext message has no archive value and no ciphertext to have opened
    //  !hasFile    - media bodies live at file_url; `display` is empty for them. Text only.
    //  !isEmpty    - nothing was opened
    //  != placeholder - decryption FAILED and this is the failure string, not the user's message
    if (!encrypted) return false;
    if (hasFile) return false;
    if (display.isEmpty()) return false;
    if (display == failedDecryptPlaceholder()) return false;
    return true;
}

DecryptedMessageArchiver::DecryptedMessageArchiver(EnabledFn enabled,
                                                   OwnerFn owner,
                                                   ArchiverFn archiver,
                                                   RepositoryFn repository)
    : m_enabled(std::move(enabled)),
      m_owner(std::move(owner)),
      m_archiver(std::move(archiver)),
      m_repository(std::move(repository)) {}

DecryptedMessageArchiver::Result DecryptedMessageArchiver::archive(const QString& chatId,
                                                                   const QString& messageId,
                                                                   bool encrypted,
                                                                   bool hasFile,
                                                                   const QString& display) {
    // The switch is checked before anything else touches the keyring or the cipher: with archiving
    // off, no root is minted, no rotation happens, and no AEAD call is made.
    if (!m_enabled || !m_enabled()) return Result::Skipped;
    if (!isArchivableDecryption(encrypted, hasFile, display)) return Result::Skipped;
    if (chatId.isEmpty() || messageId.isEmpty()) return Result::Skipped;
    if (!m_owner || !m_archiver || !m_repository) return Result::Skipped;

    const QString owner = m_owner();
    if (owner.isEmpty()) return Result::Skipped;

    ArchiveMessage msg;
    msg.userId = owner;
    msg.chatId = chatId;
    msg.messageId = messageId;
    // The plaintext the caller already holds, converted once. No trimming, no normalisation, no
    // re-parse: whatever decryptMessage produced is what is sealed.
    msg.plaintext = display.toUtf8();

    HistoryArchiver archiver = m_archiver();
    ArchiveRecord record;
    // A locked vault with no root for this chat fails here, mints nothing, and is simply not an
    // archive - never an error the message flow has to care about.
    if (!archiver.seal(msg, record, nullptr)) return Result::Failed;

    const ArchiveRepository::SaveResult saved = m_repository().save(record);
    if (saved.outcome != ArchiveRepository::Outcome::Ok) return Result::Failed;

    // The server's identity is (message_id, user_id) with ON CONFLICT DO NOTHING, so re-processing
    // the same message is already idempotent server-side. There is deliberately no local
    // "already archived" table to disagree with it.
    return saved.stored ? Result::Stored : Result::AlreadyPresent;
}
