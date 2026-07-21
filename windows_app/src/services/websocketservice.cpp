#include "websocketservice.h"
#include "../utils/config.h"
#include "../utils/credentialmanager.h"
#include "../crypto/encryption.h"
#include <QWebSocket>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QDebug>
#include <QUrlQuery>

WebSocketService::WebSocketService(QObject* parent)
    : QObject(parent)
    , m_webSocket(nullptr)
{
    m_serverUrl = Config::websocketUrl();

    m_reconnectTimer.setSingleShot(false);
    m_reconnectTimer.setInterval(5000);
    connect(&m_reconnectTimer, &QTimer::timeout, this, &WebSocketService::onReconnect);
}

WebSocketService::~WebSocketService() {
    disconnectFromServer();
}

void WebSocketService::connectToServer(const QString& token) {
    m_token = token;

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
    connect(m_webSocket, &QWebSocket::binaryMessageReceived, this, &WebSocketService::onBinaryMessageReceived);

    QUrl url(m_serverUrl);
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("token"), m_token);
    url.setQuery(query);

    m_webSocket->open(url);
}

void WebSocketService::disconnectFromServer() {
    if (m_webSocket) {
        m_webSocket->close();
        m_autoReconnect = false;
        m_reconnectTimer.stop();
        delete m_webSocket;
        m_webSocket = nullptr;
    }
}

void WebSocketService::sendTypingIndicator(const QString& chatId, bool typing) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("typing");
    obj[QStringLiteral("chatId")] = chatId;
    obj[QStringLiteral("typing")] = typing;

    sendJson(obj);
}

void WebSocketService::sendMessage(const QString& chatId, const QString& content, const QString& recipientId) {
    if (!m_webSocket || !m_webSocket->isValid()) {
        emit errorOccurred(QStringLiteral("Not connected to server"));
        return;
    }

    QString encryptedContent = content;
    QString nonce;
    bool isEncrypted = false;

    if (!recipientId.isEmpty()) {
        QString recipientPubKey = CredentialManager::instance().getToken(QStringLiteral("pubkey_%1").arg(recipientId));
        if (!recipientPubKey.isEmpty()) {
            QByteArray pubKeyBytes = recipientPubKey.toUtf8();
            QByteArray encrypted;
            QByteArray generatedNonce;
            if (Encryption::encryptMessageDirect(content.toUtf8(), pubKeyBytes, encrypted, generatedNonce)) {
                encryptedContent = QString::fromUtf8(encrypted);
                m_messageNonce = QString::fromUtf8(generatedNonce);
                isEncrypted = true;
            }
        }
    }

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("message");
    obj[QStringLiteral("chatId")] = chatId;
    obj[QStringLiteral("content")] = encryptedContent;
    obj[QStringLiteral("nonce")] = m_messageNonce;
    obj[QStringLiteral("encrypted")] = isEncrypted;
    if (!recipientId.isEmpty()) {
        obj[QStringLiteral("recipientId")] = recipientId;
    }

    sendJson(obj);
}

void WebSocketService::markAsRead(const QString& messageId, const QString& chatId) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("read_receipt");
    obj[QStringLiteral("messageId")] = messageId;
    obj[QStringLiteral("chatId")] = chatId;

    sendJson(obj);
}

void WebSocketService::loadMessages(const QString& chatId, int limit, const QString& before) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("load_messages");
    obj[QStringLiteral("chatId")] = chatId;
    obj[QStringLiteral("limit")] = limit;
    if (!before.isEmpty()) {
        obj[QStringLiteral("before")] = before;
    }

    sendJson(obj);
}

void WebSocketService::loadContacts() {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("load_contacts");
    sendJson(obj);
}

void WebSocketService::createChat(const QString& name, const QStringList& participantIds, bool isGroup) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("create_chat");
    obj[QStringLiteral("name")] = name;
    obj[QStringLiteral("group")] = isGroup;

    QJsonArray participants;
    for (const auto& id : participantIds) {
        participants.append(id);
    }
    obj[QStringLiteral("participants")] = participants;

    sendJson(obj);
}

void WebSocketService::addParticipant(const QString& chatId, const QString& participantId) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("add_participant");
    obj[QStringLiteral("chatId")] = chatId;
    obj[QStringLiteral("participantId")] = participantId;

    sendJson(obj);
}

void WebSocketService::removeParticipant(const QString& chatId, const QString& participantId) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("remove_participant");
    obj[QStringLiteral("chatId")] = chatId;
    obj[QStringLiteral("participantId")] = participantId;

    sendJson(obj);
}

void WebSocketService::updateChatName(const QString& chatId, const QString& name) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("update_chat_name");
    obj[QStringLiteral("chatId")] = chatId;
    obj[QStringLiteral("name")] = name;

    sendJson(obj);
}

void WebSocketService::deleteChat(const QString& chatId) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("delete_chat");
    obj[QStringLiteral("chatId")] = chatId;

    sendJson(obj);
}

const QList<QSharedPointer<Message>>& WebSocketService::messages(const QString& chatId) const {
    static const QList<QSharedPointer<Message>> empty;
    auto it = m_messages.find(chatId);
    return it != m_messages.end() ? it.value() : empty;
}

