#include "authservice.h"
#include "../crypto/doubleratchet.h"
#include "../utils/pairingqr.h"
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QUrlQuery>
#include <QDebug>
#include <QUrl>
#include <QHttpMultiPart>
#include <QHttpPart>
#include <QFile>
#include <QDir>
#include <QFileInfo>
#include <QFileInfo>
#include <QMimeDatabase>
#include <QSysInfo>
#include <QUuid>
#include <QVariantList>
#include <QVariantMap>
#include <QTimer>
#include <QDateTime>

namespace {
// Prefer the server's own {"error": "..."} message over Qt's generic
// "server replied: Conflict" / "Host requires authentication" strings.
QString extractErrorMessage(QNetworkReply* reply) {
    const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
    const QString serverError = obj.value(QStringLiteral("error")).toString();
    return serverError.isEmpty() ? reply->errorString() : serverError;
}

QString extractErrorFromBody(const QByteArray& resp, QNetworkReply* reply) {
    const QString serverError = QJsonDocument::fromJson(resp).object().value(QStringLiteral("error")).toString();
    return serverError.isEmpty() ? reply->errorString() : serverError;
}
}

AuthService::AuthService(QObject* parent) : QObject(parent) {
    setupNetworkManager();
    m_vaultRefreshTimer = new QTimer(this);
    m_vaultRefreshTimer->setSingleShot(true);
    m_vaultRefreshTimer->setInterval(2000);
    connect(m_vaultRefreshTimer, &QTimer::timeout, this, &AuthService::performVaultRefresh);
    m_vaultPullTimer = new QTimer(this);
    m_vaultPullTimer->setInterval(30000);
    connect(m_vaultPullTimer, &QTimer::timeout, this, [this]() { pullAndMergeVault(false); });
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

    // Republish our public key when local keys exist. Without the password we
    // cannot unlock a remote vault — do not mint a replacement identity here.
    ensureE2EEKeysAndPublish(false);
    fetchOwnProfile();
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
    m_pendingVaultPassword = password;
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
    m_pendingVaultPassword.clear();
    clearVaultSession();
    m_loggedIn = false;
    m_currentUserId.clear();
    m_currentUsername.clear();
    if (m_totpEnabled) {
        m_totpEnabled = false;
        emit totpStatusChanged();
    }

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

void AuthService::fetchTotpStatus() {
    if (m_accessToken.isEmpty()) return;
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/auth/2fa/status"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        if (reply->error() == QNetworkReply::NoError) {
            const bool on = QJsonDocument::fromJson(reply->readAll()).object()
                                .value(QStringLiteral("totp_enabled")).toBool(false);
            if (on != m_totpEnabled) {
                m_totpEnabled = on;
                emit totpStatusChanged();
            }
        }
        reply->deleteLater();
    });
}

void AuthService::startTotpSetup() {
    if (m_accessToken.isEmpty()) {
        emit totpFailed(QStringLiteral("Not signed in"));
        return;
    }
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/auth/2fa/totp/setup"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QByteArray("{}"));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray resp = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit totpFailed(extractErrorFromBody(resp, reply));
            reply->deleteLater();
            return;
        }
        const QJsonObject obj = QJsonDocument::fromJson(resp).object();
        emit totpSetupReady(obj.value(QStringLiteral("secret")).toString(),
                            obj.value(QStringLiteral("otpauth_url")).toString());
        reply->deleteLater();
    });
}

void AuthService::confirmTotp(const QString& code) {
    if (m_accessToken.isEmpty()) {
        emit totpFailed(QStringLiteral("Not signed in"));
        return;
    }
    QJsonObject body;
    body[QStringLiteral("code")] = code.trimmed();
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/auth/2fa/totp/confirm"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray resp = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit totpFailed(extractErrorFromBody(resp, reply));
            reply->deleteLater();
            return;
        }
        m_totpEnabled = true;
        QStringList codes;
        const QJsonArray arr = QJsonDocument::fromJson(resp).object().value(QStringLiteral("backup_codes")).toArray();
        for (const auto& v : arr) codes.append(v.toString());
        emit totpStatusChanged();
        emit totpConfirmSucceeded(codes);
        reply->deleteLater();
    });
}

void AuthService::disableTotp(const QString& password, const QString& code) {
    if (m_accessToken.isEmpty()) {
        emit totpFailed(QStringLiteral("Not signed in"));
        return;
    }
    QJsonObject body;
    body[QStringLiteral("password")] = password;
    body[QStringLiteral("code")] = code.trimmed();
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/auth/2fa/totp/disable"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray resp = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit totpFailed(extractErrorFromBody(resp, reply));
            reply->deleteLater();
            return;
        }
        m_totpEnabled = false;
        emit totpStatusChanged();
        reply->deleteLater();
    });
}

void AuthService::regenerateTotpBackupCodes(const QString& password, const QString& code) {
    if (m_accessToken.isEmpty()) {
        emit totpFailed(QStringLiteral("Not signed in"));
        return;
    }
    QJsonObject body;
    body[QStringLiteral("password")] = password;
    body[QStringLiteral("code")] = code.trimmed();
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/auth/2fa/totp/backup-codes"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const QByteArray resp = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit totpFailed(extractErrorFromBody(resp, reply));
            reply->deleteLater();
            return;
        }
        QStringList codes;
        const QJsonArray arr = QJsonDocument::fromJson(resp).object().value(QStringLiteral("backup_codes")).toArray();
        for (const auto& v : arr) codes.append(v.toString());
        emit totpConfirmSucceeded(codes);
        reply->deleteLater();
    });
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

void AuthService::ensureE2EEKeysAndPublish(bool allowTakeover) {
    if (m_currentUserId.isEmpty()) return;

    QString priv = e2eePrivateKey();
    QString pub = e2eePublicKey();

    // Generate a keypair the first time this user signs in on this device.
    if (priv.isEmpty() || pub.isEmpty()) {
        if (!allowTakeover) {
            qWarning() << "[E2EE] No local keys and takeover disabled — unlock vault with password";
            return;
        }
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
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

    QJsonObject body;
    body[QStringLiteral("public_key")] = pub;

    const bool mintedNow = priv.isEmpty();
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, mintedNow]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (status == 409) {
            if (mintedNow && !m_currentUserId.isEmpty()) {
                CredentialManager::instance().deleteToken(QStringLiteral("e2ee_priv_%1").arg(m_currentUserId));
                CredentialManager::instance().deleteToken(QStringLiteral("e2ee_pub_%1").arg(m_currentUserId));
            }
            emit vaultNeedsRecovery(QStringLiteral("Unlock encryption history on this device instead of replacing keys"));
        } else if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[E2EE] Failed to publish public key:" << reply->errorString();
        } else {
            qDebug() << "[E2EE] Public key published";
        }
        reply->deleteLater();
    });
}

void AuthService::syncE2EEVaultAfterLogin() {
    if (m_currentUserId.isEmpty() || m_accessToken.isEmpty()) {
        ensureE2EEKeysAndPublish(true);
        m_pendingVaultPassword.clear();
        finishLoginAfterVault();
        return;
    }
    if (m_pendingVaultPassword.isEmpty()) {
        ensureE2EEKeysAndPublish(false);
        finishLoginAfterVault();
        return;
    }

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, &AuthService::onVaultGetFinished);
}

