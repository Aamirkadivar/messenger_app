#include "historykeyringhttp.h"
#include "../utils/config.h"

#include <QEventLoop>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QUrl>

HistoryKeyringTransport::ExchangeFn makeKeyringExchange(
    QNetworkAccessManager* nam,
    std::function<void(QNetworkRequest&)> applyAuth,
    QString baseUrl) {

    return [nam, applyAuth, baseUrl](const QString& path,
                                     const QByteArray& body,
                                     const char* method) -> QPair<int, QJsonObject> {
        if (!nam) return { 0, {} };

        // Empty baseUrl - every application caller - means the one production base URL.
        const QString base = baseUrl.isEmpty() ? Config::apiBaseUrl() : baseUrl;
        QNetworkRequest req(QUrl(base + path));
        req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
        // The caller's own header function. No token store, device-id source, or auth scheme of
        // this component's own.
        if (applyAuth) applyAuth(req);

        QNetworkReply* reply = nullptr;
        if (qstrcmp(method, "GET") == 0) {
            reply = nam->get(req);
        } else if (qstrcmp(method, "POST") == 0) {
            reply = nam->post(req, body);
        } else if (qstrcmp(method, "PUT") == 0) {
            reply = nam->put(req, body);
        } else if (qstrcmp(method, "DELETE") == 0) {
            reply = nam->deleteResource(req);
        } else {
            return { 0, {} };
        }
        if (!reply) return { 0, {} };

        QEventLoop loop;
        QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        loop.exec();

        // Status 0 means the request never reached a server. Reporting it as a status keeps the
        // distinction the caller depends on: a transport failure must never look like a 404.
        const int status =
            reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        reply->deleteLater();
        return { status, obj };
    };
}

HistoryKeyringTransport::ExchangeFn makeKeyringExchange(
    QNetworkAccessManager* nam,
    std::function<QString()> bearerToken,
    std::function<QString()> deviceId,
    QString baseUrl) {

    auto applyAuth = [bearerToken, deviceId](QNetworkRequest& req) {
        if (bearerToken) {
            const QString token = bearerToken();
            // An absent token omits the header entirely rather than sending "Bearer ", so the
            // server answers 401 instead of parsing an empty credential.
            if (!token.isEmpty()) {
                req.setRawHeader("Authorization", ("Bearer " + token).toUtf8());
            }
        }
        if (deviceId) {
            const QString id = deviceId();
            if (!id.isEmpty()) {
                req.setRawHeader("X-Device-Id", id.toUtf8());
            }
        }
    };
    return makeKeyringExchange(nam, applyAuth, baseUrl);
}
