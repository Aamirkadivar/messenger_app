#pragma once

#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>
#include <QNetworkAccessManager>
#include "authservice.h"

// Group chat management: create, inspect, and administer groups. Separate
// from ChatService (which owns the message send/receive path) because group
// membership/roles are a distinct concern with their own lifecycle - mirrors
// the same split used on the Android client (GroupRepository vs
// ChatRepository).
class GroupService : public QObject {
    Q_OBJECT

public:
    explicit GroupService(AuthService* authService, QObject* parent = nullptr);

    Q_INVOKABLE void createGroup(const QString& name, const QString& description, const QVariantList& memberIds);
    Q_INVOKABLE void getGroupInfo(const QString& chatId);
    Q_INVOKABLE void updateGroup(const QString& chatId, const QString& name, const QString& description);
    Q_INVOKABLE void addMembers(const QString& chatId, const QVariantList& memberIds);
    Q_INVOKABLE void removeMember(const QString& chatId, const QString& memberId);
    Q_INVOKABLE void setMemberRole(const QString& chatId, const QString& memberId, const QString& role);
    Q_INVOKABLE void deleteGroup(const QString& chatId);
    Q_INVOKABLE void leaveGroup(const QString& chatId);
    // filePath is a local file:// or plain path from a QML FileDialog selection.
    Q_INVOKABLE void uploadGroupAvatar(const QString& chatId, const QString& filePath);

signals:
    // data matches the backend's group JSON shape: id, name, type, avatar_url,
    // description, owner_id, members[], member_count.
    void groupCreated(const QVariantMap& group);
    void groupInfoFetched(const QVariantMap& group);
    void groupUpdated(const QString& chatId);
    void membersAdded(const QString& chatId);
    void memberRemoved(const QString& chatId, const QString& memberId);
    void memberRoleChanged(const QString& chatId, const QString& memberId, const QString& role);
    void groupDeleted(const QString& chatId);
    void groupLeft(const QString& chatId);
    void groupAvatarUploaded(const QString& chatId, const QString& avatarUrl);
    void groupError(const QString& message);

private:
    QString buildAuthHeader() const;
    // Extracts the backend's {"error":..,"message":..} body, falling back to
    // the HTTP status when the body isn't the expected shape.
    QString errorMessageFrom(class QNetworkReply* reply, const QByteArray& body) const;
    static QVariantMap parseGroup(const class QJsonObject& obj);

    AuthService* m_authService = nullptr;
    QNetworkAccessManager* m_networkManager = nullptr;
};
