#include "authservice.h"
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QUrlQuery>
#include <QDebug>
#include <QUrl>

AuthService::AuthService(QObject* parent) : QObject(parent) {
    setupNetworkManager();
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

            // Fetch own public key after login
            fetchOwnPublicKey();
        } else {
            emit loginFailed(QStringLiteral("Login succeeded but no token received"));
        }
    } else {
        QString error = reply->errorString();
        emit loginFailed(error);
    }
    reply->deleteLater();
}

void AuthService::onRegisterReplyFinished() {
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

            emit registerSuccess();
            emit loginSuccess(m_currentUserId, m_currentUsername);
            emit isLoggedInChanged();
            emit currentUserIdChanged();
            emit currentUsernameChanged();
            emit tokenReady(m_accessToken);

            // Generate keypair for new user
            Encryption::KeyPair keyPair = Encryption::generateEd25519KeyPair();
            if (!keyPair.publicKey.isEmpty()) {
                QString pubKeyStr = QString::fromUtf8(keyPair.publicKey.toBase64());
                QString privKey = QString::fromUtf8(keyPair.secretKey.toBase64());
                CredentialManager::instance().saveUser(m_currentUserId, m_currentUsername, pubKeyStr, privKey);

                // Send public key to server
                QString url = Config::apiBaseUrl() + QStringLiteral("/crypto/public-key");
                QUrl urlObj(url);
                QNetworkRequest request(urlObj);
                request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
                request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());

                QJsonObject keyData;
                keyData[QStringLiteral("public_key")] = pubKeyStr;
                QNetworkReply* reply2 = m_networkManager->post(request, QJsonDocument(keyData).toJson());
                reply2->deleteLater();
            }
        } else {
            emit registerFailed(QStringLiteral("Registration succeeded but no token received"));
        }
    } else {
        QString error = reply->errorString();
        emit registerFailed(error);
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