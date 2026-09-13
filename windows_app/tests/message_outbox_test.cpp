// PHASE 71 - the Windows durable outbox.
//
// Drives the REAL production MessageCache (per-account SQLite, QStandardPaths test mode) and the
// REAL MessageOutbox over the REAL QNetworkAccessManager, against an in-process HTTP server that
// behaves like the Phase 71 backend: a client_message_id is stored once, and a repeat of it is
// answered with the stored message. No WebSocketService is constructed anywhere in this file.
//
// Windows criteria: 18 (pending survives view recreation / restart), 19 (HTTP send with the socket
// disconnected - there is no socket at all here), 20 (retry reuses client_message_id and the stored
// ciphertext), plus: no head-of-line blocking, bounded server errors, unbounded offline patience.

#include "../src/utils/messagecache.h"
#include "../src/services/messageoutbox.h"
#include "fake_http_server.h"

#include <QCoreApplication>
#include <QFile>
#include <QEventLoop>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QStandardPaths>
#include <QTextStream>
#include <QTimer>
#include <QUuid>
#include <QSet>

static int g_failures = 0;
static int g_checks = 0;
static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << "\n";
    if (!ok) ++g_failures;
}

static const QString ACCOUNT = QStringLiteral("aaaaaaaa-7171-0000-0000-00000000000a");
static const QString CHAT1 = QStringLiteral("11111111-7171-4111-8111-111111111111");
static const QString CHAT2 = QStringLiteral("22222222-7171-4222-8222-222222222222");

// ---------------------------------------------------------------- the stand-in backend

struct Backend {
    QHash<QString, QStringList> scripts;   // client_message_id -> behaviours, consumed per request
    QHash<QString, QString> stored;        // client_message_id -> server id
    QList<QByteArray> bodies;
    qint64 seq = 0;

    FakeHttpResponse handle(const FakeHttpRequest& req) {
        bodies << req.body;
        const QJsonObject o = QJsonDocument::fromJson(req.body).object();
        const QString cmid = o.value(QStringLiteral("client_message_id")).toString();
        const QString chat = o.value(QStringLiteral("chat_id")).toString();
        QStringList& s = scripts[cmid];
        const QString step = s.isEmpty() ? QString() : s.takeFirst();
        if (step == QStringLiteral("503")) return {503, R"({"error":"unavailable"})", false};
        if (step == QStringLiteral("403")) return {403, R"({"error":"forbidden"})", false};
        if (step == QStringLiteral("down")) return {0, {}, true};
        if (step == QStringLiteral("lost-ack")) {           // stored, then the answer is lost
            if (!stored.contains(cmid)) stored.insert(cmid, QUuid::createUuid().toString(QUuid::WithoutBraces));
            return {0, {}, true};
        }
        const bool replay = stored.contains(cmid);
        if (!replay) stored.insert(cmid, QUuid::createUuid().toString(QUuid::WithoutBraces));
        QJsonObject data{{"id", stored.value(cmid)}, {"chat_id", chat}, {"sender_id", "me"},
                         {"created_at", "2026-09-11T10:00:00Z"}, {"status", "accepted"},
                         {"seq", ++seq}, {"client_message_id", cmid}, {"idempotent_replay", replay}};
        return {200, QJsonDocument(QJsonObject{{"data", data}}).toJson(QJsonDocument::Compact), false};
    }
};

// ---------------------------------------------------------------- harness

static qint64 g_now = 1000000;

static MessageCache::OutboxItem queued(const QString& chat, qint64 createdAt) {
    MessageCache::OutboxItem it;
    it.clientMessageId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    it.chatId = chat;
    it.chatType = QStringLiteral("direct");
    QJsonObject body{{"chat_id", chat}, {"chat_type", "direct"},
                     {"content", QUuid::createUuid().toString(QUuid::Id128)}, // opaque ciphertext stand-in
                     {"encrypted", true}, {"encryption_version", 4}, {"client_message_id", it.clientMessageId}};
    it.requestJson = QString::fromUtf8(QJsonDocument(body).toJson(QJsonDocument::Compact));
    it.state = QStringLiteral("PENDING");
    it.createdAt = createdAt;
    it.nextAttemptAt = 0;
    return it;
}

