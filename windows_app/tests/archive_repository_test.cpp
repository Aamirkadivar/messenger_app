// Phase 37 - the durable boundary for one sealed archive record.
//
// Phase 36 proved an ArchiveRecord can be produced in memory. This proves one can leave the process
// and come back unchanged, through the server's existing /e2ee/archives store, without the server
// ever holding anything it could decrypt.
//
// The decisive property is byte preservation: the Base64 the archiver produced must be the Base64
// that comes back, and the retrieved record must still open to the exact original plaintext using
// only local key material. A boundary that normalised, re-encoded, or "repaired" the ciphertext
// would pass a shallow round-trip test and fail here.
//
// Runs against an ISOLATED backend and database only. Every account, chat and message is synthetic
// and seeded for this run. No key, root, token, or plaintext is printed.
//
// Usage: archive_repository_test <baseUrl> <tokA> <uidA> <tokB> <uidB>
//                                <chatShared> <msgShared> <chatPrivate> <msgPrivate>

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
static QByteArray hex(const char* h) { return QByteArray::fromHex(QByteArray(h).simplified()); }

// ---------------------------------------------------- Phase 35/36 fixture material

static const char* kNonceHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7";
static const char* kPlainHex =
    "503335206172636869766520666978747572653a2068c3a96c6c6f2077c3b6726c6420e29c9320"
    "e697a5e69cace8aa9e20ce95cebbcebbceb7cebdceb9cebaceac000001fffe807f20656e64";
// The Phase 35 identity and its independently-computed ciphertext, used only to prove this phase
// changed no cryptography.
static const char* kP35User = "9f1c0d4e-0000-4000-8000-0000000000a1";
static const char* kP35Chat = "3b7e5f21-0000-4000-8000-0000000000c2";
static const char* kP35Msg  = "7d2a91b0-0000-4000-8000-0000000000e3";
static const char* kP35WireB64 =
    "oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3h641gShq9PbpgibJgl1KbsA5WVozb7pWlzuY6B7HDRWkGWxlzIVsLR"
    "dDXKmPjgWah3zyVGw/I/L7L8aAhB2NlGIM6yAzb1Lzu39sI83Oi8x7oo5GUA+BTTrkTxw=";

static QByteArray fixtureRoot() {
    QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
    for (int i = 0; i < r.size(); ++i) r[i] = static_cast<char>(i);
    return r;
}

// ---------------------------------------------------------------- session stand-ins

class FakeVault {
public:
    void unlock(const QString& userId) {
        m_userId = userId;
        m_mk.resize(32);
        for (int i = 0; i < 32; ++i) m_mk[i] = static_cast<char>(0x70 + i);
    }
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

struct Session {
    MemStore store;
    FakeVault vault;
    QString owner;
    std::unique_ptr<HistoryKeyringRepository> repo;