void WebSocketService::authenticate(const QString& token) {
    if (!m_webSocket || !m_webSocket->isValid()) return;

    QJsonObject obj;
    obj[QStringLiteral("type")] = QStringLiteral("auth");
    obj[QStringLiteral("token")] = token;

    sendJson(obj);
}

void WebSocketService::sendJson(const QJsonObject& obj) {
    if (m_webSocket && m_webSocket->isValid()) {
        m_webSocket->sendTextMessage(QJsonDocument(obj).toJson(QJsonDocument::Compact));
    }
}

void WebSocketService::onConnected() {
    authenticate(m_token);
    emit connectedChanged();
    qDebug() << "WebSocket connected";
}

void WebSocketService::onDisconnected() {
    emit disconnected();
    emit connectedChanged();
    if (m_autoReconnect) {
        m_reconnectTimer.start();
    }
    qDebug() << "WebSocket disconnected";
}

void WebSocketService::onTextMessageReceived(const QString& message) {
    QJsonDocument doc = QJsonDocument::fromJson(message.toUtf8());
    if (!doc.isObject()) return;

    QJsonObject obj = doc.object();
    QString type = obj[QStringLiteral("type")].toString();

    if (type == QStringLiteral("auth_success")) {
        QJsonObject userObj = obj[QStringLiteral("user")].toObject();
        auto tempUser = QSharedPointer<User>::create();
        tempUser->setId(userObj[QStringLiteral("id")].toString());
        tempUser->setUsername(userObj[QStringLiteral("username")].toString());
        tempUser->setEmail(userObj[QStringLiteral("email")].toString());
        tempUser->setOnline(obj[QStringLiteral("online")].toBool(true));
        m_currentUser = *tempUser;
        emit currentUserChanged();
        emit connected(m_currentUser.id());
        qDebug() << "Authenticated as" << m_currentUser.username();
    } else if (type == QStringLiteral("auth_error")) {
        emit errorOccurred(obj[QStringLiteral("error")].toString());
        emit disconnected();
    } else if (type == QStringLiteral("chat_message")) {
        handleChatMessage(obj);
    } else if (type == QStringLiteral("typing")) {
        handleTypingIndicator(obj);
    } else if (type == QStringLiteral("presence")) {
        handlePresence(obj);
    } else if (type == QStringLiteral("message_list")) {
        handleMessageList(obj);
    } else if (type == QStringLiteral("chat_updated")) {
        handleChatUpdate(obj);
    } else if (type == QStringLiteral("chats_list")) {
        QJsonArray chatsArr = obj[QStringLiteral("chats")].toArray();
        QList<QSharedPointer<Chat>> updatedChats;
        for (const auto& chatVal : chatsArr) {
            QJsonObject chatObj = chatVal.toObject();
            auto chat = QSharedPointer<Chat>::create();
            chat->setId(chatObj[QStringLiteral("id")].toString());
            chat->setName(chatObj[QStringLiteral("name")].toString());
            chat->setParticipantIds(chatObj[QStringLiteral("participantIds")].toString());
            chat->setOwnUserId(m_currentUser.id());
            updatedChats.append(chat);
        }
        emit chatsUpdated(updatedChats);
    } else if (type == QStringLiteral("contacts_list")) {
        handleContactsList(obj);
    } else if (type == QStringLiteral("chat_created")) {
        handleChatCreated(obj);
    } else if (type == QStringLiteral("chat_deleted")) {
        handleChatDeleted(obj);
    } else if (type == QStringLiteral("error")) {
        emit errorOccurred(obj[QStringLiteral("error")].toString());
    }
}

void WebSocketService::onBinaryMessageReceived(QByteArray /*message*/) {
    // Binary messages not used in current implementation
}

void WebSocketService::onError(QAbstractSocket::SocketError /*error*/) {
    qDebug() << "WebSocket error:" << m_webSocket->errorString();
}

void WebSocketService::onNetworkStateChanged() {
    // Network state changed - handle reconnection if needed
    if (m_autoReconnect && (!m_webSocket || !m_webSocket->isValid())) {
        m_reconnectTimer.start();
    }
}

void WebSocketService::onReconnect() {
    if (!m_autoReconnect) {
        return;
    }
    if (m_webSocket && m_webSocket->isValid()) {
        return;
    }
    if (!m_token.isEmpty()) {
        connectToServer(m_token);
    }
}

