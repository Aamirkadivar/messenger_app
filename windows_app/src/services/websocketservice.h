#pragma once

#include <QObject>
#include <QWebSocket>
#include <QJsonObject>
#include <QUrl>
#include <QTimer>
#include "../models/user.h"
#include "../models/message.h"
#include "../models/chat.h"
#include "../crypto/encryption.h"
#include <QSharedPointer>

class WebSocketService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isConnected READ isConnected NOTIFY connectedChanged)
    Q_PROPERTY(User currentUser READ currentUser WRITE setCurrentUser NOTIFY currentUserChanged)

public:
    explicit WebSocketService(QObject* parent = nullptr);
    ~WebSocketService() override;

    bool isConnected() const { return m_webSocket && m_webSocket->state() == QAbstractSocket::ConnectedState; }
    const User& currentUser() const { return m_currentUser; }
    void setCurrentUser(const User& user) { m_currentUser = user; }

    Q_INVOKABLE void connectToServer(const QString& token);
    Q_INVOKABLE void disconnectFromServer();
    Q_INVOKABLE void sendTypingIndicator(const QString& chatId, bool typing);
    Q_INVOKABLE void sendMessage(const QString& chatId, const QString& content, const QString& recipientId = QString{});
    Q_INVOKABLE void markAsRead(const QString& messageId, const QString& chatId);
    Q_INVOKABLE void loadMessages(const QString& chatId, int limit = 20, const QString& before = QString{});
    Q_INVOKABLE void loadContacts();
    Q_INVOKABLE void createChat(const QString& name, const QStringList& participantIds, bool isGroup = false);
    Q_INVOKABLE void addParticipant(const QString& chatId, const QString& participantId);
    Q_INVOKABLE void removeParticipant(const QString& chatId, const QString& participantId);
    Q_INVOKABLE void updateChatName(const QString& chatId, const QString& name);
    Q_INVOKABLE void deleteChat(const QString& chatId);

    // Get messages for a chat
    const QList<QSharedPointer<Message>>& messages(const QString& chatId) const;

signals:
    void connectedChanged();
    void currentUserChanged();
    void connected(const QString& userId);
    void disconnected();
    void messageReceived(const QSharedPointer<Message>& message);
    void messagesLoaded(const QString& chatId, const QList<QSharedPointer<Message>>& messages);
    void typingIndicator(const QString& chatId, const QString& userId, bool typing);
    void presenceChanged(const QString& userId, bool online);
    void chatUpdated(const QSharedPointer<Chat>& chat);
    void chatsUpdated(const QList<QSharedPointer<Chat>>& chats);
    void contactsUpdated(const QList<QSharedPointer<User>>& contacts);
    void chatCreated(const QSharedPointer<Chat>& chat);
    void chatDeleted(const QString& chatId);
    void errorOccurred(const QString& error);

private slots:
    void onConnected();
    void onDisconnected();
    void onTextMessageReceived(const QString& message);
    void onBinaryMessageReceived(QByteArray message);
    void onError(QAbstractSocket::SocketError error);
    void onNetworkStateChanged();
    void onReconnect();

private:
    void authenticate(const QString& token);
    void sendJson(const QJsonObject& obj);
    void handleChatMessage(const QJsonObject& obj);
    void handleTypingIndicator(const QJsonObject& obj);
    void handlePresence(const QJsonObject& obj);
    void handleMessageList(const QJsonObject& obj);
    void handleChatUpdate(const QJsonObject& obj);
    void handleContactsList(const QJsonObject& obj);
    void handleChatCreated(const QJsonObject& obj);
    void handleChatDeleted(const QJsonObject& obj);

    QWebSocket* m_webSocket = nullptr;
    User m_currentUser;
    QTimer m_reconnectTimer;
    QString m_token;
    QString m_serverUrl;
    QString m_messageNonce;
    QMap<QString, QList<QSharedPointer<Message>>> m_messages;
    QList<QSharedPointer<Chat>> m_chats;
    QList<QSharedPointer<User>> m_contacts;
    bool m_autoReconnect = true;
};