void AuthService::onVaultGetFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QByteArray body = reply->readAll();
    reply->deleteLater();

    if (status == 404 || reply->error() == QNetworkReply::ContentNotFoundError) {
        qDebug() << "[E2EE] No vault yet — creating";
        ensureE2EEKeysAndPublish(true);
        uploadNewVault();
        return;
    }

    if (reply->error() != QNetworkReply::NoError) {
        qWarning() << "[E2EE] Vault GET failed:" << reply->errorString() << "— falling back to local keys";
        ensureE2EEKeysAndPublish(true);
        m_pendingVaultPassword.clear();
        finishLoginAfterVault();
        return;
    }

    const QJsonObject obj = QJsonDocument::fromJson(body).object();
    const int vaultVersion = obj.value(QStringLiteral("vault_version")).toInt();
    const QByteArray ct = VaultCrypto::unb64(obj.value(QStringLiteral("vault_ciphertext_b64")).toString());
    const QByteArray salt = VaultCrypto::unb64(obj.value(QStringLiteral("pw_salt_b64")).toString());
    const QByteArray wrapped = VaultCrypto::unb64(obj.value(QStringLiteral("pw_wrapped_master_b64")).toString());
    const QString pwParams = obj.value(QStringLiteral("pw_params")).toString();

    VaultCrypto::UnlockedVault unlocked;
    if (!VaultCrypto::unlockVault(m_currentUserId, m_pendingVaultPassword, vaultVersion,
                                  ct, salt, pwParams, wrapped, unlocked)) {
        qWarning() << "[E2EE] Vault unlock failed — prompting for recovery key";
        emit vaultNeedsRecovery(QStringLiteral("Vault password unlock failed — enter your recovery key"));
        return;
    }

    applyUnlockedVault(unlocked);
    rememberSession(unlocked.mk, vaultVersion, obj);
    m_pendingVaultPassword.clear();
    ensureE2EEKeysAndPublish(false);
    registerE2EEDevice();
    qDebug() << "[E2EE] Vault unlocked v" << vaultVersion;
    finishLoginAfterVault();
}

void AuthService::uploadNewVault() {
    const QString pub = e2eePublicKey();
    const QString priv = e2eePrivateKey();
    if (pub.isEmpty() || priv.isEmpty() || m_pendingVaultPassword.isEmpty()) {
        m_pendingVaultPassword.clear();
        finishLoginAfterVault();
        return;
    }

    VaultCrypto::BuiltVault built;
    if (!VaultCrypto::createVault(m_currentUserId, m_pendingVaultPassword, pub, priv, built, 1,
                                  exportVaultSenderKeys(), exportVaultPeerPubs(),
                                  exportVaultPeerSenderKeys(), exportVaultDirectRatchets())) {
        qWarning() << "[E2EE] Failed to build vault";
        m_pendingVaultPassword.clear();
        return;
    }

    QJsonObject body;
    body[QStringLiteral("vault_version")] = built.vaultVersion;
    body[QStringLiteral("protocol_version")] = VaultCrypto::PROTOCOL_VERSION;
    body[QStringLiteral("suite")] = QString::fromLatin1(VaultCrypto::SUITE_VAULT_AEAD);
    body[QStringLiteral("vault_ciphertext_b64")] = VaultCrypto::b64(built.vaultCiphertext);
    body[QStringLiteral("pw_kdf")] = QString::fromLatin1(VaultCrypto::KDF_ARGON2ID);
    body[QStringLiteral("pw_salt_b64")] = VaultCrypto::b64(built.pwSalt);
    body[QStringLiteral("pw_params")] = built.pwParamsJson;
    body[QStringLiteral("pw_wrapped_master_b64")] = VaultCrypto::b64(built.pwWrappedMaster);
    body[QStringLiteral("rk_kdf")] = QString::fromLatin1(VaultCrypto::KDF_HKDF_SHA256);
    body[QStringLiteral("rk_salt_b64")] = VaultCrypto::b64(built.rkSalt);
    body[QStringLiteral("rk_wrapped_master_b64")] = VaultCrypto::b64(built.rkWrappedMaster);
    body[QStringLiteral("expected_version")] = 0;

    const QString recoveryDisplay = built.recoveryKeyDisplay;
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

    QNetworkReply* reply = m_networkManager->put(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, recoveryDisplay, built]() {
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[E2EE] Vault PUT failed:" << reply->errorString();
            ensureE2EEKeysAndPublish(true);
            m_pendingVaultPassword.clear();
            finishLoginAfterVault();
        } else {
            qDebug() << "[E2EE] Vault created";
            QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
            if (obj.isEmpty()) {
                obj.insert(QStringLiteral("pw_kdf"), QString::fromLatin1(VaultCrypto::KDF_ARGON2ID));
                obj.insert(QStringLiteral("pw_salt_b64"), VaultCrypto::b64(built.pwSalt));
                obj.insert(QStringLiteral("pw_params"), built.pwParamsJson);
                obj.insert(QStringLiteral("pw_wrapped_master_b64"), VaultCrypto::b64(built.pwWrappedMaster));
                obj.insert(QStringLiteral("rk_kdf"), QString::fromLatin1(VaultCrypto::KDF_HKDF_SHA256));
                obj.insert(QStringLiteral("rk_salt_b64"), VaultCrypto::b64(built.rkSalt));
                obj.insert(QStringLiteral("rk_wrapped_master_b64"), VaultCrypto::b64(built.rkWrappedMaster));
            }
            rememberSession(built.mk, built.vaultVersion, obj);
            if (!recoveryDisplay.isEmpty()) emit recoveryKeyReady(recoveryDisplay);
            m_pendingVaultPassword.clear();
            registerE2EEDevice();
            finishLoginAfterVault();
        }
        reply->deleteLater();
    });
}

void AuthService::applyUnlockedVault(const VaultCrypto::UnlockedVault& unlocked) {
    CredentialManager::instance().saveToken(
        QStringLiteral("e2ee_priv_%1").arg(m_currentUserId), unlocked.plaintext.identityPrivHex);
    CredentialManager::instance().saveToken(
        QStringLiteral("e2ee_pub_%1").arg(m_currentUserId), unlocked.plaintext.identityPubHex);
    restoreVaultMaterial(unlocked.plaintext);
}

QString AuthService::getOrCreateDeviceId() const {
    const QString key = QStringLiteral("e2ee_device_id");
    QString id = CredentialManager::instance().getToken(key);
    if (!id.isEmpty()) return id;
    id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    CredentialManager::instance().saveToken(key, id);
    return id;
}

