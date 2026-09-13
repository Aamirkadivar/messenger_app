// Phase 42 - what an incomplete archive traversal is allowed to mean.
//
// Phase 41 walks the server's pages and reports `walkComplete`. Nothing consumes it, which raises
// the real question: can the restore caller mistake a truncated walk for a finished one?
//
// It cannot, and the reason is structural rather than defensive. ChatService acts only on POSITIVE
// entries in the returned hash - it substitutes a row only when the id is present, and it caches a
// row only when the id is present. It never records "this message has no archive". A truncated walk
// therefore degrades to "recovered fewer this time", and the next chat open tries again, because
// the unrecovered rows are still encrypted in the cache. Those two loops are reproduced here
// verbatim so the claim is tested rather than asserted.
//
// What Phase 41 DID get wrong is the meaning of the flag. It set walkComplete when the walk stopped
// early because everything requested had been found - which is a complete RESTORE but not a
// complete TRAVERSAL, since pages never requested may still hold archives. A later sync reading it
// would wrongly conclude it could stop paging. The two facts are separated here.
//
// Isolated backend only. No plaintext, key, root, or token is printed.
//
// Usage: archive_completeness_test <baseUrl> <tokA> <uidA> <tokB> <uidB> <bigChat> <smallChat>

#include "../src/crypto/archiverestorer.h"
#include "../src/crypto/decryptedmessagearchiver.h"
#include "../src/crypto/archiverepository.h"
#include "../src/crypto/historyarchiver.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"
#include "../src/utils/messagecache.h"

#include <QCoreApplication>
#include <QFile>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QSet>
#include <QStandardPaths>
#include <QTextStream>
#include <memory>
#include <sodium.h>

static int g_failures = 0;
static int g_checks = 0;
static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_failures;
}
static void section(const char* t) { QTextStream(stdout) << "-- " << t << " --" << Qt::endl; }

static QString readFile(const QString& p) {
    QFile f(p);
    if (!f.open(QIODevice::ReadOnly)) return {};
    const QString s = QString::fromLatin1(f.readAll()).trimmed();
    f.close();
    return s;
}
static QString bodyFor(int i) {
    return QString::fromUtf8("P42 #%1 \xC3\xA9\xC3\xB6 \xE2\x9C\x93 \xE6\x97\xA5\xE6\x9C\xAC "
                             "\xCE\x95\xCE\xBB \xC3\xBF\xC3\xBE").arg(i);
}
static QByteArray nulBody() {
    return QByteArray("nul") + QByteArray(1, char(0)) + QByteArray("inside");
}
static QString bigId(int i) {
    return QStringLiteral("42aaaaaa-0000-4000-8000-%1").arg(i, 12, 10, QLatin1Char('0'));
}
static QString smallId(int i) {
    return QStringLiteral("42bbbbbb-0000-4000-8000-%1").arg(i, 12, 10, QLatin1Char('0'));
}

// ------------------------------------------------------------------ session stand-ins

class FakeVault {
public:
    void unlock(const QString& u) {
        m_userId = u; m_mk.resize(32);
        for (int i = 0; i < 32; ++i) m_mk[i] = static_cast<char>(0x70 + i);
    }
    bool isUnlocked() const { return !m_mk.isEmpty(); }
    static QByteArray aad(const QString& u) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(u).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    bool seal(const QByteArray& p, QByteArray& o) const {
        if (m_mk.isEmpty()) return false;
        o = VaultCrypto::sealXChaCha(m_mk, p, aad(m_userId)); return !o.isEmpty();
    }
    bool open(const QByteArray& s, QByteArray& o) const {
        if (m_mk.isEmpty()) return false;
        o = VaultCrypto::openXChaCha(m_mk, s, aad(m_userId)); return !o.isEmpty();
    }
private:
    QByteArray m_mk; QString m_userId;
};

