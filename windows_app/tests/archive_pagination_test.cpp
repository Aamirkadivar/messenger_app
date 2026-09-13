// Phase 41 - archive restore across the server's page boundary.
//
// The server caps one archive page at 500 rows (maxArchiveListed) and offers a keyset cursor:
// results are ordered by (created_at, message_id) and `since` + `since_id` are compared against the
// same pair. Until now the Windows restore path issued exactly one request and stopped, so a chat
// with more than 500 archives could silently fail to recover a message that happened to sit past
// the boundary. That is the gap this proves closed.
//
// Both halves matter equally. Walking must find everything, and it must never become an unbounded
// request loop - so the stub sections below drive a server that repeats a page, one that returns no
// cursor, and one that never advances, and require the walk to stop and say it is incomplete rather
// than spin or claim success.
//
// Isolated backend only. No plaintext, key, root, or token is printed.
//
// Usage: archive_pagination_test <baseUrl> <tokA> <uidA> <tokB> <uidB> <bigChat> <smallChat>

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

/** Deterministic body per index, exercising ASCII, multibyte UTF-8 and high-bit bytes. */
static QString bodyFor(int i) {
    return QString::fromUtf8("P41 #%1 \xC3\xA9\xC3\xB6 \xE2\x9C\x93 \xE6\x97\xA5\xE6\x9C\xAC "
                             "\xCE\x95\xCE\xBB \xC3\xBF\xC3\xBE").arg(i);
}
/** The 505 seeded message ids, in the order they were inserted. */
static QString bigMessageId(int i) {
    return QStringLiteral("41aaaaaa-0000-4000-8000-%1").arg(i, 12, 10, QLatin1Char('0'));
}
static QString smallMessageId(int i) {
    return QStringLiteral("41bbbbbb-0000-4000-8000-%1").arg(i, 12, 10, QLatin1Char('0'));
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
            nullptr,
            [this]() { return vault.isUnlocked(); });
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

