#pragma once

#include <QObject>
#include <QJsonObject>
#include <QList>
#include <QString>
#include <QTimer>
#include <functional>

#include "../utils/messagecache.h"

class QNetworkAccessManager;
class QNetworkRequest;

// PHASE 71 - the durable outbox state machine.
//
// Every outgoing message is written to the account's MessageCache (the outbox
// table) BEFORE its first transmission, with its client_message_id and its
// ciphertext. drain() only ever re-sends those stored bytes, so:
//
//   - a retry is the SAME logical message: the server resolves a repeated
//     client_message_id to the row it already holds, which makes a lost ACK
//     harmless;
//   - nothing is re-encrypted on retry, so no ratchet or MLS generation is
//     spent twice;
//   - the WebSocket plays no part - the HTTP endpoint is the durable path and
//     is always tried. Nothing in this class knows a socket exists.
//
// Failure classes (identical to the Android MessageOutbox):
//
//   unreachable (no HTTP status)   PENDING, backoff, never auto-FAILED
//   408 / 425 / 429 / 5xx          PENDING, backoff; FAILED after kMaxServerAttempts
//   401                            PENDING, attempt not counted; the pass stops
//   other 4xx                      FAILED at once (a refusal retrying cannot fix)
//
// Items are independent: a PENDING item backing off, or a FAILED one, never
// holds back the items after it. Items are attempted in creation order.
class MessageOutbox : public QObject {
    Q_OBJECT

public:
    struct Config {
        MessageCache* cache = nullptr;
        QNetworkAccessManager* network = nullptr;
        std::function<QString()> apiBase;                   // e.g. http://host:3000/api/v1
        std::function<void(QNetworkRequest&)> applyHeaders; // Authorization + X-Device-Id
        std::function<QString()> owner;                     // the account a pass writes for
        std::function<qint64()> clock;                      // ms since epoch; default: wall clock
        bool autoWake = true;                               // re-run when backoff elapses
    };

    explicit MessageOutbox(Config cfg, QObject* parent = nullptr);

    // Persists a new PENDING item. Must happen before any transmission.
    bool enqueue(const MessageCache::OutboxItem& item);

    // One asynchronous pass over every due PENDING item. Single-flight: a call
    // made while a pass runs schedules exactly one follow-up pass.
    void drain();

    // Explicit user retry of a FAILED item (same row, id and ciphertext).
    bool retry(const QString& clientMessageId);

    bool isDraining() const { return m_running; }

    static qint64 backoffMs(int attempts);
    static bool isRetryableHttp(int status);
    static constexpr int kMaxServerAttempts = 8;
    static constexpr qint64 kAcceptedRetentionMs = 24LL * 60 * 60 * 1000;

signals:
    // The server durably stored the ciphertext (or already had it).
    void accepted(const QString& chatId, const QString& clientMessageId, const QJsonObject& data);
    // Still PENDING after this attempt; it will be retried automatically.
    void retrying(const QString& chatId, const QString& clientMessageId, const QString& reason);
    // Refused; waits for an explicit retry.
    void failed(const QString& chatId, const QString& clientMessageId, const QString& error);
    void passFinished(int accepted, int retrying, int failed);

private:
    qint64 now() const;
    void postNext();
    void finishPass();
    void scheduleWake();

    Config m_cfg;
    QTimer m_wake;
    bool m_running = false;
    bool m_again = false;
    QString m_passOwner;
    QList<MessageCache::OutboxItem> m_queue;
    int m_accepted = 0;
    int m_retrying = 0;
    int m_failed = 0;
};
