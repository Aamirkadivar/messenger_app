#include "authservice.h"
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QUrlQuery>
#include <QDebug>
#include <QUrl>

namespace {
// Prefer the server's own {"error": "..."} message over Qt's generic
// "server replied: Conflict" / "Host requires authentication" strings.
QString extractErrorMessage(QNetworkReply* reply) {
    const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
    const QString serverError = obj.value(QStringLiteral("error")).toString();
    return serverError.isEmpty() ? reply->errorString() : serverError;
}
}

AuthService::AuthService(QObject* parent) : QObject(parent) {
    setupNetworkManager();
}

// Restores a previously saved login (access token + user id) so the app
// doesn't force a fresh login - and therefore a fresh WebSocket connect -
// every time it's relaunched, the way most messaging apps stay signed in.
void AuthService::restoreSession() {
    QString token = CredentialManager::instance().getToken(QStringLiteral("access_token"));
    QString userId = CredentialManager::instance().getToken(QStringLiteral("current_user"));
    if (token.isEmpty() || userId.isEmpty()) return;

    m_accessToken = token;
    m_refreshToken = CredentialManager::instance().getToken(QStringLiteral("refresh_token"));
    m_currentUserId = userId;
    m_currentUsername = CredentialManager::instance().getToken(QStringLiteral("username_%1").arg(userId));
    m_loggedIn = true;

    emit isLoggedInChanged();
    emit currentUserIdChanged();
    emit currentUsernameChanged();
    emit loginSuccess(m_currentUserId, m_currentUsername);
    emit tokenReady(m_accessToken);

    // Republish our public key (and generate a keypair if this device somehow
    // has a session but no keys yet) so contacts can always encrypt to us.
    ensureE2EEKeysAndPublish();
}

void AuthService::setupNetworkManager() {
    m_networkManager = new QNetworkAccessManager(this);

    connect(m_networkManager, &QNetworkAccessManager::finished,
            this, [this](QNetworkReply* reply) {
        if (reply->error() != QNetworkReply::NoError) {
            qDebug() << "Network error:" << reply->errorString();
        }
    });
}

QString AuthService::authToken() const {
    if (!m_accessToken.isEmpty()) return m_accessToken;
    return CredentialManager::instance().getToken(QStringLiteral("access_token"));
}

void AuthService::login(const QString& email, const QString& password) {
    QString url = Config::apiBaseUrl() + QStringLiteral("/auth/login");
    QUrl urlObj(url);
    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());

    QJsonObject data;
    data[QStringLiteral("email")] = email;
    data[QStringLiteral("password")] = password;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(data).toJson());
    connect(reply, &QNetworkReply::finished, this, &AuthService::onLoginReplyFinished);
}

void AuthService::registerUser(const QString& username, const QString& email, const QString& password) {
    QString url = Config::apiBaseUrl() + QStringLiteral("/auth/register");
    QUrl urlObj(url);

    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());

    QJsonObject data;
    data[QStringLiteral("username")] = username;
    data[QStringLiteral("email")] = email;
    data[QStringLiteral("password")] = password;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(data).toJson());
    connect(reply, &QNetworkReply::finished, this, &AuthService::onRegisterReplyFinished);
}

void AuthService::logout() {
    m_accessToken.clear();
    m_refreshToken.clear();
    m_loggedIn = false;
    m_currentUserId.clear();
    m_currentUsername.clear();

    CredentialManager::instance().deleteToken(QStringLiteral("access_token"));
    CredentialManager::instance().deleteToken(QStringLiteral("refresh_token"));
    CredentialManager::instance().deleteToken(QStringLiteral("current_user"));

    // Notify QML bindings (isLoggedIn/currentUserId/currentUsername) so the UI
    // actually returns to the login screen - without these the property
    // bindings never re-evaluate and the app stays on the main page.
    emit isLoggedInChanged();
    emit currentUserIdChanged();
    emit currentUsernameChanged();

    emit loginFailed(QStringLiteral("Logged out"));
    emit logoutSuccess();
}

void AuthService::refreshToken() {
    QString refreshTokenVal = CredentialManager::instance().getToken(QStringLiteral("refresh_token"));
    if (refreshTokenVal.isEmpty()) {
        emit loginFailed(QStringLiteral("No refresh token"));
        return;
    }

    QString url = Config::apiBaseUrl() + QStringLiteral("/auth/refresh");
    QUrl urlObj(url);

    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());

    QJsonObject data;
    data[QStringLiteral("refresh_token")] = refreshTokenVal;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(data).toJson());
    connect(reply, &QNetworkReply::finished, this, &AuthService::onRefreshReplyFinished);
}

QString AuthService::e2eePrivateKey() const {
    if (m_currentUserId.isEmpty()) return QString();
    return CredentialManager::instance().getToken(QStringLiteral("e2ee_priv_%1").arg(m_currentUserId));
}

QString AuthService::e2eePublicKey() const {
    if (m_currentUserId.isEmpty()) return QString();
    return CredentialManager::instance().getToken(QStringLiteral("e2ee_pub_%1").arg(m_currentUserId));
}

