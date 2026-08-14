#include "vaultcrypto.h"
#include "doubleratchet.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <sodium.h>

namespace {

QByteArray hmacSha256(const QByteArray& key, const QByteArray& data) {
    QByteArray out(crypto_auth_hmacsha256_BYTES, 0);
    crypto_auth_hmacsha256(
        reinterpret_cast<unsigned char*>(out.data()),
        reinterpret_cast<const unsigned char*>(data.constData()),
        static_cast<unsigned long long>(data.size()),
        reinterpret_cast<const unsigned char*>(key.constData()));
    return out;
}

QByteArray hkdfSha256(const QByteArray& ikm, const QByteArray& salt, const QByteArray& info, int length) {
    const QByteArray prk = hmacSha256(salt, ikm);
    QByteArray out(length, 0);
    QByteArray t;
    int pos = 0;
    int counter = 1;
    while (pos < length) {
        QByteArray input = t + info + QByteArray(1, static_cast<char>(counter));
        t = hmacSha256(prk, input);
        const int n = qMin(t.size(), length - pos);
        memcpy(out.data() + pos, t.constData(), static_cast<size_t>(n));
        pos += n;
        counter++;
    }
    return out;
}

} // namespace

QString VaultCrypto::ArgonParams::toParamsJson() const {
    QJsonObject o;
    o.insert(QStringLiteral("m"), static_cast<int>(memoryKiB));
    o.insert(QStringLiteral("t"), static_cast<int>(time));
    o.insert(QStringLiteral("p"), static_cast<int>(threads));
    o.insert(QStringLiteral("dklen"), static_cast<int>(keyLen));
    return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

VaultCrypto::ArgonParams VaultCrypto::ArgonParams::fromParamsJson(const QString& s) {
    ArgonParams p;
    if (s.isEmpty()) return p;
    const QJsonObject o = QJsonDocument::fromJson(s.toUtf8()).object();
    if (o.contains(QStringLiteral("m")))
        p.memoryKiB = static_cast<quint32>(o.value(QStringLiteral("m")).toInt());
    if (o.contains(QStringLiteral("t")))
        p.time = static_cast<quint32>(o.value(QStringLiteral("t")).toInt());
    if (o.contains(QStringLiteral("p")))
        p.threads = static_cast<quint8>(o.value(QStringLiteral("p")).toInt());
    if (o.contains(QStringLiteral("dklen")))
        p.keyLen = static_cast<quint32>(o.value(QStringLiteral("dklen")).toInt());
    return p;
}

QByteArray VaultCrypto::randomBytes(int n) {
    QByteArray b(n, 0);
    randombytes_buf(b.data(), static_cast<size_t>(n));
    return b;
}

QByteArray VaultCrypto::derivePasswordKek(const QString& password,
                                          const QByteArray& salt,
                                          const ArgonParams& params) {
    if (salt.size() != crypto_pwhash_SALTBYTES) return {};
    QByteArray out(static_cast<int>(params.keyLen), 0);
    const QByteArray pw = password.toUtf8();
    const size_t memlimit = static_cast<size_t>(params.memoryKiB) * 1024ULL;
    if (crypto_pwhash(reinterpret_cast<unsigned char*>(out.data()),
                      out.size(),
                      pw.constData(),
                      static_cast<unsigned long long>(pw.size()),
                      reinterpret_cast<const unsigned char*>(salt.constData()),
                      params.time,
                      memlimit,
                      crypto_pwhash_ALG_ARGON2ID13) != 0) {
        return {};
    }
    return out;
}

QByteArray VaultCrypto::deriveRecoveryKek(const QByteArray& recoveryKey, const QByteArray& salt) {
    if (recoveryKey.size() < 32 || salt.size() < 16) return {};
    return hkdfSha256(recoveryKey, salt, QByteArray("messenger-e2ee-recovery-kek-v1"), 32);
}

QByteArray VaultCrypto::masterKeyAad(const QString& userId, const QString& purpose) {
    return QStringLiteral("mk|%1|%2|%3")
        .arg(userId, purpose, QLatin1String(SUITE_VAULT_AEAD))
        .toUtf8();
}

QByteArray VaultCrypto::vaultAad(const QString& userId, int vaultVersion) {
    return QStringLiteral("vault|%1|%2|%3")
        .arg(userId)
        .arg(vaultVersion)
        .arg(QLatin1String(SUITE_VAULT_AEAD))
        .toUtf8();
}

QByteArray VaultCrypto::sealXChaCha(const QByteArray& key,
                                    const QByteArray& plaintext,
                                    const QByteArray& aad) {
    if (key.size() != crypto_aead_xchacha20poly1305_ietf_KEYBYTES) return {};
    QByteArray nonce(crypto_aead_xchacha20poly1305_ietf_NPUBBYTES, 0);
    randombytes_buf(nonce.data(), nonce.size());
    QByteArray cipher(plaintext.size() + crypto_aead_xchacha20poly1305_ietf_ABYTES, 0);
    unsigned long long clen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(
            reinterpret_cast<unsigned char*>(cipher.data()), &clen,
            reinterpret_cast<const unsigned char*>(plaintext.constData()),
            static_cast<unsigned long long>(plaintext.size()),
            reinterpret_cast<const unsigned char*>(aad.constData()),
            static_cast<unsigned long long>(aad.size()),
            nullptr,
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(key.constData())) != 0) {
        return {};
    }
    cipher.resize(static_cast<int>(clen));
    return nonce + cipher;
}

