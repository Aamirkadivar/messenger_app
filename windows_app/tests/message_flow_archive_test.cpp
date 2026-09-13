// Phase 38 - the first real message-flow archive caller.
//
// ChatService::fetchMessages already decides, for every received message, whether `display` is a
// genuine decryption or a failure - that decision governs whether plaintext may be cached. Phase 38
// invokes DecryptedMessageArchiver at exactly that branch, so archiving and caching can never
// disagree about what succeeded.
//
// The hard gate proven here is negative: a FAILED decryption must never reach the archive. The
// failure representation is the literal "\xF0\x9F\x94\x92 Encrypted message" placeholder, and
// archiving it would store the failure string as though it were the user's message - permanently,
// because the MLS ratchet secret that produced the real plaintext is already consumed.
//
// The class under test is the same one ChatService calls, wired with the same four ports
// AuthService supplies. The end-to-end section drives it against a real isolated backend and then
// reopens the stored archive through the real HistoryCrypto path.
//
// No plaintext, key, root, token, or ciphertext is printed. Isolated backend only.
//
// Usage: message_flow_archive_test <baseUrl> <tokA> <uidA> <tokB> <uidB> <chat> <msg1> <msg2>

#include "../src/crypto/decryptedmessagearchiver.h"
#include "../src/crypto/archiverepository.h"
#include "../src/crypto/historyarchiver.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QCoreApplication>
#include <QFile>
#include <QHash>
#include <QNetworkAccessManager>
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

/**
 * A message body exercising the byte classes the archive must not touch: ASCII, multibyte UTF-8,
 * and a high-bit sequence. NUL is deliberately absent - ChatService carries message bodies as
 * QString, so a NUL cannot occur in this path and pretending otherwise would test a fiction.
 */
static QString awkwardBody() {
    return QString::fromUtf8("P38 \xC3\xA9\xC3\xB6 \xE2\x9C\x93 \xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E "
                             "\xCE\x95\xCE\xBB\xCE\xBB \xC3\xBF\xC3\xBE end");
}

// ------------------------------------------------------------------ session stand-ins

class FakeVault {
public:
    void unlock(const QString& u) {
        m_userId = u;
        m_mk.resize(32);
        for (int i = 0; i < 32; ++i) m_mk[i] = static_cast<char>(0x70 + i);
    }
    void lock() { m_mk.clear(); }
    bool isUnlocked() const { return !m_mk.isEmpty(); }
    static QByteArray aad(const QString& u) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(u).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    bool seal(const QByteArray& p, QByteArray& o) const {
        if (m_mk.isEmpty()) return false;
        o = VaultCrypto::sealXChaCha(m_mk, p, aad(m_userId));
        return !o.isEmpty();
    }
    bool open(const QByteArray& s, QByteArray& o) const {
        if (m_mk.isEmpty()) return false;
        o = VaultCrypto::openXChaCha(m_mk, s, aad(m_userId));
        return !o.isEmpty();
    }
private:
    QByteArray m_mk;
    QString m_userId;
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

/** Everything AuthService supplies to the caller, assembled the same way. */
struct Session {
    MemStore store;
    FakeVault vault;
    QString owner;
    int rootDraws = 0;
    std::unique_ptr<HistoryKeyringRepository> repo;

    void start(const QString& account, bool unlocked) {
        owner = account;
        if (unlocked) vault.unlock(account);
        repo = std::make_unique<HistoryKeyringRepository>(
            &store,
            [this](const QByteArray& p, QByteArray& o) { return vault.seal(p, o); },
            [this](const QByteArray& s, QByteArray& o) { return vault.open(s, o); },
            [this]() {
                ++rootDraws;
                QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
                randombytes_buf(r.data(), static_cast<size_t>(r.size()));
                return r;
            },
            [this]() { return vault.isUnlocked(); });
    }
    HistoryArchiver archiver() {
        return HistoryArchiver(
            [this](const QString& chatId, HistoryRootEntry& out, QString* err) {
                return repo->ensureRoot(owner, chatId, out, err);
            },
            [this](const QString& chatId, int rv, HistoryRootEntry& out, QString* err) {
                HistoryKeyring keyring;
                if (!repo->load(owner, keyring, err)) return false;
                if (!keyring.find(chatId, rv, out)) {
                    if (err) *err = QStringLiteral("root version not held");
                    return false;
                }
                return true;
            },
            [this]() { return owner; });
    }
};

/** Counts repository calls so "save must not be called" can be asserted, not inferred. */
struct SaveSpy {
    int calls = 0;
};

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

    QNetworkAccessManager nam;
    auto repoFor = [&](const QString& tok, const QString& dev) {
        return ArchiveRepository(makeKeyringExchange(
            &nam, [tok]() { return tok; }, [dev]() { return dev; }, baseUrl));
    };