    void start(const QString& account, const QByteArray& pinnedRoot) {
        owner = account;
        vault.unlock(account);
        repo = std::make_unique<HistoryKeyringRepository>(
            &store,
            [this](const QByteArray& p, QByteArray& o) { return vault.seal(p, o); },
            [this](const QByteArray& s, QByteArray& o) { return vault.open(s, o); },
            [pinnedRoot]() { return pinnedRoot; },
            [this]() { return vault.isUnlocked(); });
    }
    HistoryArchiver archiver() {
        return HistoryArchiver(
            [this](const QString& chatId, HistoryRootEntry& out, QString* err) {
                return repo->ensureRoot(owner, chatId, out, err);
            },
            [this](const QString& chatId, int rootVersion, HistoryRootEntry& out, QString* err) {
                HistoryKeyring keyring;
                if (!repo->load(owner, keyring, err)) return false;
                if (!keyring.find(chatId, rootVersion, out)) {
                    if (err) *err = QStringLiteral("root version not held");
                    return false;
                }
                return true;
            },
            [this]() { return owner; });
    }
};

static const char* name(ArchiveRepository::Outcome o) {
    switch (o) {
        case ArchiveRepository::Outcome::Ok: return "Ok";
        case ArchiveRepository::Outcome::NotFound: return "NotFound";
        case ArchiveRepository::Outcome::Unauthorized: return "Unauthorized";
        case ArchiveRepository::Outcome::Rejected: return "Rejected";
        case ArchiveRepository::Outcome::TooLarge: return "TooLarge";
        case ArchiveRepository::Outcome::Malformed: return "Malformed";
        case ArchiveRepository::Outcome::TransportError: return "TransportError";
    }
    return "?";
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 10) { out << "usage: see header" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString tokA = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uidA = readFile(QString::fromLocal8Bit(argv[3]));
    const QString tokB = readFile(QString::fromLocal8Bit(argv[4]));
    const QString uidB = readFile(QString::fromLocal8Bit(argv[5]));
    const QString chatShared = QString::fromLocal8Bit(argv[6]);
    const QString msgShared = QString::fromLocal8Bit(argv[7]);
    const QString chatPrivate = QString::fromLocal8Bit(argv[8]);
    const QString msgPrivate = QString::fromLocal8Bit(argv[9]);
    if (tokA.isEmpty() || uidA.isEmpty() || tokB.isEmpty() || uidB.isEmpty()) {
        out << "missing credentials" << Qt::endl; return 2;
    }

    const QByteArray plain = hex(kPlainHex);
    const QByteArray nonce = hex(kNonceHex);
    QNetworkAccessManager nam;

    auto repoFor = [&](const QString& token, const QString& device) {
        return ArchiveRepository(makeKeyringExchange(
            &nam, [token]() { return token; }, [device]() { return device; }, baseUrl));
    };
    const ArchiveRepository archA = repoFor(tokA, QStringLiteral("p37-device-a"));
    const ArchiveRepository archB = repoFor(tokB, QStringLiteral("p37-device-b"));

    out << "Phase 37 - archive record persistence boundary" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;

    // ================================================ crypto contract unchanged
    section("this phase changed no cryptography");
    {
        Session s;
        s.start(QString::fromLatin1(kP35User), fixtureRoot());
        auto arch = s.archiver();
        ArchiveMessage m;
        m.userId = QString::fromLatin1(kP35User);
        m.chatId = QString::fromLatin1(kP35Chat);
        m.messageId = QString::fromLatin1(kP35Msg);
        m.plaintext = plain;
        ArchiveRecord rec;
        QString err;
        check(arch.sealWithNonce(m, nonce, rec, &err) &&
                  rec.ciphertextB64 == QString::fromLatin1(kP35WireB64),
              QStringLiteral("the Phase 35 vector still reproduces exactly"));
    }

    // ================================================ deterministic round trip
    section("one record crosses the boundary and returns intact");

    Session sa;
    sa.start(uidA, fixtureRoot());
    auto archiverA = sa.archiver();
    ArchiveRecord original;
    QString err;
    {
        ArchiveMessage m;
        m.userId = uidA;
        m.chatId = chatShared;
        m.messageId = msgShared;
        m.plaintext = plain;
        check(archiverA.sealWithNonce(m, nonce, original, &err),
              QStringLiteral("record sealed through keyring + archiver"));
        check(original.rootVersion == 1 && original.protocolVersion == 1,
              QStringLiteral("record carries rootVersion 1, protocolVersion 1"));

        // Sealing twice with the same inputs must give the same bytes - otherwise a later byte
        // comparison would prove nothing.
        ArchiveRecord again;
        archiverA.sealWithNonce(m, nonce, again, &err);
        check(again.ciphertextB64 == original.ciphertextB64,
              QStringLiteral("the record is deterministic before it is stored"));
    }

    {
        const auto saved = archA.save(original);
        check(saved.outcome == ArchiveRepository::Outcome::Ok,
              QStringLiteral("save succeeds (%1)").arg(QLatin1String(name(saved.outcome))));
        check(saved.stored, QStringLiteral("server reports the archive was newly stored"));
        check(saved.messageId == msgShared, QStringLiteral("server echoes the message id"));
        check(saved.chatId == chatShared,
              QStringLiteral("server derives the chat id from the message row"));
    }

    ArchiveRecord retrieved;
    {
        const auto listed = archA.list(chatShared);
        check(listed.outcome == ArchiveRepository::Outcome::Ok,
              QStringLiteral("list succeeds (%1)").arg(QLatin1String(name(listed.outcome))));
        check(listed.records.size() == 1, QStringLiteral("exactly one archive comes back"));
        if (listed.records.size() == 1) {
            retrieved = listed.records.first();
            check(retrieved.messageId == original.messageId, QStringLiteral("messageId preserved"));
            check(retrieved.chatId == original.chatId, QStringLiteral("chatId preserved"));
            check(retrieved.rootVersion == original.rootVersion, QStringLiteral("rootVersion preserved"));
            check(retrieved.protocolVersion == original.protocolVersion,
                  QStringLiteral("protocolVersion preserved"));
            check(retrieved.ciphertextB64 == original.ciphertextB64,
                  QStringLiteral("ciphertext preserved BYTE FOR BYTE, not re-encoded"));
        }
    }

    {
        QByteArray back;
        check(archiverA.open(retrieved, back, &err) && back == plain,
              QStringLiteral("the retrieved record opens to the exact original plaintext"));

        // seal->open and seal->persist->retrieve->open must agree.
        QByteArray direct;
        archiverA.open(original, direct, &err);
        check(direct == back, QStringLiteral("persistence did not change cryptographic meaning"));
    }

    // ================================================ immutability / duplicates
    section("duplicate upload is idempotent and cannot overwrite");
    {
        const auto again = archA.save(original);
        check(again.outcome == ArchiveRepository::Outcome::Ok,
              QStringLiteral("a repeated upload succeeds"));
        check(!again.stored,
              QStringLiteral("stored=false: the existing archive was left untouched"));

        // An attacker-supplied ciphertext under the same identity must not replace the original.
        ArchiveRecord impostor = original;
        ArchiveMessage m;
        m.userId = uidA; m.chatId = chatShared; m.messageId = msgShared;
        m.plaintext = QByteArray("a different body entirely");
        ArchiveRecord other;
        archiverA.sealWithNonce(m, nonce, other, &err);
        impostor.ciphertextB64 = other.ciphertextB64;
        const auto overwrite = archA.save(impostor);
        check(overwrite.outcome == ArchiveRepository::Outcome::Ok && !overwrite.stored,
              QStringLiteral("a replacement ciphertext is accepted but not stored"));

        const auto listed = archA.list(chatShared);
        check(listed.records.size() == 1 &&
                  listed.records.first().ciphertextB64 == original.ciphertextB64,
              QStringLiteral("the ORIGINAL ciphertext is still what is stored"));
    }

    // ================================================ authorization / isolation
    section("account isolation");
    {
        // B is an active member of the same chat, and still sees none of A's archives.
        const auto bList = archB.list(chatShared);
        check(bList.outcome == ArchiveRepository::Outcome::Ok && bList.records.isEmpty(),
              QStringLiteral("B sees no archives despite sharing the chat"));

        // B uploading the same message id creates B's OWN row; identity is (message_id, user_id).
        Session sb;
        sb.start(uidB, fixtureRoot());
        auto archiverB = sb.archiver();
        ArchiveMessage mb;
        mb.userId = uidB; mb.chatId = chatShared; mb.messageId = msgShared;
        mb.plaintext = plain;
        ArchiveRecord recB;
        check(archiverB.sealWithNonce(mb, nonce, recB, &err),
              QStringLiteral("B seals its own record for the same message"));
        check(recB.ciphertextB64 != original.ciphertextB64,
              QStringLiteral("B's ciphertext differs from A's (the AAD binds the account)"));

        const auto bSave = archB.save(recB);
        check(bSave.outcome == ArchiveRepository::Outcome::Ok && bSave.stored,
              QStringLiteral("B stores its own archive for that message"));

        const auto aAfter = archA.list(chatShared);
        check(aAfter.records.size() == 1 &&
                  aAfter.records.first().ciphertextB64 == original.ciphertextB64,
              QStringLiteral("A's archive is untouched by B's upload"));
        const auto bAfter = archB.list(chatShared);
        check(bAfter.records.size() == 1 &&
                  bAfter.records.first().ciphertextB64 == recB.ciphertextB64,
              QStringLiteral("B sees only B's own row"));

        // A's record cannot be opened by B even if B somehow obtained the bytes.
        QByteArray stolen;
        check(!archiverB.open(original, stolen, &err) && stolen.isEmpty(),
              QStringLiteral("B cannot open A's record"));

        // A chat B is not a member of: the same generic 404 for upload and list.
        ArchiveRecord privateRec = original;
        privateRec.messageId = msgPrivate;
        privateRec.chatId = chatPrivate;
        check(archB.save(privateRec).outcome == ArchiveRepository::Outcome::NotFound,
              QStringLiteral("B cannot upload against a chat it is not in (404)"));
        check(archB.list(chatPrivate).outcome == ArchiveRepository::Outcome::NotFound,
              QStringLiteral("B cannot list a chat it is not in (404)"));
    }

    // ================================================ error contract
    section("error contract, as the server actually defines it");
    {
        ArchiveRecord r = original;

        HistoryArchiver dummy(nullptr, nullptr, nullptr);
        Q_UNUSED(dummy);

        const ArchiveRepository noAuth = repoFor(QString(), QStringLiteral("p37-device-a"));
        check(noAuth.list().outcome == ArchiveRepository::Outcome::Unauthorized,
              QStringLiteral("no bearer token -> 401 Unauthorized"));

        const ArchiveRepository noDevice = repoFor(tokA, QString());
        check(noDevice.list().outcome == ArchiveRepository::Outcome::Rejected,
              QStringLiteral("no X-Device-Id -> 400 Rejected"));

        ArchiveRecord unknownMsg = original;
        unknownMsg.messageId = QStringLiteral("99999999-0000-4000-8000-000000000fff");
        check(archA.save(unknownMsg).outcome == ArchiveRepository::Outcome::NotFound,
              QStringLiteral("nonexistent message -> 404"));

        ArchiveRecord badId = original;
        badId.messageId = QStringLiteral("not-a-uuid");
        check(archA.save(badId).outcome == ArchiveRepository::Outcome::Rejected,
              QStringLiteral("malformed message_id -> 400"));

        ArchiveRecord badB64 = original;
        badB64.ciphertextB64 = QStringLiteral("!!!not base64!!!");
        check(archA.save(badB64).outcome == ArchiveRepository::Outcome::Rejected,
              QStringLiteral("malformed ciphertext_b64 -> 400"));

        ArchiveRecord badVersion = original;
        badVersion.rootVersion = 0;
        check(archA.save(badVersion).outcome == ArchiveRepository::Outcome::Rejected,
              QStringLiteral("rootVersion 0 is refused before sending"));

        ArchiveRecord oversized = original;
        oversized.ciphertextB64 = QString::fromLatin1(QByteArray((1 << 20) + 4096, 'A').toBase64());
        check(archA.save(oversized).outcome == ArchiveRepository::Outcome::TooLarge,
              QStringLiteral("ciphertext over 1 MiB -> 413"));

        ArchiveRecord incomplete;
        check(archA.save(incomplete).outcome == ArchiveRepository::Outcome::Rejected,
              QStringLiteral("an incomplete record is refused locally, never sent"));

        const ArchiveRepository dead(makeKeyringExchange(
            &nam, [&]() { return tokA; }, [&]() { return QStringLiteral("p37-device-a"); },
            QStringLiteral("http://127.0.0.1:1/api/v1")));
        check(dead.list().outcome == ArchiveRepository::Outcome::TransportError,
              QStringLiteral("an unreachable backend is TransportError, never empty"));
        check(dead.save(original).outcome == ArchiveRepository::Outcome::TransportError,
              QStringLiteral("save against an unreachable backend is TransportError"));
    }

    // ================================================ integrity after retrieval
    section("the boundary does not repair corruption");
    {
        ArchiveRecord tampered = retrieved;
        QByteArray raw = HistoryCrypto::fromWireB64(tampered.ciphertextB64);
        raw[40] = static_cast<char>(raw[40] ^ 0x01);
        tampered.ciphertextB64 = HistoryCrypto::toWireB64(raw);
        QByteArray leaked;
        check(!archiverA.open(tampered, leaked, &err) && leaked.isEmpty(),
              QStringLiteral("a corrupted retrieved record fails to open, leaking nothing"));

        ArchiveRecord wrongVersion = retrieved;
        wrongVersion.rootVersion = 2;
        check(!archiverA.open(wrongVersion, leaked, &err),
              QStringLiteral("a retrieved record re-labelled with another root version fails"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
