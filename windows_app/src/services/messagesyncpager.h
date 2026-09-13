#pragma once

#include <QObject>
#include <QJsonArray>
#include <QString>
#include <QStringList>
#include <functional>

#include "../utils/messagecache.h"

class QNetworkAccessManager;
class QNetworkRequest;

// PHASE 71 - reconnect catch-up for every chat, not only the open one.
//
// The server numbers each chat's messages with a gap-free, commit-ordered seq,
// and GET /messages/<chat>?after=N returns what came after N, oldest first.
// This pages that endpoint from the chat's stored sync point until the server
// says there is nothing more, handing each page to `ingest` (ChatService's
// decrypt-and-cache) and advancing the point as it goes. A device offline while
// any number of messages arrived fetches all of them - not just the newest 50.
//
// Without a stored point:
//   - no local history: anchor at the head (the newest page), exactly what the
//     app always did; nothing is bulk-downloaded.
//   - local history: page BACKWARDS from the head until reaching a message this
//     device already holds, closing the gap the outage left.
//
// Bounded per call (maxPages forward, maxBackfillPages backward). A call that
// stops at a bound has saved its progress and the next call resumes there.
// Same algorithm as the Android ChatSyncPager.
class MessageSyncPager : public QObject {
    Q_OBJECT

public:
    struct Config {
        MessageCache* cache = nullptr;
        QNetworkAccessManager* network = nullptr;
        std::function<QString()> apiBase;
        std::function<void(QNetworkRequest&)> applyHeaders;
        std::function<QString()> owner;
        // Decrypt and cache one page (synchronous). Rows are in server order.
        std::function<void(const QString& chatId, const QJsonArray& rows)> ingest;
        // Optional: make the chat decryptable first (group Sender Keys / MLS).
        std::function<void(const QString& chatId, std::function<void()> ready)> prepare;
        int pageSize = 100;
        int maxPages = 50;
        int maxBackfillPages = 20;
    };

    explicit MessageSyncPager(Config cfg, QObject* parent = nullptr);

    // Queues chats for catch-up; they are processed one at a time.
    void syncChats(const QStringList& chatIds);
    bool isBusy() const { return m_busy; }

signals:
    void chatSynced(const QString& chatId, int messages, bool complete);
    void chatSyncFailed(const QString& chatId, const QString& error);
    // The queue is empty.
    void idle();

private:
    enum class Stage { Head, Forward, Backfill };

    void startNextChat();
    void requestPage();
    void onPage(int status, const QJsonObject& root, const QString& error);
    void finishChat(bool complete);

    Config m_cfg;
    QStringList m_queue;
    bool m_busy = false;

    // Current chat.
    QString m_chat;
    QString m_owner;
    Stage m_stage = Stage::Forward;
    MessageCache::SyncPoint m_point;
    bool m_hadLocal = false;
    int m_pages = 0;
    int m_backfillPages = 0;
    int m_messages = 0;
};
