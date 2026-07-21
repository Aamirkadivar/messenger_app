#pragma once

#include <QObject>
#include <QString>
#include <QDateTime>

class User : public QObject {
    Q_OBJECT
public:
    User(const User& other);
    User& operator=(const User& other);
    Q_PROPERTY(QString id READ id WRITE setId NOTIFY idChanged)
    Q_PROPERTY(QString username READ username WRITE setUsername NOTIFY usernameChanged)
    Q_PROPERTY(QString displayName READ displayName WRITE setDisplayName NOTIFY displayNameChanged)
    Q_PROPERTY(QString avatar READ avatar WRITE setAvatar NOTIFY avatarChanged)
    Q_PROPERTY(bool online READ online WRITE setOnline NOTIFY onlineChanged)
    Q_PROPERTY(QDateTime lastSeen READ lastSeen WRITE setLastSeen NOTIFY lastSeenChanged)
    Q_PROPERTY(QString publicKey READ publicKey WRITE setPublicKey NOTIFY publicKeyChanged)
    Q_PROPERTY(QString email READ email WRITE setEmail NOTIFY emailChanged)
    Q_PROPERTY(QString status READ status WRITE setStatus NOTIFY statusChanged)

    explicit User(QObject* parent = nullptr) : QObject(parent) {}

    const QString& id() const { return m_id; }
    void setId(const QString& id) { m_id = id; emit idChanged(); }

    const QString& username() const { return m_username; }
    void setUsername(const QString& username) { m_username = username; emit usernameChanged(); }

    const QString& displayName() const { return m_displayName; }
    void setDisplayName(const QString& displayName) { m_displayName = displayName; emit displayNameChanged(); }

    const QString& avatar() const { return m_avatar; }
    void setAvatar(const QString& avatar) { m_avatar = avatar; emit avatarChanged(); }

    bool online() const { return m_online; }
    void setOnline(bool online) { m_online = online; emit onlineChanged(); }

    const QDateTime& lastSeen() const { return m_lastSeen; }
    void setLastSeen(const QDateTime& lastSeen) { m_lastSeen = lastSeen; emit lastSeenChanged(); }

    const QString& publicKey() const { return m_publicKey; }
    void setPublicKey(const QString& publicKey) { m_publicKey = publicKey; emit publicKeyChanged(); }

    const QString& email() const { return m_email; }
    void setEmail(const QString& email) { m_email = email; emit emailChanged(); }

    const QString& status() const { return m_status; }
    void setStatus(const QString& status) { m_status = status; emit statusChanged(); }

    Q_INVOKABLE QString initialChars() const {
        if (m_displayName.length() >= 2)
            return m_displayName.left(2);
        return m_username.length() >= 2 ? m_username.left(2) : m_username.left(1);
    }

signals:
    void idChanged();
    void usernameChanged();
    void displayNameChanged();
    void avatarChanged();
    void onlineChanged();
    void lastSeenChanged();
    void publicKeyChanged();
    void emailChanged();
    void statusChanged();

private:
    QString m_id;
    QString m_username;
    QString m_displayName;
    QString m_avatar;
    bool m_online = false;
    QDateTime m_lastSeen;
    QString m_publicKey;
    QString m_email;
    QString m_status;
};