void WebSocketService::handleChatMessage(const QJsonObject& obj) {
    QJsonObject msgObj = obj[QStringLiteral("message")].toObject();
    auto message = QSharedPointer<Message>::create();
    message->setId(msgObj[QStringLiteral("id")].toString());
    message->setChatId(msgObj[QStringLiteral("chatId")].toString());
    message->setSenderId(msgObj[QStringLiteral("senderId")].toString());
    message->setSenderName(msgObj[QStringLiteral("senderName")].toString());
    message->setContent(msgObj[QStringLiteral("content")].toString());
    message->setEncryptedContent(msgObj[QStringLiteral("encryptedContent")].toString());
    message->setNonce(msgObj[QStringLiteral("nonce")].toString());
    message->setRecipientId(msgObj[QStringLiteral("recipientId")].toString());
    message->setEncrypted(msgObj[QStringLiteral("encrypted")].toBool(false));
    message->setTimestamp(QDateTime::fromString(msgObj[QStringLiteral("timestamp")].toString(),
                                                QStringLiteral("yyyy-MM-ddThh:mm:ss.zzz")));

    // Decrypt if needed
    if (message->encrypted() && !message->encryptedContent().isEmpty()) {
        QString senderPubKey = CredentialManager::instance().getToken(
            QStringLiteral("pubkey_%1").arg(message->senderId()));
        if (!senderPubKey.isEmpty()) {
            // Decryption would require the recipient's private key
            // For now, just use the encrypted content as content
            message->setContent(tr("[Encrypted]"));
        }
    }

    // Store message
    m_messages[message->chatId()].append(message);

    emit messageReceived(message);
}

void WebSocketService::handleTypingIndicator(const QJsonObject& obj) {
    emit typingIndicator(obj[QStringLiteral("chatId")].toString(),
                         obj[QStringLiteral("userId")].toString(),
                         obj[QStringLiteral("typing")].toBool());
}

void WebSocketService::handlePresence(const QJsonObject& obj) {
    emit presenceChanged(obj[QStringLiteral("userId")].toString(),
                         obj[QStringLiteral("online")].toBool());
}

void WebSocketService::handleMessageList(const QJsonObject& obj) {
    QString chatId = obj[QStringLiteral("chatId")].toString();
    QJsonArray msgsArr = obj[QStringLiteral("messages")].toArray();
    QList<QSharedPointer<Message>> messages;

    for (const auto& msgVal : msgsArr) {
        QJsonObject msgObj = msgVal.toObject();
        auto message = QSharedPointer<Message>::create();
        message->setId(msgObj[QStringLiteral("id")].toString());
        message->setChatId(chatId);
        message->setSenderId(msgObj[QStringLiteral("senderId")].toString());
        message->setSenderName(msgObj[QStringLiteral("senderName")].toString());
        message->setContent(msgObj[QStringLiteral("content")].toString());
        message->setEncryptedContent(msgObj[QStringLiteral("encryptedContent")].toString());
        message->setNonce(msgObj[QStringLiteral("nonce")].toString());
        message->setRecipientId(msgObj[QStringLiteral("recipientId")].toString());
        message->setEncrypted(msgObj[QStringLiteral("encrypted")].toBool(false));
        message->setTimestamp(QDateTime::fromString(msgObj[QStringLiteral("timestamp")].toString(),
                                                    QStringLiteral("yyyy-MM-ddThh:mm:ss.zzz")));
        message->setDelivered(msgObj[QStringLiteral("delivered")].toBool(false));
        message->setRead(msgObj[QStringLiteral("read")].toBool(false));
        messages.append(message);
    }

    m_messages[chatId] = messages;
    emit messagesLoaded(chatId, messages);
}

void WebSocketService::handleChatUpdate(const QJsonObject& obj) {
    auto chat = QSharedPointer<Chat>::create();
    chat->setId(obj[QStringLiteral("id")].toString());
    chat->setName(obj[QStringLiteral("name")].toString());
    QJsonValue pVal = obj[QStringLiteral("participants")];
    if (pVal.isArray()) {
        QJsonArray pArr = pVal.toArray();
        QStringList participants;
        for (const auto& p : pArr) {
            participants.append(p.toString());
        }
        chat->setParticipantIds(participants.join(","));
    }
    chat->setOwnUserId(m_currentUser.id());
    emit chatUpdated(chat);
}

void WebSocketService::handleContactsList(const QJsonObject& obj) {
    QJsonArray contactsArr = obj[QStringLiteral("contacts")].toArray();
    QList<QSharedPointer<User>> contacts;

    for (const auto& v : contactsArr) {
        QJsonObject userObj = v.toObject();
        auto user = QSharedPointer<User>::create();
        user->setId(userObj[QStringLiteral("id")].toString());
        user->setUsername(userObj[QStringLiteral("username")].toString());
        user->setEmail(userObj[QStringLiteral("email")].toString());
        user->setOnline(userObj[QStringLiteral("online")].toBool(false));
        user->setStatus(userObj[QStringLiteral("status")].toString());
        contacts.append(user);
    }

    m_contacts = contacts;
    emit contactsUpdated(m_contacts);
}

void WebSocketService::handleChatCreated(const QJsonObject& obj) {
    auto chat = QSharedPointer<Chat>::create();
    QJsonObject chatObj = obj[QStringLiteral("chat")].toObject();
    chat->setId(chatObj[QStringLiteral("id")].toString());
    chat->setName(chatObj[QStringLiteral("name")].toString());
    emit chatCreated(chat);
}

void WebSocketService::handleChatDeleted(const QJsonObject& obj) {
    emit chatDeleted(obj[QStringLiteral("chatId")].toString());
}