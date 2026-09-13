#pragma once

#include <QObject>
#include <QString>
#include <QList>
#include <QSqlDatabase>
#include <QJsonObject>

// Local on-disk cache of message history and the chat list (SQLite), so the
// app shows something instantly on open - and still works at all when
// offline - instead of always waiting on a fresh network round-trip.
//
// Stores the raw pre-decryption payload only (ciphertext, or plaintext for
// messages that were never encrypted) exactly as the server sent it, never
// the decrypted text - so a locally readable cache file on disk doesn't
// quietly undo the point of end-to-end encryption. Callers decrypt on load,
// the same way they already do for a fresh network response.
class MessageCache : public QObject {
    Q_OBJECT

public:
    struct Entry {
        QString id;
        QString senderId;
        QString senderName;
        QString content;
        bool encrypted = false;
        QString readAt;
        QString createdAt;
        // File-bearing messages (voice/image/file): fileUrl points at the
        // (opaque, possibly ciphertext) payload; fileType is "audio"/"image"/
        // "file", empty for a plain text message.
        QString fileUrl;
        QString fileType;
        // fileName/fileSize apply to image/file attachments (display before
        // fetching); durationMs applies to voice notes.
        QString fileName;
        qint64 fileSize = 0;
        qint64 durationMs = 0;
        // Which of the sender's group Sender Key versions encrypted this
        // message - 0/unused outside a group chat.
        int keyVersion = 0;
        int encryptionVersion = 1;
        // Which of the sender's devices encrypted this. v3/v4 key their ratchet
        // session by "chatId|senderDeviceId", so dropping it on the cache path
        // made a reopened chat decrypt against the wrong session and show
        // placeholders where the first (network) load had worked.
        QString senderDeviceId;
        // Display-only forward attribution (payload is always a fresh
        // ciphertext for this chat; the server never copies source blobs).
        bool isForwarded = false;
        QString forwardedFromName;
        QString replyToId;
    };

    explicit MessageCache(QObject* parent = nullptr);

    // ---------------------------------------------------------------- Gate 20
    //
    // THE CACHE NAMESPACE IS THE ACCOUNT.
    //
    // Before Gate 20 this class opened one process-wide message_cache.db in its
    // constructor - before anyone had logged in - so every account that ever
    // signed in on the machine shared it. Account B could enumerate the chat
    // list, look up a conversation or a message by id, and read A's rows.
    //
    // Isolation is now physical, matching the Android design: the authenticated
    // account selects the FILE. A query cannot reach another account's rows
    // because they are not in the database it is querying, so isolation does not
    // depend on remembering a predicate at any of the twelve call sites.
    //
    // Nothing is opened until openForAccount(). Every method below already
    // returns early when the handle is closed, so the unauthenticated state
    // fails closed: reads yield nothing and writes do not happen.

    /**
     * Deterministic, collision-resistant, filesystem-safe database file name for
     * an account. SHA-256 of the trimmed account id, hex, truncated to 128 bits.
     *
     * Case is deliberately NOT folded: merging two distinct ids into one
     * namespace would be a confidentiality failure, while splitting one id
     * across two is only a visibility annoyance. The output is [0-9a-f]{32}
     * behind a fixed prefix, so no account id - however hostile - can contain a
     * path separator, a traversal sequence or a Windows reserved device name.
     */
    static QString databaseNameForAccount(const QString& accountId);

    /**
     * The pre-Gate-20 account-blind database. QUARANTINED: no production path
     * opens a database by this name any more. It is never adopted by any
     * account, never migrated and never deleted - its rows cannot be attributed
     * to an owner, and guessing one would hand a previous account's cache to
     * whoever signs in next. Named here so the constant is greppable and so
     * tests can assert it is unreachable.
     */
    static QString legacyQuarantinedFileName();

    // Binds the cache to one account, closing any previously open namespace
    // first so two are never open at once. Idempotent for the same account.
    void openForAccount(const QString& accountId);

    // Releases the handle on logout. Does NOT delete: logging out ends access
    // to that account's offline history, not the history itself.
    void close();

    bool isOpen() const { return m_db.isOpen(); }

    // The account this cache is currently bound to, empty when closed. Callers
    // capture this when they START an operation and hand it back on commit.
    QString ownerAccountId() const { return m_accountId; }

    // Upserts by message id. `entries` should be oldest-first (matches the
    // order ChatService already builds for display).
    //
    // `owner` is the account the operation BELONGS to, captured when the network
    // request was issued. A reply that started under account A but lands after a
    // switch to B would otherwise write A's rows into B's database; the write is
    // refused when it no longer matches the open account.
    void saveMessages(const QString& chatId, const QList<Entry>& entries, const QString& owner);

