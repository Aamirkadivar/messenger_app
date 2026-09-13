#ifndef ARCHIVERESTORER_H
#define ARCHIVERESTORER_H

#include "archiverepository.h"
#include "historyarchiver.h"

#include <QHash>
#include <QSet>
#include <QString>
#include <QStringList>
#include <functional>

/**
 * The read side of Layer B: recovering a message's plaintext from its archive when normal
 * decryption cannot produce it.
 *
 * WHY THIS EXISTS. An MLS sender-ratchet secret is consumed by the first successful open. A message
 * that was cached as ciphertext - because the first open failed, or because it predates the fix that
 * stores plaintext - can therefore never be reopened from its own ciphertext again. It renders as
 * ChatService's "\xF0\x9F\x94\x92 Encrypted message" placeholder forever. The archive is the only
 * second copy, and it is keyed independently of MLS, so it is the only thing that can still recover
 * that plaintext.
 *
 * STRICTLY A FALLBACK. It is asked only about messages whose normal decryption already produced the
 * placeholder. A successful normal decrypt is authoritative and is never revisited, never compared
 * against the archive, and never replaced.
 *
 * NO SECOND DECRYPTION. This class calls no MLS function, no sender-key function, no ratchet, and
 * never re-enters ChatService::decryptMessage. It consumes archive ciphertext and a history root
 * only - both independent of MLS state - so recovering a message costs no ratchet material and
 * cannot fail a later open.
 *
 * PAGINATION. Retrieval walks the server's existing keyset cursor: each page returns at most
 * PAGE_SIZE rows ordered by (created_at, message_id), and the next request carries the last row's
 * pair as `since` / `since_id`. Both components always - a timestamp-only cursor silently skips
 * rows sharing a timestamp across a page boundary, which is data loss, not inefficiency.
 *
 * The walk is bounded four ways, because a restore must never become an unbounded request loop:
 * a short page ends it, a missing cursor ends it, a cursor that did not advance ends it, and
 * MAX_PAGES ends it regardless. Only the short page counts as having seen everything; the other
 * three report an incomplete walk rather than pretending the remaining messages have no archive.
 *
 * It also stops as soon as every requested message has been recovered - a chat with many archives
 * and one lost message costs one page, not the whole history.
 */
class ArchiveRestorer {
public:
    using RepositoryFn = std::function<ArchiveRepository()>;
    using ArchiverFn = std::function<HistoryArchiver()>;
    using OwnerFn = std::function<QString()>;
    using EnabledFn = std::function<bool()>;

    /**
     * Whether archive-backed restore runs on this build.
     *
     * OFF, matching the posture DecryptedMessageArchiver takes for the write side. Turning restore
     * on before archives exist would add a per-chat-open request that can only ever come back empty.
     * These two switches are independent on purpose: writing and reading are separate decisions, and
     * a build may reasonably archive for a while before it starts reading back.
     */
    static constexpr bool DEFAULT_ENABLED = false;

    /**
     * Whether this message is a candidate for archive recovery.
     *
     * True only for an encrypted, non-media message whose normal decryption produced the failure
     * placeholder. Anything that decrypted successfully is excluded here, which is what makes the
     * archive a fallback rather than a second opinion.
     */
    static bool needsRestore(bool encrypted, bool hasFile, const QString& display);

    /**
     * Safety stop so a misbehaving or looping server cannot spin forever. Reaching it is NOT a
     * completed walk - the same posture Android's ArchiveSync takes.
     */
    static constexpr int MAX_PAGES = 10000;

    struct Outcome {
        int requested = 0;        ///< Messages that could not be decrypted normally.
        int restored = 0;         ///< Of those, how many the archive recovered.
        bool lookupAttempted = false; ///< Whether the repository was contacted at all.
        bool lookupFailed = false;    ///< Retrieval failed; the messages keep their placeholder.
        int pages = 0;            ///< Pages actually requested.
        /**
         * The server sequence was exhausted: the last page fetched was short, so every archive in
         * this chat was seen.
         *
         * This is NOT "I found everything I was asked for" - the two are independent, and conflating
         * them is how a later sync would wrongly conclude it may stop paging. A walk that recovers
         * every requested message on a FULL first page leaves this false, because pages it never
         * requested may still hold archives.
         *
         * False after a transport failure, an unusable cursor, a stalled cursor, or the page bound.
         * False is not "nothing found" - it is "draw no conclusion about what was not found".
         */
        bool walkComplete = false;

        /**
         * Every message the caller asked about was recovered.
         *
         * Derived, not stored: this is the question a restore caller actually has, and it is
         * answerable regardless of how much of the chat was traversed. A short walk that found all
         * of them is a complete restore; a full traversal that found none is not.
         */
        bool completeForRequest() const { return requested > 0 && restored == requested; }
    };

    ArchiveRestorer(EnabledFn enabled, OwnerFn owner, ArchiverFn archiver, RepositoryFn repository);

    /**
     * Recovers what it can for `messageIds` in `chatId`.
     *
     * Returns messageId -> recovered plaintext, containing ONLY messages that opened successfully.
     * A missing archive, a tampered one, a wrong account, or an unreachable backend simply yields no
     * entry for that id: nothing is fabricated, and the caller keeps its existing placeholder.
     * Never throws.
     */
    QHash<QString, QString> restore(const QString& chatId,
                                    const QStringList& messageIds,
                                    Outcome* outcome = nullptr);

private:
    EnabledFn m_enabled;
    OwnerFn m_owner;
    ArchiverFn m_archiver;
    RepositoryFn m_repository;
};

#endif // ARCHIVERESTORER_H
