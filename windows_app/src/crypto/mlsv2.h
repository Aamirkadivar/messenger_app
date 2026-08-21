#pragma once

#include <QObject>
#include <QByteArray>
#include <QString>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QSet>
#include <functional>

// OpenMLS group messaging (encryption_version 6). Mirrors Android MlsV2Repository.
class MlsV2Engine : public QObject {
    Q_OBJECT
public:
    static constexpr int kEncryptionVersion = 6;

    explicit MlsV2Engine(QObject* parent = nullptr);
    ~MlsV2Engine() override;

    void bind(QNetworkAccessManager* nam,
              std::function<void(QNetworkRequest&)> applyAuth,
              std::function<QString()> userId,
              std::function<QString()> deviceId);

    bool isReady() const;

    bool hasGroup(const QString& chatId);
    bool ensureGroup(const QString& chatId);
    QByteArray encrypt(const QString& chatId, const QByteArray& plaintext);
    // Returns plaintext for an application message, or empty on failure.
    QByteArray decrypt(const QString& chatId, const QByteArray& ciphertext);
    void processWelcomes();
    void syncHandshakes(const QString& chatId);
    void ensureKeyPackages();

private:
    QByteArray gidOf(const QString& chatId) const { return chatId.toUtf8(); }
    bool ensureClient();
    void persist();
    void forget(const QString& chatId);
    bool loadGroup(const QString& chatId);
    void inviteMissingDevices(const QString& chatId);
    qint64 epochOf(const QString& chatId);

    QNetworkAccessManager* m_nam = nullptr;
    std::function<void(QNetworkRequest&)> m_applyAuth;
    std::function<QString()> m_userId;
    std::function<QString()> m_deviceId;

    void* m_handle = nullptr;
    /// Opaque tag for the store incarnation m_handle is backed by, or empty
    /// when this run has not published under it yet. Only scopes the "do I need
    /// more KeyPackages?" count; the tag actually published is re-derived from
    /// the packages being sent, so a stale value here can only cause a
    /// redundant republish, never a package filed under the wrong store.
    QString m_storeId;
    QSet<QString> m_liveGroups;
};
