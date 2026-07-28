#pragma once

#include <QObject>
#include <QString>
#include <QNetworkReply>
#include <QJsonObject>
#include "../utils/config.h"
#include "../utils/credentialmanager.h"
#include "../crypto/encryption.h"

class AuthService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isLoggedIn READ isLoggedIn NOTIFY isLoggedInChanged)
    Q_PROPERTY(QString currentUserId READ currentUserId NOTIFY currentUserIdChanged)
    Q_PROPERTY(QString currentUsername READ currentUsername NOTIFY currentUsernameChanged)
    Q_PROPERTY(QString currentUserEmail READ currentUserEmail NOTIFY profileChanged)
    Q_PROPERTY(QString currentUserAvatarUrl READ currentUserAvatarUrl NOTIFY profileChanged)

public:
    explicit AuthService(QObject* parent = nullptr);

    bool isLoggedIn() const { return m_loggedIn; }
    const QString& currentUserId() const { return m_currentUserId; }
    const QString& currentUsername() const { return m_currentUsername; }
    const QString& currentUserEmail() const { return m_currentUserEmail; }
    const QString& currentUserAvatarUrl() const { return m_currentUserAvatarUrl; }

    // GET /users/me - fills in email/avatar (not returned by login/restoreSession).
    Q_INVOKABLE void fetchOwnProfile();
    // POST /users/me/avatar (multipart). filePath is a file:// URL or plain path.
    Q_INVOKABLE void uploadAvatar(const QString& filePath);

    Q_INVOKABLE void login(const QString& username, const QString& password);
    Q_INVOKABLE void registerUser(const QString& username, const QString& email, const QString& password);
    Q_INVOKABLE void logout();
    Q_INVOKABLE void refreshToken();

    QNetworkAccessManager* networkManager() { return m_networkManager; }
    QString authToken() const;

    // Get user's public key from backend
    Q_INVOKABLE void fetchOwnPublicKey();
    Q_INVOKABLE QString getContactPublicKey(const QString& contactId);

    // Restores a previously saved login (call once, after connecting to this
    // service's signals, so the initial loginSuccess/tokenReady aren't missed).
    void restoreSession();

    // ---- E2EE key management (private key never leaves this device) ----
    // The signed-in user's own X25519 private/public key (hex), generated
    // locally on first login. Empty if not logged in / not yet generated.
    QString e2eePrivateKey() const;
    QString e2eePublicKey() const;

signals:
    void isLoggedInChanged();
    void currentUserIdChanged();
    void currentUsernameChanged();
    void loginSuccess(const QString& userId, const QString& username);
    void loginFailed(const QString& error);
    void registerSuccess(const QString& userId);
    void registerFailed(const QString& error);
    void logoutSuccess();
    void tokenReady(const QString& token);
    void profileChanged();
    void avatarUploaded(const QString& avatarUrl);
    void avatarUploadFailed(const QString& message);

private slots:
    void onLoginReplyFinished();
    void onRegisterReplyFinished();
    void onRefreshReplyFinished();
    void onPublicKeyReplyFinished();
    void onContactPublicKeyFinished();

private:
    void setupNetworkManager();
    QNetworkReply* postRequest(const QString& url, const QJsonObject& data);
    // Ensure a local E2EE keypair exists for the current user (generating one
    // the first time) and publish the public key to the server.
    void ensureE2EEKeysAndPublish();
    bool m_loggedIn = false;
    QString m_currentUserId;
    QString m_currentUsername;
    QString m_currentUserEmail;
    QString m_currentUserAvatarUrl;
    QNetworkAccessManager* m_networkManager = nullptr;
    QNetworkReply* m_currentReply = nullptr;
    QString m_accessToken;
    QString m_refreshToken;
    QString m_ownPublicKey;
};