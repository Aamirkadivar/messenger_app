// Phase 39 - the first real archive-backed restore.
//
// A message cached as ciphertext whose MLS ratchet secret has already been consumed can never be
// reopened from its own ciphertext: ChatService::decryptMessage returns the
// "\xF0\x9F\x94\x92 Encrypted message" placeholder, permanently. The Layer B archive is the only
// second copy, and it is keyed independently of MLS, so it is the only thing that can still recover
// that plaintext.
//
// What this proves is mostly restraint. The archive is consulted ONLY for messages that already
// failed - a successful decrypt is authoritative and is never revisited - and every failure mode
// (missing archive, tampered record, wrong account, wrong message, dead backend) leaves the message
// exactly as it was rather than fabricating a body.
//
// The repository is instrumented with a call counter, so "the archive was not consulted" is asserted
// rather than assumed. Isolated backend only; no plaintext, key, root, or token is printed.
//
// Usage: archive_restore_test <baseUrl> <tokA> <uidA> <tokB> <uidB> <chat> <msg1> <msg2>

#include "../src/crypto/archiverestorer.h"
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

/** ASCII + multibyte UTF-8 + high-bit characters. NUL is absent: the message layer is QString. */
static QString bodyOne() {
    return QString::fromUtf8("P39 restore \xC3\xA9\xC3\xB6 \xE2\x9C\x93 \xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E "
                             "\xCE\x95\xCE\xBB\xCE\xBB \xC3\xBF\xC3\xBE end");
}
static QString bodyTwo() { return QString::fromUtf8("second message \xC2\xA1hola! \xE2\x82\xAC42"); }

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

