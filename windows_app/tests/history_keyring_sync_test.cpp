// Phase 34 - keyring repository composed with the real authenticated transport.
//
// Phase 33 proved the transport contract with synthetic blobs. This proves the join: that what the
// REPOSITORY produces - a real keyring, sealed under a real master-key boundary - survives a round
// trip through the real server and comes back as the same roots, and that two accounts cannot reach
// each other's server-side keyring.
//
// NOTE ON SCOPE. HistoryKeyringRepository has no server sync of its own; it is local persistence and
// policy only. The upload/download sequencing below is performed BY THIS TEST, deliberately, because
// choosing a sync and conflict-resolution policy is not this phase's job. What is proven here is
// that the pieces compose and that the server's optimistic concurrency protects the keyring - not
// that any particular sync policy exists.
//
// Synthetic throughout: a counting-pattern master key, isolated test accounts, an isolated backend.
// Roots and the master key are never printed. Never touches production.
//
// Usage: history_keyring_sync_test <baseUrl> <tokA> <uidA> <tokB> <uidB>

#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historykeyringtransport.h"
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

// The AuthService boundary, reproduced: seal/open only, never the key.
class Vault {
public:
    explicit Vault(const QString& userId) : m_userId(userId) {
        m_mk.resize(32);
        for (int i = 0; i < 32; ++i) m_mk[i] = static_cast<char>(0x70 + i);
    }
    static QByteArray localAad(const QString& u) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(u).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    // Distinct domain for the blob that leaves the device (Phase 32 contract).
    static QByteArray recoveryAad(const QString& u) {
        return QStringLiteral("history_keyring_recovery_v1|%1|%2")
            .arg(u, QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    bool seal(const QByteArray& p, QByteArray& o) const {
        if (m_mk.isEmpty()) return false;
        o = VaultCrypto::sealXChaCha(m_mk, p, localAad(m_userId));
        return !o.isEmpty();
    }
    bool open(const QByteArray& s, QByteArray& o) const {
        if (m_mk.isEmpty()) return false;
        o = VaultCrypto::openXChaCha(m_mk, s, localAad(m_userId));
        return !o.isEmpty();
    }
    QByteArray sealForRecovery(const QByteArray& p) const {
        return VaultCrypto::sealXChaCha(m_mk, p, recoveryAad(m_userId));
    }
    QByteArray openFromRecovery(const QByteArray& s) const {
        return VaultCrypto::openXChaCha(m_mk, s, recoveryAad(m_userId));
    }
    bool isUnlocked() const { return !m_mk.isEmpty(); }

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

static std::unique_ptr<HistoryKeyringRepository> makeRepo(HistoryKeyringStore* s, const Vault* v) {
    return std::make_unique<HistoryKeyringRepository>(
        s,
        [v](const QByteArray& p, QByteArray& o) { return v->seal(p, o); },
        [v](const QByteArray& c, QByteArray& o) { return v->open(c, o); },
        nullptr,
        [v]() { return v->isUnlocked(); });
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 6) { out << "usage: <baseUrl> <tokA> <uidA> <tokB> <uidB>" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString tokA = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uidA = readFile(QString::fromLocal8Bit(argv[3]));
    const QString tokB = readFile(QString::fromLocal8Bit(argv[4]));
    const QString uidB = readFile(QString::fromLocal8Bit(argv[5]));
    if (tokA.isEmpty() || uidA.isEmpty() || tokB.isEmpty() || uidB.isEmpty()) {
        out << "missing credentials" << Qt::endl; return 2;
    }

    const QString chat = QStringLiteral("00000000-0000-0000-0000-000000000001");
    const QString devA = QStringLiteral("p34-device-a");
    const QString devB = QStringLiteral("p34-device-b");

    QNetworkAccessManager nam;
    HistoryKeyringTransport txA(makeKeyringExchange(
        &nam, [&]() { return tokA; }, [&]() { return devA; }, baseUrl));
    HistoryKeyringTransport txB(makeKeyringExchange(
        &nam, [&]() { return tokB; }, [&]() { return devB; }, baseUrl));

    out << "Phase 34 - repository + real transport" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)" << Qt::endl;

    txA.remove();
    txB.remove();

    Vault vaultA(uidA);
    MemStore storeA;
    auto repoA = makeRepo(&storeA, &vaultA);

    section("initial state");
    check(txA.get().outcome == HistoryKeyringTransport::Outcome::Absent,
          QStringLiteral("GET on a fresh account is 404/Absent, not an empty keyring"));

    section("explicit ensureRoot then publish");
    HistoryRootEntry rootA;
    QString err;
    check(repoA->ensureRoot(uidA, chat, rootA, &err),
          QStringLiteral("ensureRoot creates a root locally"));
    check(rootA.rootVersion == 1 && rootA.root.size() == 32,
          QStringLiteral("root is version 1 and 32 bytes"));

    HistoryKeyring localA;
    check(repoA->load(uidA, localA, &err), QStringLiteral("keyring loads"));
    const QByteArray sealedA = vaultA.sealForRecovery(localA.encode());
    check(!sealedA.isEmpty(), QStringLiteral("keyring seals for recovery"));
    check(!sealedA.contains(rootA.root),
          QStringLiteral("the sealed blob contains no cleartext root"));
    check(!sealedA.contains(localA.encode()),
          QStringLiteral("the sealed blob is not the keyring encoding in the clear"));

    auto p1 = txA.put(0, sealedA);
    check(p1.outcome == HistoryKeyringTransport::Outcome::Ok && p1.version == 1 && p1.created,
          QStringLiteral("PUT expected_version=0 creates server version 1"));

    section("reload from server");
    {
        auto f = txA.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Ok && f.version == 1,
              QStringLiteral("GET returns version 1"));
        check(f.sealed == sealedA, QStringLiteral("GET returns the identical ciphertext"));
        const QByteArray plain = vaultA.openFromRecovery(f.sealed);
        HistoryKeyring back;
        check(HistoryKeyring::decode(plain, back, &err),
              QStringLiteral("the fetched keyring opens and decodes"));
        HistoryRootEntry e;
        check(back.latest(chat, e) && e.root == rootA.root && e.rootVersion == 1,
              QStringLiteral("the same root and version survive the round trip"));
        // Domain separation: a recovery blob must not be openable as the local keyring.
        QByteArray wrongDomain;
        check(!vaultA.open(f.sealed, wrongDomain),
              QStringLiteral("recovery blob cannot be opened with the local keyring AAD"));
    }

    section("repeated ensureRoot does not change anything");
    {
        const qint64 revBefore = repoA->revision();
        HistoryRootEntry again;
        check(repoA->ensureRoot(uidA, chat, again, &err) && again.root == rootA.root,
              QStringLiteral("ensureRoot returns the existing root"));
        check(repoA->revision() == revBefore, QStringLiteral("no new local revision"));
        auto f = txA.get();
        check(f.version == 1, QStringLiteral("server version unchanged at 1"));
    }

    section("version progression and optimistic concurrency");
    {
        HistoryRootEntry rotated;
        check(repoA->rotate(uidA, chat, rotated, &err) && rotated.rootVersion == 2,
              QStringLiteral("rotate produces version 2 locally"));
        HistoryKeyring k2;
        repoA->load(uidA, k2, &err);
        const QByteArray sealed2 = vaultA.sealForRecovery(k2.encode());

        auto p2 = txA.put(1, sealed2);
        check(p2.outcome == HistoryKeyringTransport::Outcome::Ok && p2.version == 2 && !p2.created,
              QStringLiteral("PUT expected_version=1 advances the server to version 2"));

        // A second client that still believes the server is at 1.
        QByteArray stale = vaultA.sealForRecovery(localA.encode());
        auto p3 = txA.put(1, stale);
        check(p3.outcome == HistoryKeyringTransport::Outcome::Conflict,
              QStringLiteral("a stale expected_version is refused with 409"));
        check(p3.serverVersion == 2, QStringLiteral("the conflict reports server version 2"));

        auto f = txA.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Ok && f.version == 2,
              QStringLiteral("server is still at version 2 after the stale write"));
        check(f.sealed == sealed2,
              QStringLiteral("the stale write did NOT overwrite the stored ciphertext"));

        // And the surviving blob still holds both root versions.
        HistoryKeyring back;
        HistoryKeyring::decode(vaultA.openFromRecovery(f.sealed), back, &err);
        HistoryRootEntry v1, v2;
        check(back.find(chat, 1, v1) && v1.root == rootA.root,
              QStringLiteral("version 1 root survived rotation and upload"));
        check(back.latest(chat, v2) && v2.rootVersion == 2,
              QStringLiteral("version 2 is the latest on the server copy"));
    }