QMap<QString, QString> AuthService::exportVaultSenderKeys() const {
    QMap<QString, QString> out;
    const QMap<QString, QString> stored =
        CredentialManager::instance().tokensWithPrefix(QStringLiteral("group_senderkey_"));
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QString rest = it.key().mid(QStringLiteral("group_senderkey_").size());
        if (rest.contains(QLatin1Char('|'))) {
            if (!it.value().isEmpty()) out.insert(rest, it.value());
            continue;
        }
        const int sep = it.value().indexOf(':');
        if (sep <= 0) continue;
        const QString composite = rest + QLatin1Char('|') + it.value().left(sep);
        if (!out.contains(composite) && !it.value().mid(sep + 1).isEmpty()) {
            out.insert(composite, it.value().mid(sep + 1));
        }
    }
    return out;
}

QMap<QString, QString> AuthService::exportVaultPeerPubs() const {
    QMap<QString, QString> out;
    const QMap<QString, QString> stored =
        CredentialManager::instance().tokensWithPrefix(QStringLiteral("known_pubkey_"));
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QString chatId = it.key().mid(QStringLiteral("known_pubkey_").size());
        if (!it.value().isEmpty()) out.insert(chatId, it.value());
    }
    return out;
}

QMap<QString, QString> AuthService::exportVaultPeerSenderKeys() const {
    QMap<QString, QString> out;
    const QMap<QString, QString> stored =
        CredentialManager::instance().tokensWithPrefix(QStringLiteral("peer_senderkey_"));
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QString rest = it.key().mid(QStringLiteral("peer_senderkey_").size());
        if (rest.count(QLatin1Char('|')) >= 2 && !it.value().isEmpty())
            out.insert(rest, it.value());
    }
    return out;
}

QMap<QString, QString> AuthService::exportVaultDirectRatchets() const {
    QMap<QString, QString> out;
    const QMap<QString, QString> stored =
        CredentialManager::instance().tokensWithPrefix(QStringLiteral("dr3_"));
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QString chatId = it.key().mid(QStringLiteral("dr3_").size());
        if (!chatId.isEmpty() && !it.value().isEmpty())
            out.insert(chatId, it.value());
    }
    return out;
}

void AuthService::restoreVaultMaterial(const VaultCrypto::VaultPlaintext& plain) {
    QMap<QString, int> latestVer;
    QMap<QString, QString> latestHex;
    for (auto it = plain.ownSenderKeys.constBegin(); it != plain.ownSenderKeys.constEnd(); ++it) {
        const int sep = it.key().lastIndexOf(QLatin1Char('|'));
        if (sep <= 0 || it.value().isEmpty()) continue;
        const QString chatId = it.key().left(sep);
        const QString version = it.key().mid(sep + 1);
        CredentialManager::instance().saveToken(
            QStringLiteral("group_senderkey_%1|%2").arg(chatId, version), it.value());
        bool ok = false;
        const int ver = version.toInt(&ok);
        if (ok && ver >= latestVer.value(chatId, -1)) {
            latestVer.insert(chatId, ver);
            latestHex.insert(chatId, it.value());
        }
    }
    for (auto it = latestVer.constBegin(); it != latestVer.constEnd(); ++it) {
        CredentialManager::instance().saveToken(
            QStringLiteral("group_senderkey_%1").arg(it.key()),
            QString::number(it.value()) + QLatin1Char(':') + latestHex.value(it.key()));
    }
    for (auto it = plain.peerPubs.constBegin(); it != plain.peerPubs.constEnd(); ++it) {
        if (!it.value().isEmpty()) {
            CredentialManager::instance().saveToken(
                QStringLiteral("known_pubkey_%1").arg(it.key()), it.value());
        }
    }
    for (auto it = plain.peerSenderKeys.constBegin(); it != plain.peerSenderKeys.constEnd(); ++it) {
        if (it.key().count(QLatin1Char('|')) >= 2 && !it.value().isEmpty()) {
            CredentialManager::instance().saveToken(
                QStringLiteral("peer_senderkey_%1").arg(it.key()), it.value());
        }
    }
    for (auto it = plain.directRatchets.constBegin(); it != plain.directRatchets.constEnd(); ++it) {
        if (it.key().isEmpty() || it.value().isEmpty()) continue;
        const QString existing = CredentialManager::instance().getToken(
            QStringLiteral("dr3_%1").arg(it.key()));
        CredentialManager::instance().saveToken(
            QStringLiteral("dr3_%1").arg(it.key()),
            DoubleRatchet::State::preferJson(existing, it.value()));
    }
    emit vaultRatchetsUpdated();
}

void AuthService::registerE2EEDevice() {
    if (m_currentUserId.isEmpty() || m_accessToken.isEmpty()) return;
    const QString pub = e2eePublicKey();
    if (pub.isEmpty()) return;

    QJsonObject body;
    body[QStringLiteral("device_id")] = getOrCreateDeviceId();
    body[QStringLiteral("name")] = QSysInfo::prettyProductName();
    body[QStringLiteral("platform")] = QStringLiteral("windows");
    body[QStringLiteral("public_key")] = pub;

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/devices"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, reply, [reply]() {
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[E2EE] Device register failed:" << reply->errorString();
        } else {
            qDebug() << "[E2EE] Device registered";
        }
        reply->deleteLater();
    });
}

void AuthService::rememberSession(const QByteArray& mk, int version, const QJsonObject& vaultJson) {
    m_sessionMk = mk;
    m_sessionVaultVersion = version;
    m_sessionVaultMeta = vaultJson;
    if (m_vaultPullTimer) m_vaultPullTimer->start();
}

void AuthService::clearVaultSession() {
    if (m_vaultRefreshTimer) m_vaultRefreshTimer->stop();
    if (m_vaultPullTimer) m_vaultPullTimer->stop();
    m_sessionMk.clear();
    m_sessionVaultVersion = 0;
    m_sessionVaultMeta = QJsonObject();
    m_pairingEphPriv.clear();
    m_pairingSessionId.clear();
    if (!m_pairingQrPath.isEmpty()) {
        QFile::remove(m_pairingQrPath);
        m_pairingQrPath.clear();
        emit pairingQrPathChanged();
    }
}

void AuthService::startDevicePairing() {
    if (m_accessToken.isEmpty() || m_currentUserId.isEmpty()) {
        emit devicePairingFailed(QStringLiteral("Not signed in"));
        return;
    }
    QByteArray priv, pub;
    if (!VaultCrypto::generateBoxKeyPair(priv, pub)) {
        emit devicePairingFailed(QStringLiteral("Keygen failed"));
        return;
    }
    QJsonObject body;
    body[QStringLiteral("ephemeral_pub_hex")] = QString::fromLatin1(pub.toHex());
    body[QStringLiteral("device_id")] = getOrCreateDeviceId();

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/pairing"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, priv]() {
        const QByteArray resp = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit devicePairingFailed(QStringLiteral("Could not start device link"));
            reply->deleteLater();
            return;
        }
        const QJsonObject obj = QJsonDocument::fromJson(resp).object();
        m_pairingEphPriv = priv;
        m_pairingSessionId = obj.value(QStringLiteral("session_id")).toString();
        const QString code = obj.value(QStringLiteral("pairing_string")).toString();
        const QString path = QDir::temp().filePath(
            QStringLiteral("messenger-pair-%1.png").arg(m_pairingSessionId));
        if (PairingQr::savePng(code, path)) {
            if (!m_pairingQrPath.isEmpty() && m_pairingQrPath != path)
                QFile::remove(m_pairingQrPath);
            m_pairingQrPath = path;
            emit pairingQrPathChanged();
        }
        emit devicePairingStarted(code);
        pollPairingPayload();
        reply->deleteLater();
    });
}

