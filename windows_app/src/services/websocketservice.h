#pragma once

#include <QObject>
#include <QWebSocket>
#include <QJsonObject>
#include <QVariantMap>
#include <QUrl>
#include <QTimer>
#include <QSet>

// Real-time client for the backend's WebSocket hub (back-end/websocket/handler.go).
// Authentication happens via the ?token= query param at connect time - the server
// validates it before allowing the upgrade, so there is no separate auth handshake.
// To receive messages for a chat, the client must join its room ("join") first;
// the backend then pushes any message broadcast for that chat_id to the room's members.
class WebSocketService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isConnected READ isConnected NOTIFY connectedChanged)

public:
    explicit WebSocketService(QObject* parent = nullptr);
    ~WebSocketService() override;

    bool isConnected() const { return m_webSocket && m_webSocket->state() == QAbstractSocket::ConnectedState; }

    Q_INVOKABLE void connectToServer(const QString& token);
    Q_INVOKABLE void disconnectFromServer();
    Q_INVOKABLE void joinChat(const QString& chatId);
    Q_INVOKABLE void leaveChat(const QString& chatId);
    Q_INVOKABLE void sendTypingIndicator(const QString& chatId, const QString& userId, bool typing);

signals:
    void connectedChanged();
    void connected();
    void disconnected();
    // message: {id, senderId, content, createdAt}
    void messageReceived(const QString& chatId, const QVariantMap& message);
    void typingIndicator(const QString& chatId, const QString& userId, bool typing);
    void errorOccurred(const QString& error);

private slots:
    void onConnected();
    void onDisconnected();
    void onTextMessageReceived(const QString& message);
    void onError(QAbstractSocket::SocketError error);
    void onReconnect();

private:
    void sendJson(const QJsonObject& obj);
    void rejoinRooms();

    QWebSocket* m_webSocket = nullptr;
    QTimer m_reconnectTimer;
    QString m_token;
    QString m_serverUrl;
    QSet<QString> m_joinedChats;
    bool m_autoReconnect = true;
};
