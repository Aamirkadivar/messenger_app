// Phase 43 - forensic probe: can a NEW DEVICE recover history, and what authorizes it?
//
// This is a probe, not a feature. It changes no production behaviour and adds no production caller.
// It drives the EXISTING production classes exactly as a real client would, against an isolated
// backend, to answer one question with evidence rather than reading:
//
//   Which secret actually stands between an authenticated account and its history roots?
//
// The three devices below are three separate in-process worlds. Nothing is shared between them
// except what a real device could obtain: an access token, an asserted X-Device-Id, and whatever
// the server hands back. In particular, DEVICE 2 and DEVICE 3 start with NO local keyring, no
// master key, and no root - exactly a fresh install.
//
// No secret is printed. Roots and keys are reported as SHA-256 prefixes, lengths, or booleans.
//
// Usage: newdevice_recovery_probe <baseUrl> <tokenFile> <uidFile> <password> <chatId> <messageId>

#include "../src/crypto/archiverepository.h"
#include "../src/crypto/historyarchiver.h"
#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historykeyringtransport.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QTextStream>
#include <memory>
#include <sodium.h>

static int g_fail = 0, g_checks = 0;
static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream(stdout) << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_fail;
}
static void note(const QString& s) { QTextStream(stdout) << "        . " << s << Qt::endl; }
static void section(const char* t) { QTextStream(stdout) << "\n== " << t << " ==" << Qt::endl; }

/** Secrets are only ever reported as a short digest prefix. */
static QString fp(const QByteArray& secret) {
    if (secret.isEmpty()) return QStringLiteral("<absent>");
    return QString::fromLatin1(
        QCryptographicHash::hash(secret, QCryptographicHash::Sha256).toHex().left(12));
}
static QString readFile(const QString& p) {
    QFile f(p);
    if (!f.open(QIODevice::ReadOnly)) return {};
    const QString s = QString::fromLatin1(f.readAll()).trimmed();
    f.close();
    return s;
}

// ----------------------------------------------------------------- a device's local world

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

/**
 * One device. Its ONLY inputs are an access token and a device id it asserts for itself; whether it
 * holds a master key is entirely a function of what it managed to obtain.
 */
struct Device {
    QString label, userId, deviceId, token;
    MemStore store;
    QByteArray mk;                      // empty == locked / never obtained
    std::unique_ptr<HistoryKeyringRepository> repo;

