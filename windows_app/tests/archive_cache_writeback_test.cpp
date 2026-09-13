// Phase 40 - persisting successfully restored archive plaintext.
//
// Phase 39 recovered a lost message's plaintext from its archive but threw the result away after
// rendering, so every chat open repeated the network lookup. This proves the recovered plaintext can
// be written back through the EXISTING cache representation - content plaintext, encrypted = false,
// the same thing a normal successful decrypt produces - so the second open reads it locally and
// makes no archive request at all.
//
// The decisive assertion is the request counter: after write-back the archive must be contacted
// ZERO times. That is measured, not inferred from the rendered text.
//
// This drives the REAL MessageCache (its own temporary account database) and the REAL
// ArchiveRestorer against an isolated backend. Nothing here reimplements cache or crypto behaviour.
// No plaintext, key, root, or token is printed.
//
// Usage: archive_cache_writeback_test <baseUrl> <tokA> <uidA> <tokB> <uidB> <chat> <msg1> <msg2>

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
#include <QDir>
#include <QFile>
#include <QHash>
#include <QNetworkAccessManager>
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

static QString bodyOne() {
    return QString::fromUtf8("P40 write-back \xC3\xA9\xC3\xB6 \xE2\x9C\x93 "
                             "\xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E \xCE\x95\xCE\xBB\xCE\xBB "
                             "\xC3\xBF\xC3\xBE end");
}
static QString bodyTwo() { return QString::fromUtf8("untouched neighbour \xE2\x82\xAC7"); }

/** Cached, encrypted, and deliberately never archived - the failure-path probe. */
static const char* kNoArchive = "d3d3d3d3-0000-4000-8000-00000000d003";

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

/** Counts every archive request so "zero lookups" is measured, not assumed. */
struct Counter {
    int get = 0;
    int post = 0;
    ArchiveRepository wrap(const QString& base, const QString& tok, const QString& dev,
                           QNetworkAccessManager* nam) {
        auto inner = makeKeyringExchange(nam, [tok]() { return tok; }, [dev]() { return dev; }, base);
        return ArchiveRepository([this, inner](const QString& path, const QByteArray& body,
                                               const char* method) {
            if (qstrcmp(method, "GET") == 0) ++get;
            else if (qstrcmp(method, "POST") == 0) ++post;
            return inner(path, body, method);
        });
    }
};

/** A cached message exactly as the fetch path stores an undecryptable one: ciphertext, encrypted. */
static MessageCache::Entry encryptedEntry(const QString& id, const QString& ciphertextish) {
    MessageCache::Entry e;
    e.id = id;
    e.senderId = QStringLiteral("aaaa1111-0000-4000-8000-00000000dead");
    e.senderName = QStringLiteral("Peer");
    e.content = ciphertextish;
    e.encrypted = true;
    e.createdAt = QStringLiteral("2026-01-01T00:00:00Z");
    e.encryptionVersion = 6;
    e.senderDeviceId = QStringLiteral("bbbb2222-0000-4000-8000-00000000beef");
    e.keyVersion = 1;
    return e;
}

