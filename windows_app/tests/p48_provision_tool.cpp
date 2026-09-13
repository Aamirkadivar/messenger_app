// Phase 48 fixture provisioner - the SOURCE device, in C++, against the isolated backend.
//
// Builds exactly what the Android destination device needs to recover, and nothing else:
//
//   account vault (carrying identity_priv_hex, which is what Phase 44 enrolment proves)
//   -> a history root for one chat
//   -> one message sealed into a Layer B archive, uploaded
//   -> the keyring published as an MK-sealed recovery blob
//
// The Android device then arrives as a genuinely new install: it copies none of this device's local
// storage, and recovers the root through the supported password -> MK -> keyring path.
//
// Sealing here rather than on Android is deliberate: it also demonstrates that the archive an
// Android client opens can be one a Windows client produced, which is the cross-platform contract
// Phases 42/45 pinned with fixtures. The protocol constants are untouched.
//
// Prints only ids, lengths, HTTP statuses and digest prefixes. Never a key, root, or plaintext.
//
// Usage: p48_provision_tool <baseUrl> <tokenFile> <uidFile> <password> <deviceId> <chatId> <msgId>

#include "../src/crypto/archiverepository.h"
#include "../src/crypto/deviceenrollment.h"
#include "../src/crypto/historyarchiver.h"
#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historykeyringtransport.h"
#include "../src/crypto/vaultcrypto.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QTextStream>
#include <memory>
#include <sodium.h>

