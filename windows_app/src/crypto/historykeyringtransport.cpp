#include "historykeyringtransport.h"
#include "vaultcrypto.h"

#include <QJsonDocument>

namespace {

/**
 * Strict base64.
 *
 * `VaultCrypto::unb64` uses Qt's default decoder, which SKIPS characters outside the alphabet - so
 * "!!!not base64!!!" decodes to "notbase64" rather than failing. That leniency is fine for values
 * this client produced itself, but the keyring ciphertext arrives from the network, and quietly
 * turning a corrupt field into shorter plausible bytes would surface later as an unopenable keyring
 * rather than as the transport error it actually is. Kept local: the shared helper has sixteen other
 * callers across the vault and recovery paths, and changing their behaviour is not this phase's job.
 */
bool strictUnb64(const QString& s, QByteArray& out) {
    const auto r = QByteArray::fromBase64Encoding(
        s.toLatin1(), QByteArray::Base64Encoding | QByteArray::AbortOnBase64DecodingErrors);
    if (r.decodingStatus != QByteArray::Base64DecodingStatus::Ok) return false;
    out = r.decoded;
    return !out.isEmpty();
}

} // namespace

HistoryKeyringTransport::HistoryKeyringTransport(ExchangeFn exchange)
    : m_exchange(std::move(exchange)) {}

HistoryKeyringTransport::Outcome HistoryKeyringTransport::classify(int status) {
    if (status == 200 || status == 201 || status == 204) return Outcome::Ok;
    if (status == 404) return Outcome::Absent;
    if (status == 409) return Outcome::Conflict;
    if (status == 401 || status == 403) return Outcome::Unauthorized;
    if (status == 400 || status == 413) return Outcome::Rejected;
    return Outcome::TransportError;
}

HistoryKeyringTransport::Fetch HistoryKeyringTransport::get() const {
    Fetch f;
    if (!m_exchange) return f;

    const QPair<int, QJsonObject> resp = m_exchange(QString::fromLatin1(PATH), {}, "GET");
    f.outcome = classify(resp.first);
    if (f.outcome != Outcome::Ok) return f;

    const QJsonObject& o = resp.second;
    const QString b64 = o.value(QStringLiteral("ciphertext_b64")).toString();
    if (b64.isEmpty()) {
        f.outcome = Outcome::Malformed;
        return f;
    }
    QByteArray sealed;
    if (!strictUnb64(b64, sealed)) {
        // A present-but-undecodable ciphertext is malformed, never "nothing stored".
        f.outcome = Outcome::Malformed;
        return f;
    }
    f.version = o.value(QStringLiteral("version")).toInt();
    f.sealed = sealed;
    return f;
}

HistoryKeyringTransport::Put HistoryKeyringTransport::put(int expectedVersion,
                                                          const QByteArray& sealed) const {
    Put p;
    if (!m_exchange) return p;
    if (expectedVersion < 0 || sealed.isEmpty()) {
        p.outcome = Outcome::Rejected;
        return p;
    }

    QJsonObject body;
    body.insert(QStringLiteral("expected_version"), expectedVersion);
    body.insert(QStringLiteral("ciphertext_b64"), VaultCrypto::b64(sealed));

    const QPair<int, QJsonObject> resp = m_exchange(
        QString::fromLatin1(PATH), QJsonDocument(body).toJson(QJsonDocument::Compact), "PUT");
    p.outcome = classify(resp.first);

    if (p.outcome == Outcome::Conflict) {
        p.serverVersion = resp.second.value(QStringLiteral("server_version")).toInt();
        return p;
    }
    if (p.outcome != Outcome::Ok) return p;

    const QJsonObject& o = resp.second;
    if (!o.contains(QStringLiteral("version"))) {
        p.outcome = Outcome::Malformed;
        return p;
    }
    p.version = o.value(QStringLiteral("version")).toInt();
    p.created = o.value(QStringLiteral("created")).toBool();
    if (p.version <= 0) {
        p.outcome = Outcome::Malformed;
        return p;
    }
    return p;
}

HistoryKeyringTransport::Outcome HistoryKeyringTransport::remove() const {
    if (!m_exchange) return Outcome::TransportError;
    const QPair<int, QJsonObject> resp = m_exchange(QString::fromLatin1(PATH), {}, "DELETE");
    const Outcome o = classify(resp.first);
    // Deletion is idempotent server-side; a 404 means the same end state the caller asked for.
    return o == Outcome::Absent ? Outcome::Ok : o;
}
