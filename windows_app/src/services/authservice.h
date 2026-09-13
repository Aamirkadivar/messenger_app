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
#include "../crypto/historykeyringtransport.h"
#include "../crypto/historykeyringstore.h"
#include "../crypto/deviceenrollment.h"
#include "../crypto/historykeyringrecovery.h"
#include "../crypto/historyarchiver.h"
#include "../crypto/archiverepository.h"
#include <memory>

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

    /**
     * The Layer B history-keyring transport, bound to this session's authenticated HTTP path.
     *
     * Uses this service's own QNetworkAccessManager, access token and device id - the same three
     * things the /e2ee/vault calls below already use - so there is no second HTTP client, token
     * store, or device identity anywhere in the keyring path.
     *
     * Constructing it performs no I/O and touches no keyring state: it neither loads nor mints a
     * history root. Callers drive it explicitly. The returned object borrows this service, so it
     * must not outlive it.
     */
    HistoryKeyringTransport historyKeyringTransport();

    /**
     * The Phase 44 enrolment client, bound to this session's token and device id.
     *
     * It reads the identity keypair through the same accessors the rest of the class uses and
     * returns no key material of its own.
     */
    DeviceEnrollment deviceEnrollment();

    /** Server-side keyring recovery for this account, merged into the local keyring. */
    HistoryKeyringRecovery historyKeyringRecovery();

    /**
     * Recovers the history keyring from the server, once per session.
     *
     * This is what lets a fresh install open archives it never had the roots for. Called after
     * unlock, because it needs MK to open the blob and a verified device to fetch it. Failing is
     * never fatal: it costs recovery coverage, never a message.
     */
    void recoverHistoryKeyring();

    /**
     * Seals / opens the history keyring under the session master key.
     *
     * This pair is the ONLY way the keyring reaches MK, and it is deliberately the whole interface:
     * callers hand over plaintext and receive ciphertext, or the reverse. There is no accessor
     * returning m_sessionMk and there must never be one - the master key stays private to this
     * service exactly as it does on Android.
     *
     * Both fail closed. A locked vault (no session master key) or a missing account identity is a
     * failure, never an unsealed result. The AAD binds the account, so one account's keyring cannot
     * be opened as another's, and the domain is distinct from the vault body's.
     */
    bool sealHistoryKeyring(const QByteArray& plaintext, QByteArray& sealedOut) const;
    bool openHistoryKeyring(const QByteArray& sealed, QByteArray& plainOut) const;

    /**
     * The same pair for the SERVER-STORED recovery blob, under its own AAD domain.
     *
     * Separate from the local pair on purpose: both protect the keyring under MK, but one never
     * leaves the machine and the other is uploaded, so a shared domain would let a recovery blob be
     * opened as a local keyring. Same rule as above - MK does not cross this boundary, and there is
     * still no accessor returning it.
     */
    bool sealHistoryKeyringForRecovery(const QByteArray& plaintext, QByteArray& sealedOut) const;
    bool openHistoryKeyringFromRecovery(const QByteArray& sealed, QByteArray& plainOut) const;

    /** True when a session master key is held, i.e. the vault is unlocked. Reveals no key material. */
    bool isVaultUnlocked() const { return !m_sessionMk.isEmpty(); }

    /**
     * The Layer B history keyring repository for this session.
     *
     * Built on first use and never during startup, so merely opening the application performs no
     * network I/O and creates no history root. Root creation stays an explicit ensureRoot() call.
     *
     * Wired to the things that already exist: DPAPI-protected local storage, the seal/open pair
     * above, and this service's own identity. Returns null before an account is known.
     */
    HistoryKeyringRepository* historyKeyring();

    /**
     * The application-facing keyring operations.
     *
     * These exist because HistoryKeyringRepository takes the account id as a PARAMETER, while the
     * cold-start cache it reads is addressed by that id and is deliberately readable without the
     * vault. A caller that passed the wrong id would therefore be served another account's roots.
     * Android removes that possibility structurally - its repository reads currentUserId() itself -
     * and these two methods are the Windows equivalent: they always pass m_currentUserId and give
     * no way to name a different account.
     *
     * Prefer these over historyKeyring() everywhere in the application. Both fail when no account
     * is signed in, and ensureHistoryRoot additionally fails - minting nothing - when the vault is
     * locked and the chat has no root yet.
     */
    bool ensureHistoryRoot(const QString& chatId, HistoryRootEntry& out, QString* error = nullptr);
    bool loadHistoryKeyring(HistoryKeyring& out, QString* error = nullptr);

    /**
     * An exact (chatId, rootVersion) lookup for the signed-in account.
     *
     * Needed to reopen an archive sealed under a root that has since been rotated away from. Fails
     * when this device does not hold that version rather than falling back to a different one.
     */
    bool findHistoryRoot(const QString& chatId, int rootVersion,
                         HistoryRootEntry& out, QString* error = nullptr);

    /**
     * The Layer B archiver for this session: keyring plus sealing, wired together.
     *
     * Purely local orchestration. It performs no I/O, stores nothing, and archives nothing on its
     * own - a caller must hand it one explicit message. It is bound to this service's own account
     * identity, so a caller cannot name a different account to reach its roots.
     *
     * The returned object borrows this service and must not outlive it.
     */
    HistoryArchiver historyArchiver();

    /**
     * The durable boundary for sealed archive records: the server's /e2ee/archives store.
     *
     * Bound to this session's authenticated HTTP path - the same QNetworkAccessManager, token and
     * device id every other request uses, so there is no second client, token store, or device
     * identity. It carries only the record's public metadata and its ciphertext; no master key,
     * history root, derived key, or plaintext ever reaches it.
     *
     * Constructing it performs no I/O. The returned object borrows this service.
     */
    ArchiveRepository archiveRepository();

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

    // ---- K_device: this machine's own device-identity keypair ----
    //
    // Separate from the account keypair above ON PURPOSE. The account key is
    // written into the vault and handed to every device that unlocks it, so
    // proving possession of it proves possession of the ACCOUNT, not of this
    // device - which is why revoking a device never took that key away.
    //
    // K_device is generated here, stored in its own DPAPI slot, never serialized
    // into VaultPlaintext and never overwritten by applyUnlockedVault(). It is
    // the only key device enrolment presents.
    QString e2eeDevicePublicKey() const;
    QString e2eeDevicePrivateKey() const;

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
    /** This device completed proof of possession and the session is bound to it. */
    void deviceEnrolled();

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
    // Generate K_device the first time this user signs in on this machine. Needs
    // no vault, which is precisely what lets enrolment run before the vault
    // exists.
    void ensureDeviceKey();
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
    // One history-keyring recovery attempt per session; cleared on a transient failure so a later
    // attempt can still run, and reset on sign-out with the rest of the session.
    bool m_historyRecoveryAttempted = false;
    // Layer B keyring, built lazily by historyKeyring(). Declared in dependency order so the
    // repository is destroyed before the store it borrows, and the store before its protector.
    std::unique_ptr<DpapiProtector> m_keyringProtector;
    std::unique_ptr<RegistryHistoryKeyringStore> m_keyringStore;
    std::unique_ptr<HistoryKeyringRepository> m_historyKeyring;
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

private:
    // Drops the persisted pending-refresh record (old credential + request_id).
    void clearPendingRefresh();
    // True while a rotation is on the wire; keeps refresh single-flight so two
    // callers cannot rotate one credential with two request_ids.
    bool m_refreshInFlight = false;
};