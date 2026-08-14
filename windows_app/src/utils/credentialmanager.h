#pragma once

#include <QObject>
#include <QString>
#include <QMap>
#include <QMutex>
#include <QByteArray>
#include <QStandardPaths>
#include <QDir>

class CredentialManager : public QObject {
    Q_OBJECT
public:
    static CredentialManager& instance();

    bool saveToken(const QString& key, const QString& value);
    QString getToken(const QString& key) const;
    QMap<QString, QString> tokensWithPrefix(const QString& prefix) const;
    void removeToken(const QString& key);
    bool deleteToken(const QString& key);
    QString getPersistentData(const QString& key) const;
    void savePersistentData(const QString& key, const QString& value);
    void clearAllTokens();
    bool saveUser(const QString& userId, const QString& username, const QString& pubKey);
    void loadLocalStore();
    void saveLocalStore();
    void persistToOS(const QString& key, const QString& value, bool isSecret = true);
    QString retrieveFromOS(const QString& key) const;
    void removeFromOS(const QString& key) const;
    QByteArray dpapiEncrypt(const QByteArray& data);
    QByteArray dpapiDecrypt(const QByteArray& data) const;

    QString dataPath() const { return m_dataPath; }

signals:
    void tokenChanged();

private:
    Q_DISABLE_COPY(CredentialManager)

    CredentialManager();
    ~CredentialManager();

    mutable QMutex m_mutex;
    QString m_dataPath;
    QMap<QString, QString> m_tokens;
    bool m_localStoreLoaded = false;
};