void AuthService::pollPairingPayload() {
    if (m_pairingSessionId.isEmpty() || m_pairingEphPriv.isEmpty() || m_accessToken.isEmpty()) return;
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/pairing/%1/payload").arg(m_pairingSessionId));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray resp = reply->readAll();
        if (status == 204) {
            QTimer::singleShot(1500, this, [this]() { pollPairingPayload(); });
            reply->deleteLater();
            return;
        }
        if (reply->error() != QNetworkReply::NoError || status >= 400) {
            emit devicePairingFailed(QStringLiteral("Pairing expired or failed"));
            m_pairingEphPriv.clear();
            m_pairingSessionId.clear();
            reply->deleteLater();
            return;
        }
        const QJsonObject obj = QJsonDocument::fromJson(resp).object();
        const QByteArray sealed = VaultCrypto::unb64(obj.value(QStringLiteral("payload_b64")).toString());
        const QByteArray senderPub = QByteArray::fromHex(
            obj.value(QStringLiteral("sender_pub_hex")).toString().toLatin1());
        const QByteArray mk = VaultCrypto::openPairingMk(m_pairingEphPriv, senderPub, sealed);
        if (mk.isEmpty()) {
            emit devicePairingFailed(QStringLiteral("Could not open pairing payload"));
            reply->deleteLater();
            return;
        }
        // Fetch vault and open with MK.
        QUrl vaultUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
        QNetworkRequest vaultReq(vaultUrl);
        vaultReq.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
        vaultReq.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
        QNetworkReply* vaultReply = m_networkManager->get(vaultReq);
        connect(vaultReply, &QNetworkReply::finished, this, [this, vaultReply, mk]() {
            const QByteArray body = vaultReply->readAll();
            if (vaultReply->error() != QNetworkReply::NoError) {
                emit devicePairingFailed(QStringLiteral("Vault fetch failed"));
                vaultReply->deleteLater();
                return;
            }
            const QJsonObject vaultObj = QJsonDocument::fromJson(body).object();
            const int vaultVersion = vaultObj.value(QStringLiteral("vault_version")).toInt();
            const QByteArray ct = VaultCrypto::unb64(vaultObj.value(QStringLiteral("vault_ciphertext_b64")).toString());
            VaultCrypto::UnlockedVault unlocked;
            if (!VaultCrypto::openVaultWithMk(m_currentUserId, vaultVersion, ct, mk, unlocked)) {
                emit devicePairingFailed(QStringLiteral("Vault open failed"));
                vaultReply->deleteLater();
                return;
            }
            applyUnlockedVault(unlocked);
            rememberSession(unlocked.mk, vaultVersion, vaultObj);
            m_pairingEphPriv.clear();
            m_pairingSessionId.clear();
            m_pendingVaultPassword.clear();
            ensureE2EEKeysAndPublish(false);
            registerE2EEDevice();
            finishLoginAfterVault();
            emit devicePairingSucceeded();
            vaultReply->deleteLater();
        });
        reply->deleteLater();
    });
}

void AuthService::approveDevicePairing(const QString& pairingString) {
    if (m_sessionMk.isEmpty()) {
        emit devicePairingFailed(QStringLiteral("Unlock this device's vault before linking"));
        return;
    }
    QString sessionId;
    QByteArray recipientPub;
    if (!VaultCrypto::parsePairingString(pairingString, sessionId, recipientPub)) {
        emit devicePairingFailed(QStringLiteral("Invalid pairing code"));
        return;
    }
    QByteArray senderPub, sealed;
    if (!VaultCrypto::sealPairingMk(recipientPub, m_sessionMk, senderPub, sealed)) {
        emit devicePairingFailed(QStringLiteral("Failed to seal master key"));
        return;
    }
    QJsonObject body;
    body[QStringLiteral("payload_b64")] = VaultCrypto::b64(sealed);
    body[QStringLiteral("sender_pub_hex")] = QString::fromLatin1(senderPub.toHex());

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/pairing/%1/complete").arg(sessionId));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        if (reply->error() != QNetworkReply::NoError) {
            const QString msg = extractErrorMessage(reply);
            emit devicePairingFailed(msg.isEmpty() ? QStringLiteral("Link failed") : msg);
        } else {
            emit devicePairingSucceeded();
        }
        reply->deleteLater();
    });
}

void AuthService::unlockWithRecoveryKey(const QString& recoveryKeyB64) {
    if (m_currentUserId.isEmpty() || m_accessToken.isEmpty()) {
        emit loginFailed(QStringLiteral("Not signed in"));
        return;
    }
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, recoveryKeyB64]() {
        const QByteArray body = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit loginFailed(QStringLiteral("Vault fetch failed"));
            reply->deleteLater();
            return;
        }
        const QJsonObject obj = QJsonDocument::fromJson(body).object();
        const int vaultVersion = obj.value(QStringLiteral("vault_version")).toInt();
        const QByteArray ct = VaultCrypto::unb64(obj.value(QStringLiteral("vault_ciphertext_b64")).toString());
        const QByteArray rkSalt = VaultCrypto::unb64(obj.value(QStringLiteral("rk_salt_b64")).toString());
        const QByteArray rkWrap = VaultCrypto::unb64(obj.value(QStringLiteral("rk_wrapped_master_b64")).toString());
        VaultCrypto::UnlockedVault unlocked;
        if (!VaultCrypto::unlockVaultWithRecovery(m_currentUserId, recoveryKeyB64, vaultVersion,
                                                  ct, rkSalt, rkWrap, unlocked)) {
            emit loginFailed(QStringLiteral("Invalid recovery key"));
            reply->deleteLater();
            return;
        }
        applyUnlockedVault(unlocked);
        rememberSession(unlocked.mk, vaultVersion, obj);

        // Rewrap under current account password when we still have it.
        if (!m_pendingVaultPassword.isEmpty()) {
            QByteArray salt, wrapped;
            QString paramsJson;
            if (VaultCrypto::wrapMasterWithPassword(m_currentUserId, m_pendingVaultPassword,
                                                    unlocked.mk, salt, wrapped, paramsJson)) {
                const int expected = vaultVersion;
                const int next = expected + 1;
                VaultCrypto::VaultPlaintext plain = unlocked.plaintext;
                plain.ownSenderKeys = exportVaultSenderKeys();
                plain.peerPubs = exportVaultPeerPubs();
                plain.peerSenderKeys = exportVaultPeerSenderKeys();
                plain.directRatchets = exportVaultDirectRatchets();
                const QByteArray sealed = VaultCrypto::resealVault(m_currentUserId, unlocked.mk, plain, next);
                if (!sealed.isEmpty()) {
                    QJsonObject putBody;
                    putBody[QStringLiteral("vault_version")] = next;
                    putBody[QStringLiteral("protocol_version")] = VaultCrypto::PROTOCOL_VERSION;
                    putBody[QStringLiteral("suite")] = QString::fromLatin1(VaultCrypto::SUITE_VAULT_AEAD);
                    putBody[QStringLiteral("vault_ciphertext_b64")] = VaultCrypto::b64(sealed);
                    putBody[QStringLiteral("pw_kdf")] = QString::fromLatin1(VaultCrypto::KDF_ARGON2ID);
                    putBody[QStringLiteral("pw_salt_b64")] = VaultCrypto::b64(salt);
                    putBody[QStringLiteral("pw_params")] = paramsJson;
                    putBody[QStringLiteral("pw_wrapped_master_b64")] = VaultCrypto::b64(wrapped);
                    putBody[QStringLiteral("rk_kdf")] = obj.value(QStringLiteral("rk_kdf")).toString();
                    putBody[QStringLiteral("rk_salt_b64")] = obj.value(QStringLiteral("rk_salt_b64")).toString();
                    putBody[QStringLiteral("rk_wrapped_master_b64")] = obj.value(QStringLiteral("rk_wrapped_master_b64")).toString();
                    putBody[QStringLiteral("expected_version")] = expected;

                    QUrl putUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
                    QNetworkRequest putReq(putUrl);
                    putReq.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
                    putReq.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
                    putReq.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
                    QNetworkReply* putReply = m_networkManager->put(
                        putReq, QJsonDocument(putBody).toJson(QJsonDocument::Compact));
                    connect(putReply, &QNetworkReply::finished, putReply, [this, putReply, unlocked, next, salt, wrapped, paramsJson]() {
                        if (putReply->error() == QNetworkReply::NoError) {
                            QJsonObject meta = m_sessionVaultMeta;
                            meta[QStringLiteral("pw_salt_b64")] = VaultCrypto::b64(salt);
                            meta[QStringLiteral("pw_params")] = paramsJson;
                            meta[QStringLiteral("pw_wrapped_master_b64")] = VaultCrypto::b64(wrapped);
                            rememberSession(unlocked.mk, next, meta);
                            qDebug() << "[E2EE] Rewrapped vault after recovery unlock";
                        }
                        putReply->deleteLater();
                    });
                }
            }
        }

        m_pendingVaultPassword.clear();
        ensureE2EEKeysAndPublish(false);
        registerE2EEDevice();
        finishLoginAfterVault();
        reply->deleteLater();
    });
}

