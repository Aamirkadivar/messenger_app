#include "deviceenrollment.h"
#include "vaultcrypto.h"

#include <QJsonDocument>
#include <sodium.h>

namespace {

/**
 * Strict base64, matching HistoryKeyringTransport's reasoning.
 *
 * The sealed challenge arrives from the network, and Qt's default decoder SKIPS characters outside
 * the alphabet - so a corrupt field would quietly become shorter plausible bytes and surface as
 * "the challenge would not open" rather than as the transport error it is.
 */
bool strictUnb64(const QString& s, QByteArray& out) {
    const auto r = QByteArray::fromBase64Encoding(
        s.toLatin1(), QByteArray::Base64Encoding | QByteArray::AbortOnBase64DecodingErrors);
    if (r.decodingStatus != QByteArray::Base64DecodingStatus::Ok) return false;
    out = r.decoded;
    return !out.isEmpty();
}

DeviceEnrollment::Outcome classify(int status) {
    if (status == 200 || status == 201) return DeviceEnrollment::Outcome::Ok;
    if (status == 401) return DeviceEnrollment::Outcome::Unauthorized;
    if (status == 403) return DeviceEnrollment::Outcome::Refused;
    if (status == 400 || status == 413) return DeviceEnrollment::Outcome::Rejected;
    return DeviceEnrollment::Outcome::TransportError;
}

/** hex -> exactly `expected` bytes, or empty. Never a partial decode. */
QByteArray fromHexExact(const QString& hex, int expected) {
    const QByteArray raw = QByteArray::fromHex(hex.trimmed().toLatin1());
    return raw.size() == expected ? raw : QByteArray();
}

} // namespace

DeviceEnrollment::DeviceEnrollment(ExchangeFn exchange,
                                   IdentityKeyFn publicHex,
                                   IdentityKeyFn privateHex)
    : m_exchange(std::move(exchange)),
      m_publicHex(std::move(publicHex)),
      m_privateHex(std::move(privateHex)) {}

DeviceEnrollment::Result DeviceEnrollment::enroll(const QString& deviceId,
                                                  const QString& name,
                                                  const QString& platform) const {
    Result out;
    if (!m_exchange || !m_publicHex || !m_privateHex || deviceId.isEmpty()) {
        out.outcome = Outcome::TransportError;
        return out;
    }

    // Both halves must be present. A device that cannot open the challenge must not ask for one:
    // consuming a challenge it cannot answer is pure noise, and a public key with no private half
    // is exactly the "self-asserted identity" this whole flow exists to refuse.
    const QByteArray pub = fromHexExact(m_publicHex(), crypto_box_PUBLICKEYBYTES);
    const QByteArray priv = fromHexExact(m_privateHex(), crypto_box_SECRETKEYBYTES);
    if (pub.isEmpty() || priv.isEmpty()) {
        out.outcome = Outcome::Locked;
        return out;
    }
    const QString pubHex = QString::fromLatin1(pub.toHex());

    // --- step one: ask for a challenge sealed to this key.
    QJsonObject ask;
    ask.insert(QStringLiteral("device_id"), deviceId);
    ask.insert(QStringLiteral("public_key"), pubHex);
    const QPair<int, QJsonObject> chResp = m_exchange(
        QString::fromLatin1(CHALLENGE_PATH), QJsonDocument(ask).toJson(QJsonDocument::Compact),
        "POST");
    const Outcome chOutcome = classify(chResp.first);
    if (chOutcome != Outcome::Ok) {
        out.outcome = chOutcome;
        return out;
    }

    const QString challengeId = chResp.second.value(QStringLiteral("challenge_id")).toString();
    const QByteArray senderPub =
        fromHexExact(chResp.second.value(QStringLiteral("sender_pub_hex")).toString(),
                     crypto_box_PUBLICKEYBYTES);
    QByteArray sealed;
    if (challengeId.isEmpty() || senderPub.isEmpty() ||
        !strictUnb64(chResp.second.value(QStringLiteral("sealed_b64")).toString(), sealed) ||
        sealed.size() < crypto_box_NONCEBYTES + crypto_box_MACBYTES) {
        out.outcome = Outcome::Malformed;
        return out;
    }

    // --- step two: open it locally. This is the entire proof.
    //
    // Deliberately VaultCrypto::openPairingMk rather than a second crypto_box call: the wire shape
    // is identical (nonce || crypto_box_easy output under an ephemeral sender key), so reusing the
    // existing helper keeps one implementation of this open in the client.
    QByteArray proof = VaultCrypto::openPairingMk(priv, senderPub, sealed);
    if (proof.size() != CHALLENGE_BYTES) {
        sodium_memzero(proof.data(), static_cast<size_t>(proof.size()));
        out.outcome = Outcome::Malformed;
        return out;
    }

    // --- step three: redeem it.
    QJsonObject reg;
    reg.insert(QStringLiteral("device_id"), deviceId);
    reg.insert(QStringLiteral("name"), name);
    reg.insert(QStringLiteral("platform"), platform);
    reg.insert(QStringLiteral("public_key"), pubHex);
    // Phase 67: declare that this is a key minted for THIS device, not the
    // account-wide identity key. The server does not take our word for it - a row
    // is recorded as per-device authority only when this declaration and a spent
    // account-recovery marker agree - but without it an unchanged legacy client
    // would be indistinguishable from a modern one.
    reg.insert(QStringLiteral("authority_kind"), QStringLiteral("device"));
    reg.insert(QStringLiteral("challenge_id"), challengeId);
    reg.insert(QStringLiteral("proof_b64"), VaultCrypto::b64(proof));
    const QByteArray body = QJsonDocument(reg).toJson(QJsonDocument::Compact);
    sodium_memzero(proof.data(), static_cast<size_t>(proof.size()));

    const QPair<int, QJsonObject> regResp =
        m_exchange(QString::fromLatin1(REGISTER_PATH), body, "POST");
    out.outcome = classify(regResp.first);
    out.created = regResp.first == 201;
    return out;
}
