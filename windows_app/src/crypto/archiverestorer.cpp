#include "archiverestorer.h"
#include "decryptedmessagearchiver.h"

bool ArchiveRestorer::needsRestore(bool encrypted, bool hasFile, const QString& display) {
    // Only an encrypted, non-media message whose normal decryption produced the failure placeholder.
    // The placeholder string itself is owned by DecryptedMessageArchiver so the write gate and the
    // read gate cannot come to disagree about what "failed" looks like.
    if (!encrypted) return false;
    if (hasFile) return false;
    return display == DecryptedMessageArchiver::failedDecryptPlaceholder();
}

ArchiveRestorer::ArchiveRestorer(EnabledFn enabled,
                                 OwnerFn owner,
                                 ArchiverFn archiver,
                                 RepositoryFn repository)
    : m_enabled(std::move(enabled)),
      m_owner(std::move(owner)),
      m_archiver(std::move(archiver)),
      m_repository(std::move(repository)) {}

QHash<QString, QString> ArchiveRestorer::restore(const QString& chatId,
                                                 const QStringList& messageIds,
                                                 Outcome* outcome) {
    Outcome local;
    Outcome& out = outcome ? *outcome : local;
    out.requested = messageIds.size();

    QHash<QString, QString> recovered;
    if (!m_enabled || !m_enabled()) return recovered;
    if (messageIds.isEmpty() || chatId.isEmpty()) return recovered;
    if (!m_owner || !m_archiver || !m_repository) return recovered;
    if (m_owner().isEmpty()) return recovered;

    // What is still outstanding. Recovering everything asked for ends the walk early, so a chat
    // with a long archive history costs one page when only one message was lost.
    QSet<QString> wanted;
    for (const QString& id : messageIds) wanted.insert(id);

    HistoryArchiver archiver = m_archiver();
    const ArchiveRepository repository = m_repository();

    QString since;
    QString sinceId;
    out.lookupAttempted = true;

    for (int page = 0; page < MAX_PAGES; ++page) {
        const ArchiveRepository::ListResult listed = repository.list(chatId, since, sinceId);
        ++out.pages;

        if (listed.outcome != ArchiveRepository::Outcome::Ok) {
            // Missing, unauthorised, malformed, or unreachable. Whatever was recovered so far
            // stands; the rest keep their placeholder and the walk is explicitly incomplete.
            out.lookupFailed = true;
            return recovered;
        }

        for (const ArchiveRecord& r : listed.records) {
            if (!wanted.contains(r.messageId)) continue;   // exact id only, never a near match
            if (r.chatId != chatId) continue;              // filed under another chat
            if (recovered.contains(r.messageId)) continue; // a duplicate row cannot overwrite

            QByteArray plain;
            if (!archiver.open(r, plain, nullptr)) continue;

            recovered.insert(r.messageId, QString::fromUtf8(plain));
            wanted.remove(r.messageId);
            ++out.restored;
        }

        // A short page means the server had nothing more to give. Computed before the early exit
        // below so that stopping early still reports the traversal truthfully.
        const bool serverExhausted = listed.records.size() < ArchiveRepository::PAGE_SIZE;

        // Everything asked for is in hand; no reason to keep walking. This is a complete RESTORE
        // (completeForRequest()), but it is only a complete TRAVERSAL if this page was also short -
        // otherwise pages we never requested may still hold archives, and claiming otherwise would
        // mislead any later consumer into skipping them.
        if (wanted.isEmpty()) {
            out.walkComplete = serverExhausted;
            return recovered;
        }

        if (serverExhausted) {
            out.walkComplete = true;
            return recovered;
        }

        // A full page with no usable cursor cannot be continued. Stopping is right; claiming the
        // walk finished would not be.
        if (listed.nextSince.isEmpty() || listed.nextSinceId.isEmpty()) return recovered;

        // The server did not move. Refuse to ask for the same page forever.
        if (listed.nextSince == since && listed.nextSinceId == sinceId) return recovered;

        since = listed.nextSince;
        sinceId = listed.nextSinceId;
    }

    // Page bound reached: incomplete, never silently truncated.
    return recovered;
}