    out << "Phase 38 - first real message-flow archive caller" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;

    const QString placeholder = DecryptedMessageArchiver::failedDecryptPlaceholder();
    const QString body = awkwardBody();

    // ==================================================== the gate, in isolation
    section("the successful-decrypt gate");
    {
        using D = DecryptedMessageArchiver;
        check(D::isArchivableDecryption(true, false, QStringLiteral("hello")),
              QStringLiteral("a real decryption is archivable"));
        check(!D::isArchivableDecryption(true, false, placeholder),
              QStringLiteral("FAILED DECRYPT: the placeholder is never archivable"));
        check(!D::isArchivableDecryption(true, false, QString()),
              QStringLiteral("an empty result is never archivable"));
        check(!D::isArchivableDecryption(false, false, QStringLiteral("hello")),
              QStringLiteral("an unencrypted message is not archived"));
        check(!D::isArchivableDecryption(true, true, QStringLiteral("hello")),
              QStringLiteral("a media message is not archived (text only)"));
        check(placeholder == QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message"),
              QStringLiteral("the placeholder matches ChatService's literal exactly"));
        check(D::DEFAULT_ENABLED == false,
              QStringLiteral("archiving ships OFF: production behaviour is unchanged"));
    }

    // ==================================================== failed decrypt never uploads
    section("HARD GATE: a failed decryption never reaches the repository");
    {
        Session s; s.start(uidA, true);
        SaveSpy spy;
        // A repository whose exchange records every call. If the gate leaks, this counter moves.
        DecryptedMessageArchiver caller(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return s.archiver(); },
            [&]() {
                return ArchiveRepository([&spy](const QString&, const QByteArray&, const char*) {
                    ++spy.calls;
                    return QPair<int, QJsonObject>{ 500, QJsonObject() };
                });
            });

        check(caller.archive(chatId, msg1, true, false, placeholder) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("placeholder -> Skipped"));
        check(caller.archive(chatId, msg1, true, false, QString()) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("empty plaintext -> Skipped"));
        check(caller.archive(chatId, msg1, false, false, QStringLiteral("plain")) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("unencrypted -> Skipped"));
        check(caller.archive(chatId, msg1, true, true, QStringLiteral("media")) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("media -> Skipped"));
        check(caller.archive(QString(), msg1, true, false, body) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("missing chatId -> Skipped"));
        check(caller.archive(chatId, QString(), true, false, body) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("missing messageId -> Skipped"));