static MessageOutbox* makeOutbox(MessageCache* cache, QNetworkAccessManager* nam, const QString& base,
                                 QObject* parent) {
    MessageOutbox::Config cfg;
    cfg.cache = cache;
    cfg.network = nam;
    cfg.apiBase = [base]() { return base; };
    cfg.applyHeaders = [](QNetworkRequest& r) {
        r.setRawHeader("Authorization", "Bearer test-token");
        r.setRawHeader("X-Device-Id", "device-under-test");
    };
    cfg.owner = []() { return ACCOUNT; };
    cfg.clock = []() { return g_now; };
    cfg.autoWake = false; // passes are driven explicitly below
    return new MessageOutbox(cfg, parent);
}

/** One pass, waited for. */
static void pass(MessageOutbox* ob) {
    QEventLoop loop;
    QObject::connect(ob, &MessageOutbox::passFinished, &loop, &QEventLoop::quit);
    QTimer::singleShot(15000, &loop, &QEventLoop::quit);
    ob->drain();
    loop.exec();
}

/** Passes until nothing PENDING remains (clock jumps past every backoff) or max passes. */
static int settle(MessageOutbox* ob, MessageCache* cache, int max = 30) {
    int n = 0;
    while (n < max) {
        pass(ob);
        ++n;
        const qint64 next = cache->earliestPendingOutbox();
        if (next < 0) break;
        g_now = qMax(g_now, next);
    }
    return n;
}

