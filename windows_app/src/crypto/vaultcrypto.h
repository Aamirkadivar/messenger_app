#ifndef VAULTCRYPTO_H
#define VAULTCRYPTO_H

#include <QByteArray>
#include <QString>
#include <QMap>

class VaultCrypto {
public:
    static constexpr const char* SUITE_VAULT_AEAD = "suite:aead-xchacha20poly1305-v1";
    static constexpr const char* SUITE_NACL_BOX = "suite:nacl-box-xsalsa20poly1305-v1";
    static constexpr const char* KDF_ARGON2ID = "kdf:argon2id-v1";
    static constexpr const char* KDF_HKDF_SHA256 = "kdf:hkdf-sha256-v1";
    static constexpr int PROTOCOL_VERSION = 1;
    static constexpr int VAULT_FORMAT = 1;

    struct ArgonParams {
        quint32 memoryKiB = 64 * 1024;
        quint32 time = 3;
        quint8 threads = 1;
        quint32 keyLen = 32;
        QString toParamsJson() const;
        static ArgonParams fromParamsJson(const QString& s);
    };

    struct VaultPlaintext {
        QString identityPubHex;
        QString identityPrivHex;
        QMap<QString, QString> ownSenderKeys;
        QMap<QString, QString> peerPubs;
        QMap<QString, QString> peerSenderKeys;
        QMap<QString, QString> directRatchets;
    };

    struct BuiltVault {
        int vaultVersion = 1;
        QByteArray vaultCiphertext;
        QByteArray pwSalt;
        QString pwParamsJson;
        QByteArray pwWrappedMaster;
        QByteArray rkSalt;
        QByteArray rkWrappedMaster;
        QString recoveryKeyDisplay;
        QByteArray mk;
    };

    struct UnlockedVault {
        VaultPlaintext plaintext;
        QByteArray mk;
    };

    static QByteArray randomBytes(int n);
    static QByteArray derivePasswordKek(const QString& password, const QByteArray& salt, const ArgonParams& params);
    static QByteArray deriveRecoveryKek(const QByteArray& recoveryKey, const QByteArray& salt);
    static QByteArray masterKeyAad(const QString& userId, const QString& purpose);
    static QByteArray vaultAad(const QString& userId, int vaultVersion);
    static QByteArray sealXChaCha(const QByteArray& key, const QByteArray& plaintext, const QByteArray& aad);
    static QByteArray openXChaCha(const QByteArray& key, const QByteArray& sealed, const QByteArray& aad);
    static QByteArray encodeVaultPlaintext(const QString& pubHex,
                                           const QString& privHex,
                                           const QMap<QString, QString>& ownSenderKeys = {},
                                           const QMap<QString, QString>& peerPubs = {},
                                           const QMap<QString, QString>& peerSenderKeys = {},
                                           const QMap<QString, QString>& directRatchets = {});
    static bool decodeVaultPlaintext(const QByteArray& json, VaultPlaintext& out);
    static bool createVault(const QString& userId,
                            const QString& password,
                            const QString& pubHex,
                            const QString& privHex,
                            BuiltVault& out,
                            int vaultVersion = 1,
                            const QMap<QString, QString>& ownSenderKeys = {},
                            const QMap<QString, QString>& peerPubs = {},
                            const QMap<QString, QString>& peerSenderKeys = {},
                            const QMap<QString, QString>& directRatchets = {});
    static bool createVault(const QString& userId,
                            const QString& password,
                            const QString& pubHex,
                            const QString& privHex,
                            BuiltVault& out,
                            int vaultVersion,
                            const ArgonParams& params,
                            const QMap<QString, QString>& ownSenderKeys,
                            const QMap<QString, QString>& peerPubs,
                            const QMap<QString, QString>& peerSenderKeys = {},
                            const QMap<QString, QString>& directRatchets = {});
    static bool unlockVault(const QString& userId,
                            const QString& password,
                            int vaultVersion,
                            const QByteArray& vaultCiphertext,
                            const QByteArray& pwSalt,
                            const QString& pwParamsJson,
                            const QByteArray& pwWrappedMaster,
                            UnlockedVault& out);
    static bool unlockVaultWithRecovery(const QString& userId,
                                        const QString& recoveryKeyB64,
                                        int vaultVersion,
                                        const QByteArray& vaultCiphertext,
                                        const QByteArray& rkSalt,
                                        const QByteArray& rkWrappedMaster,
                                        UnlockedVault& out);
    static bool openVaultWithMk(const QString& userId,
                                int vaultVersion,
                                const QByteArray& vaultCiphertext,
                                const QByteArray& mk,
                                UnlockedVault& out);
    static bool generateBoxKeyPair(QByteArray& privOut, QByteArray& pubOut);
    static bool sealPairingMk(const QByteArray& recipientPub,
                              const QByteArray& mk,
                              QByteArray& senderPubOut,
                              QByteArray& sealedOut);
    static QByteArray openPairingMk(const QByteArray& ourPriv,
                                    const QByteArray& senderPub,
                                    const QByteArray& sealed);
    static QString formatPairingString(const QString& sessionId, const QByteArray& ephemeralPub);
    static bool parsePairingString(const QString& raw, QString& sessionIdOut, QByteArray& ephemeralPubOut);
    static QByteArray resealVault(const QString& userId,
                                  const QByteArray& mk,
                                  const VaultPlaintext& plaintext,
                                  int vaultVersion);
    static VaultPlaintext mergePlaintext(const VaultPlaintext& local, const VaultPlaintext& remote);
    static bool wrapMasterWithPassword(const QString& userId,
                                       const QString& password,
                                       const QByteArray& mk,
                                       QByteArray& saltOut,
                                       QByteArray& wrappedOut,
                                       QString& paramsJsonOut);
    static QString b64(const QByteArray& data);
    static QByteArray unb64(const QString& s);
};

#endif