void AuthService::refreshVaultContents() {
    if (m_sessionMk.isEmpty() || m_sessionVaultVersion < 1 || m_accessToken.isEmpty()) return;
    if (m_vaultRefreshTimer) m_vaultRefreshTimer->start();
}

void AuthService::pullAndMergeVault(bool force) {
    if (m_sessionMk.isEmpty() || m_sessionVaultVersion < 1 || m_accessToken.isEmpty()) return;
    if (m_vaultPullInFlight) return;
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (!force && m_lastVaultPullMs > 0 && now - m_lastVaultPullMs < 2000) return;
    m_vaultPullInFlight = true;

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, now]() {
        m_vaultPullInFlight = false;
        const QByteArray raw = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        reply->deleteLater();
        if (status != 200) return;
        const QJsonObject obj = QJsonDocument::fromJson(raw).object();
        const int ver = obj.value(QStringLiteral("vault_version")).toInt();
        m_lastVaultPullMs = now;
        if (ver == m_sessionVaultVersion) return;
        const QByteArray ct = VaultCrypto::unb64(obj.value(QStringLiteral("vault_ciphertext_b64")).toString());
        VaultCrypto::UnlockedVault unlocked;
        if (!VaultCrypto::openVaultWithMk(m_currentUserId, ver, ct, m_sessionMk, unlocked)) {
            qWarning() << "[E2EE] Vault pull: cannot open remote ciphertext";
            return;
        }
        VaultCrypto::VaultPlaintext local;
        local.identityPubHex = e2eePublicKey();
        local.identityPrivHex = e2eePrivateKey();
        local.ownSenderKeys = exportVaultSenderKeys();
        local.peerPubs = exportVaultPeerPubs();
        local.peerSenderKeys = exportVaultPeerSenderKeys();
        local.directRatchets = exportVaultDirectRatchets();
        restoreVaultMaterial(VaultCrypto::mergePlaintext(local, unlocked.plaintext));
        rememberSession(m_sessionMk, ver, obj);
        qDebug() << "[E2EE] Pulled and merged vault v" << ver;
    });
}

void AuthService::performVaultRefresh() {
    performVaultRefreshAttempt(0);
}

void AuthService::performVaultRefreshAttempt(int attempt) {
    if (m_sessionMk.isEmpty() || m_sessionVaultVersion < 1 || m_accessToken.isEmpty()) return;
    const QString pub = e2eePublicKey();
    const QString priv = e2eePrivateKey();
    if (pub.isEmpty() || priv.isEmpty()) return;

    const int expected = m_sessionVaultVersion;
    const int next = expected + 1;
    VaultCrypto::VaultPlaintext plain;
    plain.identityPubHex = pub;
    plain.identityPrivHex = priv;
    plain.ownSenderKeys = exportVaultSenderKeys();
    plain.peerPubs = exportVaultPeerPubs();
    plain.peerSenderKeys = exportVaultPeerSenderKeys();
    plain.directRatchets = exportVaultDirectRatchets();
    const QByteArray sealed = VaultCrypto::resealVault(m_currentUserId, m_sessionMk, plain, next);
    if (sealed.isEmpty()) return;

    QJsonObject body;
    body[QStringLiteral("vault_version")] = next;
    body[QStringLiteral("protocol_version")] = VaultCrypto::PROTOCOL_VERSION;
    body[QStringLiteral("suite")] = QString::fromLatin1(VaultCrypto::SUITE_VAULT_AEAD);
    body[QStringLiteral("vault_ciphertext_b64")] = VaultCrypto::b64(sealed);
    body[QStringLiteral("pw_kdf")] = m_sessionVaultMeta.value(QStringLiteral("pw_kdf")).toString(QString::fromLatin1(VaultCrypto::KDF_ARGON2ID));
    body[QStringLiteral("pw_salt_b64")] = m_sessionVaultMeta.value(QStringLiteral("pw_salt_b64")).toString();
    body[QStringLiteral("pw_params")] = m_sessionVaultMeta.value(QStringLiteral("pw_params")).toString();
    body[QStringLiteral("pw_wrapped_master_b64")] = m_sessionVaultMeta.value(QStringLiteral("pw_wrapped_master_b64")).toString();
    body[QStringLiteral("rk_kdf")] = m_sessionVaultMeta.value(QStringLiteral("rk_kdf")).toString();
    body[QStringLiteral("rk_salt_b64")] = m_sessionVaultMeta.value(QStringLiteral("rk_salt_b64")).toString();
    body[QStringLiteral("rk_wrapped_master_b64")] = m_sessionVaultMeta.value(QStringLiteral("rk_wrapped_master_b64")).toString();
    body[QStringLiteral("expected_version")] = expected;

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->put(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, next, attempt]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (status == 409 && attempt < 3) {
            reply->deleteLater();
            QUrl getUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
            QNetworkRequest getReq(getUrl);
            getReq.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
            getReq.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
            QNetworkReply* getReply = m_networkManager->get(getReq);
            connect(getReply, &QNetworkReply::finished, this, [this, getReply, attempt]() {
                const QByteArray raw = getReply->readAll();
                getReply->deleteLater();
                const QJsonObject obj = QJsonDocument::fromJson(raw).object();
                const int ver = obj.value(QStringLiteral("vault_version")).toInt();
                const QByteArray ct = VaultCrypto::unb64(obj.value(QStringLiteral("vault_ciphertext_b64")).toString());
                VaultCrypto::UnlockedVault unlocked;
                if (!VaultCrypto::openVaultWithMk(m_currentUserId, ver, ct, m_sessionMk, unlocked)) {
                    qWarning() << "[E2EE] Stale vault merge: cannot open remote ciphertext";
                    return;
                }
                VaultCrypto::VaultPlaintext local;
                local.identityPubHex = e2eePublicKey();
                local.identityPrivHex = e2eePrivateKey();
                local.ownSenderKeys = exportVaultSenderKeys();
                local.peerPubs = exportVaultPeerPubs();
                local.peerSenderKeys = exportVaultPeerSenderKeys();
                local.directRatchets = exportVaultDirectRatchets();
                const VaultCrypto::VaultPlaintext merged = VaultCrypto::mergePlaintext(local, unlocked.plaintext);
                restoreVaultMaterial(merged);
                rememberSession(m_sessionMk, ver, obj);
                qDebug() << "[E2EE] Merged remote vault v" << ver << "after stale PUT";
                performVaultRefreshAttempt(attempt + 1);
            });
            return;
        }
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[E2EE] Vault refresh failed:" << reply->errorString();
        } else {
            m_sessionVaultVersion = next;
            qDebug() << "[E2EE] Vault refreshed to v" << next;
        }
        reply->deleteLater();
    });
}

