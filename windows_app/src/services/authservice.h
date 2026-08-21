#pragma once

#include <QObject>
#include <QString>
#include <QStringList>
#include <QNetworkReply>
#include <QVariantList>
#include <QJsonObject>
#include <QTimer>
#include "../utils/config.h"
#include "../utils/credentialmanager.h"
#include "../crypto/encryption.h"
#include "../crypto/vaultcrypto.h"

class AuthService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isLoggedIn READ isLoggedIn NOTIFY isLoggedInChanged)
    Q_PROPERTY(QString currentUserId READ currentUserId NOTIFY currentUserIdChanged)
    Q_PROPERTY(QString currentUsername READ currentUsername NOTIFY currentUsernameChanged)
    Q_PROPERTY(QString currentUserEmail READ currentUserEmail NOTIFY profileChanged)
    Q_PROPERTY(QString currentUserAvatarUrl READ currentUserAvatarUrl NOTIFY profileChanged)
    Q_PROPERTY(QString pairingQrPath READ pairingQrPath NOTIFY pairingQrPathChanged)
    // WhatsApp-style scan-to-sign-in, shown on the login screen.
    Q_PROPERTY(QString qrLoginPath READ qrLoginPath NOTIFY qrLoginChanged)
    Q_PROPERTY(QString qrLoginStatus READ qrLoginStatus NOTIFY qrLoginChanged)
    Q_PROPERTY(bool totpEnabled READ totpEnabled NOTIFY totpStatusChanged)

public:
    explicit AuthService(QObject* parent = nullptr);

    bool isLoggedIn() const { return m_loggedIn; }
    const QString& currentUserId() const { return m_currentUserId; }
    const QString& currentUsername() const { return m_currentUsername; }
    const QString& currentUserEmail() const { return m_currentUserEmail; }
    const QString& currentUserAvatarUrl() const { return m_currentUserAvatarUrl; }
    const QString& pairingQrPath() const { return m_pairingQrPath; }
    const QString& qrLoginPath() const { return m_qrLoginPath; }
    const QString& qrLoginStatus() const { return m_qrLoginStatus; }

    // Begins a scan-to-sign-in session: asks the server for a session, renders
    // the QR, and polls until an already-signed-in device approves it.
    Q_INVOKABLE void startQrLogin();
    Q_INVOKABLE void cancelQrLogin();
    bool totpEnabled() const { return m_totpEnabled; }

    // GET /users/me - fills in email/avatar (not returned by login/restoreSession).
    Q_INVOKABLE void fetchOwnProfile();
    // POST /users/me/avatar (multipart). filePath is a file:// URL or plain path.
    Q_INVOKABLE void uploadAvatar(const QString& filePath);

    Q_INVOKABLE void login(const QString& username, const QString& password);
    Q_INVOKABLE void verify2FA(const QString& challengeId, const QString& code);
    Q_INVOKABLE void startPasswordReset(const QString& email);
    Q_INVOKABLE void completePasswordReset(const QString& challengeId, const QString& code, const QString& newPassword, const QString& totpCode);
    Q_INVOKABLE void unlockWithRecoveryKey(const QString& recoveryKeyB64);
    // Unlocks the E2EE vault on a device that is already signed in but has no
    // identity keys - the state a QR sign-in leaves you in, since scanning
    // grants a session but deliberately not message access.
    Q_INVOKABLE void unlockVaultWithPassword(const QString& password);
    // True when this device is signed in but cannot read encrypted messages.
    Q_INVOKABLE bool needsVaultUnlock() const;
    Q_INVOKABLE void refreshVaultContents();
    void pullAndMergeVault(bool force = false);
    Q_INVOKABLE void fetchE2EEDevices();
    Q_INVOKABLE void revokeE2EEDevice(const QString& deviceId);
    Q_INVOKABLE void changePassword(const QString& currentPassword, const QString& newPassword);
    Q_INVOKABLE void fetchTotpStatus();
    Q_INVOKABLE void startTotpSetup();
    Q_INVOKABLE void confirmTotp(const QString& code);
    Q_INVOKABLE void disableTotp(const QString& password, const QString& code);
    Q_INVOKABLE void regenerateTotpBackupCodes(const QString& password, const QString& code);
    Q_INVOKABLE void startDevicePairing();
    Q_INVOKABLE void approveDevicePairing(const QString& pairingString);
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
    // Stable per-install device id (also sent as X-Device-Id / WS device_id).
    // Q_INVOKABLE so QML can tell "my own echo on this device"
    // apart from "my message from another device".
    Q_INVOKABLE QString getOrCreateDeviceId() const;
    QString e2eePublicKey() const;

