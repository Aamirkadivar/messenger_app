#pragma once

#include <QObject>
#include <QString>
#include <QList>
#include <QHash>
#include <QVariantList>
#include <QNetworkReply>
#include <QJsonObject>
#include <QJsonArray>
#include "../utils/config.h"
#include "authservice.h"

class ChatService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isLoading READ isLoading NOTIFY isLoadingChanged)

public:
    explicit ChatService(AuthService* authService, QObject* parent = nullptr);
    ~ChatService();

    bool isLoading() const { return m_isLoading; }

    QNetworkAccessManager* networkManager() { return m_networkManager; }

    Q_INVOKABLE void fetchChats();
    Q_INVOKABLE void searchUsers(const QString& query);
    Q_INVOKABLE void startDirectChat(const QString& userId, const QString& userName);
    Q_INVOKABLE void fetchMessages(const QString& chatId);
    Q_INVOKABLE void sendMessage(const QString& chatId, const QString& text);
    Q_INVOKABLE void markAsRead(const QString& chatId);

    // Decrypt an (E2EE) message for a chat. If the message isn't encrypted, or
    // we lack the key, returns the content as-is / a placeholder. Exposed to
    // QML so WebSocket-delivered ciphertext can be decrypted at the display site.
    Q_INVOKABLE QString decryptMessage(const QString& chatId, const QString& content, bool encrypted) const;

signals:
    void isLoadingChanged();
    // Each entry is a QVariantMap matching the backend's chat list JSON shape
    // (id, type, name, avatar_url, other_user, last_message, last_message_at,
    // unread_count, is_online, updated_at) - see ChatService::parseChatItem.
    void chatsFetched(const QVariantList& chats);
    void chatError(const QString& error);
    void usersFound(const QVariantList& users);
    void searchError(const QString& error);
    void directChatReady(const QString& chatId, const QString& chatName);
    void messagesFetched(const QString& chatId, const QVariantList& messages);
    void messageSent(const QString& chatId, const QVariantMap& message);
    void messageError(const QString& error);
    void chatRead(const QString& chatId);

private slots:
    void onChatsReplyFinished();

private:
    void setupNetworkManager();
    QString buildAuthHeader() const;

    AuthService* m_authService = nullptr;
    QNetworkAccessManager* m_networkManager = nullptr;
    QNetworkReply* m_currentReply = nullptr;
    bool m_isLoading = false;

    // chatId -> the other participant's public key (hex), learned from the
    // chat list. For a direct chat this key both encrypts our outgoing
    // messages and decrypts everything in the thread (box is symmetric in the
    // shared-secret sense), so one key per chat is all we need.
    QHash<QString, QString> m_chatOtherPub;

    // Helper: parse a single chat from JSON into a QML-friendly QVariantMap
    QVariantMap parseChatItem(const QJsonObject& obj);
};