void AuthService::fetchE2EEDevices() {
    if (m_accessToken.isEmpty()) return;
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/devices"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        QVariantList out;
        if (reply->error() == QNetworkReply::NoError) {
            const QJsonArray devices = QJsonDocument::fromJson(reply->readAll())
                                           .object().value(QStringLiteral("devices")).toArray();
            const QString currentId = getOrCreateDeviceId();
            for (const QJsonValue& v : devices) {
                const QJsonObject d = v.toObject();
                QVariantMap m;
                m[QStringLiteral("device_id")] = d.value(QStringLiteral("device_id")).toString();
                m[QStringLiteral("name")] = d.value(QStringLiteral("name")).toString();
                m[QStringLiteral("platform")] = d.value(QStringLiteral("platform")).toString();
                m[QStringLiteral("revoked")] = !d.value(QStringLiteral("revoked_at")).isNull()
                    && !d.value(QStringLiteral("revoked_at")).toString().isEmpty();
                m[QStringLiteral("is_current")] = d.value(QStringLiteral("device_id")).toString() == currentId;
                out.append(m);
            }
        } else {
            qWarning() << "[E2EE] list devices failed:" << reply->errorString();
        }
        emit e2eeDevicesLoaded(out);
        reply->deleteLater();
    });
}

void AuthService::revokeE2EEDevice(const QString& deviceId) {
    if (m_accessToken.isEmpty() || deviceId.isEmpty()) return;
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/devices/%1/revoke").arg(deviceId));
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->post(request, QByteArray("{}"));
    connect(reply, &QNetworkReply::finished, this, [this, reply, deviceId]() {
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[E2EE] revoke device failed:" << reply->errorString();
        } else {
            emit e2eeDeviceRevoked(deviceId);
        }
        reply->deleteLater();
    });
}

