#ifndef HISTORYKEYRINGHTTP_H
#define HISTORYKEYRINGHTTP_H

#include "historykeyringtransport.h"

#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <functional>

/**
 * Binds HistoryKeyringTransport to the application's existing authenticated HTTP path.
 *
 * WHY THIS EXISTS RATHER THAN REUSING `httpJson`. That helper is file-local to mlsv2.cpp
 * (anonymous namespace, internal linkage) and, more decisively, it implements only GET and POST -
 * it returns {0,{}} for any other verb. The keyring endpoints need PUT and DELETE, so exporting it
 * would not have been enough; it would have had to be extended, changing a helper that every MLS
 * call already depends on.
 *
 * This is deliberately NOT a second HTTP client and NOT a second authentication mechanism. It
 * borrows the caller's QNetworkAccessManager and the caller's existing header function - the same
 * `(nam, applyAuth)` injection MlsV2Engine::bind already uses - so tokens and the device id keep
 * coming from the one session that owns them. The codebase's established shape is many request
 * sites sharing one manager and one auth helper (ChatService and AuthService both issue PUT and
 * DELETE directly this way); this follows that shape instead of inventing a new one.
 *
 * Synchronous, like `httpJson`, for the same reason: these e2ee calls are sequenced against vault
 * state and the existing code already blocks on them.
 *
 * Serves BOTH Layer B server paths - the history keyring and the archive repository - so there is
 * one authenticated JSON exchange in the application, not one per feature. The name predates the
 * archive path and is now narrower than the role; the signature is deliberately generic.
 *
 * The exchange returns (httpStatus, parsedJsonObject). A transport-level failure - no reply, DNS,
 * timeout - is reported as status 0, which HistoryKeyringTransport maps to TransportError and
 * never to "no keyring".
 *
 * `baseUrl` is for tests only. Left empty - which is what every application caller does - it
 * resolves to Config::apiBaseUrl(), so the production path is exactly the one every other request
 * uses. It exists so the contract can be exercised against an isolated backend without pointing
 * the application at one.
 */
HistoryKeyringTransport::ExchangeFn makeKeyringExchange(
    QNetworkAccessManager* nam,
    std::function<void(QNetworkRequest&)> applyAuth,
    QString baseUrl = QString());

/**
 * Convenience for the common case: builds the header function from the session's own accessors.
 *
 * Takes getters rather than a service pointer so this stays free of any dependency on the services
 * layer, and so a test can supply a synthetic session without constructing one.
 *
 * `bearerToken` must return the raw token; the "Bearer " prefix is added here. An empty token or
 * device id simply omits that header - it never substitutes a placeholder, so an unauthenticated
 * request fails as 401 rather than silently succeeding as somebody else.
 */
HistoryKeyringTransport::ExchangeFn makeKeyringExchange(
    QNetworkAccessManager* nam,
    std::function<QString()> bearerToken,
    std::function<QString()> deviceId,
    QString baseUrl = QString());

#endif // HISTORYKEYRINGHTTP_H