QByteArray VaultCrypto::openXChaCha(const QByteArray& key,
                                    const QByteArray& sealed,
                                    const QByteArray& aad) {
    if (key.size() != crypto_aead_xchacha20poly1305_ietf_KEYBYTES) return {};
    const int npub = crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
    const int abytes = crypto_aead_xchacha20poly1305_ietf_ABYTES;
    if (sealed.size() < npub + abytes) return {};
    const QByteArray nonce = sealed.left(npub);
    const QByteArray cipher = sealed.mid(npub);
    QByteArray plain(cipher.size() - abytes, 0);
    unsigned long long plen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(
            reinterpret_cast<unsigned char*>(plain.data()), &plen,
            nullptr,
            reinterpret_cast<const unsigned char*>(cipher.constData()),
            static_cast<unsigned long long>(cipher.size()),
            reinterpret_cast<const unsigned char*>(aad.constData()),
            static_cast<unsigned long long>(aad.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(key.constData())) != 0) {
        return {};
    }
    plain.resize(static_cast<int>(plen));
    return plain;
}

QByteArray VaultCrypto::encodeVaultPlaintext(const QString& pubHex,
                                             const QString& privHex,
                                             const QMap<QString, QString>& ownSenderKeys,
                                             const QMap<QString, QString>& peerPubs,
                                             const QMap<QString, QString>& peerSenderKeys,
                                             const QMap<QString, QString>& directRatchets) {
    QJsonObject o;
    o.insert(QStringLiteral("format_version"), VAULT_FORMAT);
    o.insert(QStringLiteral("protocol_version"), PROTOCOL_VERSION);
    QJsonArray suites;
    suites.append(QString::fromLatin1(SUITE_NACL_BOX));
    suites.append(QString::fromLatin1(SUITE_VAULT_AEAD));
    o.insert(QStringLiteral("suite_ids"), suites);
    o.insert(QStringLiteral("identity_pub_hex"), pubHex);
    o.insert(QStringLiteral("identity_priv_hex"), privHex);
    if (!ownSenderKeys.isEmpty()) {
        QJsonObject sk;
        for (auto it = ownSenderKeys.constBegin(); it != ownSenderKeys.constEnd(); ++it)
            sk.insert(it.key(), it.value());
        o.insert(QStringLiteral("own_sender_keys"), sk);
    }
    if (!peerPubs.isEmpty()) {
        QJsonObject pp;
        for (auto it = peerPubs.constBegin(); it != peerPubs.constEnd(); ++it)
            pp.insert(it.key(), it.value());
        o.insert(QStringLiteral("peer_pubs"), pp);
    }
    if (!peerSenderKeys.isEmpty()) {
        QJsonObject psk;
        for (auto it = peerSenderKeys.constBegin(); it != peerSenderKeys.constEnd(); ++it)
            psk.insert(it.key(), it.value());
        o.insert(QStringLiteral("peer_sender_keys"), psk);
    }
    if (!directRatchets.isEmpty()) {
        QJsonObject dr;
        for (auto it = directRatchets.constBegin(); it != directRatchets.constEnd(); ++it)
            dr.insert(it.key(), it.value());
        o.insert(QStringLiteral("direct_ratchets"), dr);
    }
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

bool VaultCrypto::decodeVaultPlaintext(const QByteArray& json, VaultPlaintext& out) {
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    if (o.value(QStringLiteral("format_version")).toInt() != VAULT_FORMAT) return false;
    out.identityPubHex = o.value(QStringLiteral("identity_pub_hex")).toString();
    out.identityPrivHex = o.value(QStringLiteral("identity_priv_hex")).toString();
    out.ownSenderKeys.clear();
    out.peerPubs.clear();
    out.peerSenderKeys.clear();
    out.directRatchets.clear();
    const QJsonObject sk = o.value(QStringLiteral("own_sender_keys")).toObject();
    for (auto it = sk.constBegin(); it != sk.constEnd(); ++it)
        out.ownSenderKeys.insert(it.key(), it.value().toString());
    const QJsonObject pp = o.value(QStringLiteral("peer_pubs")).toObject();
    for (auto it = pp.constBegin(); it != pp.constEnd(); ++it)
        out.peerPubs.insert(it.key(), it.value().toString());
    const QJsonObject psk = o.value(QStringLiteral("peer_sender_keys")).toObject();
    for (auto it = psk.constBegin(); it != psk.constEnd(); ++it)
        out.peerSenderKeys.insert(it.key(), it.value().toString());
    const QJsonObject dr = o.value(QStringLiteral("direct_ratchets")).toObject();
    for (auto it = dr.constBegin(); it != dr.constEnd(); ++it)
        out.directRatchets.insert(it.key(), it.value().toString());
    return !out.identityPubHex.isEmpty() && !out.identityPrivHex.isEmpty();
}

bool VaultCrypto::createVault(const QString& userId,
                              const QString& password,
                              const QString& pubHex,
                              const QString& privHex,
                              BuiltVault& out,
                              int vaultVersion,
                              const QMap<QString, QString>& ownSenderKeys,
                              const QMap<QString, QString>& peerPubs,
                              const QMap<QString, QString>& peerSenderKeys,
                              const QMap<QString, QString>& directRatchets) {
    return createVault(userId, password, pubHex, privHex, out, vaultVersion, ArgonParams{},
                         ownSenderKeys, peerPubs, peerSenderKeys, directRatchets);
}

bool VaultCrypto::createVault(const QString& userId,
                              const QString& password,
                              const QString& pubHex,
                              const QString& privHex,
                              BuiltVault& out,
                              int vaultVersion,
                              const ArgonParams& params,
                              const QMap<QString, QString>& ownSenderKeys,
                              const QMap<QString, QString>& peerPubs,
                              const QMap<QString, QString>& peerSenderKeys,
                              const QMap<QString, QString>& directRatchets) {
    const QByteArray salt = randomBytes(crypto_pwhash_SALTBYTES);
    const QByteArray kek = derivePasswordKek(password, salt, params);
    if (kek.isEmpty()) return false;
    const QByteArray mk = randomBytes(32);
    const QByteArray wrappedMk = sealXChaCha(kek, mk, masterKeyAad(userId, QStringLiteral("password")));
    if (wrappedMk.isEmpty()) return false;

    const QByteArray recoveryKey = randomBytes(32);
    const QByteArray rkSalt = randomBytes(crypto_pwhash_SALTBYTES);
    const QByteArray rkKek = deriveRecoveryKek(recoveryKey, rkSalt);
    if (rkKek.isEmpty()) return false;
    const QByteArray wrappedRk = sealXChaCha(rkKek, mk, masterKeyAad(userId, QStringLiteral("recovery")));
    if (wrappedRk.isEmpty()) return false;

    const QByteArray plain = encodeVaultPlaintext(pubHex, privHex, ownSenderKeys, peerPubs, peerSenderKeys, directRatchets);
    const QByteArray sealed = sealXChaCha(mk, plain, vaultAad(userId, vaultVersion));
    if (sealed.isEmpty()) return false;
    out.vaultVersion = vaultVersion;
    out.vaultCiphertext = sealed;
    out.pwSalt = salt;
    out.pwParamsJson = params.toParamsJson();
    out.pwWrappedMaster = wrappedMk;
    out.rkSalt = rkSalt;
    out.rkWrappedMaster = wrappedRk;
    out.recoveryKeyDisplay = b64(recoveryKey);
    out.mk = mk;
    return true;
}

bool VaultCrypto::unlockVault(const QString& userId,
                              const QString& password,
                              int vaultVersion,
                              const QByteArray& vaultCiphertext,
                              const QByteArray& pwSalt,
                              const QString& pwParamsJson,
                              const QByteArray& pwWrappedMaster,
                              UnlockedVault& out) {
    const ArgonParams params = ArgonParams::fromParamsJson(pwParamsJson);
    const QByteArray kek = derivePasswordKek(password, pwSalt, params);
    if (kek.isEmpty()) return false;
    const QByteArray mk = openXChaCha(kek, pwWrappedMaster, masterKeyAad(userId, QStringLiteral("password")));
    if (mk.isEmpty()) return false;
    const QByteArray plain = openXChaCha(mk, vaultCiphertext, vaultAad(userId, vaultVersion));
    if (plain.isEmpty()) return false;
    if (!decodeVaultPlaintext(plain, out.plaintext)) return false;
    out.mk = mk;
    return true;
}

bool VaultCrypto::unlockVaultWithRecovery(const QString& userId,
                                          const QString& recoveryKeyB64,
                                          int vaultVersion,
                                          const QByteArray& vaultCiphertext,
                                          const QByteArray& rkSalt,
                                          const QByteArray& rkWrappedMaster,
                                          UnlockedVault& out) {
    const QByteArray recoveryKey = unb64(recoveryKeyB64.trimmed());
    if (recoveryKey.size() < 32) return false;
    const QByteArray kek = deriveRecoveryKek(recoveryKey, rkSalt);
    if (kek.isEmpty()) return false;
    const QByteArray mk = openXChaCha(kek, rkWrappedMaster, masterKeyAad(userId, QStringLiteral("recovery")));
    if (mk.isEmpty()) return false;
    const QByteArray plain = openXChaCha(mk, vaultCiphertext, vaultAad(userId, vaultVersion));
    if (plain.isEmpty()) return false;
    if (!decodeVaultPlaintext(plain, out.plaintext)) return false;
    out.mk = mk;
    return true;
}

bool VaultCrypto::openVaultWithMk(const QString& userId,
                                  int vaultVersion,
                                  const QByteArray& vaultCiphertext,
                                  const QByteArray& mk,
                                  UnlockedVault& out) {
    const QByteArray plain = openXChaCha(mk, vaultCiphertext, vaultAad(userId, vaultVersion));
    if (plain.isEmpty()) return false;
    if (!decodeVaultPlaintext(plain, out.plaintext)) return false;
    out.mk = mk;
    return true;
}

bool VaultCrypto::generateBoxKeyPair(QByteArray& privOut, QByteArray& pubOut) {
    pubOut.resize(crypto_box_PUBLICKEYBYTES);
    privOut.resize(crypto_box_SECRETKEYBYTES);
    return crypto_box_keypair(reinterpret_cast<unsigned char*>(pubOut.data()),
                              reinterpret_cast<unsigned char*>(privOut.data())) == 0;
}

bool VaultCrypto::sealPairingMk(const QByteArray& recipientPub,
                                const QByteArray& mk,
                                QByteArray& senderPubOut,
                                QByteArray& sealedOut) {
    if (recipientPub.size() != crypto_box_PUBLICKEYBYTES || mk.size() != 32) return false;
    QByteArray senderSk;
    if (!generateBoxKeyPair(senderSk, senderPubOut)) return false;
    QByteArray nonce(crypto_box_NONCEBYTES, 0);
    randombytes_buf(nonce.data(), static_cast<size_t>(nonce.size()));
    QByteArray cipher(mk.size() + crypto_box_MACBYTES, 0);
    if (crypto_box_easy(reinterpret_cast<unsigned char*>(cipher.data()),
                        reinterpret_cast<const unsigned char*>(mk.constData()),
                        static_cast<unsigned long long>(mk.size()),
                        reinterpret_cast<const unsigned char*>(nonce.constData()),
                        reinterpret_cast<const unsigned char*>(recipientPub.constData()),
                        reinterpret_cast<const unsigned char*>(senderSk.constData())) != 0) {
        return false;
    }
    sealedOut = nonce + cipher;
    return true;
}

QByteArray VaultCrypto::openPairingMk(const QByteArray& ourPriv,
                                      const QByteArray& senderPub,
                                      const QByteArray& sealed) {
    if (ourPriv.size() != crypto_box_SECRETKEYBYTES ||
        senderPub.size() != crypto_box_PUBLICKEYBYTES ||
        sealed.size() < crypto_box_NONCEBYTES + crypto_box_MACBYTES) {
        return {};
    }
    const QByteArray nonce = sealed.left(crypto_box_NONCEBYTES);
    const QByteArray cipher = sealed.mid(crypto_box_NONCEBYTES);
    QByteArray plain(cipher.size() - crypto_box_MACBYTES, 0);
    if (crypto_box_open_easy(reinterpret_cast<unsigned char*>(plain.data()),
                             reinterpret_cast<const unsigned char*>(cipher.constData()),
                             static_cast<unsigned long long>(cipher.size()),
                             reinterpret_cast<const unsigned char*>(nonce.constData()),
                             reinterpret_cast<const unsigned char*>(senderPub.constData()),
                             reinterpret_cast<const unsigned char*>(ourPriv.constData())) != 0) {
        return {};
    }
    return plain;
}

QString VaultCrypto::formatPairingString(const QString& sessionId, const QByteArray& ephemeralPub) {
    return QStringLiteral("mp1.%1.%2")
        .arg(sessionId, QString::fromLatin1(ephemeralPub.toHex()));
}

bool VaultCrypto::parsePairingString(const QString& raw, QString& sessionIdOut, QByteArray& ephemeralPubOut) {
    const QString s = raw.trimmed();
    if (s.size() < 8 || s.size() > 256) return false;
    const QStringList parts = s.split(QLatin1Char('.'));
    if (parts.size() != 3 || parts[0] != QLatin1String("mp1") || parts[1].isEmpty() || parts[1].size() > 80) return false;
    if (parts[2].size() != 64) return false;
    ephemeralPubOut = QByteArray::fromHex(parts[2].toLatin1());
    if (ephemeralPubOut.size() != 32) return false;
    sessionIdOut = parts[1];
    return true;
}

QByteArray VaultCrypto::resealVault(const QString& userId,
                                    const QByteArray& mk,
                                    const VaultPlaintext& plaintext,
                                    int vaultVersion) {
    const QByteArray plain = encodeVaultPlaintext(
        plaintext.identityPubHex, plaintext.identityPrivHex,
        plaintext.ownSenderKeys, plaintext.peerPubs, plaintext.peerSenderKeys, plaintext.directRatchets);
    return sealXChaCha(mk, plain, vaultAad(userId, vaultVersion));
}

VaultCrypto::VaultPlaintext VaultCrypto::mergePlaintext(const VaultPlaintext& local,
                                                       const VaultPlaintext& remote) {
    VaultPlaintext out = local;
    if (out.identityPubHex.isEmpty()) out.identityPubHex = remote.identityPubHex;
    if (out.identityPrivHex.isEmpty()) out.identityPrivHex = remote.identityPrivHex;
    for (auto it = remote.ownSenderKeys.constBegin(); it != remote.ownSenderKeys.constEnd(); ++it) {
        if (!out.ownSenderKeys.contains(it.key())) out.ownSenderKeys.insert(it.key(), it.value());
    }
    for (auto it = remote.peerPubs.constBegin(); it != remote.peerPubs.constEnd(); ++it) {
        if (!out.peerPubs.contains(it.key())) out.peerPubs.insert(it.key(), it.value());
    }
    for (auto it = remote.peerSenderKeys.constBegin(); it != remote.peerSenderKeys.constEnd(); ++it) {
        if (!out.peerSenderKeys.contains(it.key())) out.peerSenderKeys.insert(it.key(), it.value());
    }
    out.directRatchets = DoubleRatchet::State::mergeMaps(local.directRatchets, remote.directRatchets);
    return out;
}

bool VaultCrypto::wrapMasterWithPassword(const QString& userId,
                                         const QString& password,
                                         const QByteArray& mk,
                                         QByteArray& saltOut,
                                         QByteArray& wrappedOut,
                                         QString& paramsJsonOut) {
    ArgonParams params;
    saltOut = randomBytes(crypto_pwhash_SALTBYTES);
    const QByteArray kek = derivePasswordKek(password, saltOut, params);
    if (kek.isEmpty()) return false;
    wrappedOut = sealXChaCha(kek, mk, masterKeyAad(userId, QStringLiteral("password")));
    if (wrappedOut.isEmpty()) return false;
    paramsJsonOut = params.toParamsJson();
    return true;
}

QString VaultCrypto::b64(const QByteArray& data) {
    return QString::fromLatin1(data.toBase64());
}

QByteArray VaultCrypto::unb64(const QString& s) {
    return QByteArray::fromBase64(s.toLatin1());
}