void AuthService::changePassword(const QString& currentPassword, const QString& newPassword) {
    if (m_accessToken.isEmpty() || m_currentUserId.isEmpty()) {
        emit passwordChangeFailed(QStringLiteral("Not signed in"));
        return;
    }
    if (newPassword.length() < 8) {
        emit passwordChangeFailed(QStringLiteral("New password must be at least 8 characters"));
        return;
    }
    if (currentPassword == newPassword) {
        emit passwordChangeFailed(QStringLiteral("New password must differ from current password"));
        return;
    }

    auto finishServerAndRewrap = [this](const QString& currentPassword, const QString& newPassword, QByteArray mk, QJsonObject vaultObj) {
        QJsonObject body;
        body[QStringLiteral("current_password")] = currentPassword;
        body[QStringLiteral("new_password")] = newPassword;

        QUrl url(Config::apiBaseUrl() + QStringLiteral("/users/me/password"));
        QNetworkRequest request(url);
        request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
        request.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
        QNetworkReply* reply = m_networkManager->post(
            request, QJsonDocument(body).toJson(QJsonDocument::Compact));
        connect(reply, &QNetworkReply::finished, this, [this, reply, newPassword, mk, vaultObj]() {
            const QByteArray resp = reply->readAll();
            if (reply->error() != QNetworkReply::NoError) {
                QString msg = QStringLiteral("Password change failed");
                const QJsonObject err = QJsonDocument::fromJson(resp).object();
                const QString serverErr = err.value(QStringLiteral("error")).toString();
                if (!serverErr.isEmpty()) msg = serverErr;
                emit passwordChangeFailed(msg);
                reply->deleteLater();
                return;
            }

            // Rewrap vault under the new password when we have MK + vault meta.
            if (!mk.isEmpty() && !vaultObj.isEmpty()) {
                QByteArray salt, wrapped;
                QString paramsJson;
                if (VaultCrypto::wrapMasterWithPassword(m_currentUserId, newPassword, mk, salt, wrapped, paramsJson)) {
                    const int expected = vaultObj.value(QStringLiteral("vault_version")).toInt(m_sessionVaultVersion);
                    const int next = expected + 1;
                    VaultCrypto::VaultPlaintext plain;
                    plain.identityPubHex = e2eePublicKey();
                    plain.identityPrivHex = e2eePrivateKey();
                    plain.ownSenderKeys = exportVaultSenderKeys();
                    plain.peerPubs = exportVaultPeerPubs();
                    plain.peerSenderKeys = exportVaultPeerSenderKeys();
                    plain.directRatchets = exportVaultDirectRatchets();
                    const QByteArray sealed = VaultCrypto::resealVault(m_currentUserId, mk, plain, next);
                    if (!sealed.isEmpty()) {
                        QJsonObject putBody;
                        putBody[QStringLiteral("vault_version")] = next;
                        putBody[QStringLiteral("protocol_version")] = VaultCrypto::PROTOCOL_VERSION;
                        putBody[QStringLiteral("suite")] = QString::fromLatin1(VaultCrypto::SUITE_VAULT_AEAD);
                        putBody[QStringLiteral("vault_ciphertext_b64")] = VaultCrypto::b64(sealed);
                        putBody[QStringLiteral("pw_kdf")] = QString::fromLatin1(VaultCrypto::KDF_ARGON2ID);
                        putBody[QStringLiteral("pw_salt_b64")] = VaultCrypto::b64(salt);
                        putBody[QStringLiteral("pw_params")] = paramsJson;
                        putBody[QStringLiteral("pw_wrapped_master_b64")] = VaultCrypto::b64(wrapped);
                        putBody[QStringLiteral("rk_kdf")] = vaultObj.value(QStringLiteral("rk_kdf")).toString();
                        putBody[QStringLiteral("rk_salt_b64")] = vaultObj.value(QStringLiteral("rk_salt_b64")).toString();
                        putBody[QStringLiteral("rk_wrapped_master_b64")] = vaultObj.value(QStringLiteral("rk_wrapped_master_b64")).toString();
                        putBody[QStringLiteral("expected_version")] = expected;

                        QUrl putUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
                        QNetworkRequest putReq(putUrl);
                        putReq.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
                        putReq.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
                    putReq.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
                        QNetworkReply* putReply = m_networkManager->put(
                            putReq, QJsonDocument(putBody).toJson(QJsonDocument::Compact));
                        connect(putReply, &QNetworkReply::finished, this, [this, putReply, mk, next, salt, wrapped, paramsJson]() {
                            if (putReply->error() == QNetworkReply::NoError) {
                                QJsonObject meta = m_sessionVaultMeta;
                                meta[QStringLiteral("pw_salt_b64")] = VaultCrypto::b64(salt);
                                meta[QStringLiteral("pw_params")] = paramsJson;
                                meta[QStringLiteral("pw_wrapped_master_b64")] = VaultCrypto::b64(wrapped);
                                rememberSession(mk, next, meta);
                                qDebug() << "[E2EE] Rewrapped vault under new password";
                            } else {
                                qWarning() << "[E2EE] Vault rewrap after password change failed:"
                                           << putReply->errorString();
                            }
                            emit passwordChangeSucceeded();
                            putReply->deleteLater();
                        });
                        reply->deleteLater();
                        return;
                    }
                }
                qWarning() << "[E2EE] Login password updated but vault rewrap failed";
            }
            emit passwordChangeSucceeded();
            reply->deleteLater();
        });
    };

    // Prefer in-memory MK; otherwise unlock with the current password first.
    if (!m_sessionMk.isEmpty()) {
        QJsonObject vaultObj = m_sessionVaultMeta;
        if (vaultObj.isEmpty() || !vaultObj.contains(QStringLiteral("vault_version"))) {
            vaultObj[QStringLiteral("vault_version")] = m_sessionVaultVersion;
            vaultObj[QStringLiteral("rk_kdf")] = m_sessionVaultMeta.value(QStringLiteral("rk_kdf"));
            vaultObj[QStringLiteral("rk_salt_b64")] = m_sessionVaultMeta.value(QStringLiteral("rk_salt_b64"));
            vaultObj[QStringLiteral("rk_wrapped_master_b64")] = m_sessionVaultMeta.value(QStringLiteral("rk_wrapped_master_b64"));
        }
        finishServerAndRewrap(currentPassword, newPassword, m_sessionMk, vaultObj);
        return;
    }

    QUrl vaultUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/vault"));
    QNetworkRequest vaultReq(vaultUrl);
    vaultReq.setRawHeader("Authorization", ("Bearer " + m_accessToken).toUtf8());
        vaultReq.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());
    QNetworkReply* vaultReply = m_networkManager->get(vaultReq);
    connect(vaultReply, &QNetworkReply::finished, this,
            [this, vaultReply, currentPassword, newPassword, finishServerAndRewrap]() {
        const QByteArray body = vaultReply->readAll();
        QByteArray mk;
        QJsonObject vaultObj;
        if (vaultReply->error() == QNetworkReply::NoError) {
            vaultObj = QJsonDocument::fromJson(body).object();
            const int vaultVersion = vaultObj.value(QStringLiteral("vault_version")).toInt();
            const QByteArray ct = VaultCrypto::unb64(vaultObj.value(QStringLiteral("vault_ciphertext_b64")).toString());
            const QByteArray salt = VaultCrypto::unb64(vaultObj.value(QStringLiteral("pw_salt_b64")).toString());
            const QByteArray wrapped = VaultCrypto::unb64(vaultObj.value(QStringLiteral("pw_wrapped_master_b64")).toString());
            const QString params = vaultObj.value(QStringLiteral("pw_params")).toString();
            VaultCrypto::UnlockedVault unlocked;
            if (!VaultCrypto::unlockVault(m_currentUserId, currentPassword, vaultVersion,
                                          ct, salt, params, wrapped, unlocked)) {
                emit passwordChangeFailed(QStringLiteral("Current password does not unlock the vault"));
                vaultReply->deleteLater();
                return;
            }
            mk = unlocked.mk;
            rememberSession(mk, vaultVersion, vaultObj);
        }
        // No vault (or fetch failed): still change the login password.
        finishServerAndRewrap(currentPassword, newPassword, mk, vaultObj);
        vaultReply->deleteLater();
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
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

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
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, &AuthService::onContactPublicKeyFinished);
    return QString{}; // Will be filled asynchronously
}

void AuthService::fetchOwnProfile() {
    QString token = authToken();
    if (token.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/users/me"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + token).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[AuthService] fetchOwnProfile failed:" << reply->errorString();
            return;
        }
        // The backend wraps this one: {"user": {...}} - unlike most endpoints,
        // which return {"data": {...}}.
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        QJsonObject user = root.value(QStringLiteral("user")).toObject();
        if (user.isEmpty()) return;

        m_currentUserEmail = user.value(QStringLiteral("email")).toString();
        m_currentUserAvatarUrl = user.value(QStringLiteral("avatar_url")).toString();
        if (m_currentUsername.isEmpty()) {
            m_currentUsername = user.value(QStringLiteral("username")).toString();
            emit currentUsernameChanged();
        }
        emit profileChanged();
    });
}

void AuthService::uploadAvatar(const QString& filePath) {
    QString token = authToken();
    if (token.isEmpty()) {
        emit avatarUploadFailed(QStringLiteral("Not authenticated. Please login first."));
        return;
    }

    QString localPath = QUrl(filePath).isLocalFile() ? QUrl(filePath).toLocalFile() : filePath;
    auto* file = new QFile(localPath);
    if (!file->open(QIODevice::ReadOnly)) {
        emit avatarUploadFailed(QStringLiteral("Could not open the selected image"));
        delete file;
        return;
    }

    auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart imagePart;
    QString mime = QMimeDatabase().mimeTypeForFile(localPath).name();
    imagePart.setHeader(QNetworkRequest::ContentTypeHeader, mime.isEmpty() ? "application/octet-stream" : mime);
    imagePart.setHeader(QNetworkRequest::ContentDispositionHeader,
                         QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"%1\"")
                                      .arg(QFileInfo(localPath).fileName())));
    imagePart.setBodyDevice(file);
    file->setParent(multiPart);
    multiPart->append(imagePart);

    QUrl url(Config::apiBaseUrl() + QStringLiteral("/users/me/avatar"));
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", ("Bearer " + token).toUtf8());
    request.setRawHeader("X-Device-Id", getOrCreateDeviceId().toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, multiPart);
    multiPart->setParent(reply);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        // extractErrorMessage() calls reply->readAll() itself, so the error
        // branch must not read the body first - a second readAll() on an
        // already-drained QNetworkReply returns empty, which would silently
        // fall back to Qt's generic error string instead of the server's.
        if (reply->error() != QNetworkReply::NoError) {
            emit avatarUploadFailed(extractErrorMessage(reply));
            return;
        }
        QByteArray data = reply->readAll();
        QString avatarUrl = QJsonDocument::fromJson(data).object().value(QStringLiteral("avatar_url")).toString();
        if (avatarUrl.isEmpty()) {
            emit avatarUploadFailed(QStringLiteral("Server did not return an avatar URL"));
            return;
        }
        m_currentUserAvatarUrl = avatarUrl;
        emit profileChanged();
        emit avatarUploaded(avatarUrl);
    });
}

