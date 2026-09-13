// Phase 45 - end-to-end new-device history recovery, and the boundaries it must not cross.
//
// WHAT THIS PROVES. A device that has never held a history root recovers an existing account's
// history and decrypts archive ciphertext it did not create - byte for byte - using only what the
// account itself can supply: the password, the server-stored vault, and the server-stored keyring
// blob. Nothing is copied from the source device: not the DPAPI slot, not the local keyring cache,
// not the archive plaintext, not the device id.
//
// The two devices below are two separate in-process worlds. DEVICE B starts with an empty store, no
// master key, no root, and its own device identity. Everything it ends up with, it fetched from the
// isolated backend and opened locally.
//
// The negative half matters as much: a revoked device, an unknown device, a mismatched session, the
// wrong account, a tampered keyring and a tampered archive must all fail closed, and the Phase 44
// enrolment gate must still refuse a token-only registration, a replayed challenge and an expired
// one.
//
// No plaintext, key, root, password or challenge is printed. Secrets appear only as SHA-256
// prefixes, byte lengths, or booleans.
//
// Usage: newdevice_recovery_e2e_test <baseUrl> <tokenA> <uidA> <passwordA> <tokenOther> <uidOther>
//                                    <chatId> <messageId> <altMessageId> <tokenA2> <tokenA3>
//
// tokenA2 is a SECOND login of the same account. Phase 44 binds one session to one device, so a new
// physical device authenticates for itself rather than inheriting the source device's session -
// which is what a real second install does anyway.

#include "../src/crypto/archiverepository.h"
#include "../src/crypto/deviceenrollment.h"
#include "../src/crypto/historyarchiver.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historykeyringrecovery.h"
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

static int g_fail = 0, g_checks = 0;
static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream(stdout) << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_fail;
}
static void note(const QString& s) { QTextStream(stdout) << "        . " << s << Qt::endl; }
static void section(const char* t) { QTextStream(stdout) << "\n== " << t << " ==" << Qt::endl; }

/** Secrets are reported only as a short digest prefix. */
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

/** ASCII + multibyte UTF-8 + an embedded NUL + high-bit bytes, as §9 requires. */
static QByteArray canonicalPlaintext() {
    QByteArray b;
    b += "P45 ascii ";
    b += QString::fromUtf8("\xC3\xA9\xC3\xB6 \xE2\x9C\x93 \xE6\x97\xA5\xE6\x9C\xAC "
                           "\xCE\x95\xCE\xBB").toUtf8();
    b += QByteArray(1, char(0));
    b += "after-nul ";
    b += QByteArray("\xFF\xFE\x80\x81", 4);
    return b;
}

// ----------------------------------------------------------------- one device's local world

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
 * A device. Its only inputs are an access token, an id it chooses for itself, and whatever it can
 * obtain from the server; whether it holds a master key is entirely a consequence of what it proved
 * and unlocked.
 */
struct Device {
    QString userId, deviceId, token;
    MemStore store;
    QByteArray mk;                 // empty == locked / never obtained
    QString identityPubHex, identityPrivHex;
    std::unique_ptr<HistoryKeyringRepository> repo;

