#pragma once

#include <QObject>
#include <QString>
#include <QDateTime>
#include <QList>
#include <QVariantList>
#include "user.h"

class Chat : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString id READ id WRITE setId NOTIFY idChanged)
    Q_PROPERTY(QString name READ name WRITE setName NOTIFY nameChanged)
    Q_PROPERTY(QString avatar READ avatar WRITE setAvatar NOTIFY avatarChanged)
    Q_PROPERTY(QString lastMessage READ lastMessage WRITE setLastMessage NOTIFY lastMessageChanged)
    Q_PROPERTY(QDateTime lastMessageTime READ lastMessageTime WRITE setLastMessageTime NOTIFY lastMessageTimeChanged)
    Q_PROPERTY(int unreadCount READ unreadCount WRITE setUnreadCount NOTIFY unreadCountChanged)
    Q_PROPERTY(bool online READ online WRITE setOnline NOTIFY onlineChanged)
    Q_PROPERTY(bool typing READ typing WRITE setTyping NOTIFY typingChanged)
    Q_PROPERTY(bool group READ isGroup NOTIFY isGroupChanged)
    Q_PROPERTY(QVariantList participants READ participants WRITE setParticipants NOTIFY participantsChanged)
    Q_PROPERTY(QString participantIds READ participantIds WRITE setParticipantIds NOTIFY participantIdsChanged)
    Q_PROPERTY(QString ownUserId READ ownUserId WRITE setOwnUserId NOTIFY ownUserIdChanged)

public:
    Chat() = default;
    explicit Chat(QObject* parent) : QObject(parent) {}

    const QString& id() const { return m_id; }
    void setId(const QString& id) { m_id = id; emit idChanged(); }

    const QString& name() const { return m_name; }
    void setName(const QString& name) { m_name = name; emit nameChanged(); }

    const QString& avatar() const { return m_avatar; }
    void setAvatar(const QString& avatar) { m_avatar = avatar; emit avatarChanged(); }

    const QString& lastMessage() const { return m_lastMessage; }
    void setLastMessage(const QString& lastMessage) { m_lastMessage = lastMessage; emit lastMessageChanged(); }

    const QDateTime& lastMessageTime() const { return m_lastMessageTime; }
    void setLastMessageTime(const QDateTime& lastMessageTime) { m_lastMessageTime = lastMessageTime; emit lastMessageTimeChanged(); }

    int unreadCount() const { return m_unreadCount; }
    void setUnreadCount(int unreadCount) { m_unreadCount = unreadCount; emit unreadCountChanged(); }

    bool online() const { return m_online; }
    void setOnline(bool online) { m_online = online; emit onlineChanged(); }

    bool typing() const { return m_typing; }
    void setTyping(bool typing) { m_typing = typing; emit typingChanged(); }

    bool isGroup() const { return m_group; }
    void setGroup(bool group) { m_group = group; emit isGroupChanged(); }

    const QVariantList& participants() const { return m_participants; }
    void setParticipants(const QVariantList& participants) { m_participants = participants; emit participantsChanged(); }

    const QString& participantIds() const { return m_participantIds; }
    void setParticipantIds(const QString& participantIds) { m_participantIds = participantIds; emit participantIdsChanged(); }

    const QString& ownUserId() const { return m_ownUserId; }
    void setOwnUserId(const QString& ownUserId) { m_ownUserId = ownUserId; emit ownUserIdChanged(); }

    Q_INVOKABLE QString formattedLastMessageTime() const {
        if (m_lastMessageTime.isNull()) return {};
        QDate today = QDate::currentDate();
        QDate yesterday = today.addDays(-1);
        if (m_lastMessageTime.date() == today)
            return m_lastMessageTime.toString(QStringLiteral("HH:mm"));
        if (m_lastMessageTime.date() == yesterday)
            return QStringLiteral("Yesterday");
        return m_lastMessageTime.toString(QStringLiteral("dd MMM yyyy"));
    }

    Q_INVOKABLE QString otherParticipantId() const {
        if (!m_group && !m_participantIds.isEmpty()) {
            QStringList ids = m_participantIds.split(QChar(','));
            for (const auto& id : ids) {
                if (id != m_ownUserId) return id;
            }
        }
        return {};
    }

    Q_INVOKABLE QString displayName() const {
        if (m_group && !m_name.isEmpty()) return m_name;
        if (!m_group && !m_participants.isEmpty()) {
            return m_participants[0].value<QObject*>()->property("username").toString();
        }
        return {};
    }

signals:
    void idChanged();
    void nameChanged();
    void avatarChanged();
    void lastMessageChanged();
    void lastMessageTimeChanged();
    void unreadCountChanged();
    void onlineChanged();
    void typingChanged();
    void isGroupChanged();
    void participantsChanged();
    void participantIdsChanged();
    void ownUserIdChanged();

private:
    QString m_id;
    QString m_name;
    QString m_avatar;
    QString m_lastMessage;
    QDateTime m_lastMessageTime;
    int m_unreadCount = 0;
    bool m_online = false;
    bool m_typing = false;
    bool m_group = false;
    QVariantList m_participants;
    QString m_participantIds;
    QString m_ownUserId;
};