class MemStore : public HistoryKeyringStore {
public:
    QHash<QString, QByteArray> k, c, g;
    bool saveKeyring(const QString& o, const QByteArray& s) override { k[o] = s; return true; }
    SlotRead loadKeyring(const QString& o) override {
        return k.contains(o) ? SlotRead::ok(k[o]) : SlotRead::absent(); }
    bool deleteKeyring(const QString& o) override { k.remove(o); return true; }
    bool saveCache(const QString& o, const QByteArray& p) override { c[o] = p; return true; }
    SlotRead loadCache(const QString& o) override {
        return c.contains(o) ? SlotRead::ok(c[o]) : SlotRead::absent(); }
    bool deleteCache(const QString& o) override { c.remove(o); return true; }
    bool saveGeneration(const QString& o, qint64 v) override { g[o] = QByteArray::number(v); return true; }
    SlotRead loadGeneration(const QString& o) override {
        return g.contains(o) ? SlotRead::ok(g[o]) : SlotRead::absent(); }
    bool deleteGeneration(const QString& o) override { g.remove(o); return true; }
};

struct Session {
    MemStore store; FakeVault vault; QString owner;
    std::unique_ptr<HistoryKeyringRepository> repo;
    void start(const QString& account) {
        owner = account; vault.unlock(account);
        repo = std::make_unique<HistoryKeyringRepository>(
            &store,
            [this](const QByteArray& p, QByteArray& o) { return vault.seal(p, o); },
            [this](const QByteArray& s, QByteArray& o) { return vault.open(s, o); },
            nullptr, [this]() { return vault.isUnlocked(); });
    }
    HistoryArchiver archiver() {
        return HistoryArchiver(
            [this](const QString& c, HistoryRootEntry& out, QString* e) {
                return repo->ensureRoot(owner, c, out, e); },
            [this](const QString& c, int rv, HistoryRootEntry& out, QString* e) {
                HistoryKeyring k;
                if (!repo->load(owner, k, e)) return false;
                if (!k.find(c, rv, out)) { if (e) *e = QStringLiteral("root version not held"); return false; }
                return true; },
            [this]() { return owner; });
    }
};

struct Tracker {
    int get = 0, post = 0;
    QStringList cursors;
    ArchiveRepository wrap(const QString& base, const QString& tok, const QString& dev,
                           QNetworkAccessManager* nam) {
        auto inner = makeKeyringExchange(nam, [tok]() { return tok; }, [dev]() { return dev; }, base);
        return ArchiveRepository([this, inner](const QString& path, const QByteArray& body,
                                               const char* method) {
            if (qstrcmp(method, "GET") == 0) {
                ++get;
                const int q = path.indexOf(QStringLiteral("since="));
                cursors.append(q < 0 ? QString() : path.mid(q));
            } else if (qstrcmp(method, "POST") == 0) ++post;
            return inner(path, body, method);
        });
    }
};

/**
 * ChatService's two loops, reproduced exactly: substitute only present ids, cache only present ids.
 * Returns the rows it would have written back.
 */
