#ifndef DECRYPTEDMESSAGEARCHIVER_H
#define DECRYPTEDMESSAGEARCHIVER_H

#include "archiverepository.h"
#include "historyarchiver.h"

#include <QString>
#include <functional>

/**
 * The message-flow caller for Layer B archiving.
 *
 * ChatService::fetchMessages already computes, for every message it receives, a `display` string and
 * then decides whether that string is a real decryption or a failure. That decision point - the one
 * that governs whether plaintext may be written to MessageCache - is the only place in the Windows
 * client where a received message's plaintext is known to be genuine. This class is invoked there,
 * with the same inputs, so archiving can never disagree with caching about what succeeded.
 *
 * It performs no cryptography and no HTTP of its own: it calls the proven HistoryArchiver (Phase 36)
 * and ArchiveRepository (Phase 37). It exists as a separate class only so the gate can be tested
 * without standing up a QNetworkReply and a full ChatService.
 *
 * WHAT IT MUST NEVER DO. Archive a failed decryption. The failure representation in this codebase is
 * the literal placeholder "\xF0\x9F\x94\x92 Encrypted message" that decryptMessage() returns, and an
 * empty string when there was nothing to open. Either would be archived as though it were the user's
 * message and would then be indistinguishable from real history forever - the archive is a durable
 * second copy precisely because the MLS ratchet secret is already gone.
 *
 * It also never re-decrypts. The plaintext handed in is the one the caller already obtained; an MLS
 * sender-ratchet secret is consumed by the first successful open, so a second attempt would fail and
 * a second call would be a bug, not a retry.
 */
class DecryptedMessageArchiver {
public:
    /** Compile-time archive switch, mirroring Android's HistoryArchiveFeature. */
    using EnabledFn = std::function<bool()>;
    /** The signed-in account. Never caller-supplied. */
    using OwnerFn = std::function<QString()>;
    /** Built per call from the session, exactly as AuthService exposes them. */
    using ArchiverFn = std::function<HistoryArchiver()>;
    using RepositoryFn = std::function<ArchiveRepository()>;

    /**
     * Whether new messages are sealed into the Layer B archive on this build.
     *
     * OFF.
     *
     * Mirrors Android's HistoryArchiveFeature convention - a compile-time constant flipped as a
     * deliberate per-build act - but takes the posture Android took before its own write path had
     * been exercised against real traffic. The caller below is wired into the real fetch path and
     * proven end to end by the Phase 38 tests; turning it on for shipped Windows builds is a
     * separate decision, because with it on every chat open uploads an archive per decrypted
     * message and nothing yet reads one back.
     *
     * The flag governs SEALING ONLY. It does not gate opening an existing archive, and it does not
     * touch the keyring: turning archiving off must never make already-archived history unreadable.
     */
    static constexpr bool DEFAULT_ENABLED = false;

    /** The failure placeholder decryptMessage() returns. Never archivable. */
    static QString failedDecryptPlaceholder();

    /**
     * The exact gate ChatService applies before it will cache `display` as plaintext.
     *
     * Kept as one static predicate so the caching decision and the archiving decision cannot drift
     * apart: both ask this question, and a change to one is a change to both.
     */
    static bool isArchivableDecryption(bool encrypted, bool hasFile, const QString& display);

    enum class Result {
        Skipped,        ///< Disabled, not archivable, or no account - nothing was attempted.
        Stored,         ///< The server stored a new archive.
        AlreadyPresent, ///< The server already held one; it was left untouched.
        Failed,         ///< Sealing or upload failed. Contained: the message is unaffected.
    };

    DecryptedMessageArchiver(EnabledFn enabled,
                             OwnerFn owner,
                             ArchiverFn archiver,
                             RepositoryFn repository);

    /**
     * Archives one successfully decrypted message.
     *
     * Takes the same four values the caching decision takes, so the gate is applied here rather than
     * trusted from the call site. Never throws and never reports anything but a Result: a locked
     * vault, a missing root, or an unreachable backend must leave message processing untouched.
     */
    Result archive(const QString& chatId,
                   const QString& messageId,
                   bool encrypted,
                   bool hasFile,
                   const QString& display);

private:
    EnabledFn m_enabled;
    OwnerFn m_owner;
    ArchiverFn m_archiver;
    RepositoryFn m_repository;
};

#endif // DECRYPTEDMESSAGEARCHIVER_H
