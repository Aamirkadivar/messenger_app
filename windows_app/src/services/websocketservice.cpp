#include "websocketservice.h"
#include "../utils/config.h"
#include <QWebSocket>
#include <QJsonDocument>
#include <QJsonObject>
#include <QDebug>
#include <QUrlQuery>
#include <QDateTime>
#include <QNetworkInformation>
#include <QRandomGenerator>

namespace {
constexpr int kHealthCheckIntervalMs = 12000;
constexpr qint64 kStaleConnectionMs = 26000;
constexpr int kReconnectDelayMinMs = 1000;
constexpr int kReconnectDelayMaxMs = 30000;
}

WebSocketService::WebSocketService(QObject* parent)
    : QObject(parent)
{
    m_serverUrl = Config::websocketUrl();

    m_reconnectTimer.setSingleShot(true);
    connect(&m_reconnectTimer, &QTimer::timeout, this, &WebSocketService::onReconnect);

    m_healthTimer.setSingleShot(false);
    m_healthTimer.setInterval(kHealthCheckIntervalMs);
    connect(&m_healthTimer, &QTimer::timeout, this, &WebSocketService::checkConnectionHealth);

    QNetworkInformation::loadDefaultBackend();
    if (auto* net = QNetworkInformation::instance()) {
        connect(net, &QNetworkInformation::reachabilityChanged, this,
                [this](QNetworkInformation::Reachability reach) {
            if (!m_autoReconnect || m_token.isEmpty()) return;
            if (reach == QNetworkInformation::Reachability::Online ||
                reach == QNetworkInformation::Reachability::Site ||
                reach == QNetworkInformation::Reachability::Local) {
                m_reconnectAttempts = 0;
                if (!isConnected() && !m_reconnectTimer.isActive())
                    scheduleReconnect();
                else if (!isConnected()) {
                    m_reconnectTimer.stop();
                    onReconnect();
                }
            }
        });
    }
}

WebSocketService::~WebSocketService() {
    disconnectFromServer();
}

void WebSocketService::connectToServer(const QString& token) {
    m_token = token;
    m_autoReconnect = true;
    setConnectionState(nextRetryState());

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
    connect(m_webSocket, &QWebSocket::pong, this, &WebSocketService::onPong);

    QUrl url(m_serverUrl);
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("token"), m_token);
    if (!m_deviceId.isEmpty()) {
        query.addQueryItem(QStringLiteral("device_id"), m_deviceId);
    }
    url.setQuery(query);

    m_webSocket->open(url);
}