    // The established "this message is held in the clear" representation, applied to a copy.
    //
    // A successfully decrypted message is cached as plaintext with encrypted = false - that is what
    // the fetch path already does when normal decryption works, and saveMessages then refuses to
    // let a later encrypted copy overwrite such a row. Layer B recovery produces exactly the same
    // outcome by a different route, so it must produce exactly the same representation rather than
    // inventing a second one. Every other field is carried over untouched.
    static Entry withRecoveredPlaintext(const Entry& source, const QString& plaintext);

    // Returns cached messages for a chat, oldest-first, most recent `limit`.
    QList<Entry> loadMessages(const QString& chatId, int limit = 50) const;

    // Chat list cache: stored as the raw per-chat JSON object exactly as the
    // server sent it (same reasoning as above - the last-message preview
    // inside it may be ciphertext), keyed by chat id. Callers re-run it
    // through the same parsing/decryption path used for a fresh response.
    void saveChats(const QList<QJsonObject>& chats, const QString& owner);
    QList<QJsonObject> loadChats() const;

    // Drops one conversation's cached messages and chat row.
    void deleteChat(const QString& chatId);
    // Wipes every cached message and chat (e.g. a user-triggered "clear
    // cache" in Settings) and reclaims the freed space on disk.
    void clear();

    // Drops one message's cached row. Without this a message deleted while
    // online reappears on the next cold start, because the cache is painted
    // before the network refresh can correct it.
    void deleteMessage(const QString& messageId);
    // Size of the on-disk database file, for showing in Settings.
    qint64 sizeBytes() const;

    // -------------------------------------------------------------- Phase 71
    //
    // THE DURABLE OUTBOX. One row per outgoing message, written BEFORE its
    // first transmission, in this account's own database file - the same
    // per-account isolation as everything above. requestJson is the exact
    // POST /messages body: ciphertext, protocol metadata and client_message_id.
    // Never plaintext. Every retry re-sends those bytes unchanged, and the
    // server resolves a repeated client_message_id to the message it already
    // stored, so a retry after a lost ACK cannot create a second message.
    //
    // state: PENDING (not yet accepted; retried automatically), ACCEPTED (the
    // server durably stored it; never retransmitted again), FAILED (refused;
    // waits for an explicit retry, never blocks other items).
    //
    // Deliberately NOT wiped by clear(): "clear cache" must not lose unsent
    // messages.
    struct OutboxItem {
        QString clientMessageId;
        QString chatId;
        QString chatType;
        QString requestJson;
        QString state;
        qint64 createdAt = 0;
        int attempts = 0;
        qint64 nextAttemptAt = 0;
        QString lastError;
        QString serverMessageId;
        qint64 acceptedAt = 0;
    };
    // Refuses (returns false) a client_message_id that already exists.
    bool insertOutbox(const OutboxItem& item, const QString& owner);
    bool outboxItem(const QString& clientMessageId, OutboxItem* out) const;
    // PENDING items whose backoff has elapsed, oldest first.
    QList<OutboxItem> dueOutbox(qint64 now) const;
    // PENDING and FAILED items of one chat, oldest first.
    QList<OutboxItem> unacceptedOutbox(const QString& chatId) const;
    // Earliest nextAttemptAt among PENDING items, or -1 when there are none.
    qint64 earliestPendingOutbox() const;
    void markOutboxAccepted(const QString& clientMessageId, const QString& serverId, qint64 at,
                            const QString& owner);
    // Transient failure: stays PENDING. Never touches an ACCEPTED or FAILED row.
    void markOutboxRetry(const QString& clientMessageId, int attempts, qint64 nextAttemptAt,
                         const QString& error, const QString& owner);
    void markOutboxFailed(const QString& clientMessageId, int attempts, const QString& error,
                          const QString& owner);
    // Explicit retry of a FAILED item: same row, same id, same ciphertext.
    bool resetOutboxForRetry(const QString& clientMessageId, qint64 now, const QString& owner);
    void pruneAcceptedOutbox(qint64 olderThan, const QString& owner);

    // PER-CHAT SYNC POINT: the highest server seq this device has ingested for a
    // chat, and where an unfinished backward catch-up resumes (-1: none). See
    // MessageSyncPager.
    struct SyncPoint {
        qint64 lastSeq = 0;
        qint64 backfillBefore = -1;
    };
    bool loadSyncPoint(const QString& chatId, SyncPoint* out) const;
    void saveSyncPoint(const QString& chatId, const SyncPoint& point, const QString& owner);
    bool hasMessage(const QString& messageId) const;
    bool hasMessagesInChat(const QString& chatId) const;

private:
    void ensureSchema();
    // True when `owner` still matches the open account. A mismatch means the
    // operation outlived the session that started it.
    bool ownedBy(const QString& owner) const;

    QSqlDatabase m_db;
    QString m_accountId;      // raw id; never used in a path
    QString m_connectionName; // per-account, since Qt connection names are global
};