static int g_fail = 0;
static void step(bool ok, const QString& what) {
    QTextStream(stdout) << (ok ? "  OK    " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_fail;
}
static QString fp(const QByteArray& b) {
    if (b.isEmpty()) return QStringLiteral("<absent>");
    return QString::fromLatin1(
        QCryptographicHash::hash(b, QCryptographicHash::Sha256).toHex().left(12));
}
static QString readFile(const QString& p) {
    QFile f(p);
    if (!f.open(QIODevice::ReadOnly)) return {};
    const QString s = QString::fromLatin1(f.readAll()).trimmed();
    f.close();
    return s;
}

/**
 * The canonical plaintext: ASCII, multibyte Unicode, an embedded NUL, and high-bit bytes.
 *
 * Every byte here is valid UTF-8, because Android's MessageArchiver.open returns a String - the
 * archive carries message text, not arbitrary binary. The high-bit requirement is met by characters
 * whose UTF-8 encoding sets the high bit (0xC3 0xBF for U+00FF, and so on), which is what actually
 * travels through the AEAD. A lone 0xFF would not survive UTF-8 decoding on either platform and
 * would be testing the String contract rather than the archive.
 */
static QByteArray canonicalPlaintext() {
    QByteArray b;
    b += "P48 ascii ";
    b += QString::fromUtf8("\xC3\xA9\xC3\xB6\xC3\xBF \xE2\x9C\x93 \xE6\x97\xA5\xE6\x9C\xAC "
                           "\xCE\x95\xCE\xBB").toUtf8();
    b += QByteArray(1, char(0));   // embedded NUL
    b += "after-nul";
    return b;
}

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

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 8) { out << "usage: see header" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString token = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uid = readFile(QString::fromLocal8Bit(argv[3]));
    const QString password = QString::fromLocal8Bit(argv[4]);
    const QString deviceId = QString::fromLocal8Bit(argv[5]);
    const QString chatId = QString::fromLocal8Bit(argv[6]);
    const QString messageId = QString::fromLocal8Bit(argv[7]);
    if (token.isEmpty() || uid.isEmpty()) { out << "missing credentials" << Qt::endl; return 2; }

    QNetworkAccessManager nam;
    auto exchange = makeKeyringExchange(&nam, [&]() { return token; },
                                        [&]() { return deviceId; }, baseUrl);

    out << "Phase 48 fixture provisioner (source device)" << Qt::endl;
    out << "base " << baseUrl << "  account " << uid.left(8) << "...  chat " << chatId.left(8)
        << "...  message " << messageId.left(8) << "..." << Qt::endl;

    const QByteArray plaintext = canonicalPlaintext();
    out << "plaintext: " << plaintext.size() << " bytes, digest " << fp(plaintext) << Qt::endl;

    // --- the account vault, carrying the identity keypair the destination will prove with.
    QByteArray ipriv, ipub;
    step(VaultCrypto::generateBoxKeyPair(ipriv, ipub), QStringLiteral("identity keypair generated"));
    const QString ipubHex = QString::fromLatin1(ipub.toHex());
    const QString iprivHex = QString::fromLatin1(ipriv.toHex());

    VaultCrypto::BuiltVault built;
    step(VaultCrypto::createVault(uid, password, ipubHex, iprivHex, built, 1),
         QStringLiteral("vault created (identity_priv_hex inside)"));
    {
        QJsonObject v;
        v.insert(QStringLiteral("vault_version"), 1);
        v.insert(QStringLiteral("expected_version"), 0);
        v.insert(QStringLiteral("suite"), QLatin1String(VaultCrypto::SUITE_VAULT_AEAD));
        v.insert(QStringLiteral("vault_ciphertext_b64"), VaultCrypto::b64(built.vaultCiphertext));
        v.insert(QStringLiteral("pw_kdf"), QStringLiteral("argon2id"));
        v.insert(QStringLiteral("pw_salt_b64"), VaultCrypto::b64(built.pwSalt));
        v.insert(QStringLiteral("pw_params"), built.pwParamsJson);
        v.insert(QStringLiteral("pw_wrapped_master_b64"), VaultCrypto::b64(built.pwWrappedMaster));
        v.insert(QStringLiteral("rk_kdf"), QStringLiteral("hkdf-sha256"));
        v.insert(QStringLiteral("rk_salt_b64"), VaultCrypto::b64(built.rkSalt));
        v.insert(QStringLiteral("rk_wrapped_master_b64"), VaultCrypto::b64(built.rkWrappedMaster));
        const auto put = exchange(QStringLiteral("/e2ee/vault"),
                                  QJsonDocument(v).toJson(QJsonDocument::Compact), "PUT");
        step(put.first == 200 || put.first == 201,
             QStringLiteral("vault published (HTTP %1)").arg(put.first));
    }

    // --- this device must be enrolled before it may touch the device-gated routes.
    {
        const DeviceEnrollment enrol(exchange, [&]() { return ipubHex; }, [&]() { return iprivHex; });
        const auto e = enrol.enroll(deviceId, QStringLiteral("p48-source"), QStringLiteral("windows"));
        step(e.verified(), QStringLiteral("source device enrolled with proof of possession"));
    }

    // --- root, seal, upload.
    MemStore store;
    HistoryKeyringRepository repo(
        &store,
        [&](const QByteArray& p, QByteArray& o) {
            o = VaultCrypto::sealXChaCha(built.mk, p,
                QStringLiteral("history-keyring|%1|%2|%3").arg(uid).arg(1)
                    .arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8());
            return !o.isEmpty(); },
        [&](const QByteArray& s, QByteArray& o) {
            o = VaultCrypto::openXChaCha(built.mk, s,
                QStringLiteral("history-keyring|%1|%2|%3").arg(uid).arg(1)
                    .arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8());
            return !o.isEmpty(); },
        nullptr, [&]() { return true; });

    HistoryRootEntry root;
    step(repo.ensureRoot(uid, chatId, root, nullptr), QStringLiteral("history root minted"));
    out << "  root v" << root.rootVersion << " digest " << fp(root.root) << Qt::endl;

    HistoryArchiver archiver(
        [&](const QString& c, HistoryRootEntry& o, QString* e) { return repo.ensureRoot(uid, c, o, e); },
        [&](const QString& c, int rv, HistoryRootEntry& o, QString* e) {
            HistoryKeyring k;
            if (!repo.load(uid, k, e)) return false;
            if (!k.find(c, rv, o)) { if (e) *e = QStringLiteral("root not held"); return false; }
            return true; },
        [&]() { return uid; });

    ArchiveMessage m;
    m.userId = uid; m.chatId = chatId; m.messageId = messageId; m.plaintext = plaintext;
    ArchiveRecord rec;
    step(archiver.seal(m, rec, nullptr), QStringLiteral("message sealed into an archive"));
    {
        const ArchiveRepository ar(exchange);
        const auto saved = ar.save(rec);
        step(saved.outcome == ArchiveRepository::Outcome::Ok,
             QStringLiteral("archive uploaded (stored=%1)").arg(saved.stored ? "true" : "false"));
    }

    // --- publish the keyring so the destination device can recover the root.
    {
        HistoryKeyring kr;
        step(repo.load(uid, kr, nullptr), QStringLiteral("local keyring loaded"));
        const QByteArray sealedKr = VaultCrypto::sealXChaCha(
            built.mk, kr.encode(),
            QStringLiteral("history_keyring_recovery_v1|%1|%2")
                .arg(uid, QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8());
        const HistoryKeyringTransport tr(exchange);
        auto p = tr.put(0, sealedKr);
        if (p.outcome == HistoryKeyringTransport::Outcome::Conflict) p = tr.put(p.serverVersion, sealedKr);
        step(p.outcome == HistoryKeyringTransport::Outcome::Ok,
             QStringLiteral("keyring recovery blob published"));
    }

    // --- sanity: the source device can reopen what it just sealed.
    {
        QByteArray back;
        step(archiver.open(rec, back, nullptr) && back == plaintext,
             QStringLiteral("source device reopens its own archive (byte-exact)"));
    }

    out << (g_fail == 0 ? "FIXTURE READY" : "FIXTURE INCOMPLETE") << Qt::endl;
    return g_fail == 0 ? 0 : 1;
}
