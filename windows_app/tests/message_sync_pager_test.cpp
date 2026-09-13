// PHASE 71 - Windows reconnect catch-up (criterion 21: catch-up beyond the first page).
//
// The REAL MessageSyncPager over the REAL QNetworkAccessManager and the REAL per-account
// MessageCache, against an in-process HTTP server that models the Phase 71 GET /messages contract:
// a chat whose messages carry a gap-free seq, ?after= ascending, ?before= descending, exact has_more.

#include "../src/utils/messagecache.h"
#include "../src/services/messagesyncpager.h"
#include "fake_http_server.h"

#include <QCoreApplication>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QStandardPaths>
#include <QTextStream>
#include <QTimer>
#include <QUrl>
#include <QUrlQuery>

static int g_failures = 0;
static int g_checks = 0;
static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << "\n";
    if (!ok) ++g_failures;
}

static const QString ACCOUNT = QStringLiteral("aaaaaaaa-7172-0000-0000-00000000000a");

/** One chat on the server: seq 1..total. */
struct ServerChat {
    int total = 0;
    QStringList requests;

    static QString idFor(qint64 seq) { return QStringLiteral("m-%1").arg(seq); }

    FakeHttpResponse handle(const FakeHttpRequest& req) {
        requests << QString::fromUtf8(req.path);
        const QUrl url(QStringLiteral("http://x") + QString::fromUtf8(req.path));
        const QUrlQuery q(url);
        const int limit = q.queryItemValue(QStringLiteral("limit")).toInt();
        const bool hasAfter = q.hasQueryItem(QStringLiteral("after"));
        const bool hasBefore = q.hasQueryItem(QStringLiteral("before"));
        const qint64 after = q.queryItemValue(QStringLiteral("after")).toLongLong();
        const qint64 before = q.queryItemValue(QStringLiteral("before")).toLongLong();
        QList<qint64> rows;
        if (hasAfter) { for (qint64 s = after + 1; s <= total; ++s) rows << s; }
        else if (hasBefore) { for (qint64 s = before - 1; s >= 1; --s) rows << s; }
        else { for (qint64 s = total; s >= 1; --s) rows << s; }
        QJsonArray data;
        for (int i = 0; i < rows.size() && i < limit; ++i) {
            data.append(QJsonObject{{"id", idFor(rows[i])}, {"chat_id", "chat"}, {"sender_id", "peer"},
                                    {"content", QStringLiteral("c%1").arg(rows[i])}, {"encrypted", true},
                                    {"created_at", "2026-09-11T10:00:00Z"}, {"seq", rows[i]}});
        }
        QJsonObject root{{"data", data}, {"has_more", rows.size() > limit},
                         {"order", hasAfter ? "asc" : "desc"}};
        return {200, QJsonDocument(root).toJson(QJsonDocument::Compact), false};
    }
};

struct Harness {
    MessageCache cache;
    QNetworkAccessManager nam;
    QList<qint64> ingested;
    MessageSyncPager* pager = nullptr;

    Harness(const QString& base, int pageSize, int maxPages, int maxBackfill) {
        cache.openForAccount(ACCOUNT);
        cache.clear(); // each case starts from an empty history and no sync points
        MessageSyncPager::Config cfg;
        cfg.cache = &cache;
        cfg.network = &nam;
        cfg.apiBase = [base]() { return base; };
        cfg.applyHeaders = [](QNetworkRequest& r) { r.setRawHeader("Authorization", "Bearer t"); };
        cfg.owner = []() { return ACCOUNT; };
        // Stand-in for ChatService's decrypt-and-cache: record, and write the rows to the cache so
        // "already known locally" behaves as it does in production.
        cfg.ingest = [this](const QString& chatId, const QJsonArray& rows) {
            QList<MessageCache::Entry> entries;
            for (const QJsonValue& v : rows) {
                const QJsonObject o = v.toObject();
                ingested << o.value(QStringLiteral("seq")).toVariant().toLongLong();
                MessageCache::Entry e;
                e.id = o.value(QStringLiteral("id")).toString();
                e.senderId = QStringLiteral("peer");
                e.content = o.value(QStringLiteral("content")).toString();
                e.encrypted = true;
                e.createdAt = o.value(QStringLiteral("created_at")).toString();
                entries << e;
            }
            cache.saveMessages(chatId, entries, ACCOUNT);
        };
        cfg.pageSize = pageSize;
        cfg.maxPages = maxPages;
        cfg.maxBackfillPages = maxBackfill;
        pager = new MessageSyncPager(cfg);
    }
    ~Harness() { delete pager; cache.clear(); cache.close(); }

    /** Runs one sync of [chat] and returns its (messages, complete). */
    QPair<int, bool> run(const QString& chat) {
        QPair<int, bool> result{-1, false};
        QEventLoop loop;
        QObject::connect(pager, &MessageSyncPager::chatSynced, &loop,
                         [&](const QString&, int n, bool complete) { result = {n, complete}; });
        QObject::connect(pager, &MessageSyncPager::idle, &loop, &QEventLoop::quit);
        QTimer::singleShot(20000, &loop, &QEventLoop::quit);
        pager->syncChats({chat});
        loop.exec();
        return result;
    }