/** Counts requests and records the cursor of every page asked for. */
struct Tracker {
    int get = 0;
    int post = 0;
    QStringList cursors; ///< "" for the first page, then the since/since_id pair sent
    ArchiveRepository wrap(const QString& base, const QString& tok, const QString& dev,
                           QNetworkAccessManager* nam) {
        auto inner = makeKeyringExchange(nam, [tok]() { return tok; }, [dev]() { return dev; }, base);
        return ArchiveRepository([this, inner](const QString& path, const QByteArray& body,
                                               const char* method) {
            if (qstrcmp(method, "GET") == 0) {
                ++get;
                const int q = path.indexOf(QStringLiteral("since="));
                cursors.append(q < 0 ? QString() : path.mid(q));
            } else if (qstrcmp(method, "POST") == 0) {
                ++post;
            }
            return inner(path, body, method);
        });
    }
};

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

    out << "Phase 41 - archive restore pagination" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;
    out << "server page cap: " << ArchiveRepository::PAGE_SIZE << Qt::endl;

    Session sa; sa.start(uidA);

    // ============================================ seed 505 + 3 archives
    section("seed: 506 archives in one chat, 3 in another");
    {
        HistoryArchiver arch = sa.archiver();
        const ArchiveRepository repo = plainRepo(tokA, QStringLiteral("p41-dev-a"));
        int stored = 0;
        for (int i = 1; i <= 506; ++i) {
            ArchiveMessage m;
            m.userId = uidA; m.chatId = bigChat; m.messageId = bigMessageId(i);
            m.plaintext = bodyFor(i).toUtf8();
            // One message carries an embedded NUL, which the archive layer must preserve.
            if (i == 506) m.plaintext = (QByteArray("nul") + QByteArray(1, char(0)) + QByteArray("inside"));
            ArchiveRecord rec;
            if (arch.seal(m, rec, nullptr) && repo.save(rec).stored) ++stored;
        }
        check(stored == 506, QStringLiteral("506 archives newly stored (got %1)").arg(stored));

        int small = 0;
        for (int i = 1; i <= 3; ++i) {
            ArchiveMessage m;
            m.userId = uidA; m.chatId = smallChat; m.messageId = smallMessageId(i);
            m.plaintext = bodyFor(1000 + i).toUtf8();
            ArchiveRecord rec;
            if (arch.seal(m, rec, nullptr) && repo.save(rec).stored) ++small;
        }
        check(small == 3, QStringLiteral("3 archives stored in the small chat"));
    }

    // ============================================ the boundary is real
    section("the server really does cap a page at 500");
    QStringList ordering;
    {
        const ArchiveRepository repo = plainRepo(tokA, QStringLiteral("p41-dev-a"));
        const auto page1 = repo.list(bigChat);
        check(page1.outcome == ArchiveRepository::Outcome::Ok, QStringLiteral("page 1 fetched"));
        check(page1.records.size() == 500,
              QStringLiteral("page 1 holds exactly 500 rows - the 500/501 boundary"));
        check(!page1.nextSince.isEmpty() && !page1.nextSinceId.isEmpty(),
              QStringLiteral("page 1 yields a usable (created_at, message_id) cursor"));

        const auto page2 = repo.list(bigChat, page1.nextSince, page1.nextSinceId);
        check(page2.outcome == ArchiveRepository::Outcome::Ok, QStringLiteral("page 2 fetched"));
        check(page2.records.size() == 6,
              QStringLiteral("page 2 holds the remaining 6 - a short page ends the sequence"));

        for (const auto& r : page1.records) ordering.append(r.messageId);
        for (const auto& r : page2.records) ordering.append(r.messageId);
        check(ordering.size() == 506, QStringLiteral("506 distinct rows across two pages"));
        check(QSet<QString>(ordering.begin(), ordering.end()).size() == 506,
              QStringLiteral("no row is duplicated across the page boundary"));

        // The single-page chat behaves exactly as before.
        const auto small = repo.list(smallChat);
        check(small.records.size() == 3 && small.records.size() < ArchiveRepository::PAGE_SIZE,
              QStringLiteral("the small chat returns one short page"));
    }

    const QString beforeBoundary = ordering.value(0);
    const QString atBoundary     = ordering.value(499); // last row of page 1
    const QString afterBoundary  = ordering.value(500); // first row of page 2
    const QString lastOfAll      = ordering.value(505);

    // ============================================ 2/3. recovery across the boundary
    section("recovery before, at and after the page boundary");
    {
        Tracker t;
        ArchiveRestorer restorer(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });

        ArchiveRestorer::Outcome o;
        const auto rec = restorer.restore(
            bigChat, { beforeBoundary, atBoundary, afterBoundary, lastOfAll }, &o);

        check(o.restored == 4 && rec.size() == 4,
              QStringLiteral("all four messages recovered (%1)").arg(o.restored));
        check(rec.contains(beforeBoundary), QStringLiteral("recovered the row before the boundary"));
        check(rec.contains(atBoundary), QStringLiteral("recovered the row AT the boundary (#500)"));
        check(rec.contains(afterBoundary), QStringLiteral("recovered the row AFTER it (#501)"));
        check(rec.contains(lastOfAll), QStringLiteral("recovered the final row"));
        check(o.pages == 2, QStringLiteral("exactly two pages were requested (%1)").arg(o.pages));
        check(t.get == 2, QStringLiteral("exactly two archive GETs"));
        check(t.post == 0, QStringLiteral("13. no archive POST: restoring never re-archives"));
        check(o.walkComplete, QStringLiteral("the walk reports itself complete"));
        check(t.cursors.size() == 2 && t.cursors.at(0).isEmpty() && !t.cursors.at(1).isEmpty(),
              QStringLiteral("page 1 sent no cursor, page 2 sent one"));

        // 14. exact plaintext, including the NUL-bearing body.
        const int idx = ordering.indexOf(bigMessageId(506));
        check(idx >= 0, QStringLiteral("the NUL-bearing message is in the ordering"));
        const auto nulRec = restorer.restore(bigChat, { bigMessageId(506) });
        check(nulRec.value(bigMessageId(506)) == QString::fromUtf8(QByteArray("nul") + QByteArray(1, char(0)) + QByteArray("inside")),
              QStringLiteral("14. an embedded NUL survives the archive round trip"));
    }

    // ============================================ 1. single page is unchanged
    section("1. a chat within the cap still costs one request");
    {
        Tracker t;
        ArchiveRestorer restorer(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });
        ArchiveRestorer::Outcome o;
        const auto rec = restorer.restore(smallChat, { smallMessageId(2) }, &o);
        check(rec.size() == 1 && o.pages == 1 && t.get == 1,
              QStringLiteral("one page, one GET, one recovery"));
        check(o.walkComplete, QStringLiteral("short page completes the walk"));
    }

    // ============================================ early stop
    section("the walk stops as soon as everything asked for is found");
    {
        Tracker t;
        ArchiveRestorer restorer(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });
        ArchiveRestorer::Outcome o;
        restorer.restore(bigChat, { beforeBoundary }, &o);
        check(o.pages == 1 && t.get == 1,
              QStringLiteral("a row on page 1 does not drag the whole history down"));
    }

    // ============================================ 5. determinism
    section("5. the page sequence is deterministic");
    {
        Tracker t1, t2;
        ArchiveRestorer r1([]() { return true; }, [&]() { return uidA; },
                           [&]() { return sa.archiver(); },
                           [&]() { return t1.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });
        ArchiveRestorer r2([]() { return true; }, [&]() { return uidA; },
                           [&]() { return sa.archiver(); },
                           [&]() { return t2.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });
        const auto a = r1.restore(bigChat, { afterBoundary });
        const auto b = r2.restore(bigChat, { afterBoundary });
        check(t1.cursors == t2.cursors, QStringLiteral("identical cursor sequence across runs"));
        check(a == b && a.size() == 1, QStringLiteral("identical recovery across runs"));
    }

    // ============================================ 4/6/7. hostile servers
    section("4/6/7. a misbehaving server cannot cause a loop or a false success");
    {
        // A full page whose rows never change: the cursor cannot advance.
        const ArchiveRepository probe = plainRepo(tokA, QStringLiteral("p41-dev-a"));
        const auto realPage = probe.list(bigChat);
        QJsonArray frozen;
        for (int i = 0; i < realPage.records.size(); ++i) {
            const auto& r = realPage.records.at(i);
            QJsonObject o;
            o.insert(QStringLiteral("message_id"), r.messageId);
            o.insert(QStringLiteral("chat_id"), r.chatId);
            o.insert(QStringLiteral("root_version"), r.rootVersion);
            o.insert(QStringLiteral("protocol_version"), r.protocolVersion);
            o.insert(QStringLiteral("ciphertext_b64"), r.ciphertextB64);
            o.insert(QStringLiteral("created_at"), QStringLiteral("2026-01-01T00:00:00Z"));
            frozen.append(o);
        }

        int stalls = 0;
        ArchiveRestorer stalled(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() {
                return ArchiveRepository([&](const QString&, const QByteArray&, const char*) {
                    ++stalls;
                    QJsonObject body;
                    body.insert(QStringLiteral("archives"), frozen);
                    body.insert(QStringLiteral("count"), frozen.size());
                    return QPair<int, QJsonObject>{ 200, body };
                });
            });
        ArchiveRestorer::Outcome so;
        stalled.restore(bigChat, { afterBoundary }, &so);
        check(so.pages == 2 && stalls == 2,
              QStringLiteral("6. a non-advancing cursor stops after the repeat (%1 requests)").arg(stalls));
        check(!so.walkComplete, QStringLiteral("6. and the walk is NOT reported complete"));

        // 4. the repeated page cannot corrupt the result: a duplicate row is ignored.
        ArchiveRestorer::Outcome dup;
        const auto dupRec = stalled.restore(bigChat, { beforeBoundary }, &dup);
        check(dupRec.size() == 1 && dup.restored == 1,
              QStringLiteral("4. a duplicated row recovers once, not twice"));

        // 7. a full page carrying no usable cursor cannot be continued.
        QJsonArray noCursor;
        for (const QJsonValue& v : frozen) {
            QJsonObject o = v.toObject();
            o.remove(QStringLiteral("created_at"));
            noCursor.append(o);
        }
        int calls = 0;
        ArchiveRestorer blind(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() {
                return ArchiveRepository([&](const QString&, const QByteArray&, const char*) {
                    ++calls;
                    QJsonObject body;
                    body.insert(QStringLiteral("archives"), noCursor);
                    body.insert(QStringLiteral("count"), noCursor.size());
                    return QPair<int, QJsonObject>{ 200, body };
                });
            });
        ArchiveRestorer::Outcome bo;
        blind.restore(bigChat, { afterBoundary }, &bo);
        check(calls == 1 && bo.pages == 1,
              QStringLiteral("7. a page with no cursor stops immediately"));
        check(!bo.walkComplete, QStringLiteral("7. and is not reported complete"));
    }

    // ============================================ 8/9/10/11. existing behaviour preserved
    section("8/9/10/11. the Phase 39 failure and priority rules still hold");
    {
        ArchiveRestorer live(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return plainRepo(tokA, QStringLiteral("p41-dev-a")); });

        // 8. missing archive
        ArchiveRestorer::Outcome mo;
        check(live.restore(bigChat, { QStringLiteral("deadbeef-0000-4000-8000-000000000fff") }, &mo)
                  .isEmpty(),
              QStringLiteral("8. a message with no archive recovers nothing"));

        // 9. tampered archive
        const auto page = plainRepo(tokA, QStringLiteral("p41-dev-a")).list(smallChat);
        ArchiveRecord t = page.records.first();
        QByteArray raw = HistoryCrypto::fromWireB64(t.ciphertextB64);
        raw[40] = static_cast<char>(raw[40] ^ 0x01);
        t.ciphertextB64 = HistoryCrypto::toWireB64(raw);
        QByteArray plain;
        HistoryArchiver arch = sa.archiver();
        check(!arch.open(t, plain, nullptr) && plain.isEmpty(),
              QStringLiteral("9. a tampered archive still does not open"));

        // 10. backend unavailable
        ArchiveRestorer dead(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return ArchiveRepository(makeKeyringExchange(
                &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p41-dev-a"); },
                QStringLiteral("http://127.0.0.1:1/api/v1"))); });
        ArchiveRestorer::Outcome dop;
        check(dead.restore(bigChat, { afterBoundary }, &dop).isEmpty() && dop.lookupFailed &&
                  !dop.walkComplete,
              QStringLiteral("10. an unreachable backend fails closed and is not 'complete'"));

        // 11. a normal successful decrypt never reaches the archive
        Tracker t2;
        ArchiveRestorer quiet(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return t2.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });
        check(!ArchiveRestorer::needsRestore(true, false, QStringLiteral("normal plaintext")),
              QStringLiteral("11. a successful decrypt is not a restore candidate"));
        quiet.restore(bigChat, QStringList());
        check(t2.get == 0, QStringLiteral("11. zero archive requests"));
    }

    // ============================================ 12. Phase 40 write-back still applies
    section("12. recovered plaintext persists; the next open needs no request");
    {
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
        QFile::remove(dir + "/" + MessageCache::databaseNameForAccount(uidA));
        MessageCache cache;
        cache.openForAccount(uidA);

        MessageCache::Entry e;
        e.id = afterBoundary;
        e.senderId = QStringLiteral("aaaa1111-0000-4000-8000-00000000dead");
        e.content = QStringLiteral("CIPHERTEXT");
        e.encrypted = true;
        e.createdAt = QStringLiteral("2026-01-01T00:00:00Z");
        e.encryptionVersion = 6;
        cache.saveMessages(bigChat, { e }, uidA);

        Tracker t;
        ArchiveRestorer restorer(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return t.wrap(baseUrl, tokA, QStringLiteral("p41-dev-a"), &nam); });
        const auto rec = restorer.restore(bigChat, { afterBoundary });

        QList<MessageCache::Entry> rows;
        for (const MessageCache::Entry& c : cache.loadMessages(bigChat)) {
            const auto it = rec.constFind(c.id);
            if (it != rec.constEnd()) rows.append(MessageCache::withRecoveredPlaintext(c, *it));
        }
        cache.saveMessages(bigChat, rows, uidA);

        MessageCache::Entry after;
        bool found = false;
        for (const MessageCache::Entry& c : cache.loadMessages(bigChat))
            if (c.id == afterBoundary) { after = c; found = true; }
        check(found && !after.encrypted && after.content == rec.value(afterBoundary),
              QStringLiteral("12. the cache holds the recovered plaintext"));

        const int getsAfterRestore = t.get;
        // Second open: the row is plaintext, so nothing qualifies and nothing is requested.
        QStringList unrecovered;
        if (ArchiveRestorer::needsRestore(after.encrypted, false, after.content))
            unrecovered.append(after.id);
        restorer.restore(bigChat, unrecovered);
        check(t.get == getsAfterRestore,
              QStringLiteral("12. the second open performs ZERO further archive GETs"));
        check(t.post == 0, QStringLiteral("12. still no archive POST"));
    }

    // ============================================ 15. account isolation
    section("15. another account cannot recover across pages either");
    {
        Session sb; sb.start(uidB);
        Tracker t;
        ArchiveRestorer restorerB(
            []() { return true; }, [&]() { return uidB; }, [&]() { return sb.archiver(); },
            [&]() { return t.wrap(baseUrl, tokB, QStringLiteral("p41-dev-b"), &nam); });
        ArchiveRestorer::Outcome o;
        check(restorerB.restore(bigChat, { beforeBoundary, atBoundary, afterBoundary }, &o).isEmpty(),
              QStringLiteral("15. B recovers nothing from A's archives"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