static QString stateOf(MessageCache* cache, const QString& cmid, int* attempts = nullptr) {
    MessageCache::OutboxItem it;
    if (!cache->outboxItem(cmid, &it)) return QStringLiteral("<missing>");
    if (attempts) *attempts = it.attempts;
    return it.state;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QStandardPaths::setTestModeEnabled(true);
    QCoreApplication::setApplicationName(QStringLiteral("p71_outbox_test"));
    // Start from nothing: clear() deliberately keeps the outbox, so remove the test database file.
    QFile::remove(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/")
                  + MessageCache::databaseNameForAccount(ACCOUNT));

    Backend backend;
    FakeHttpServer server([&backend](const FakeHttpRequest& r) { return backend.handle(r); });
    QNetworkAccessManager nam;

    QTextStream out(stdout);

    // ------------------------------------------------ 18: survives view recreation and restart
    out << "\n[18] pending message survives the view and an application restart\n";
    QString survivorId;
    QString survivorJson;
    {
        MessageCache cache; cache.openForAccount(ACCOUNT);
        const auto item = queued(CHAT1, g_now);
        survivorId = item.clientMessageId;
        survivorJson = item.requestJson;
        // Owned by ChatService (not by QML): enqueue, then the "view" and every object go away.
        MessageOutbox* ob = makeOutbox(&cache, &nam, QStringLiteral("http://127.0.0.1:1/api/v1"), nullptr);
        check(ob->enqueue(item), "enqueue persists the item before any transmission");
        delete ob;
        cache.close();
    }
    {
        MessageCache cache; cache.openForAccount(ACCOUNT); // a fresh process's cache
        const QList<MessageCache::OutboxItem> rows = cache.unacceptedOutbox(CHAT1);
        check(rows.size() == 1 && rows.first().clientMessageId == survivorId,
              "after restart the pending item is still there");
        check(!rows.isEmpty() && rows.first().requestJson == survivorJson,
              "byte-identical request body survives the restart");
        MessageOutbox* ob = makeOutbox(&cache, &nam, server.baseUrl(), nullptr);
        settle(ob, &cache);
        check(stateOf(&cache, survivorId) == QStringLiteral("ACCEPTED"),
              "the restarted outbox delivers it");
        delete ob;
        cache.close();
    }

    MessageCache cache; cache.openForAccount(ACCOUNT);
    MessageOutbox* ob = makeOutbox(&cache, &nam, server.baseUrl(), &app);

    // ------------------------------------------------ 19: HTTP send with no socket at all
    out << "\n[19] HTTP send is accepted with no WebSocket in existence\n";
    {
        const auto item = queued(CHAT1, g_now);
        ob->enqueue(item);
        pass(ob);
        MessageCache::OutboxItem row; cache.outboxItem(item.clientMessageId, &row);
        check(row.state == QStringLiteral("ACCEPTED"), "accepted over HTTP");
        check(row.serverMessageId == backend.stored.value(item.clientMessageId), "server id recorded");
    }

    // ------------------------------------------------ 20: retry reuses the id and the ciphertext
    out << "\n[20] every retry resends the stored bytes and resolves to one message\n";
    {
        const auto item = queued(CHAT1, g_now);
        backend.scripts[item.clientMessageId] = {QStringLiteral("503"), QStringLiteral("lost-ack")};
        const int before = backend.bodies.size();
        ob->enqueue(item);
        settle(ob, &cache);
        const QList<QByteArray> mine = backend.bodies.mid(before);
        bool identical = !mine.isEmpty();
        for (const QByteArray& b : mine) identical = identical && (b == item.requestJson.toUtf8());
        check(mine.size() >= 3, QStringLiteral("at least three attempts (%1)").arg(mine.size()));
        check(identical, "every attempt carried the identical stored JSON (same id, same ciphertext)");
        int storedForThis = backend.stored.contains(item.clientMessageId) ? 1 : 0;
        check(storedForThis == 1, "exactly one logical message on the server");
        MessageCache::OutboxItem row; cache.outboxItem(item.clientMessageId, &row);
        check(row.state == QStringLiteral("ACCEPTED") && row.serverMessageId == backend.stored.value(item.clientMessageId),
              "the retry after the lost ACK resolved to the stored message");
    }

    // ------------------------------------------------ no head-of-line blocking
    out << "\n[16w] a refused or backing-off item never blocks the items after it\n";
    {
        const auto refused = queued(CHAT1, g_now + 1);
        const auto afterRefused = queued(CHAT1, g_now + 2);
        const auto stuck = queued(CHAT2, g_now + 3);
        const auto afterStuck = queued(CHAT2, g_now + 4);
        backend.scripts[refused.clientMessageId] = {QStringLiteral("403")};
        backend.scripts[stuck.clientMessageId] = {QStringLiteral("503"), QStringLiteral("503")};
        for (const auto& i : {refused, afterRefused, stuck, afterStuck}) ob->enqueue(i);
        pass(ob);
        check(stateOf(&cache, refused.clientMessageId) == QStringLiteral("FAILED"), "refused item FAILED");
        check(stateOf(&cache, afterRefused.clientMessageId) == QStringLiteral("ACCEPTED"), "item after it still ACCEPTED");
        check(stateOf(&cache, stuck.clientMessageId) == QStringLiteral("PENDING"), "5xx item PENDING, backing off");
        check(stateOf(&cache, afterStuck.clientMessageId) == QStringLiteral("ACCEPTED"), "item after it still ACCEPTED");
        // explicit retry of the refused one: same row, same bytes
        check(ob->retry(refused.clientMessageId), "explicit retry re-arms a FAILED item");
        settle(ob, &cache);
        check(stateOf(&cache, refused.clientMessageId) == QStringLiteral("ACCEPTED"), "retried item accepted");
    }

    // ------------------------------------------------ bounds
    out << "\n[bounds] server errors fail after the bound; an unreachable server never does\n";
    {
        const auto broken = queued(CHAT1, g_now);
        QStringList many; for (int i = 0; i < 20; ++i) many << QStringLiteral("503");
        backend.scripts[broken.clientMessageId] = many;
        ob->enqueue(broken);
        settle(ob, &cache, 20);
        int attempts = 0;
        const QString st = stateOf(&cache, broken.clientMessageId, &attempts);
        check(st == QStringLiteral("FAILED") && attempts == MessageOutbox::kMaxServerAttempts,
              QStringLiteral("5xx item FAILED after %1 attempts").arg(attempts));

        const auto offline = queued(CHAT2, g_now);
        MessageOutbox* dead = makeOutbox(&cache, &nam, QStringLiteral("http://127.0.0.1:1/api/v1"), &app);
        dead->enqueue(offline);
        for (int i = 0; i < 12; ++i) {
            pass(dead);
            MessageCache::OutboxItem r; cache.outboxItem(offline.clientMessageId, &r);
            g_now = qMax(g_now, r.nextAttemptAt);
        }
        int oa = 0;
        const QString os = stateOf(&cache, offline.clientMessageId, &oa);
        check(os == QStringLiteral("PENDING") && oa >= 12,
              QStringLiteral("offline item still PENDING after %1 attempts").arg(oa));
    }

    out << "\n" << (g_failures == 0 ? "OK" : "FAILED") << ": " << (g_checks - g_failures) << "/" << g_checks
        << " checks passed\n";
    cache.clear();
    cache.close();
    return g_failures == 0 ? 0 : 1;
}