/** Counts repository requests so "the archive was never consulted" can be asserted. */
struct Counter { int calls = 0; };

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

    const QString placeholder = DecryptedMessageArchiver::failedDecryptPlaceholder();
    out << "Phase 39 - archive-backed restore" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;

    // ============================================ the fallback gate
    section("the fallback gate: only failed decryptions qualify");
    {
        using R = ArchiveRestorer;
        check(R::needsRestore(true, false, placeholder),
              QStringLiteral("an encrypted text message showing the placeholder qualifies"));
        check(!R::needsRestore(true, false, QStringLiteral("real plaintext")),
              QStringLiteral("A. a SUCCESSFUL decrypt never qualifies"));
        check(!R::needsRestore(false, false, placeholder),
              QStringLiteral("an unencrypted message never qualifies"));
        check(!R::needsRestore(true, true, placeholder),
              QStringLiteral("a media message never qualifies"));
        check(!R::needsRestore(true, false, QString()),
              QStringLiteral("an empty body is not the failure representation"));
        check(R::DEFAULT_ENABLED == false,
              QStringLiteral("restore ships OFF: production behaviour is unchanged"));
    }

    // ============================================ A. success is never revisited
    section("A. a successful decrypt does not consult the archive");
    {
        Counter c;
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { Session s; s.start(uidA); return s.archiver(); },
            [&]() {
                return ArchiveRepository([&c](const QString&, const QByteArray&, const char*) {
                    ++c.calls; return QPair<int, QJsonObject>{ 200, QJsonObject() };
                });
            });
        ArchiveRestorer::Outcome o;
        const auto got = restorer.restore(chatId, QStringList(), &o);
        check(got.isEmpty() && o.requested == 0 && !o.lookupAttempted,
              QStringLiteral("nothing to restore -> no lookup at all"));
        check(c.calls == 0, QStringLiteral("the repository was NEVER contacted"));
    }

    // ============================================ seed two real archives
    section("seed: two archived messages in one chat");
    Session sa; sa.start(uidA);
    {
        HistoryArchiver arch = sa.archiver();
        const ArchiveRepository repo = repoFor(tokA, QStringLiteral("p39-dev-a"));
        QString err;
        for (const auto& pair : QList<QPair<QString, QString>>{ { msg1, bodyOne() },
                                                                { msg2, bodyTwo() } }) {
            ArchiveMessage m;
            m.userId = uidA; m.chatId = chatId; m.messageId = pair.first;
            m.plaintext = pair.second.toUtf8();
            ArchiveRecord rec;
            const bool sealed = arch.seal(m, rec, &err);
            const auto saved = repo.save(rec);
            check(sealed && saved.outcome == ArchiveRepository::Outcome::Ok,
                  QStringLiteral("archived message %1").arg(pair.first.left(8)));
        }
    }

    // ============================================ B + C + I. the real restore
    section("B/C/I. restore recovers the exact plaintext for the exact message");
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return repoFor(tokA, QStringLiteral("p39-dev-a")); });

        ArchiveRestorer::Outcome o;
        const auto got = restorer.restore(chatId, { msg1 }, &o);
        check(o.lookupAttempted && !o.lookupFailed, QStringLiteral("the archive was consulted"));
        check(o.requested == 1 && o.restored == 1, QStringLiteral("exactly one message restored"));
        check(got.size() == 1 && got.contains(msg1),
              QStringLiteral("C. the requested messageId was selected, not another"));
        check(got.value(msg1) == bodyOne(),
              QStringLiteral("B. the recovered text equals the original exactly"));
        check(got.value(msg1).toUtf8() == bodyOne().toUtf8(),
              QStringLiteral("I. multibyte UTF-8 and high-bit bytes survived byte-for-byte"));
        check(got.value(msg1) != bodyTwo(),
              QStringLiteral("C. the other archive in the same chat was not applied"));

        // Both at once, each matched to its own id.
        const auto both = restorer.restore(chatId, { msg1, msg2 });
        check(both.value(msg1) == bodyOne() && both.value(msg2) == bodyTwo(),
              QStringLiteral("C. two messages each recover their own body"));
    }

    // ============================================ D. missing archive
    section("D. a message with no archive is not fabricated");
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return repoFor(tokA, QStringLiteral("p39-dev-a")); });
        const QString unknown = QStringLiteral("deadbeef-0000-4000-8000-000000000fff");
        ArchiveRestorer::Outcome o;
        const auto got = restorer.restore(chatId, { unknown }, &o);
        check(got.isEmpty() && o.restored == 0,
              QStringLiteral("no archive -> no entry, nothing invented"));
        check(!o.lookupFailed, QStringLiteral("the lookup itself succeeded; the message simply had none"));
    }

    // ============================================ G. wrong message id
    section("G. an archive cannot be applied to a different message");
    {
        const auto listed = repoFor(tokA, QStringLiteral("p39-dev-a")).list(chatId);
        check(listed.outcome == ArchiveRepository::Outcome::Ok && listed.records.size() >= 2,
              QStringLiteral("both archives are retrievable"));
        ArchiveRecord relabelled;
        for (const auto& r : listed.records) if (r.messageId == msg1) relabelled = r;
        relabelled.messageId = msg2; // pretend msg1's ciphertext belongs to msg2
        QByteArray plain; QString err;
        HistoryArchiver arch = sa.archiver();
        check(!arch.open(relabelled, plain, &err) && plain.isEmpty(),
              QStringLiteral("a re-labelled archive does not open (messageId is in the AAD)"));
    }

    // ============================================ E. tampered ciphertext
    section("E. a tampered archive yields nothing");
    {
        const auto listed = repoFor(tokA, QStringLiteral("p39-dev-a")).list(chatId);
        ArchiveRecord tampered;
        for (const auto& r : listed.records) if (r.messageId == msg1) tampered = r;
        QByteArray raw = HistoryCrypto::fromWireB64(tampered.ciphertextB64);
        raw[40] = static_cast<char>(raw[40] ^ 0x01);
        tampered.ciphertextB64 = HistoryCrypto::toWireB64(raw);
        QByteArray plain; QString err;
        HistoryArchiver arch = sa.archiver();
        check(!arch.open(tampered, plain, &err) && plain.isEmpty(),
              QStringLiteral("tampered ciphertext is rejected, leaking nothing"));
    }

    // ============================================ F. wrong account
    section("F. another account cannot recover this plaintext");
    {
        Session sb; sb.start(uidB);
        ArchiveRestorer restorerB(
            []() { return true; },
            [&]() { return uidB; },
            [&]() { return sb.archiver(); },
            [&]() { return repoFor(tokB, QStringLiteral("p39-dev-b")); });

        ArchiveRestorer::Outcome o;
        const auto got = restorerB.restore(chatId, { msg1 }, &o);
        check(got.isEmpty() && o.restored == 0,
              QStringLiteral("B recovers nothing: it holds no archive for that message"));

        // Even handed A's actual record, B's keys cannot open it - the AAD binds the account.
        const auto listed = repoFor(tokA, QStringLiteral("p39-dev-a")).list(chatId);
        ArchiveRecord aRecord;
        for (const auto& r : listed.records) if (r.messageId == msg1) aRecord = r;
        QByteArray stolen; QString err;
        HistoryArchiver archB = sb.archiver();
        check(!archB.open(aRecord, stolen, &err) && stolen.isEmpty(),
              QStringLiteral("B cannot open A's archive even holding the exact record"));
    }

    // ============================================ H. backend unavailable
    section("H. an unreachable backend leaves the message untouched");
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() {
                return ArchiveRepository(makeKeyringExchange(
                    &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p39-dev-a"); },
                    QStringLiteral("http://127.0.0.1:1/api/v1")));
            });
        ArchiveRestorer::Outcome o;
        const auto got = restorer.restore(chatId, { msg1 }, &o);
        check(got.isEmpty(), QStringLiteral("no plaintext is produced"));
        check(o.lookupAttempted && o.lookupFailed,
              QStringLiteral("the failure is reported, not swallowed as 'no archive'"));
    }

    // ============================================ disabled switch
    section("the read switch is checked before anything else");
    {
        Counter c;
        ArchiveRestorer off(
            []() { return false; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() {
                return ArchiveRepository([&c](const QString&, const QByteArray&, const char*) {
                    ++c.calls; return QPair<int, QJsonObject>{ 200, QJsonObject() };
                });
            });
        ArchiveRestorer::Outcome o;
        check(off.restore(chatId, { msg1 }, &o).isEmpty() && !o.lookupAttempted && c.calls == 0,
              QStringLiteral("disabled -> no lookup, no request"));
    }

    // ============================================ L. repeated restore
    section("L. restoring twice changes nothing");
    {
        ArchiveRestorer restorer(
            []() { return true; },
            [&]() { return uidA; },
            [&]() { return sa.archiver(); },
            [&]() { return repoFor(tokA, QStringLiteral("p39-dev-a")); });

        const auto before = repoFor(tokA, QStringLiteral("p39-dev-a")).list(chatId);
        const auto first = restorer.restore(chatId, { msg1 });
        const auto second = restorer.restore(chatId, { msg1 });
        const auto after = repoFor(tokA, QStringLiteral("p39-dev-a")).list(chatId);

        check(first.value(msg1) == second.value(msg1) && second.value(msg1) == bodyOne(),
              QStringLiteral("both restores return the identical plaintext"));
        check(before.records.size() == after.records.size(),
              QStringLiteral("no archive records were created by reading"));
        check(after.records.size() == 2, QStringLiteral("still exactly the two seeded archives"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