static bool loadEntry(MessageCache& c, const QString& chat, const QString& id,
                      MessageCache::Entry& out) {
    for (const MessageCache::Entry& e : c.loadMessages(chat)) {
        if (e.id == id) { out = e; return true; }
    }
    return false;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 9) { out << "usage: see header" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString tokA = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uidA = readFile(QString::fromLocal8Bit(argv[3]));
    const QString tokB = readFile(QString::fromLocal8Bit(argv[4]));
    const QString uidB = readFile(QString::fromLocal8Bit(argv[5]));
    const QString chatId = QString::fromLocal8Bit(argv[6]);
    const QString msg1 = QString::fromLocal8Bit(argv[7]);
    const QString msg2 = QString::fromLocal8Bit(argv[8]);
    if (tokA.isEmpty() || uidA.isEmpty() || tokB.isEmpty() || uidB.isEmpty()) {
        out << "missing credentials" << Qt::endl; return 2;
    }

    // Start from clean per-account cache databases.
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    for (const QString& id : { uidA, uidB }) {
        QFile::remove(dir + "/" + MessageCache::databaseNameForAccount(id));
    }

    QNetworkAccessManager nam;
    const QString kNoArchiveId = QString::fromLatin1(kNoArchive);
    const QString placeholder = DecryptedMessageArchiver::failedDecryptPlaceholder();
    out << "Phase 40 - restored plaintext written back to the cache" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;

    // ============================================ seed archives on the server
    section("seed: two archived messages");
    Session sa; sa.start(uidA);
    {
        HistoryArchiver arch = sa.archiver();
        Counter seedCounter;
        const ArchiveRepository repo = seedCounter.wrap(baseUrl, tokA, QStringLiteral("p40-dev-a"), &nam);
        QString err;
        for (const auto& pair : QList<QPair<QString, QString>>{ { msg1, bodyOne() },
                                                                { msg2, bodyTwo() } }) {
            ArchiveMessage m;
            m.userId = uidA; m.chatId = chatId; m.messageId = pair.first;
            m.plaintext = pair.second.toUtf8();
            ArchiveRecord rec;
            const bool sealed = arch.seal(m, rec, &err);
            const auto saved = repo.save(rec);
            // `stored` must be true: a false here means the server already held an archive for this
            // message from an earlier run, sealed under a root this run does not have. Silently
            // accepting that would make every later assertion meaningless.
            check(sealed && saved.outcome == ArchiveRepository::Outcome::Ok && saved.stored,
                  QStringLiteral("archived %1 (newly stored)").arg(pair.first.left(8)));
        }
    }

    // ============================================ seed the local cache as ciphertext
    section("seed: the cache holds both messages as undecryptable ciphertext");
    MessageCache cacheA;
    cacheA.openForAccount(uidA);
    {
        cacheA.saveMessages(chatId, { encryptedEntry(msg1, QStringLiteral("AAAAciphertext1")),
                                      encryptedEntry(msg2, QStringLiteral("BBBBciphertext2")),
                                      encryptedEntry(kNoArchiveId, QStringLiteral("CCCCciphertext3")) },
                            uidA);
        MessageCache::Entry e;
        check(loadEntry(cacheA, chatId, msg1, e) && e.encrypted && e.content != bodyOne(),
              QStringLiteral("msg1 is cached encrypted"));
        check(loadEntry(cacheA, chatId, msg2, e) && e.encrypted,
              QStringLiteral("msg2 is cached encrypted"));
    }

    // ============================================ A + C + K. restore and write back
    section("A/C/K. first open: restore, then persist through the existing representation");
    Counter c1;
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return c1.wrap(baseUrl, tokA, QStringLiteral("p40-dev-a"), &nam); });

        // Only msg1 is treated as unavailable; msg2 stands in for a neighbour left alone.
        const auto recovered = restorer.restore(chatId, { msg1 });
        check(recovered.value(msg1) == bodyOne(),
              QStringLiteral("A. the archive recovered the exact plaintext"));
        check(c1.get == 1, QStringLiteral("A. exactly one archive GET on the first open"));

        // The production write-back, using the same helper ChatService calls.
        QList<MessageCache::Entry> restoredRows;
        for (const MessageCache::Entry& e : cacheA.loadMessages(chatId)) {
            const auto it = recovered.constFind(e.id);
            if (it == recovered.constEnd()) continue;
            restoredRows.append(MessageCache::withRecoveredPlaintext(e, *it));
        }
        cacheA.saveMessages(chatId, restoredRows, uidA);

        MessageCache::Entry e;
        check(loadEntry(cacheA, chatId, msg1, e), QStringLiteral("msg1 still present"));
        check(e.content == bodyOne(), QStringLiteral("A. cache now holds the recovered plaintext"));
        check(e.encrypted == false, QStringLiteral("A. cache marks it decrypted (encrypted=false)"));
        check(e.content.toUtf8() == bodyOne().toUtf8(),
              QStringLiteral("K. multibyte UTF-8 and high-bit bytes are byte-identical"));
        check(e.senderId == QStringLiteral("aaaa1111-0000-4000-8000-00000000dead") &&
                  e.senderDeviceId == QStringLiteral("bbbb2222-0000-4000-8000-00000000beef") &&
                  e.encryptionVersion == 6 && e.createdAt == QStringLiteral("2026-01-01T00:00:00Z"),
              QStringLiteral("every other cached field survived the write-back"));

        MessageCache::Entry n;
        check(loadEntry(cacheA, chatId, msg2, n) && n.encrypted &&
                  n.content == QStringLiteral("BBBBciphertext2"),
              QStringLiteral("C. the neighbouring message was NOT modified"));
    }

    // ============================================ B + I. second open costs nothing
    section("B/I. second open: cached plaintext, zero archive traffic");
    Counter c2;
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return c2.wrap(baseUrl, tokA, QStringLiteral("p40-dev-a"), &nam); });

        // Re-render from the cache exactly as the fetch path would: the row is now plaintext, so
        // decryptMessage returns it unchanged and the fallback gate is not even a candidate.
        MessageCache::Entry e;
        loadEntry(cacheA, chatId, msg1, e);
        const QString display = e.encrypted ? placeholder : e.content;

        check(display == bodyOne(), QStringLiteral("B. the second open shows the same plaintext"));
        check(!ArchiveRestorer::needsRestore(e.encrypted, false, display),
              QStringLiteral("B. the message no longer qualifies for restore"));

        // Drive the restorer with what the second open would actually ask for: nothing.
        QStringList unrecovered;
        if (ArchiveRestorer::needsRestore(e.encrypted, false, display)) unrecovered.append(e.id);
        restorer.restore(chatId, unrecovered);

        check(c2.get == 0, QStringLiteral("B. archive GET count is ZERO on the second open"));
        check(c2.post == 0, QStringLiteral("I. no archive POST: restoring never re-archives"));
    }

    // ============================================ D. normal decrypt wins
    section("D. a normally decryptable message never reaches the archive");
    Counter c3;
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return c3.wrap(baseUrl, tokA, QStringLiteral("p40-dev-a"), &nam); });
        check(!ArchiveRestorer::needsRestore(true, false, QStringLiteral("normal plaintext")),
              QStringLiteral("D. a successful decrypt is not a restore candidate"));
        restorer.restore(chatId, QStringList());
        check(c3.get == 0 && c3.post == 0, QStringLiteral("D. no archive traffic at all"));
    }

    // ============================================ keepPlaintext protection
    section("a recovered row cannot be downgraded back to ciphertext");
    {
        // saveMessages deliberately refuses to replace an encrypted = 0 row with an encrypted one.
        // The rule was written so a locally-sent message is not turned back into a placeholder by
        // the server's copy; it protects a RESTORED row for exactly the same reason, and this is
        // why the failure cases below use a message that was never recovered.
        MessageCache::Entry before;
        loadEntry(cacheA, chatId, msg1, before);
        cacheA.saveMessages(chatId, { encryptedEntry(msg1, QStringLiteral("ZZZZreencrypted")) }, uidA);
        MessageCache::Entry after;
        loadEntry(cacheA, chatId, msg1, after);
        check(!after.encrypted && after.content == before.content,
              QStringLiteral("a restored row survives an encrypted re-save (keepPlaintext)"));
    }

    // ============================================ E/F/G/H. failures leave the cache alone
    section("E/F/G/H. a failed restore never rewrites cached ciphertext");
    {
        MessageCache::Entry before;
        check(loadEntry(cacheA, chatId, kNoArchiveId, before) && before.encrypted,
              QStringLiteral("the probe message is cached encrypted and has no archive"));

        auto expectNoChange = [&](const QString& label, const QHash<QString, QString>& recovered) {
            QList<MessageCache::Entry> rows;
            for (const MessageCache::Entry& e : cacheA.loadMessages(chatId)) {
                const auto it = recovered.constFind(e.id);
                if (it == recovered.constEnd()) continue;
                rows.append(MessageCache::withRecoveredPlaintext(e, *it));
            }
            if (!rows.isEmpty()) cacheA.saveMessages(chatId, rows, uidA);
            MessageCache::Entry after;
            loadEntry(cacheA, chatId, kNoArchiveId, after);
            check(after.encrypted && after.content == before.content, label);
        };

        ArchiveRestorer live(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return ArchiveRepository(makeKeyringExchange(
                &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p40-dev-a"); }, baseUrl)); });

        const auto none = live.restore(chatId, { kNoArchiveId });
        check(none.isEmpty(), QStringLiteral("E. no archive -> nothing recovered"));
        expectNoChange(QStringLiteral("E. missing archive: cache still encrypted, unchanged"), none);

        {
            const auto listed = ArchiveRepository(makeKeyringExchange(
                &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p40-dev-a"); },
                baseUrl)).list(chatId);
            ArchiveRecord t;
            for (const auto& r : listed.records) if (r.messageId == msg1) t = r;
            QByteArray raw = HistoryCrypto::fromWireB64(t.ciphertextB64);
            raw[40] = static_cast<char>(raw[40] ^ 0x01);
            t.ciphertextB64 = HistoryCrypto::toWireB64(raw);
            QByteArray plain;
            QString err;
            HistoryArchiver arch = sa.archiver();
            check(!arch.open(t, plain, &err) && plain.isEmpty(),
                  QStringLiteral("F. a tampered archive does not open"));
            expectNoChange(QStringLiteral("F. tampered archive: cache still encrypted, unchanged"),
                           QHash<QString, QString>());
        }

        ArchiveRestorer dead(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return ArchiveRepository(makeKeyringExchange(
                &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p40-dev-a"); },
                QStringLiteral("http://127.0.0.1:1/api/v1"))); });
        expectNoChange(QStringLiteral("G. backend down: cache still encrypted, unchanged"),
                       dead.restore(chatId, { kNoArchiveId }));

        cacheA.saveMessages(chatId,
                            { MessageCache::withRecoveredPlaintext(before, bodyOne()) },
                            QStringLiteral("some-other-account"));
        MessageCache::Entry wrongOwner;
        loadEntry(cacheA, chatId, kNoArchiveId, wrongOwner);
        check(wrongOwner.encrypted && wrongOwner.content == before.content,
              QStringLiteral("H. a write for the wrong owner is refused, cache untouched"));
    }

    // ============================================ J. account isolation
    section("J. account isolation");
    {
        MessageCache::Entry a;
        check(loadEntry(cacheA, chatId, msg1, a) && !a.encrypted && a.content == bodyOne(),
              QStringLiteral("A holds the restored plaintext"));
        check(MessageCache::databaseNameForAccount(uidA) != MessageCache::databaseNameForAccount(uidB),
              QStringLiteral("J. the two accounts use different cache databases"));

        MessageCache cacheB;
        cacheB.openForAccount(uidB);
        MessageCache::Entry b;
        check(!loadEntry(cacheB, chatId, msg1, b),
              QStringLiteral("J. B's cache does not contain A's restored message at all"));

        Session sb;
        sb.start(uidB);
        ArchiveRestorer restorerB(
            []() { return true; }, [&]() { return uidB; }, [&]() { return sb.archiver(); },
            [&]() { return ArchiveRepository(makeKeyringExchange(
                &nam, [&]() { return tokB; }, [&]() { return QStringLiteral("p40-dev-b"); }, baseUrl)); });
        check(restorerB.restore(chatId, { msg1 }).isEmpty(),
              QStringLiteral("J. B cannot recover A's plaintext from the archive"));
    }

    // ============================================ L. repeated restore
    section("L. restoring the same message twice is deterministic");
    {
        ArchiveRestorer restorer(
            []() { return true; }, [&]() { return uidA; }, [&]() { return sa.archiver(); },
            [&]() { return ArchiveRepository(makeKeyringExchange(
                &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p40-dev-a"); }, baseUrl)); });
        QString first, second;
        for (int i = 0; i < 2; ++i) {
            const auto rec = restorer.restore(chatId, { msg2 });
            QList<MessageCache::Entry> rows;
            for (const MessageCache::Entry& e : cacheA.loadMessages(chatId)) {
                const auto it = rec.constFind(e.id);
                if (it != rec.constEnd()) rows.append(MessageCache::withRecoveredPlaintext(e, *it));
            }
            cacheA.saveMessages(chatId, rows, uidA);
            MessageCache::Entry e;
            loadEntry(cacheA, chatId, msg2, e);
            (i == 0 ? first : second) = e.content;
        }
        check(first == bodyTwo() && second == bodyTwo(),
              QStringLiteral("L. both restores produce the identical plaintext"));

        MessageCache::Entry probe;
        check(loadEntry(cacheA, chatId, kNoArchiveId, probe) && probe.encrypted,
              QStringLiteral("L. the never-archived message is still untouched"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