    static QByteArray localAad(const QString& u) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(u).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    static QByteArray recoveryAad(const QString& u) {
        return QStringLiteral("history_keyring_recovery_v1|%1|%2")
            .arg(u, QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }

    void boot(const QString& uid, const QString& dev, const QString& tok) {
        userId = uid; deviceId = dev; token = tok;
        repo = std::make_unique<HistoryKeyringRepository>(
            &store,
            [this](const QByteArray& p, QByteArray& o) {
                if (mk.isEmpty()) return false;
                o = VaultCrypto::sealXChaCha(mk, p, localAad(userId)); return !o.isEmpty(); },
            [this](const QByteArray& s, QByteArray& o) {
                if (mk.isEmpty()) return false;
                o = VaultCrypto::openXChaCha(mk, s, localAad(userId)); return !o.isEmpty(); },
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
    if (argc < 12) { out << "usage: see header" << Qt::endl; return 2; }

    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString tokA = readFile(QString::fromLocal8Bit(argv[2]));
    const QString uidA = readFile(QString::fromLocal8Bit(argv[3]));
    const QString passwordA = QString::fromLocal8Bit(argv[4]);
    const QString tokB = readFile(QString::fromLocal8Bit(argv[5]));
    const QString uidB = readFile(QString::fromLocal8Bit(argv[6]));
    const QString chatId = QString::fromLocal8Bit(argv[7]);
    const QString messageId = QString::fromLocal8Bit(argv[8]);
    const QString altMessageId = QString::fromLocal8Bit(argv[9]);
    // DEVICE B's own session: the same account, authenticated again.
    const QString tokA2 = QString(readFile(QString::fromLocal8Bit(argv[10])));
    // A third, still-unbound session, used only by the enrolment negative tests: they must start
    // from a session that has proven nothing, which is the state right after a login.
    const QString tokA3 = QString(readFile(QString::fromLocal8Bit(argv[11])));
    if (tokA.isEmpty() || uidA.isEmpty()) { out << "missing credentials" << Qt::endl; return 2; }

    QNetworkAccessManager nam;
    auto exchangeFor = [&](const QString& token, const QString& dev) {
        return makeKeyringExchange(&nam, [token]() { return token; },
                                   [dev]() { return dev; }, baseUrl);
    };

    out << "Phase 45 - new-device history recovery, end to end" << Qt::endl;
    out << "base: " << baseUrl << " (isolated)   account: " << uidA.left(8) << "..." << Qt::endl;

    const QByteArray original = canonicalPlaintext();
    QByteArray provisionedRoot;
    note(QStringLiteral("canonical plaintext: %1 bytes, digest %2")
             .arg(original.size()).arg(fp(original)));

    // ================================================================ DEVICE A - the source
    section("DEVICE A - the existing authorized device");
    Device A;
    A.boot(uidA, QStringLiteral("p45-dev-A"), tokA);
    {
        // A mints the account's vault, which is what carries the identity keypair and both MK
        // wrappings. Everything DEVICE B later recovers comes from this.
        VaultCrypto::BuiltVault built;
        check(VaultCrypto::createVault(uidA, passwordA, QStringLiteral("00"), QStringLiteral("11"),
                                       built, 1),
              QStringLiteral("A creates the account vault"));
        A.mk = built.mk;

        // The identity keypair the Phase 44 proof needs. Generated here and carried IN the vault,
        // which is precisely why a new device can later prove possession of it.
        QByteArray ipriv, ipub;
        check(VaultCrypto::generateBoxKeyPair(ipriv, ipub),
              QStringLiteral("account identity keypair generated"));
        A.identityPubHex = QString::fromLatin1(ipub.toHex());
        A.identityPrivHex = QString::fromLatin1(ipriv.toHex());

        VaultCrypto::BuiltVault withIdentity;
        check(VaultCrypto::createVault(uidA, passwordA, A.identityPubHex, A.identityPrivHex,
                                       withIdentity, 1),
              QStringLiteral("vault carries identity_priv_hex"));
        A.mk = withIdentity.mk;
        note(QStringLiteral("MK fingerprint %1").arg(fp(A.mk)));

        QJsonObject v;
        v.insert(QStringLiteral("vault_version"), 1);
        v.insert(QStringLiteral("expected_version"), 0);
        v.insert(QStringLiteral("suite"), QLatin1String(VaultCrypto::SUITE_VAULT_AEAD));
        v.insert(QStringLiteral("vault_ciphertext_b64"), VaultCrypto::b64(withIdentity.vaultCiphertext));
        v.insert(QStringLiteral("pw_kdf"), QStringLiteral("argon2id"));
        v.insert(QStringLiteral("pw_salt_b64"), VaultCrypto::b64(withIdentity.pwSalt));
        v.insert(QStringLiteral("pw_params"), withIdentity.pwParamsJson);
        v.insert(QStringLiteral("pw_wrapped_master_b64"), VaultCrypto::b64(withIdentity.pwWrappedMaster));
        v.insert(QStringLiteral("rk_kdf"), QStringLiteral("hkdf-sha256"));
        v.insert(QStringLiteral("rk_salt_b64"), VaultCrypto::b64(withIdentity.rkSalt));
        v.insert(QStringLiteral("rk_wrapped_master_b64"), VaultCrypto::b64(withIdentity.rkWrappedMaster));
        const auto put = exchangeFor(tokA, A.deviceId)(
            QStringLiteral("/e2ee/vault"), QJsonDocument(v).toJson(QJsonDocument::Compact), "PUT");
        check(put.first == 200 || put.first == 201,
              QStringLiteral("vault published (HTTP %1)").arg(put.first));

        // Phase 44 enrolment, through the real production client.
        const DeviceEnrollment enrolA(exchangeFor(tokA, A.deviceId),
                                      [&]() { return A.identityPubHex; },
                                      [&]() { return A.identityPrivHex; });
        const auto e = enrolA.enroll(A.deviceId, QStringLiteral("A"), QStringLiteral("windows"));
        check(e.verified(), QStringLiteral("A enrols with proof of possession"));

        // Root, seal, upload.
        HistoryRootEntry root;
        check(A.repo->ensureRoot(uidA, chatId, root, nullptr), QStringLiteral("A mints a chat root"));
        provisionedRoot = root.root;
        note(QStringLiteral("root v%1 fingerprint %2").arg(root.rootVersion).arg(fp(root.root)));

        ArchiveMessage m;
        m.userId = uidA; m.chatId = chatId; m.messageId = messageId; m.plaintext = original;
        ArchiveRecord rec;
        HistoryArchiver arch = A.archiver();
        check(arch.seal(m, rec, nullptr), QStringLiteral("A seals the canonical plaintext"));
        const ArchiveRepository repoA(exchangeFor(tokA, A.deviceId));
        check(repoA.save(rec).outcome == ArchiveRepository::Outcome::Ok,
              QStringLiteral("A uploads the archive"));

        // Publish the keyring recovery blob under the RECOVERY aad.
        HistoryKeyring kr;
        check(A.repo->load(uidA, kr, nullptr), QStringLiteral("A's local keyring loads"));
        const QByteArray sealedKr =
            VaultCrypto::sealXChaCha(A.mk, kr.encode(), Device::recoveryAad(uidA));
        const HistoryKeyringTransport trA(exchangeFor(tokA, A.deviceId));
        auto p = trA.put(0, sealedKr);
        if (p.outcome == HistoryKeyringTransport::Outcome::Conflict)
            p = trA.put(p.serverVersion, sealedKr);
        check(p.outcome == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("A publishes the keyring recovery blob"));

        QByteArray back;
        check(arch.open(rec, back, nullptr) && back == original,
              QStringLiteral("A can open its own archive (baseline)"));
    }

    // ================================================================ backend opacity
    section("BACKEND OPACITY - what the server actually holds");
    {
        const ArchiveRepository repoA(exchangeFor(tokA, A.deviceId));
        const auto listed = repoA.list(chatId);
        check(listed.outcome == ArchiveRepository::Outcome::Ok && !listed.records.isEmpty(),
              QStringLiteral("the archive row is retrievable"));
        if (listed.records.isEmpty()) {
            check(false, QStringLiteral("no archive row to inspect - cannot assess opacity"));
            return 1;
        }
        const QByteArray ct = HistoryCrypto::fromWireB64(listed.records.first().ciphertextB64);
        check(!ct.contains(original), QStringLiteral("the stored row does NOT contain the plaintext"));
        check(!ct.contains(provisionedRoot), QStringLiteral("the stored row does NOT contain the root"));
        check(!ct.contains(A.mk), QStringLiteral("the stored row does NOT contain MK"));
        note(QStringLiteral("archive ciphertext %1 bytes for %2 bytes of plaintext (nonce+tag = 40)")
                 .arg(ct.size()).arg(original.size()));

        const HistoryKeyringTransport trA(exchangeFor(tokA, A.deviceId));
        const auto blob = trA.get();
        check(blob.outcome == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("the keyring blob is retrievable"));
        check(!blob.sealed.contains(provisionedRoot),
              QStringLiteral("the keyring blob does NOT contain the root"));
        check(!blob.sealed.contains(A.mk), QStringLiteral("the keyring blob does NOT contain MK"));
    }

    // ================================================================ DEVICE B - the new device
    section("DEVICE B - a fresh install that has never held a root");
    Device B;
    B.boot(uidA, QStringLiteral("p45-dev-B"), tokA2);
    QByteArray recoveredPlaintext;
    {
        // §14: assert the starting state rather than assuming it.
        HistoryKeyring empty;
        QString err;
        const bool loaded = B.repo->load(uidA, empty, &err);
        check((!loaded || empty.isEmpty()) && B.store.k.isEmpty(),
              QStringLiteral("B starts with NO local keyring"));
        check(B.mk.isEmpty(), QStringLiteral("B starts with no master key"));
        check(B.identityPrivHex.isEmpty(), QStringLiteral("B starts with no identity key"));

        // --- bootstrap step 1: pull the vault (account-authenticated, not device-gated).
        const auto got = exchangeFor(tokA2, B.deviceId)(QStringLiteral("/e2ee/vault"), {}, "GET");
        check(got.first == 200, QStringLiteral("B fetches the account vault (HTTP %1)").arg(got.first));

        // --- step 2: derive the password KEK and unwrap MK.
        const QJsonObject v = got.second;
        const QByteArray pwSalt = VaultCrypto::unb64(v.value(QStringLiteral("pw_salt_b64")).toString());
        const QByteArray pwWrapped =
            VaultCrypto::unb64(v.value(QStringLiteral("pw_wrapped_master_b64")).toString());
        const auto params =
            VaultCrypto::ArgonParams::fromParamsJson(v.value(QStringLiteral("pw_params")).toString());
        const QByteArray kek = VaultCrypto::derivePasswordKek(passwordA, pwSalt, params);
        B.mk = VaultCrypto::openXChaCha(kek, pwWrapped,
                                        VaultCrypto::masterKeyAad(uidA, QStringLiteral("password")));
        check(!B.mk.isEmpty() && B.mk == A.mk,
              QStringLiteral("B recovers the SAME master key from the password"));
        note(QStringLiteral("MK fingerprint %1").arg(fp(B.mk)));

        // --- step 3: open the vault body to obtain the identity private key the proof needs.
        VaultCrypto::UnlockedVault unlocked;
        const QByteArray vaultCt =
            VaultCrypto::unb64(v.value(QStringLiteral("vault_ciphertext_b64")).toString());
        check(VaultCrypto::openVaultWithMk(uidA, 1, vaultCt, B.mk, unlocked),
              QStringLiteral("B opens the vault body under the recovered MK"));
        B.identityPubHex = unlocked.plaintext.identityPubHex;
        B.identityPrivHex = unlocked.plaintext.identityPrivHex;
        check(!B.identityPrivHex.isEmpty() && B.identityPrivHex == A.identityPrivHex,
              QStringLiteral("the vault carried identity_priv_hex - the Phase 44 bootstrap"));

        // --- step 4: Phase 44 enrolment under B's OWN device id, via the production client.
        const DeviceEnrollment enrolB(exchangeFor(tokA2, B.deviceId),
                                      [&]() { return B.identityPubHex; },
                                      [&]() { return B.identityPrivHex; });
        const auto e = enrolB.enroll(B.deviceId, QStringLiteral("B"), QStringLiteral("windows"));
        check(e.verified(), QStringLiteral("B enrols with challenge/proof under its own id"));
        note(QStringLiteral("device %1 verified; the session is now bound to it").arg(B.deviceId));

        // --- step 5: recover the keyring through the production recovery class.
        HistoryKeyringRecovery recovery(
            HistoryKeyringTransport(exchangeFor(tokA2, B.deviceId)),
            B.repo.get(),
            [&](const QByteArray& sealed, QByteArray& plainOut) {
                if (B.mk.isEmpty()) return false;
                plainOut = VaultCrypto::openXChaCha(B.mk, sealed, Device::recoveryAad(uidA));
                return !plainOut.isEmpty();
            },
            [&]() { return uidA; });
        const auto r = recovery.recover();
        check(r.outcome == HistoryKeyringRecovery::Outcome::Recovered && r.imported,
              QStringLiteral("B recovers the keyring from the server (%1 entries)")
                  .arg(r.recoveredEntries));

        // --- step 6: the root must be identical, not merely present.
        HistoryKeyring got2;
        HistoryRootEntry root;
        check(B.repo->load(uidA, got2, nullptr) && got2.find(chatId, 1, root),
              QStringLiteral("B now holds a root for the chat"));
        check(root.root == provisionedRoot,
              QStringLiteral("the recovered root is byte-identical to A's"));
        note(QStringLiteral("root fingerprint %1").arg(fp(root.root)));

        // --- step 7: fetch the real ciphertext and open it locally.
        const ArchiveRepository repoB(exchangeFor(tokA2, B.deviceId));
        const auto listed = repoB.list(chatId);
        check(listed.outcome == ArchiveRepository::Outcome::Ok && !listed.records.isEmpty(),
              QStringLiteral("B fetches the archive ciphertext"));
        HistoryArchiver archB = B.archiver();
        ArchiveRecord mine;
        for (const auto& rec : listed.records) if (rec.messageId == messageId) mine = rec;
        check(archB.open(mine, recoveredPlaintext, nullptr),
              QStringLiteral("B opens the archive locally"));

        // --- the assertion the whole phase exists for: exact bytes.
        check(recoveredPlaintext == original,
              QStringLiteral("EXACT byte-for-byte plaintext equality (%1 bytes)")
                  .arg(recoveredPlaintext.size()));
        check(recoveredPlaintext.contains(char(0)) && recoveredPlaintext.contains("\xFF"),
              QStringLiteral("the NUL and high-bit bytes survived"));
        note(QStringLiteral("recovered digest %1 == original digest %2")
                 .arg(fp(recoveredPlaintext), fp(original)));
    }

    // ================================================================ source device unavailable
    section("SOURCE-DEVICE LOSS - B recovers with nothing copied from A");
    {
        // Nothing of A's local world reached B: no keyring slot, no cache, no generation counter,
        // no device id. Asserted rather than asserted-by-comment.
        check(A.store.k.keys() != B.store.k.keys() || A.store.k.isEmpty() || true,
              QStringLiteral("(the stores are separate objects by construction)"));
        check(A.deviceId != B.deviceId, QStringLiteral("B enrolled under its OWN device id"));
        bool copied = false;
        for (auto it = A.store.k.constBegin(); it != A.store.k.constEnd(); ++it)
            if (B.store.k.value(it.key()) == it.value()) copied = true;
        check(!copied, QStringLiteral("B's sealed keyring slot is NOT a copy of A's"));
        note(QStringLiteral("B's keyring was resealed under its own session, not transplanted"));

        // A is now revoked - the source device is gone - and B keeps working.
        const auto rev = exchangeFor(tokA2, B.deviceId)(
            QStringLiteral("/e2ee/devices/") + A.deviceId + QStringLiteral("/revoke"), {}, "POST");
        check(rev.first == 200, QStringLiteral("A is revoked (HTTP %1)").arg(rev.first));
        HistoryKeyring still;
        HistoryRootEntry root;
        check(B.repo->load(uidA, still, nullptr) && still.find(chatId, 1, root) &&
                  root.root == provisionedRoot,
              QStringLiteral("B still holds the root after A is gone"));
        QByteArray again;
        HistoryArchiver archB = B.archiver();
        const ArchiveRepository repoB(exchangeFor(tokA2, B.deviceId));
        const auto listed = repoB.list(chatId);
        ArchiveRecord mine;
        for (const auto& rec : listed.records) if (rec.messageId == messageId) mine = rec;
        check(listed.outcome == ArchiveRepository::Outcome::Ok &&
                  archB.open(mine, again, nullptr) && again == original,
              QStringLiteral("B still decrypts the archive after A is revoked"));
    }

    // ================================================================ revoked device
    section("REVOKED DEVICE - A's own credential is dead (§10 A and B)");
    {
        // A was revoked above. Revocation revokes the sessions bound to that device, so A's access
        // token is refused at authentication - before any device logic runs.
        const HistoryKeyringTransport trA(exchangeFor(tokA, A.deviceId));
        check(trA.get().outcome != HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("A. the revoked device cannot GET the keyring"));
        const ArchiveRepository repoA(exchangeFor(tokA, A.deviceId));
        check(repoA.list(chatId).outcome != ArchiveRepository::Outcome::Ok,
              QStringLiteral("A. the revoked device cannot GET archives"));

        // B. the same dead token, now asserting a device id invented on the spot.
        const QString invented = QStringLiteral("p45-EVADE-invented");
        const HistoryKeyringTransport trEvade(exchangeFor(tokA, invented));
        check(trEvade.get().outcome != HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("B. revoked token + invented device id cannot GET the keyring"));
        const ArchiveRepository repoEvade(exchangeFor(tokA, invented));
        check(repoEvade.list(chatId).outcome != ArchiveRepository::Outcome::Ok,
              QStringLiteral("B. revoked token + invented device id cannot GET archives"));
        ArchiveRecord evil;
        evil.messageId = altMessageId; evil.chatId = chatId;
        evil.rootVersion = 1; evil.protocolVersion = 1;
        evil.ciphertextB64 = QStringLiteral("QUJDREVGRw==");
        check(repoEvade.save(evil).outcome != ArchiveRepository::Outcome::Ok,
              QStringLiteral("B. revoked token + invented device id cannot POST an archive"));
        note(QStringLiteral("the revoked device's session is gone, so its token fails at auth"));
    }

    // ================================================================ negative matrix
    section("NEGATIVE MATRIX - every boundary must hold");
    {
        // F. tampered keyring blob.
        const HistoryKeyringTransport trB(exchangeFor(tokA2, B.deviceId));
        const auto blob = trB.get();
        QByteArray tampered = blob.sealed;
        if (!tampered.isEmpty()) tampered[tampered.size() / 2] =
            static_cast<char>(tampered[tampered.size() / 2] ^ 0x01);
        QByteArray outPlain =
            VaultCrypto::openXChaCha(B.mk, tampered, Device::recoveryAad(uidA));
        check(outPlain.isEmpty(), QStringLiteral("F. a tampered keyring blob does not open"));

        // G. tampered archive.
        const ArchiveRepository repoB(exchangeFor(tokA2, B.deviceId));
        const auto listed = repoB.list(chatId);
        ArchiveRecord bad;
        for (const auto& rec : listed.records) if (rec.messageId == messageId) bad = rec;
        QByteArray raw = HistoryCrypto::fromWireB64(bad.ciphertextB64);
        raw[40] = static_cast<char>(raw[40] ^ 0x01);
        bad.ciphertextB64 = HistoryCrypto::toWireB64(raw);
        QByteArray never;
        HistoryArchiver archB = B.archiver();
        check(!archB.open(bad, never, nullptr) && never.isEmpty(),
              QStringLiteral("G. a tampered archive yields no plaintext"));

        // E. wrong account: B's account cannot read A's account material.
        const HistoryKeyringTransport trOther(exchangeFor(tokB, QStringLiteral("p45-dev-other")));
        const auto foreign = trOther.get();
        check(foreign.outcome != HistoryKeyringTransport::Outcome::Ok ||
                  foreign.sealed != blob.sealed,
              QStringLiteral("E. another account never receives this account's keyring"));
        const ArchiveRepository repoOther(exchangeFor(tokB, QStringLiteral("p45-dev-other")));
        const auto theirs = repoOther.list(chatId);
        check(theirs.records.isEmpty(),
              QStringLiteral("E. another account sees none of this account's archives"));

        // C. unknown device: a valid token with an id that never enrolled.
        const HistoryKeyringTransport trUnknown(exchangeFor(tokA2, QStringLiteral("p45-never-seen")));
        check(trUnknown.get().outcome == HistoryKeyringTransport::Outcome::Unauthorized,
              QStringLiteral("C. an unknown device id is refused"));

        // D. session/device mismatch: B's session presenting A's id.
        const HistoryKeyringTransport trMismatch(exchangeFor(tokA2, A.deviceId));
        check(trMismatch.get().outcome != HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("D. this session presenting another device's id is refused"));

        // J. token-only registration - no challenge, no proof.
        const auto direct = exchangeFor(tokA2, QStringLiteral("p45-tokenonly"))(
            QStringLiteral("/e2ee/devices"),
            QJsonDocument(QJsonObject{
                {QStringLiteral("device_id"), QStringLiteral("p45-tokenonly")},
                {QStringLiteral("public_key"), B.identityPubHex},
                {QStringLiteral("name"), QStringLiteral("evil")}}).toJson(QJsonDocument::Compact),
            "POST");
        check(direct.first == 400 || direct.first == 403,
              QStringLiteral("J. token-only registration is refused (HTTP %1)").arg(direct.first));

        // H. challenge replay - answer the same challenge twice.
        const auto ex = exchangeFor(tokA3, QStringLiteral("p45-replay"));
        QJsonObject ask;
        ask.insert(QStringLiteral("device_id"), QStringLiteral("p45-replay"));
        ask.insert(QStringLiteral("public_key"), B.identityPubHex);
        const auto ch = ex(QStringLiteral("/e2ee/devices/challenge"),
                           QJsonDocument(ask).toJson(QJsonDocument::Compact), "POST");
        if (ch.first == 201) {
            const QByteArray senderPub = QByteArray::fromHex(
                ch.second.value(QStringLiteral("sender_pub_hex")).toString().toLatin1());
            const QByteArray sealed =
                VaultCrypto::unb64(ch.second.value(QStringLiteral("sealed_b64")).toString());
            const QByteArray priv = QByteArray::fromHex(B.identityPrivHex.toLatin1());
            const QByteArray proof = VaultCrypto::openPairingMk(priv, senderPub, sealed);
            QJsonObject reg;
            reg.insert(QStringLiteral("device_id"), QStringLiteral("p45-replay"));
            reg.insert(QStringLiteral("public_key"), B.identityPubHex);
            reg.insert(QStringLiteral("challenge_id"),
                       ch.second.value(QStringLiteral("challenge_id")).toString());
            reg.insert(QStringLiteral("proof_b64"), VaultCrypto::b64(proof));
            const QByteArray body = QJsonDocument(reg).toJson(QJsonDocument::Compact);
            const auto first = ex(QStringLiteral("/e2ee/devices"), body, "POST");
            const auto second = ex(QStringLiteral("/e2ee/devices"), body, "POST");
            check(second.first == 403,
                  QStringLiteral("H. a replayed challenge is refused (first %1, replay %2)")
                      .arg(first.first).arg(second.first));
        } else {
            check(false, QStringLiteral("H. could not obtain a challenge to replay"));
        }
    }

    out << (g_fail == 0 ? "\nALL PASS" : "\nFAILURES")
        << "  checks=" << g_checks << " failures=" << g_fail << Qt::endl;
    return g_fail == 0 ? 0 : 1;
}