    static QByteArray keyringAad(const QString& u) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(u).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    static QByteArray recoveryAad(const QString& u) {
        return QStringLiteral("history_keyring_recovery_v1|%1|%2")
            .arg(u, QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }

    void boot(const QString& lbl, const QString& uid, const QString& dev, const QString& tok) {
        label = lbl; userId = uid; deviceId = dev; token = tok;
        repo = std::make_unique<HistoryKeyringRepository>(
            &store,
            [this](const QByteArray& p, QByteArray& o) {
                if (mk.isEmpty()) return false;
                o = VaultCrypto::sealXChaCha(mk, p, keyringAad(userId)); return !o.isEmpty(); },
            [this](const QByteArray& s, QByteArray& o) {
                if (mk.isEmpty()) return false;
                o = VaultCrypto::openXChaCha(mk, s, keyringAad(userId)); return !o.isEmpty(); },
            nullptr, [this]() { return !mk.isEmpty(); });
    }
    HistoryArchiver archiver() {
        return HistoryArchiver(
            [this](const QString& c, HistoryRootEntry& out, QString* e) {
                return repo->ensureRoot(userId, c, out, e); },
            [this](const QString& c, int rv, HistoryRootEntry& out, QString* e) {
                HistoryKeyring k;
                if (!repo->load(userId, k, e)) return false;
                if (!k.find(c, rv, out)) { if (e) *e = QStringLiteral("root not held"); return false; }
                return true; },
            [this]() { return userId; });
    }
};

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 7) { out << "usage: see header" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString token = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uid = readFile(QString::fromLocal8Bit(argv[3]));
    const QString password = QString::fromLocal8Bit(argv[4]);
    const QString chatId = QString::fromLocal8Bit(argv[5]);
    const QString messageId = QString::fromLocal8Bit(argv[6]);
    if (token.isEmpty() || uid.isEmpty()) { out << "missing credentials" << Qt::endl; return 2; }

    QNetworkAccessManager nam;
    auto exchange = [&](const QString& dev) {
        return makeKeyringExchange(&nam, [&]() { return token; },
                                   [dev]() { return dev; }, baseUrl);
    };

    out << "Phase 43 - new-device history recovery probe" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)   account: " << uid.left(8) << "..." << Qt::endl;

    QByteArray provisionedRoot;   // what DEVICE 1 established, for comparison only
    QString sealedArchiveB64;

    // ================================================================ DEVICE 1
    section("DEVICE 1 - the provisioned device (CASE A)");
    Device d1;
    d1.boot(QStringLiteral("D1"), uid, QStringLiteral("p43-dev-A2"), token);
    {
        // A real unlock derives MK from the password and the server vault. Here D1 is the device
        // that CREATES the vault, so it mints MK the way createVault does.
        VaultCrypto::BuiltVault built;
        const bool made = VaultCrypto::createVault(
            uid, password, QStringLiteral("00"), QStringLiteral("11"), built, 1);
        check(made, QStringLiteral("vault created (MK minted locally, never sent)"));
        d1.mk = built.mk;
        note(QStringLiteral("MK fingerprint %1 (len %2)").arg(fp(d1.mk)).arg(d1.mk.size()));

        // Publish the vault: ciphertext + BOTH wrappings. This is the only thing the server sees.
        QJsonObject body;
        body.insert(QStringLiteral("vault_version"), 1);
        body.insert(QStringLiteral("expected_version"), 0);
        body.insert(QStringLiteral("suite"), QLatin1String(VaultCrypto::SUITE_VAULT_AEAD));
        body.insert(QStringLiteral("vault_ciphertext_b64"), VaultCrypto::b64(built.vaultCiphertext));
        body.insert(QStringLiteral("pw_kdf"), QStringLiteral("argon2id"));
        body.insert(QStringLiteral("pw_salt_b64"), VaultCrypto::b64(built.pwSalt));
        body.insert(QStringLiteral("pw_params"), built.pwParamsJson);
        body.insert(QStringLiteral("pw_wrapped_master_b64"), VaultCrypto::b64(built.pwWrappedMaster));
        body.insert(QStringLiteral("rk_kdf"), QStringLiteral("hkdf-sha256"));
        body.insert(QStringLiteral("rk_salt_b64"), VaultCrypto::b64(built.rkSalt));
        body.insert(QStringLiteral("rk_wrapped_master_b64"), VaultCrypto::b64(built.rkWrappedMaster));
        const auto put = exchange(QStringLiteral("p43-dev-A2"))(
            QStringLiteral("/e2ee/vault"), QJsonDocument(body).toJson(QJsonDocument::Compact), "PUT");
        // The server answers a create with 201 and a replace with 200; both are success here.
        check(put.first == 200 || put.first == 201,
              QStringLiteral("vault published (HTTP %1)").arg(put.first));

        // Mint a history root and seal a message under it.
        HistoryRootEntry root;
        check(d1.repo->ensureRoot(uid, chatId, root, nullptr),
              QStringLiteral("history root minted for the chat"));
        provisionedRoot = root.root;
        note(QStringLiteral("root v%1 fingerprint %2").arg(root.rootVersion).arg(fp(root.root)));

        ArchiveMessage m;
        m.userId = uid; m.chatId = chatId; m.messageId = messageId;
        m.plaintext = QByteArray("PHASE43 secret history line");
        ArchiveRecord rec;
        HistoryArchiver arch = d1.archiver();
        check(arch.seal(m, rec, nullptr), QStringLiteral("message sealed into an archive record"));
        sealedArchiveB64 = rec.ciphertextB64;

        const ArchiveRepository repo(exchange(QStringLiteral("p43-dev-A2")));
        const auto saved = repo.save(rec);
        check(saved.outcome == ArchiveRepository::Outcome::Ok,
              QStringLiteral("archive uploaded (opaque to the server)"));

        // Publish the keyring recovery blob, sealed under MK.
        HistoryKeyring kr;
        check(d1.repo->load(uid, kr, nullptr), QStringLiteral("local keyring loaded"));
        QByteArray sealedKr = VaultCrypto::sealXChaCha(
            d1.mk, kr.encode(), Device::recoveryAad(uid));
        const HistoryKeyringTransport tr(exchange(QStringLiteral("p43-dev-A2")));
        // Version 1 already exists from the shell probe; replace it rather than create.
        auto p = tr.put(0, sealedKr);
        if (p.outcome == HistoryKeyringTransport::Outcome::Conflict) p = tr.put(p.serverVersion, sealedKr);
        check(p.outcome == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("keyring recovery blob published (sealed under MK)"));

        // EXPERIMENT 1: the provisioned device opens its own archive.
        QByteArray plain;
        check(arch.open(rec, plain, nullptr) && plain == m.plaintext,
              QStringLiteral("E1. provisioned device opens the archive"));
    }

    // ================================================================ DEVICE 2
    section("DEVICE 2 - fresh install, valid token, NO password (CASE B, no bootstrap)");
    Device d2;
    d2.boot(QStringLiteral("D2"), uid, QStringLiteral("p43-dev-FRESH"), token);
    {
        // EXPERIMENT 2: a fresh device holds nothing.
        HistoryKeyring empty;
        QString err;
        const bool loaded = d2.repo->load(uid, empty, &err);
        check(!loaded || empty.entries().isEmpty(),
              QStringLiteral("E2. fresh device has NO local history root"));
        check(d2.mk.isEmpty(), QStringLiteral("E2. fresh device holds no master key"));

        // EXPERIMENT 3/4: it authenticates fine, and its self-asserted device id is accepted.
        const auto who = exchange(d2.deviceId)(QStringLiteral("/e2ee/devices"), {}, "GET");
        check(who.first == 200, QStringLiteral("E3/E4. fresh device authenticates (HTTP %1)").arg(who.first));

        // EXPERIMENT 5: it CAN obtain the keyring ciphertext.
        const HistoryKeyringTransport tr(exchange(d2.deviceId));
        const auto fetch = tr.get();
        check(fetch.outcome == HistoryKeyringTransport::Outcome::Ok && !fetch.sealed.isEmpty(),
              QStringLiteral("E5. fresh device DOES obtain the sealed keyring (%1 bytes)")
                  .arg(fetch.sealed.size()));

        // EXPERIMENT 6: it CAN obtain the archive ciphertext.
        const ArchiveRepository repo(exchange(d2.deviceId));
        const auto listed = repo.list(chatId);
        check(listed.outcome == ArchiveRepository::Outcome::Ok && !listed.records.isEmpty(),
              QStringLiteral("E6. fresh device DOES obtain archive ciphertext (%1 rows)")
                  .arg(listed.records.size()));

        // EXPERIMENT 7: and can do NOTHING with either.
        QByteArray opened = VaultCrypto::openXChaCha(QByteArray(32, 0), fetch.sealed,
                                                     Device::recoveryAad(uid));
        check(opened.isEmpty(), QStringLiteral("E7. keyring will not open without MK"));

        QByteArray plain;
        HistoryArchiver arch = d2.archiver();
        check(!listed.records.isEmpty() && !arch.open(listed.records.first(), plain, nullptr) &&
                  plain.isEmpty(),
              QStringLiteral("E7. archive is UNDECRYPTABLE - no root, no plaintext"));
        note(QStringLiteral("ciphertext held: %1 bytes; plaintext recovered: 0")
                 .arg(sealedArchiveB64.size()));
    }

    // ================================================================ DEVICE 3
    section("DEVICE 3 - fresh install that ALSO knows the password (the bootstrap)");
    Device d3;
    d3.boot(QStringLiteral("D3"), uid, QStringLiteral("p43-dev-NEW"), token);
    {
        // Step 1: pull the vault. This is NOT device-guarded - account token is enough.
        const auto got = exchange(d3.deviceId)(QStringLiteral("/e2ee/vault"), {}, "GET");
        check(got.first == 200, QStringLiteral("B1. new device fetches the vault (HTTP %1)").arg(got.first));
        const QJsonObject v = got.second;

        // Step 2: derive the password KEK and unwrap MK. Argon2id over password + server salt.
        const QByteArray pwSalt = VaultCrypto::unb64(v.value(QStringLiteral("pw_salt_b64")).toString());
        const QByteArray pwWrapped =
            VaultCrypto::unb64(v.value(QStringLiteral("pw_wrapped_master_b64")).toString());
        const auto params =
            VaultCrypto::ArgonParams::fromParamsJson(v.value(QStringLiteral("pw_params")).toString());
        const QByteArray kek = VaultCrypto::derivePasswordKek(password, pwSalt, params);
        check(!kek.isEmpty(), QStringLiteral("B2. password KEK derived (Argon2id)"));
        d3.mk = VaultCrypto::openXChaCha(kek, pwWrapped,
                                         VaultCrypto::masterKeyAad(uid, QStringLiteral("password")));
        check(!d3.mk.isEmpty(), QStringLiteral("B3. MASTER KEY recovered on the new device"));
        note(QStringLiteral("MK fingerprint %1 - matches D1: %2")
                 .arg(fp(d3.mk), d3.mk == d1.mk ? QStringLiteral("YES") : QStringLiteral("NO")));

        // Step 3: pull and open the keyring recovery blob.
        const HistoryKeyringTransport tr(exchange(d3.deviceId));
        const auto fetch = tr.get();
        check(fetch.outcome == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("B4. keyring blob fetched"));
        const QByteArray krPlain =
            VaultCrypto::openXChaCha(d3.mk, fetch.sealed, Device::recoveryAad(uid));
        check(!krPlain.isEmpty(), QStringLiteral("B5. keyring OPENED under the recovered MK"));

        HistoryKeyring kr;
        check(HistoryKeyring::decode(krPlain, kr), QStringLiteral("B6. keyring decoded"));
        HistoryRootEntry root;
        check(kr.find(chatId, 1, root) && root.root == provisionedRoot,
              QStringLiteral("B7. HISTORY ROOT recovered, identical to the provisioned one"));
        note(QStringLiteral("root fingerprint %1").arg(fp(root.root)));

        // Step 4: adopt the keyring locally, then open the archive.
        // Adoption is exactly what a real new device would do: reseal the recovered keyring
        // under its OWN local AAD and store it, then read it back through the repository.
        QByteArray localSealed = VaultCrypto::sealXChaCha(d3.mk, krPlain, Device::keyringAad(uid));
        check(!localSealed.isEmpty() && d3.store.saveKeyring(uid, localSealed),
              QStringLiteral("B8. keyring adopted into local storage"));
        HistoryKeyring adopted;
        check(d3.repo->load(uid, adopted, nullptr) && !adopted.isEmpty(),
              QStringLiteral("B8. repository reads the adopted keyring back"));
        const ArchiveRepository repo(exchange(d3.deviceId));
        const auto listed = repo.list(chatId);
        QByteArray plain;
        HistoryArchiver arch = d3.archiver();
        check(!listed.records.isEmpty() && arch.open(listed.records.first(), plain, nullptr) &&
                  plain == QByteArray("PHASE43 secret history line"),
              QStringLiteral("B9. ARCHIVE DECRYPTED on a device that never held the root"));
    }

    // ================================================================ device authorization
    section("What the DEVICE identity actually contributed");
    {
        // D3's device id was never registered by any deliberate act - it was minted by the
        // permissive guard on first contact. Prove the archive route accepts an id invented here.
        const QString invented = QStringLiteral("p43-invented-%1")
            .arg(QDateTime::currentMSecsSinceEpoch());
        const ArchiveRepository repo(exchange(invented));
        const auto listed = repo.list(chatId);
        check(listed.outcome == ArchiveRepository::Outcome::Ok,
              QStringLiteral("an id invented seconds ago reads archives (%1 rows)")
                  .arg(listed.records.size()));
        const HistoryKeyringTransport tr(exchange(invented));
        check(tr.get().outcome == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("the same invented id reads the keyring blob"));
        note(QStringLiteral("=> RequireDeviceIdentity is satisfied by self-assertion alone"));
    }

    out << (g_fail == 0 ? "\nALL PASS" : "\nFAILURES")
        << "  checks=" << g_checks << " failures=" << g_fail << Qt::endl;
    return g_fail == 0 ? 0 : 1;
}
