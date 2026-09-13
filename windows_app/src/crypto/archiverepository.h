#ifndef ARCHIVEREPOSITORY_H
#define ARCHIVEREPOSITORY_H

#include "historyarchiver.h"

#include <QJsonObject>
#include <QPair>
#include <QString>
#include <QVector>
#include <functional>

/**
 * The durable boundary for one sealed archive record: the server's /e2ee/archives store.
 *
 * Transport only. It receives an already-sealed ArchiveRecord and moves it, byte for byte. There is
 * no cryptography here - no key, no root, no plaintext ever reaches this class - and no storage,
 * scheduling, caching, or state machine. It does not decode, re-encode, or normalise the ciphertext:
 * the Base64 string the archiver produced is the Base64 string that is sent.
 *
 * Server contract (back-end/handlers/archive.go), reproduced rather than invented:
 *
 *   POST /e2ee/archives   {message_id, root_version, protocol_version, ciphertext_b64}
 *                       -> 200 {message_id, chat_id, stored}
 *                          `stored=false` means an archive already existed and was left untouched
 *                       -> 400 invalid body / message_id / root_version / protocol_version /
 *                              ciphertext_b64
 *                       -> 404 no such message, OR the caller is not a member of its chat
 *                       -> 413 ciphertext over 1 MiB
 *
 *   GET  /e2ee/archives?chat_id=&since=&since_id=
 *                       -> 200 {archives: [...], count}
 *                       -> 400 invalid chat_id / since / since_id
 *                       -> 404 caller is not a member of the requested chat
 *
 * Both routes require a known, non-revoked X-Device-Id in addition to the bearer token.
 *
 * NOTE ON chat_id. Upload deliberately does NOT send it: the server derives the chat from the
 * authoritative message row, so an archive cannot be bound to a conversation the message does not
 * belong to. The record's own chatId is therefore local context, and the value that comes back on
 * download is the server's.
 *
 * IMMUTABILITY. Identity is (message_id, user_id) and the insert is ON CONFLICT DO NOTHING, so an
 * upload is idempotent and a replayed or attacker-supplied ciphertext can never overwrite the
 * original. That is the server's rule, not a policy invented here.
 */
class ArchiveRepository {
public:
    /** Same signature as the application's one authenticated JSON exchange. */
    using ExchangeFn = std::function<QPair<int, QJsonObject>(const QString& path,
                                                             const QByteArray& body,
                                                             const char* method)>;

    static constexpr const char* PATH = "/e2ee/archives";

    enum class Outcome {
        Ok,
        NotFound,       ///< 404 - no such message, or not available to this caller.
        Unauthorized,   ///< 401/403.
        Rejected,       ///< 400 - the server refused the body.
        TooLarge,       ///< 413.
        Malformed,      ///< 2xx whose payload did not parse, or failed validation.
        TransportError, ///< no response, 5xx, or an unexpected status.
    };

    struct SaveResult {
        Outcome outcome = Outcome::TransportError;
        /** false means the archive already existed server-side and was left untouched. */
        bool stored = false;
        QString messageId;
        QString chatId; ///< As derived by the server from the message row.
    };

    struct ListResult {
        Outcome outcome = Outcome::TransportError;
        QVector<ArchiveRecord> records;
        int count = 0;
        /**
         * The keyset cursor for the NEXT page: the created_at and message_id of the last row in
         * this one. Empty when the page was empty, or when a row arrived without usable cursor
         * components - a caller must then stop rather than guess.
         *
         * They are returned here rather than stored on ArchiveRecord because they are a property of
         * the transport's ordering, not of the sealed archive.
         */
        QString nextSince;
        QString nextSinceId;
    };

    /** The server's page cap (back-end/handlers/archive.go, maxArchiveListed). */
    static constexpr int PAGE_SIZE = 500;

    explicit ArchiveRepository(ExchangeFn exchange);

    /** Stores one sealed record. The ciphertext is forwarded unchanged. */
    SaveResult save(const ArchiveRecord& record) const;

    /**
     * One page of the caller's own archives, optionally narrowed to one chat. Never another
     * account's.
     *
     * `since` / `sinceId` are the server's EXCLUSIVE keyset cursor: pass the created_at AND
     * message_id of the last row already seen. Both together, never created_at alone - the server
     * orders by (created_at, message_id) and compares the same pair, and a timestamp-only cursor
     * silently skips rows that share a timestamp across a page boundary.
     *
     * Returns at most PAGE_SIZE rows. A short page is the end of the sequence.
     */
    ListResult list(const QString& chatId = QString(),
                    const QString& since = QString(),
                    const QString& sinceId = QString()) const;

private:
    static Outcome classify(int status);

    ExchangeFn m_exchange;
};

#endif // ARCHIVEREPOSITORY_H