    section("account isolation across the server");
    {
        auto fb = txB.get();
        check(fb.outcome == HistoryKeyringTransport::Outcome::Absent,
              QStringLiteral("B sees no keyring even though A has published one"));

        Vault vaultB(uidB);
        MemStore storeB;
        auto repoB = makeRepo(&storeB, &vaultB);
        HistoryRootEntry rootB;
        check(repoB->ensureRoot(uidB, chat, rootB, &err),
              QStringLiteral("B creates its own root for the same chat id"));
        check(rootB.root != rootA.root, QStringLiteral("B's root differs from A's"));

        HistoryKeyring kb;
        repoB->load(uidB, kb, &err);
        auto pb = txB.put(0, vaultB.sealForRecovery(kb.encode()));
        check(pb.outcome == HistoryKeyringTransport::Outcome::Ok && pb.version == 1,
              QStringLiteral("B publishes its own keyring at its own version 1"));

        auto fa = txA.get();
        check(fa.version == 2, QStringLiteral("A's server keyring is unaffected by B"));

        // B's credentials cannot reach A's row, and B's vault cannot open A's blob.
        check(vaultB.openFromRecovery(fa.sealed).isEmpty(),
              QStringLiteral("B's vault cannot open A's server keyring"));
        auto fb2 = txB.get();
        check(fb2.outcome == HistoryKeyringTransport::Outcome::Ok && fb2.sealed != fa.sealed,
              QStringLiteral("B's row is a different object from A's"));
    }

    // Leave the isolated accounts clean.
    txA.remove();
    txB.remove();
    check(txA.get().outcome == HistoryKeyringTransport::Outcome::Absent &&
              txB.get().outcome == HistoryKeyringTransport::Outcome::Absent,
          QStringLiteral("both accounts end with no server keyring"));

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