void AuthService::ensureE2EEKeysAndPublish() {
    if (m_currentUserId.isEmpty()) return;

    QString priv = e2eePrivateKey();
    QString pub = e2eePublicKey();

    // Generate a keypair the first time this user signs in on this device.
    if (priv.isEmpty() || pub.isEmpty()) {
        QString newPub, newPriv;
        if (!Encryption::boxKeyPair(newPub, newPriv)) {
            qWarning() << "[E2EE] Failed to generate keypair";
            return;
        }
        CredentialManager::instance().saveToken(QStringLiteral("e2ee_priv_%1").arg(m_currentUserId), newPriv);
        CredentialManager::instance().saveToken(QStringLiteral("e2ee_pub_%1").arg(m_currentUserId), newPub);
        pub = newPub;
        qDebug() << "[E2EE] Generated new keypair for" << m_currentUserId;
    }

    // Publish (upsert) the public key to the server so contacts can encrypt to us.
    QString token = authToken();
    if (token.isEmpty() || pub.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/crypto/public-key"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + token).toUtf8());

    QJsonObject body;
    body[QStringLiteral("public_key")] = pub;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, reply, [reply]() {
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[E2EE] Failed to publish public key:" << reply->errorString();
        } else {
            qDebug() << "[E2EE] Public key published";
        }
        reply->deleteLater();
    });
}

void AuthService::fetchOwnPublicKey() {
    QString token = authToken();
    if (token.isEmpty()) return;

    QString url = Config::apiBaseUrl() + QStringLiteral("/crypto/public-key");
    QUrl urlObj(url);

    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + token).toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, &AuthService::onPublicKeyReplyFinished);
}

QString AuthService::getContactPublicKey(const QString& contactId) {
    // First check cached keys
    QString cachedKey = CredentialManager::instance().getToken(QStringLiteral("pubkey_%1").arg(contactId));
    if (!cachedKey.isEmpty()) return cachedKey;

    // Fetch from server
    QString token = authToken();
    if (token.isEmpty()) return QString{};

    QString url = Config::apiBaseUrl() + QStringLiteral("/crypto/public-key/%1").arg(contactId);
    QUrl urlObj(url);

    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + token).toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, &AuthService::onContactPublicKeyFinished);
    return QString{}; // Will be filled asynchronously
}

void AuthService::onLoginReplyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();

        // Backend returns: {"tokens": {"access_token": "...", "refresh_token": "..."}, "user": {...}}
        QJsonValue tokensVal = obj.value(QStringLiteral("tokens"));
        QJsonObject tokensObj = tokensVal.isObject() ? tokensVal.toObject() : obj;
        m_accessToken = tokensObj.value(QStringLiteral("access_token")).toString();
        m_refreshToken = tokensObj.value(QStringLiteral("refresh_token")).toString();

        QJsonValue userVal = obj.value(QStringLiteral("user"));
        QJsonObject userObj = userVal.isObject() ? userVal.toObject() : QJsonObject();
        m_currentUserId = userObj.value(QStringLiteral("id")).toString();
        m_currentUsername = userObj.value(QStringLiteral("username")).toString();
        m_loggedIn = !m_accessToken.isEmpty();

        if (m_loggedIn) {
            CredentialManager::instance().saveToken(QStringLiteral("access_token"), m_accessToken);
            CredentialManager::instance().saveToken(QStringLiteral("refresh_token"), m_refreshToken);
            CredentialManager::instance().saveToken(QStringLiteral("current_user"), m_currentUserId);

            emit loginSuccess(m_currentUserId, m_currentUsername);
            emit isLoggedInChanged();
            emit currentUserIdChanged();
            emit currentUsernameChanged();
            emit tokenReady(m_accessToken);

            // Ensure a local E2EE keypair exists and publish our public key.
            ensureE2EEKeysAndPublish();
        } else {
            emit loginFailed(QStringLiteral("Login succeeded but no token received"));
        }
    } else {
        emit loginFailed(extractErrorMessage(reply));
    }
    reply->deleteLater();
}

void AuthService::onRegisterReplyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        // Backend register response is {"message": "...", "user": {...}} - it does
        // NOT issue tokens (only /auth/login does). The caller (Register.qml) logs
        // in with the just-registered credentials once registerSuccess fires.
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        QJsonValue userVal = obj.value(QStringLiteral("user"));
        QJsonObject userObj = userVal.isObject() ? userVal.toObject() : QJsonObject();
        QString userId = userObj.value(QStringLiteral("id")).toString();

        if (!userId.isEmpty()) {
            emit registerSuccess(userId);
        } else {
            emit registerFailed(QStringLiteral("Registration succeeded but response was malformed"));
        }
    } else {
        emit registerFailed(extractErrorMessage(reply));
    }
    reply->deleteLater();
}

void AuthService::onRefreshReplyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();

        // Backend returns: {"tokens": {"access_token": "...", "refresh_token": "..."}}
        QJsonValue tokensVal = obj.value(QStringLiteral("tokens"));
        QJsonObject tokensObj = tokensVal.isObject() ? tokensVal.toObject() : obj;
        m_accessToken = tokensObj.value(QStringLiteral("access_token")).toString();
        QString newRefresh = tokensObj.value(QStringLiteral("refresh_token")).toString();
        if (!newRefresh.isEmpty()) {
            CredentialManager::instance().saveToken(QStringLiteral("refresh_token"), newRefresh);
        }
        CredentialManager::instance().saveToken(QStringLiteral("access_token"), m_accessToken);
        emit tokenReady(m_accessToken);
    } else {
        emit loginFailed(QStringLiteral("Token refresh failed"));
        logout();
    }
    reply->deleteLater();
}

void AuthService::onPublicKeyReplyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        m_ownPublicKey = obj.value(QStringLiteral("public_key")).toString();
        CredentialManager::instance().saveUser(m_currentUserId, m_currentUsername,
                                               m_ownPublicKey, CredentialManager::instance().getToken(QStringLiteral("private_key")));
        emit tokenReady(m_accessToken);
    }
    reply->deleteLater();
}

void AuthService::onContactPublicKeyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        QString pubKey = obj.value(QStringLiteral("public_key")).toString();
        // Cache the contact's public key
        CredentialManager::instance().saveToken(QStringLiteral("pubkey_%1").arg(m_currentUserId), pubKey);
    }
    reply->deleteLater();
}