        check(spy.calls == 0,
              QStringLiteral("the repository was NEVER called for any non-archivable input"));
        check(s.rootDraws == 0,
              QStringLiteral("no history root was minted for a non-archivable input"));
    }

    // ==================================================== disabled flag
    section("the feature switch is checked before anything else");
    {
        Session s; s.start(uidA, true);
        SaveSpy spy;
        DecryptedMessageArchiver off(
            []() { return false; },
            [&]() { return uidA; },
            [&]() { return s.archiver(); },
            [&]() {
                return ArchiveRepository([&spy](const QString&, const QByteArray&, const char*) {
                    ++spy.calls; return QPair<int, QJsonObject>{ 200, QJsonObject() };
                });
            });
        check(off.archive(chatId, msg1, true, false, body) ==
                  DecryptedMessageArchiver::Result::Skipped,
              QStringLiteral("disabled -> Skipped"));
        check(spy.calls == 0 && s.rootDraws == 0,
              QStringLiteral("disabled mints no root and makes no request"));
    }

    // ==================================================== locked vault
    section("a locked vault is contained, never a message failure");
    {
        Session s; s.start(uidA, /*unlocked=*/false);
        SaveSpy spy;
        DecryptedMessageArchiver caller(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return s.archiver(); },
            [&]() {
                return ArchiveRepository([&spy](const QString&, const QByteArray&, const char*) {
                    ++spy.calls; return QPair<int, QJsonObject>{ 200, QJsonObject() };
                });
            });
        const auto r = caller.archive(chatId, msg1, true, false, body);
        check(r == DecryptedMessageArchiver::Result::Failed,
              QStringLiteral("locked vault + no root -> Failed, contained"));
        check(s.rootDraws == 0, QStringLiteral("NO root material was generated while locked"));
        check(spy.calls == 0, QStringLiteral("nothing was uploaded while locked"));
        check(s.store.k.isEmpty(), QStringLiteral("nothing was persisted while locked"));
    }

    // ==================================================== upload failure contained
    section("an unreachable backend is contained");
    {
        Session s; s.start(uidA, true);
        DecryptedMessageArchiver caller(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return s.archiver(); },
            [&]() {
                return ArchiveRepository(makeKeyringExchange(
                    &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p38-dev-a"); },
                    QStringLiteral("http://127.0.0.1:1/api/v1")));
            });
        check(caller.archive(chatId, msg1, true, false, body) ==
                  DecryptedMessageArchiver::Result::Failed,
              QStringLiteral("unreachable backend -> Failed, never a throw"));
    }

    // ==================================================== REAL end-to-end
    section("real end-to-end: message -> archive -> server -> retrieve -> open");
    Session sa; sa.start(uidA, true);
    {
        DecryptedMessageArchiver caller(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return repoFor(tokA, QStringLiteral("p38-dev-a")); });

        const auto first = caller.archive(chatId, msg1, true, false, body);
        check(first == DecryptedMessageArchiver::Result::Stored,
              QStringLiteral("a successfully decrypted message is archived and Stored"));
        check(sa.rootDraws == 1, QStringLiteral("exactly one root was minted for the chat"));

        const auto listed = repoFor(tokA, QStringLiteral("p38-dev-a")).list(chatId);
        check(listed.outcome == ArchiveRepository::Outcome::Ok && listed.records.size() == 1,
              QStringLiteral("the server holds exactly one archive"));

        if (listed.records.size() == 1) {
            const ArchiveRecord& stored = listed.records.first();
            check(stored.messageId == msg1, QStringLiteral("messageId is the authoritative one"));
            check(stored.chatId == chatId, QStringLiteral("chatId is the server-derived one"));
            check(stored.rootVersion == 1, QStringLiteral("rootVersion came from the keyring"));
            check(stored.protocolVersion == 1, QStringLiteral("protocolVersion is 1"));

            QByteArray back;
            QString err;
            HistoryArchiver arch = sa.archiver();
            check(arch.open(stored, back, &err),
                  QStringLiteral("the stored archive opens through HistoryCrypto"));
            check(back == body.toUtf8(),
                  QStringLiteral("the recovered bytes are EXACTLY the decrypted message"));
            check(QString::fromUtf8(back) == body,
                  QStringLiteral("multibyte UTF-8 and high-bit bytes survived unchanged"));
        }
    }

    // ==================================================== duplicate processing
    section("re-processing the same message stores nothing new");
    {
        DecryptedMessageArchiver caller(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return repoFor(tokA, QStringLiteral("p38-dev-a")); });

        const auto before = repoFor(tokA, QStringLiteral("p38-dev-a")).list(chatId);
        const QString originalCt =
            before.records.isEmpty() ? QString() : before.records.first().ciphertextB64;

        const auto again = caller.archive(chatId, msg1, true, false, body);
        check(again == DecryptedMessageArchiver::Result::AlreadyPresent,
              QStringLiteral("a second pass reports AlreadyPresent, not Stored"));

        const auto after = repoFor(tokA, QStringLiteral("p38-dev-a")).list(chatId);
        check(after.records.size() == 1, QStringLiteral("still exactly one archive on the server"));
        check(!after.records.isEmpty() && after.records.first().ciphertextB64 == originalCt,
              QStringLiteral("the ORIGINAL ciphertext is unchanged"));
    }

    // ==================================================== account isolation
    section("account isolation across the real backend");
    {
        Session sb; sb.start(uidB, true);
        DecryptedMessageArchiver callerB(
            []() { return true; },
            [&]() { return uidB; },
            [&]() { return sb.archiver(); },
            [&]() { return repoFor(tokB, QStringLiteral("p38-dev-b")); });

        check(callerB.archive(chatId, msg2, true, false, body) ==
                  DecryptedMessageArchiver::Result::Stored,
              QStringLiteral("B archives its own message"));

        const auto aList = repoFor(tokA, QStringLiteral("p38-dev-a")).list(chatId);
        const auto bList = repoFor(tokB, QStringLiteral("p38-dev-b")).list(chatId);
        check(aList.records.size() == 1, QStringLiteral("A still sees only its own archive"));
        check(bList.records.size() == 1, QStringLiteral("B sees only its own archive"));
        check(!aList.records.isEmpty() && !bList.records.isEmpty() &&
                  aList.records.first().ciphertextB64 != bList.records.first().ciphertextB64,
              QStringLiteral("the two accounts' ciphertexts differ"));

        QByteArray stolen;
        QString err;
        HistoryArchiver archB = sb.archiver();
        check(!aList.records.isEmpty() && !archB.open(aList.records.first(), stolen, &err) &&
                  stolen.isEmpty(),
              QStringLiteral("B cannot open A's archive"));

        // The caller cannot be told to act as another account.
        DecryptedMessageArchiver confused(
            []() { return true; },
            [&]() { return uidB; },                       // signed in as B
            [&]() { return sa.archiver(); },              // but handed A's archiver
            [&]() { return repoFor(tokB, QStringLiteral("p38-dev-b")); });
        check(confused.archive(chatId, msg1, true, false, body) ==
                  DecryptedMessageArchiver::Result::Failed,
              QStringLiteral("a mismatched account context is refused, not archived"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