void WebSocketService::disconnectFromServer() {
    m_autoReconnect = false;
    m_reconnectTimer.stop();
    m_healthTimer.stop();
    m_joinedChats.clear();
    m_hasConnectedBefore = false;
    setConnectionState(QStringLiteral("disconnected"));

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

void WebSocketService::sendCallSignal(const QString& type, const QVariantMap& data) {
    if (!isConnected()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = type;
    obj[QStringLiteral("data")] = QJsonObject::fromVariantMap(data);
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
    m_hasConnectedBefore = true;
    m_reconnectAttempts = 0;
    m_reconnectTimer.stop();
    noteActivity();
    m_healthTimer.start();
    setConnectionState(QStringLiteral("connected"));
    emit connectedChanged();
    emit connected();
    rejoinRooms();
}

void WebSocketService::onDisconnected() {
    qDebug() << "[WebSocketService] Disconnected";
    m_healthTimer.stop();
    setConnectionState(m_autoReconnect ? nextRetryState() : QStringLiteral("disconnected"));
    emit disconnected();
    emit connectedChanged();
    if (m_autoReconnect) {
        emit connectionAttemptFailed();
        scheduleReconnect();
    }
}

void WebSocketService::onTextMessageReceived(const QString& message) {
    noteActivity();

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
        m["fileUrl"] = data[QStringLiteral("file_url")].toString();
        m["fileType"] = data[QStringLiteral("file_type")].toString();
        m["fileName"] = data[QStringLiteral("file_name")].toString();
        m["fileSize"] = static_cast<qint64>(data[QStringLiteral("file_size")].toDouble(0));
        m["durationMs"] = static_cast<qint64>(data[QStringLiteral("duration_ms")].toDouble(0));
        m["thumbnailUrl"] = data[QStringLiteral("thumbnail_url")].toString();
        m["keyVersion"] = data[QStringLiteral("key_version")].toInt(0);
        int encVer = data[QStringLiteral("encryption_version")].toInt(1);
        m["encryptionVersion"] = encVer <= 0 ? 1 : encVer;
        m["senderDeviceId"] = data[QStringLiteral("sender_device_id")].toString();
        m["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);
        m["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();
        m["forwardedFromMessageId"] = data[QStringLiteral("forwarded_from_message_id")].toString();
        m["replyToId"] = data[QStringLiteral("reply_to_id")].toString();

        emit messageReceived(chatId, m);
    } else if (type == QStringLiteral("typing")) {
        QString chatId = data[QStringLiteral("chat_id")].toString();
        QString userId = data[QStringLiteral("user_id")].toString();
        bool typing = data[QStringLiteral("typing")].toBool();
        emit typingIndicator(chatId, userId, typing);
    } else if (type == QStringLiteral("read")) {
        QString chatId = data[QStringLiteral("chat_id")].toString();
        QString readerId = data[QStringLiteral("reader_id")].toString();
        QString readAt = data[QStringLiteral("read_at")].toString();
        emit messageRead(chatId, readerId, readAt);
    } else if (type == QStringLiteral("presence")) {
        QString userId = data[QStringLiteral("user_id")].toString();
        bool online = data[QStringLiteral("is_online")].toBool(false);
        emit presenceChanged(userId, online);
    } else if (type == QStringLiteral("message:deleted")) {
        emit messageDeletedRemotely(data[QStringLiteral("chat_id")].toString(),
                                    data[QStringLiteral("message_id")].toString());
    } else if (type == QStringLiteral("error")) {
        emit errorOccurred(data[QStringLiteral("error")].toString());
    } else if (type.startsWith(QStringLiteral("call:"))) {
        // Pairwise (invite/answer/ICE/…) and group session (group_invite/join/leave)
        // signals both carry optional group_call_id / participant_ids; CallService
        // decides how to route. toVariantMap preserves JSON arrays as QVariantList.
        emit callSignalReceived(type, data.toVariantMap());
    }
}

void WebSocketService::onError(QAbstractSocket::SocketError /*error*/) {
    if (m_webSocket) {
        qDebug() << "[WebSocketService] Error:" << m_webSocket->errorString();
    }
    // A failed connection attempt (e.g. "connection refused" because the
    // server is down) never reaches Connected, so disconnected() never
    // fires for it - re-check here too so the UI doesn't miss the update.
    if (m_connectionState != QStringLiteral("connected")) {
        setConnectionState(m_autoReconnect ? nextRetryState() : QStringLiteral("disconnected"));
        if (m_autoReconnect && !m_reconnectTimer.isActive())
            scheduleReconnect();
    }
    emit connectedChanged();
}

void WebSocketService::scheduleReconnect() {
    if (!m_autoReconnect || isConnected() || m_token.isEmpty()) return;
    if (m_reconnectTimer.isActive()) return;

    const int exp = qMin(m_reconnectAttempts, 10);
    int delay = kReconnectDelayMinMs * (1 << exp);
    if (delay > kReconnectDelayMaxMs) delay = kReconnectDelayMaxMs;
    const int jitter = QRandomGenerator::global()->bounded(qMax(1, delay / 4));
    ++m_reconnectAttempts;
    qDebug() << "[WebSocketService] Reconnect in" << (delay + jitter) << "ms (attempt" << m_reconnectAttempts << ")";
    m_reconnectTimer.start(delay + jitter);
}

void WebSocketService::onReconnect() {
    if (!m_autoReconnect || isConnected() || m_token.isEmpty()) {
        return;
    }
    connectToServer(m_token);
}

void WebSocketService::onPong(quint64 /*elapsedTime*/, const QByteArray& /*payload*/) {
    noteActivity();
}

void WebSocketService::checkConnectionHealth() {
    if (!m_webSocket || m_webSocket->state() != QAbstractSocket::ConnectedState) {
        return;
    }

    qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now - m_lastActivityMs > kStaleConnectionMs) {
        // Nothing heard from the server in too long - the connection is
        // almost certainly dead even though the OS hasn't noticed yet.
        // Force it closed so onDisconnected() fires and reconnect kicks in.
        qDebug() << "[WebSocketService] No activity for" << (now - m_lastActivityMs)
                  << "ms, treating connection as dead";
        m_webSocket->abort();
        return;
    }

    m_webSocket->ping();
}

void WebSocketService::noteActivity() {
    m_lastActivityMs = QDateTime::currentMSecsSinceEpoch();
}

void WebSocketService::setConnectionState(const QString& state) {
    if (m_connectionState == state) return;
    qDebug() << "[WebSocketService] connectionState:" << m_connectionState << "->" << state;
    m_connectionState = state;
    emit connectionStateChanged();
}
