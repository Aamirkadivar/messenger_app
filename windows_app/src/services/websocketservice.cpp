#include "websocketservice.h"
#include "../utils/config.h"
#include <QWebSocket>
#include <QJsonDocument>
#include <QJsonObject>
#include <QDebug>
#include <QUrlQuery>

WebSocketService::WebSocketService(QObject* parent)
    : QObject(parent)
{
    m_serverUrl = Config::websocketUrl();

    m_reconnectTimer.setSingleShot(false);
    m_reconnectTimer.setInterval(Config::reconnectDelayMs());
    connect(&m_reconnectTimer, &QTimer::timeout, this, &WebSocketService::onReconnect);
}

WebSocketService::~WebSocketService() {
    disconnectFromServer();
}

void WebSocketService::connectToServer(const QString& token) {
    m_token = token;
    m_autoReconnect = true;

    if (m_webSocket) {
        delete m_webSocket;
        m_webSocket = nullptr;
    }

    m_webSocket = new QWebSocket();
    connect(m_webSocket, &QWebSocket::connected, this, &WebSocketService::onConnected);
    connect(m_webSocket, &QWebSocket::disconnected, this, &WebSocketService::onDisconnected);
    connect(m_webSocket, QOverload<QAbstractSocket::SocketError>::of(&QWebSocket::error),
            this, &WebSocketService::onError);
    connect(m_webSocket, &QWebSocket::textMessageReceived, this, &WebSocketService::onTextMessageReceived);

    QUrl url(m_serverUrl);
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("token"), m_token);
    url.setQuery(query);

    m_webSocket->open(url);
}

void WebSocketService::disconnectFromServer() {
    m_autoReconnect = false;
    m_reconnectTimer.stop();
    m_joinedChats.clear();

    if (m_webSocket) {
        m_webSocket->close();
        delete m_webSocket;
        m_webSocket = nullptr;
    }
}

void WebSocketService::joinChat(const QString& chatId) {
    if (chatId.isEmpty()) return;
    m_joinedChats.insert(chatId);

    if (!isConnected()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("join");
    obj[QStringLiteral("data")] = QJsonObject{{QStringLiteral("chat_id"), chatId}};
    sendJson(obj);
}

void WebSocketService::leaveChat(const QString& chatId) {
    m_joinedChats.remove(chatId);
    if (!isConnected()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("leave");
    obj[QStringLiteral("data")] = QJsonObject{{QStringLiteral("chat_id"), chatId}};
    sendJson(obj);
}

void WebSocketService::sendTypingIndicator(const QString& chatId, const QString& userId, bool typing) {
    if (!isConnected()) return;

    QJsonObject data;
    data[QStringLiteral("chat_id")] = chatId;
    data[QStringLiteral("user_id")] = userId;
    data[QStringLiteral("typing")] = typing;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("typing");
    obj[QStringLiteral("data")] = data;
    sendJson(obj);
}

void WebSocketService::sendJson(const QJsonObject& obj) {
    if (m_webSocket && m_webSocket->isValid()) {
        m_webSocket->sendTextMessage(QJsonDocument(obj).toJson(QJsonDocument::Compact));
    }
}

void WebSocketService::rejoinRooms() {
    for (const QString& chatId : m_joinedChats) {
        QJsonObject obj;
        obj[QStringLiteral("type")] = QStringLiteral("join");
        obj[QStringLiteral("data")] = QJsonObject{{QStringLiteral("chat_id"), chatId}};
        sendJson(obj);
    }
}

void WebSocketService::onConnected() {
    qDebug() << "[WebSocketService] Connected";
    emit connectedChanged();
    emit connected();
    rejoinRooms();
}

void WebSocketService::onDisconnected() {
    qDebug() << "[WebSocketService] Disconnected";
    emit disconnected();
    emit connectedChanged();
    if (m_autoReconnect) {
        m_reconnectTimer.start();
    }
}

void WebSocketService::onTextMessageReceived(const QString& message) {
    QJsonDocument doc = QJsonDocument::fromJson(message.toUtf8());
    if (!doc.isObject()) return;

    QJsonObject obj = doc.object();
    QString type = obj[QStringLiteral("type")].toString();
    QJsonObject data = obj[QStringLiteral("data")].toObject();

    if (type == QStringLiteral("message")) {
        QString chatId = data[QStringLiteral("chat_id")].toString();

        QVariantMap m;
        m["id"] = data[QStringLiteral("message_id")].toString();
        m["senderId"] = data[QStringLiteral("sender_id")].toString();
        m["content"] = data[QStringLiteral("content")].toString();
        m["encrypted"] = data[QStringLiteral("encrypted")].toBool(false);
        m["createdAt"] = data[QStringLiteral("timestamp")].toString();

        emit messageReceived(chatId, m);
    } else if (type == QStringLiteral("typing")) {
        QString chatId = data[QStringLiteral("chat_id")].toString();
        QString userId = data[QStringLiteral("user_id")].toString();
        bool typing = data[QStringLiteral("typing")].toBool();
        emit typingIndicator(chatId, userId, typing);
    } else if (type == QStringLiteral("error")) {
        emit errorOccurred(data[QStringLiteral("error")].toString());
    }
}

void WebSocketService::onError(QAbstractSocket::SocketError /*error*/) {
    if (m_webSocket) {
        qDebug() << "[WebSocketService] Error:" << m_webSocket->errorString();
    }
}

void WebSocketService::onReconnect() {
    if (!m_autoReconnect || isConnected() || m_token.isEmpty()) {
        return;
    }
    connectToServer(m_token);
}