signals:
    void isLoggedInChanged();
    void currentUserIdChanged();
    void currentUsernameChanged();
    void loginSuccess(const QString& userId, const QString& username);
    void loginFailed(const QString& error);
    // DEV 2FA: password accepted; enter code (relayed to @koueosh / server log).
    void twoFactorRequired(const QString& challengeId, const QString& hint);
    void passwordResetStarted(const QString& challengeId, const QString& hint, bool totpRequired);
    void passwordResetCompleted(const QString& message);
    void passwordResetFailed(const QString& error);
    void registerSuccess(const QString& userId);
    void registerFailed(const QString& error);
    void logoutSuccess();
    void tokenReady(const QString& token);
    void profileChanged();
    void avatarUploaded(const QString& avatarUrl);
    void avatarUploadFailed(const QString& message);
    // Shown once after first vault creation on this account.
    void recoveryKeyReady(const QString& recoveryKeyB64);
    // Password wrap failed — enter recovery key (tokens already valid).
    void vaultNeedsRecovery(const QString& message);
    // Emitted after a sign-in that produced no usable E2EE identity, so the UI
    // can prompt for the password or recovery key instead of leaving the user
    // with a working session that silently cannot send.
    void vaultUnlockRequired();
    void vaultUnlocked();
    void e2eeDevicesLoaded(const QVariantList& devices);
    void e2eeDeviceRevoked(const QString& deviceId);
    void passwordChangeSucceeded();
    void passwordChangeFailed(const QString& message);
    void devicePairingStarted(const QString& pairingString);
    void devicePairingSucceeded();
    void devicePairingFailed(const QString& message);
    void pairingQrPathChanged();
    void qrLoginChanged();
    void qrLoginFailed(const QString& message);
    void totpStatusChanged();
    void totpSetupReady(const QString& secret, const QString& otpauthUrl);
    void totpConfirmSucceeded(const QStringList& backupCodes);
    void totpFailed(const QString& message);
    void vaultRatchetsUpdated();

private slots:
    void onLoginReplyFinished();
    void onVerify2FAReplyFinished();
    void onPasswordResetStartFinished();
    void onPasswordResetCompleteFinished();
    void onRegisterReplyFinished();
    void onRefreshReplyFinished();
    void onPublicKeyReplyFinished();
    void onContactPublicKeyFinished();

private:
    void setupNetworkManager();
    QNetworkReply* postRequest(const QString& url, const QJsonObject& data);
    // Ensure a local E2EE keypair exists for the current user (generating one
    // the first time) and publish the public key to the server.
    void ensureE2EEKeysAndPublish(bool allowTakeover = true);
    // After password login: unlock remote vault or create one from local keys.
    void syncE2EEVaultAfterLogin();
    void onVaultGetFinished();
    void uploadNewVault();
    void finishLoginAfterVault();
    void applyUnlockedVault(const VaultCrypto::UnlockedVault& unlocked);
    void registerE2EEDevice();
    QMap<QString, QString> exportVaultSenderKeys() const;
    QMap<QString, QString> exportVaultPeerPubs() const;
    QMap<QString, QString> exportVaultPeerSenderKeys() const;
    QMap<QString, QString> exportVaultDirectRatchets() const;
    void restoreVaultMaterial(const VaultCrypto::VaultPlaintext& plain);
    void rememberSession(const QByteArray& mk, int version, const QJsonObject& vaultJson);
    void clearVaultSession();
    void performVaultRefresh();
    void performVaultRefreshAttempt(int attempt);
    qint64 m_lastVaultPullMs = 0;
    bool m_vaultPullInFlight = false;
    QTimer* m_vaultPullTimer = nullptr;
    void pollPairingPayload();

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
    // Cleared after vault sync (or logout). Needed for Argon2id unlock / rewrap.
    QString m_pendingVaultPassword;
    QByteArray m_sessionMk;
    int m_sessionVaultVersion = 0;
    QJsonObject m_sessionVaultMeta;
    QTimer* m_vaultRefreshTimer = nullptr;
    QByteArray m_pairingEphPriv;
    QString m_pairingSessionId;
    QString m_pairingQrPath;
    // QR sign-in state. m_qrLoginVerifier is this client's secret proof that it
    // started the session; it is never transmitted, only its SHA-256 is.
    QString m_qrLoginPath;
    QString m_qrLoginStatus;
    QString m_qrLoginSessionId;
    QString m_qrLoginVerifier;
    QTimer* m_qrLoginPoll = nullptr;

    void pollQrLogin();
    void claimQrLogin();
    bool m_totpEnabled = false;
};