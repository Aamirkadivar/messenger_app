#ifndef HISTORYKEYRINGTRANSPORT_H
#define HISTORYKEYRINGTRANSPORT_H

#include <QByteArray>
#include <QJsonObject>
#include <QPair>
#include <QString>
#include <functional>

/**
 * Windows client for the history-keyring recovery endpoints.
 *
 * Protocol only. It moves an already-sealed blob to and from the server and never sees a history
 * root, the keyring plaintext, or the master key - the blob it carries is sealed under
 * `history_keyring_recovery_v1|userId`, a domain distinct from the local keyring's, so a recovery
 * blob can never be opened as a local keyring or a vault body.
 *
 * It deliberately owns no HTTP client. The exchange is injected with the same shape as the existing
 * `httpJson` helper, so the app binds it to the one authenticated network path it already has and
 * tests bind it to a local boundary. There is no second client and no second auth mechanism here.
 *
 * Server contract (back-end/handlers/keyring_recovery.go):
 *
 *     GET    /e2ee/history-keyring  -> 200 {version, ciphertext_b64} | 404 when nothing stored
 *     PUT    /e2ee/history-keyring  <- {expected_version, ciphertext_b64}
 *                                   -> 200 {version, created} | 409 {server_version} | 400 | 413
 *     DELETE /e2ee/history-keyring  -> success, idempotent
 *
 * A 404 is "nothing stored", NOT "an empty keyring". Collapsing the two would let a client mistake
 * absence for an authoritative empty keyring and mint over live archives.
 */
class HistoryKeyringTransport {
public:
    /** Same shape as the existing httpJson helper: (path, body, method) -> (httpStatus, json). */
    using ExchangeFn = std::function<QPair<int, QJsonObject>(const QString& path,
                                                             const QByteArray& body,
                                                             const char* method)>;

    static constexpr const char* PATH = "/e2ee/history-keyring";

    enum class Outcome {
        Ok,
        Absent,         ///< 404 - nothing stored for this account.
        Conflict,       ///< 409 - the stored keyring moved on; fetch, merge, retry.
        Unauthorized,   ///< 401/403.
        Rejected,       ///< 400/413 - the server refused the body.
        Malformed,      ///< 2xx whose payload did not parse.
        TransportError, ///< no response, or an unexpected status.
    };

    struct Fetch {
        Outcome outcome = Outcome::TransportError;
        int version = 0;
        QByteArray sealed;
    };

    struct Put {
        Outcome outcome = Outcome::TransportError;
        int version = 0;
        bool created = false;
        int serverVersion = 0; ///< Populated on Conflict.
    };

    explicit HistoryKeyringTransport(ExchangeFn exchange);

    Fetch get() const;

    /** `expectedVersion` 0 means "no object yet" and is create-only, per the server. */
    Put put(int expectedVersion, const QByteArray& sealed) const;

    Outcome remove() const;

private:
    static Outcome classify(int status);

    ExchangeFn m_exchange;
};

#endif // HISTORYKEYRINGTRANSPORT_H