struct CallerResult {
    QStringList substituted;
    QStringList leftAsPlaceholder;
    QList<MessageCache::Entry> written;
};
static CallerResult applyLikeChatService(const QList<MessageCache::Entry>& cached,
                                         const QStringList& requested,
                                         const QHash<QString, QString>& recovered) {
    CallerResult r;
    for (const MessageCache::Entry& e : cached) {
        const auto it = recovered.constFind(e.id);
        if (it != recovered.constEnd()) {
            r.substituted.append(e.id);
            r.written.append(MessageCache::withRecoveredPlaintext(e, *it));
        } else if (requested.contains(e.id)) {
            r.leftAsPlaceholder.append(e.id);
        }
    }
    return r;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 8) { out << "usage: see header" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString tokA = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uidA = readFile(QString::fromLocal8Bit(argv[3]));
    const QString tokB = readFile(QString::fromLocal8Bit(argv[4]));
    const QString uidB = readFile(QString::fromLocal8Bit(argv[5]));
    const QString bigChat = QString::fromLocal8Bit(argv[6]);
    const QString smallChat = QString::fromLocal8Bit(argv[7]);
    if (tokA.isEmpty() || uidA.isEmpty() || tokB.isEmpty() || uidB.isEmpty()) {
        out << "missing credentials" << Qt::endl; return 2;
    }

    QNetworkAccessManager nam;
    auto plainRepo = [&](const QString& tok, const QString& dev) {
        return ArchiveRepository(makeKeyringExchange(
            &nam, [tok]() { return tok; }, [dev]() { return dev; }, baseUrl));
    };

    out << "Phase 42 - restore completeness semantics" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;

    Session sa; sa.start(uidA);

    // ============================================ seed
    section("seed: 506 archives in one chat, 3 in another");
    {
        HistoryArchiver arch = sa.archiver();
        const ArchiveRepository repo = plainRepo(tokA, QStringLiteral("p42-dev-a"));
        int stored = 0;
        for (int i = 1; i <= 506; ++i) {
            ArchiveMessage m;
            m.userId = uidA; m.chatId = bigChat; m.messageId = bigId(i);
            m.plaintext = (i == 506) ? nulBody() : bodyFor(i).toUtf8();
            ArchiveRecord rec;
            if (arch.seal(m, rec, nullptr) && repo.save(rec).stored) ++stored;
        }
        check(stored == 506, QStringLiteral("506 archives stored (got %1)").arg(stored));
        int small = 0;
        for (int i = 1; i <= 3; ++i) {
            ArchiveMessage m;
            m.userId = uidA; m.chatId = smallChat; m.messageId = smallId(i);
            m.plaintext = bodyFor(2000 + i).toUtf8();
            ArchiveRecord rec;
            if (arch.seal(m, rec, nullptr) && repo.save(rec).stored) ++small;
        }
        check(small == 3, QStringLiteral("3 archives stored in the small chat"));
    }

    QStringList ordering;
    {
        const ArchiveRepository repo = plainRepo(tokA, QStringLiteral("p42-dev-a"));
        const auto p1 = repo.list(bigChat);
        const auto p2 = repo.list(bigChat, p1.nextSince, p1.nextSinceId);
        for (const auto& r : p1.records) ordering.append(r.messageId);
        for (const auto& r : p2.records) ordering.append(r.messageId);
        check(ordering.size() == 506, QStringLiteral("506 rows across two pages"));
    }
    const QString onPage1 = ordering.value(3);
    const QString atBoundary = ordering.value(499);
    const QString onPage2 = ordering.value(500);

    // ============================================ 1. complete-by-request
    section("1. everything found on page 1: complete FOR THE REQUEST, traversal not claimed");
    {
        Tracker t;
        ArchiveRestorer r([]() { return true; }, [&]() { return uidA; },
                          [&]() { return sa.archiver(); },
                          [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p42-dev-a"), &nam); });
        ArchiveRestorer::Outcome o;
        const auto rec = r.restore(bigChat, { onPage1 }, &o);
        check(rec.size() == 1 && o.restored == 1, QStringLiteral("the message was recovered"));
        check(o.completeForRequest(), QStringLiteral("completeForRequest() is TRUE"));
        check(!o.walkComplete,
              QStringLiteral("walkComplete is FALSE - page 1 was full, later pages unseen"));
        check(o.pages == 1 && t.get == 1, QStringLiteral("no unnecessary second page"));
    }

    // ============================================ 2. spanning the boundary
    section("2. recovery spanning page 1 and page 2");
    {
        Tracker t;
        ArchiveRestorer r([]() { return true; }, [&]() { return uidA; },
                          [&]() { return sa.archiver(); },
                          [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p42-dev-a"), &nam); });
        ArchiveRestorer::Outcome o;
        const auto rec = r.restore(bigChat, { onPage1, atBoundary, onPage2 }, &o);
        check(rec.size() == 3 && o.completeForRequest(),
              QStringLiteral("all three recovered across the boundary"));
        check(o.walkComplete,
              QStringLiteral("walkComplete TRUE - it stopped on the short final page"));
        check(o.pages == 2 && t.get == 2, QStringLiteral("exactly two pages"));
        check(t.post == 0, QStringLiteral("12. no archive POST"));
    }

    // ============================================ 3. short final page
    section("3. a chat inside the cap is exhausted in one page");
    {
        Tracker t;
        ArchiveRestorer r([]() { return true; }, [&]() { return uidA; },
                          [&]() { return sa.archiver(); },
                          [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p42-dev-a"), &nam); });
        ArchiveRestorer::Outcome o;
        r.restore(smallChat, { smallId(2) }, &o);
        check(o.walkComplete && o.completeForRequest(),
              QStringLiteral("both complete: server exhausted AND request satisfied"));
        check(o.pages == 1, QStringLiteral("one page"));
    }

    // ============================================ 4/5/6/7. hostile continuations
    section("4/5/6/7. continuation edge cases");
    QJsonArray fullPage;
    {
        const auto real = plainRepo(tokA, QStringLiteral("p42-dev-a")).list(bigChat);
        for (const auto& r : real.records) {
            QJsonObject o;
            o.insert(QStringLiteral("message_id"), r.messageId);
            o.insert(QStringLiteral("chat_id"), r.chatId);
            o.insert(QStringLiteral("root_version"), r.rootVersion);
            o.insert(QStringLiteral("protocol_version"), r.protocolVersion);
            o.insert(QStringLiteral("ciphertext_b64"), r.ciphertextB64);
            o.insert(QStringLiteral("created_at"), QStringLiteral("2026-01-01T00:00:00Z"));
            fullPage.append(o);
        }
        check(fullPage.size() == ArchiveRepository::PAGE_SIZE,
              QStringLiteral("stub page is a full 500 rows"));
    }
    {
        // 4. full page + advancing cursor -> continuation occurs.
        int calls = 0;
        ArchiveRestorer adv([]() { return true; }, [&]() { return uidA; },
                            [&]() { return sa.archiver(); },
                            [&]() {
            return ArchiveRepository([&](const QString&, const QByteArray&, const char*) {
                ++calls;
                QJsonArray page = fullPage;
                // Each page carries a strictly later timestamp, so the cursor advances.
                for (int i = 0; i < page.size(); ++i) {
                    QJsonObject o = page.at(i).toObject();
                    o.insert(QStringLiteral("created_at"),
                             QStringLiteral("2026-01-%1T00:00:00Z").arg(calls + 1, 2, 10, QLatin1Char('0')));
                    page.replace(i, o);
                }
                QJsonObject body;
                body.insert(QStringLiteral("archives"), page);
                body.insert(QStringLiteral("count"), page.size());
                return QPair<int, QJsonObject>{ 200, body };
            });
        });
        ArchiveRestorer::Outcome o;
        adv.restore(bigChat, { QStringLiteral("00000000-0000-4000-8000-000000000nope") }, &o);
        check(calls > 1, QStringLiteral("4. an advancing cursor does continue past page 1"));
        check(o.pages == ArchiveRestorer::MAX_PAGES && calls == ArchiveRestorer::MAX_PAGES,
              QStringLiteral("7. and stops exactly at MAX_PAGES (%1)").arg(calls));
        check(!o.walkComplete, QStringLiteral("7. the safety bound is NOT completeness"));
        check(!o.completeForRequest(), QStringLiteral("7. and the request was not satisfied"));
    }
    {
        // 5. full page + missing cursor metadata -> incomplete, one request.
        QJsonArray noCursor;
        for (const QJsonValue& v : fullPage) {
            QJsonObject o = v.toObject();
            o.remove(QStringLiteral("created_at"));
            noCursor.append(o);
        }
        int calls = 0;
        ArchiveRestorer blind([]() { return true; }, [&]() { return uidA; },
                              [&]() { return sa.archiver(); },
                              [&]() {
            return ArchiveRepository([&](const QString&, const QByteArray&, const char*) {
                ++calls;
                QJsonObject body;
                body.insert(QStringLiteral("archives"), noCursor);
                body.insert(QStringLiteral("count"), noCursor.size());
                return QPair<int, QJsonObject>{ 200, body };
            });
        });
        ArchiveRestorer::Outcome o;
        blind.restore(bigChat, { onPage2 }, &o);
        check(calls == 1 && !o.walkComplete,
              QStringLiteral("5. a full page with no cursor stops and is incomplete"));
    }
    {
        // 6. full page + frozen cursor -> incomplete, exactly two requests.
        int calls = 0;
        ArchiveRestorer stalled([]() { return true; }, [&]() { return uidA; },
                                [&]() { return sa.archiver(); },
                                [&]() {
            return ArchiveRepository([&](const QString&, const QByteArray&, const char*) {
                ++calls;
                QJsonObject body;
                body.insert(QStringLiteral("archives"), fullPage);
                body.insert(QStringLiteral("count"), fullPage.size());
                return QPair<int, QJsonObject>{ 200, body };
            });
        });
        ArchiveRestorer::Outcome o;
        stalled.restore(bigChat, { onPage2 }, &o);
        check(calls == 2 && !o.walkComplete,
              QStringLiteral("6. a non-advancing cursor stops after the repeat, incomplete"));
    }

    // ============================================ 8. the caller under an incomplete walk
    section("8. THE POINT: an incomplete walk cannot look like a finished one");
    {
        // A stub that serves page 1 then fails: one message recovers, the other cannot.
        int calls = 0;
        auto inner = makeKeyringExchange(&nam, [&]() { return tokA; },
                                         [&]() { return QStringLiteral("p42-dev-a"); }, baseUrl);
        ArchiveRestorer partial([]() { return true; }, [&]() { return uidA; },
                                [&]() { return sa.archiver(); },
                                [&]() {
            return ArchiveRepository([&](const QString& p, const QByteArray& b, const char* m) {
                if (++calls == 1) return inner(p, b, m);           // real page 1
                return QPair<int, QJsonObject>{ 500, QJsonObject() }; // then the server dies
            });
        });
        ArchiveRestorer::Outcome o;
        const auto rec = partial.restore(bigChat, { onPage1, onPage2 }, &o);

        check(rec.contains(onPage1) && !rec.contains(onPage2),
              QStringLiteral("8. the page-1 message recovered; the page-2 one did not"));
        check(o.lookupFailed && !o.walkComplete && !o.completeForRequest(),
              QStringLiteral("8. reported as failed AND incomplete AND not satisfied"));
        check(o.restored == 1 && o.requested == 2,
              QStringLiteral("8. the counts state exactly what was and was not recovered"));

        // Now drive ChatService's own two loops over that partial result.
        QList<MessageCache::Entry> cached;
        for (const QString& id : { onPage1, onPage2 }) {
            MessageCache::Entry e;
            e.id = id; e.content = QStringLiteral("CIPHERTEXT"); e.encrypted = true;
            e.senderId = QStringLiteral("aaaa1111-0000-4000-8000-00000000dead");
            e.createdAt = QStringLiteral("2026-01-01T00:00:00Z"); e.encryptionVersion = 6;
            cached.append(e);
        }
        const CallerResult cr = applyLikeChatService(cached, { onPage1, onPage2 }, rec);
        check(cr.substituted == QStringList{ onPage1 },
              QStringLiteral("8. only the recovered row is substituted"));
        check(cr.leftAsPlaceholder == QStringList{ onPage2 },
              QStringLiteral("8. the unrecovered row keeps its placeholder"));
        check(cr.written.size() == 1 && cr.written.first().id == onPage1 &&
                  !cr.written.first().encrypted,
              QStringLiteral("8. only the recovered row is written back"));
        bool negativeCached = false;
        for (const auto& w : cr.written) if (w.id == onPage2) negativeCached = true;
        check(!negativeCached,
              QStringLiteral("8. NO negative result is cached - the next open retries"));
    }

    // ============================================ 9/10/13/14. unchanged guarantees
    section("9/10/13/14. the standing guarantees still hold");
    {
        // 9. tampered archive
        const auto page = plainRepo(tokA, QStringLiteral("p42-dev-a")).list(smallChat);
        ArchiveRecord t = page.records.first();
        QByteArray raw = HistoryCrypto::fromWireB64(t.ciphertextB64);
        raw[40] = static_cast<char>(raw[40] ^ 0x01);
        t.ciphertextB64 = HistoryCrypto::toWireB64(raw);
        QByteArray plain;
        HistoryArchiver arch = sa.archiver();
        check(!arch.open(t, plain, nullptr) && plain.isEmpty(),
              QStringLiteral("9. a tampered archive yields nothing"));

        // 10. normal decrypt
        Tracker t2;
        ArchiveRestorer quiet([]() { return true; }, [&]() { return uidA; },
                              [&]() { return sa.archiver(); },
                              [&]() { return t2.wrap(baseUrl, tokA, QStringLiteral("p42-dev-a"), &nam); });
        check(!ArchiveRestorer::needsRestore(true, false, QStringLiteral("normal plaintext")),
              QStringLiteral("10. a successful decrypt is not a candidate"));
        quiet.restore(bigChat, QStringList());
        check(t2.get == 0, QStringLiteral("10. zero archive requests"));

        // 13. account isolation
        Session sb; sb.start(uidB);
        ArchiveRestorer rb([]() { return true; }, [&]() { return uidB; },
                           [&]() { return sb.archiver(); },
                           [&]() { return plainRepo(tokB, QStringLiteral("p42-dev-b")); });
        ArchiveRestorer::Outcome bo;
        check(rb.restore(bigChat, { onPage1, onPage2 }, &bo).isEmpty() && !bo.completeForRequest(),
              QStringLiteral("13. B recovers nothing and is not 'complete'"));

        // 14. NUL / unicode
        ArchiveRestorer r([]() { return true; }, [&]() { return uidA; },
                          [&]() { return sa.archiver(); },
                          [&]() { return plainRepo(tokA, QStringLiteral("p42-dev-a")); });
        const auto nul = r.restore(bigChat, { bigId(506) });
        check(nul.value(bigId(506)) == QString::fromUtf8(nulBody()),
              QStringLiteral("14. an embedded NUL survives byte-for-byte"));
    }

    // ============================================ 11/12. Phase 40 write-back intact
    section("11/12. successful recoveries still persist and cost nothing next time");
    {
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
        QFile::remove(dir + "/" + MessageCache::databaseNameForAccount(uidA));
        MessageCache cache;
        cache.openForAccount(uidA);
        MessageCache::Entry e;
        e.id = onPage2; e.content = QStringLiteral("CIPHERTEXT"); e.encrypted = true;
        e.senderId = QStringLiteral("aaaa1111-0000-4000-8000-00000000dead");
        e.createdAt = QStringLiteral("2026-01-01T00:00:00Z"); e.encryptionVersion = 6;
        cache.saveMessages(bigChat, { e }, uidA);

        Tracker t;
        ArchiveRestorer r([]() { return true; }, [&]() { return uidA; },
                          [&]() { return sa.archiver(); },
                          [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p42-dev-a"), &nam); });
        const auto rec = r.restore(bigChat, { onPage2 });
        const CallerResult cr = applyLikeChatService(cache.loadMessages(bigChat), { onPage2 }, rec);
        cache.saveMessages(bigChat, cr.written, uidA);

        MessageCache::Entry after;
        for (const auto& c : cache.loadMessages(bigChat)) if (c.id == onPage2) after = c;
        check(!after.encrypted && after.content == rec.value(onPage2),
              QStringLiteral("11. the recovered plaintext persisted"));

        const int before = t.get;
        QStringList still;
        if (ArchiveRestorer::needsRestore(after.encrypted, false, after.content)) still.append(after.id);
        r.restore(bigChat, still);
        check(t.get == before, QStringLiteral("11. the second open performs ZERO archive GETs"));
        check(t.post == 0, QStringLiteral("12. and no archive POST at any point"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