void AuthService::onLoginReplyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();

        if (obj.value(QStringLiteral("requires_2fa")).toBool(false)) {
            const QString challengeId = obj.value(QStringLiteral("challenge_id")).toString();
            const QString hint = obj.value(QStringLiteral("relay_hint")).toString(
                QStringLiteral("Enter the 6-digit DEV 2FA code"));
            if (challengeId.isEmpty()) {
                emit loginFailed(QStringLiteral("2FA required but no challenge was issued"));
            } else {
                emit twoFactorRequired(challengeId, hint);
            }
            reply->deleteLater();
            return;
        }

        // Backend returns: {"tokens": {"access_token": "...", "refresh_token": "..."}, "user": {...}}
        QJsonValue tokensVal = obj.value(QStringLiteral("tokens"));
        QJsonObject tokensObj = tokensVal.isObject() ? tokensVal.toObject() : obj;
        m_accessToken = tokensObj.value(QStringLiteral("access_token")).toString();
        m_refreshToken = tokensObj.value(QStringLiteral("refresh_token")).toString();

        QJsonValue userVal = obj.value(QStringLiteral("user"));
        QJsonObject userObj = userVal.isObject() ? userVal.toObject() : QJsonObject();
        m_currentUserId = userObj.value(QStringLiteral("id")).toString();
        m_currentUsername = userObj.value(QStringLiteral("username")).toString();

        if (!m_accessToken.isEmpty()) {
            CredentialManager::instance().saveToken(QStringLiteral("access_token"), m_accessToken);
            CredentialManager::instance().saveToken(QStringLiteral("refresh_token"), m_refreshToken);
            CredentialManager::instance().saveToken(QStringLiteral("current_user"), m_currentUserId);
            // Defer isLoggedIn until vault unlock/create finishes (or recovery).
            syncE2EEVaultAfterLogin();
        } else {
            emit loginFailed(QStringLiteral("Login succeeded but no token received"));
        }
    } else {
        m_pendingVaultPassword.clear();
        emit loginFailed(extractErrorMessage(reply));
    }
    reply->deleteLater();
}

void AuthService::finishLoginAfterVault() {
    if (m_accessToken.isEmpty() || m_currentUserId.isEmpty()) return;
    const bool wasLoggedIn = m_loggedIn;
    m_loggedIn = true;
    if (!wasLoggedIn) emit isLoggedInChanged();
    emit currentUserIdChanged();
    emit currentUsernameChanged();
    emit tokenReady(m_accessToken);
    emit loginSuccess(m_currentUserId, m_currentUsername);
}

void AuthService::verify2FA(const QString& challengeId, const QString& code) {
    QString url = Config::apiBaseUrl() + QStringLiteral("/auth/2fa/verify");
    QUrl urlObj(url);
    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());

    QJsonObject data;
    data[QStringLiteral("challenge_id")] = challengeId;
    data[QStringLiteral("code")] = code.trimmed();

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(data).toJson());
    connect(reply, &QNetworkReply::finished, this, &AuthService::onVerify2FAReplyFinished);
}

void AuthService::onVerify2FAReplyFinished() {
    // Same token handling as a successful password login.
    onLoginReplyFinished();
}

void AuthService::startPasswordReset(const QString& email) {
    QString url = Config::apiBaseUrl() + QStringLiteral("/auth/password-reset/start");
    QUrl urlObj(url);
    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    QJsonObject data;
    data[QStringLiteral("email")] = email.trimmed();
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(data).toJson());
    connect(reply, &QNetworkReply::finished, this, &AuthService::onPasswordResetStartFinished);
}

void AuthService::onPasswordResetStartFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;
    if (reply->error() == QNetworkReply::NoError) {
        const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        const QString challengeId = obj.value(QStringLiteral("challenge_id")).toString();
        QString hint = obj.value(QStringLiteral("relay_hint")).toString();
        if (hint.isEmpty()) {
            hint = obj.value(QStringLiteral("message")).toString();
        }
        emit passwordResetStarted(challengeId, hint, obj.value(QStringLiteral("totp_required")).toBool());
    } else {
        emit passwordResetFailed(extractErrorMessage(reply));
    }
    reply->deleteLater();
}

void AuthService::completePasswordReset(const QString& challengeId, const QString& code, const QString& newPassword, const QString& totpCode) {
    QString url = Config::apiBaseUrl() + QStringLiteral("/auth/password-reset/complete");
    QUrl urlObj(url);
    QNetworkRequest request(urlObj);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json").toUtf8());
    QJsonObject data;
    data[QStringLiteral("challenge_id")] = challengeId;
    data[QStringLiteral("code")] = code.trimmed();
    data[QStringLiteral("new_password")] = newPassword;
    if (!totpCode.trimmed().isEmpty())
        data[QStringLiteral("totp_code")] = totpCode.trimmed();
    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(data).toJson());
    connect(reply, &QNetworkReply::finished, this, &AuthService::onPasswordResetCompleteFinished);
}

void AuthService::onPasswordResetCompleteFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;
    if (reply->error() == QNetworkReply::NoError) {
        const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        emit passwordResetCompleted(obj.value(QStringLiteral("message")).toString(
            QStringLiteral("Password updated. Sign in, then unlock with your recovery key.")));
    } else {
        emit passwordResetFailed(extractErrorMessage(reply));
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
        int httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (httpStatus == 401 || httpStatus == 403) {
            // The server actually looked at the refresh token and rejected
            // it (expired/revoked) - there's no way to recover without a
            // fresh login.
            emit loginFailed(QStringLiteral("Session expired. Please log in again."));
            logout();
        } else {
            // Never reached the server at all (offline, DNS, timeout) - the
            // refresh token is probably still fine, so don't wipe a good
            // session over a network blip. Whoever asked for the refresh
            // (WebSocketService's reconnect loop) will just try again later.
            qWarning() << "[AuthService] Token refresh failed (network):" << reply->errorString();
        }
    }
    reply->deleteLater();
}

void AuthService::onPublicKeyReplyFinished() {
    QNetworkReply* reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) return;

    if (reply->error() == QNetworkReply::NoError) {
        QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        m_ownPublicKey = obj.value(QStringLiteral("public_key")).toString();
        CredentialManager::instance().saveUser(m_currentUserId, m_currentUsername, m_ownPublicKey);
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