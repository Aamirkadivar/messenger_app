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
    // One of "disconnected" / "connecting" / "reconnecting" / "connected" -
    // distinguishes the very first connection attempt from a retry after a
    // drop, so the UI can say "Connecting…" vs "Reconnecting…" like Telegram
    // does, instead of a single generic online/offline flag.
    Q_PROPERTY(QString connectionState READ connectionState NOTIFY connectionStateChanged)

public:
    explicit WebSocketService(QObject* parent = nullptr);
    ~WebSocketService() override;

    bool isConnected() const { return m_webSocket && m_webSocket->state() == QAbstractSocket::ConnectedState; }
    QString connectionState() const { return m_connectionState; }

    Q_INVOKABLE void setDeviceId(const QString& deviceId) { m_deviceId = deviceId; }
    Q_INVOKABLE void connectToServer(const QString& token);
    Q_INVOKABLE void disconnectFromServer();
    Q_INVOKABLE void joinChat(const QString& chatId);
    Q_INVOKABLE void leaveChat(const QString& chatId);
    Q_INVOKABLE void sendTypingIndicator(const QString& chatId, const QString& userId, bool typing);
    // Sends a call:* signaling message - see back-end/websocket/calls.go for
    // the field contract (to_user_id/from_user_id/call_id + type-specific
    // fields like sdp/candidate/reason).
    Q_INVOKABLE void sendCallSignal(const QString& type, const QVariantMap& data);

signals:
    void connectedChanged();
    void connectionStateChanged();
    void connected();
    void disconnected();
    // message: {id, senderId, content, createdAt}
    // MLS group epoch advanced for this chat (fetch Welcome / handshakes).
    void mlsCommitReceived(const QString& chatId);

    void messageReceived(const QString& chatId, const QVariantMap& message);
    void typingIndicator(const QString& chatId, const QString& userId, bool typing);
    // Fired when the other participant marks messages in a chat as read.
    void messageRead(const QString& chatId, const QString& readerId, const QString& readAt);
    // Fired whenever any user connects/disconnects - not scoped to a chat room.
    void presenceChanged(const QString& userId, bool online);
    void errorOccurred(const QString& error);
    // Someone retracted a message for everyone; the open chat should drop it
    // rather than leave it on screen until the next refetch.
    void messageDeletedRemotely(const QString& chatId, const QString& messageId);
    // Fired once per failed connect/reconnect attempt (while auto-reconnect
    // is on) - distinct from connectionStateChanged so a caller can react to
    // "an attempt just failed" without having to infer it from state-string
    // transitions. The most common cause is a stale access token that the
    // server keeps rejecting; a caller can use this to refresh the token and
    // hand the socket a fresh one instead of retrying the same dead one
    // forever.
    void connectionAttemptFailed();
    // One call:* signaling event, forwarded to CallService. type is a pairwise
    // "call:invite"/"answer"/"ice_candidate"/"reject"/"end"/"media" or a group
    // session "call:group_invite"/"group_join"/"group_leave". data may include
    // group_call_id and participant_ids[] for mesh edges (see calls.go).
    void callSignalReceived(const QString& type, const QVariantMap& data);

private slots:
    void onConnected();
    void onDisconnected();
    void onTextMessageReceived(const QString& message);
    void onError(QAbstractSocket::SocketError error);
    void onReconnect();
    void onPong(quint64 elapsedTime, const QByteArray& payload);
    // Pings the server and checks that *something* (a pong or any message)
    // has been heard recently. An abruptly-killed server doesn't send a
    // TCP close, so the OS can take a very long time - or never - to notice
    // the connection is dead on its own; this catches it within one cycle.
    void checkConnectionHealth();

private:
    void sendJson(const QJsonObject& obj);
    void rejoinRooms();
    void noteActivity();
    void setConnectionState(const QString& state);
    // "reconnecting" once we've connected successfully at least once,
    // otherwise "connecting" - so a run of failed first-attempts doesn't
    // flicker between the two labels before ever reaching the server.
    void scheduleReconnect();
    QString nextRetryState() const { return m_hasConnectedBefore ? QStringLiteral("reconnecting") : QStringLiteral("connecting"); }

    QWebSocket* m_webSocket = nullptr;
    QTimer m_reconnectTimer;
    QTimer m_healthTimer;
    qint64 m_lastActivityMs = 0;
    int m_reconnectAttempts = 0;
    QString m_token;
    QString m_deviceId;
    QString m_serverUrl;
    QSet<QString> m_joinedChats;
    bool m_autoReconnect = true;
    QString m_connectionState = QStringLiteral("disconnected");
    bool m_hasConnectedBefore = false;
};