    void seedLocal(const QString& chat, qint64 upTo) {
        QList<MessageCache::Entry> entries;
        for (qint64 s = 1; s <= upTo; ++s) {
            MessageCache::Entry e;
            e.id = ServerChat::idFor(s);
            e.senderId = QStringLiteral("peer");
            e.content = QStringLiteral("c%1").arg(s);
            e.createdAt = QStringLiteral("2026-09-11T10:00:00Z");
            entries << e;
        }
        cache.saveMessages(chat, entries, ACCOUNT);
    }
};

static bool contiguous(QList<qint64> seqs, qint64 from, qint64 to) {
    std::sort(seqs.begin(), seqs.end());
    seqs.erase(std::unique(seqs.begin(), seqs.end()), seqs.end());
    if (seqs.size() != to - from + 1) return false;
    for (int i = 0; i < seqs.size(); ++i) if (seqs[i] != from + i) return false;
    return true;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QStandardPaths::setTestModeEnabled(true);
    QCoreApplication::setApplicationName(QStringLiteral("p71_sync_pager_test"));
    QTextStream out(stdout);

    ServerChat server;
    FakeHttpServer http([&server](const FakeHttpRequest& r) { return server.handle(r); });

    out << "\n[21] catch-up continues past the first page until synchronised\n";
    {
        server.total = 237;
        Harness h(http.baseUrl(), 50, 50, 20);
        h.seedLocal(QStringLiteral("chat-a"), 12);
        h.cache.saveSyncPoint(QStringLiteral("chat-a"), MessageCache::SyncPoint{12, -1}, ACCOUNT);
        server.requests.clear();
        const auto r = h.run(QStringLiteral("chat-a"));
        MessageCache::SyncPoint p;
        h.cache.loadSyncPoint(QStringLiteral("chat-a"), &p);
        check(r.second, "the pass reports the chat synchronised");
        check(r.first == 225, QStringLiteral("ingested all 225 missed messages (got %1)").arg(r.first));
        check(contiguous(h.ingested, 13, 237), "seq 13..237 ingested exactly, none skipped");
        check(p.lastSeq == 237, QStringLiteral("sync point advanced to 237 (got %1)").arg(p.lastSeq));
        check(server.requests.size() == 5, QStringLiteral("5 pages of 50 (got %1 requests)").arg(server.requests.size()));
        check(server.requests.value(0).contains(QStringLiteral("after=12")), "first request is ?after=<sync point>");
    }

    out << "\n[21b] a bounded pass resumes exactly where it stopped\n";
    {
        server.total = 300;
        Harness h(http.baseUrl(), 40, 2, 20);
        h.cache.saveSyncPoint(QStringLiteral("chat-b"), MessageCache::SyncPoint{1, -1}, ACCOUNT);
        auto r = h.run(QStringLiteral("chat-b"));
        MessageCache::SyncPoint p;
        h.cache.loadSyncPoint(QStringLiteral("chat-b"), &p);
        check(!r.second && p.lastSeq == 81, QStringLiteral("first pass stops at the bound, point 81 (got %1)").arg(p.lastSeq));
        int passes = 1;
        while (!r.second && passes < 20) { r = h.run(QStringLiteral("chat-b")); ++passes; }
        check(r.second && contiguous(h.ingested, 2, 300), QStringLiteral("finished after %1 passes with 2..300").arg(passes));
    }

    out << "\n[21c] no local history: start at the head, no bulk download\n";
    {
        server.total = 5000;
        Harness h(http.baseUrl(), 100, 50, 20);
        const auto r = h.run(QStringLiteral("chat-c"));
        MessageCache::SyncPoint p;
        const bool found = h.cache.loadSyncPoint(QStringLiteral("chat-c"), &p);
        check(r.first == 100, QStringLiteral("only the newest page (got %1)").arg(r.first));
        check(found && p.lastSeq == 5000 && p.backfillBefore < 0, "point anchored at the head, no backfill");
    }

    out << "\n[21d] a gap behind the head is backfilled down to the local cache\n";
    {
        server.total = 400;
        Harness h(http.baseUrl(), 100, 50, 20);
        h.seedLocal(QStringLiteral("chat-d"), 40); // held 1..40, offline while 41..400 arrived
        const auto r = h.run(QStringLiteral("chat-d"));
        MessageCache::SyncPoint p;
        h.cache.loadSyncPoint(QStringLiteral("chat-d"), &p);
        QList<qint64> all = h.ingested;
        for (qint64 s = 1; s <= 40; ++s) all << s;
        check(r.second, "complete after backfill");
        check(contiguous(all, 1, 400), "local cache now covers 1..400");
        check(p.lastSeq == 400 && p.backfillBefore < 0, "point 400, no backfill outstanding");
    }

    out << "\n" << (g_failures == 0 ? "OK" : "FAILED") << ": " << (g_checks - g_failures) << "/" << g_checks
        << " checks passed\n";
    return g_failures == 0 ? 0 : 1;
}
