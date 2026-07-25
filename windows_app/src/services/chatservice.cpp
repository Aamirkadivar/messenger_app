#include "chatservice.h"
#include "../crypto/encryption.h"
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QVariantMap>
#include <QDebug>

ChatService::ChatService(AuthService* authService, QObject* parent)
    : QObject(parent)
    , m_authService(authService)
{
    setupNetworkManager();
}

ChatService::~ChatService() {
    if (m_currentReply) {
        m_currentReply->deleteLater();
    }
}

void ChatService::setupNetworkManager() {
    m_networkManager = new QNetworkAccessManager(this);
    connect(m_networkManager, &QNetworkAccessManager::finished,
            this, &ChatService::onChatsReplyFinished);
}

QString ChatService::buildAuthHeader() const {
    if (m_authService) {
        QString token = m_authService->authToken();
        if (!token.isEmpty()) {
            return "Bearer " + token;
        }
    }
    return {};
}

void ChatService::fetchChats() {
    if (m_isLoading) return;

    m_isLoading = true;
    emit isLoadingChanged();

    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit chatError("Not authenticated. Please login first.");
        m_isLoading = false;
        emit isLoadingChanged();
        return;
    }

    QString baseUrl = Config::apiBaseUrl();
    QUrl url(baseUrl + "/chats");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    qDebug() << "[ChatService] Fetching chats from:" << url.toString();

    m_currentReply = m_networkManager->get(request);
}

void ChatService::onChatsReplyFinished() {
    if (m_currentReply == nullptr) return;

    auto reply = m_currentReply;
    m_currentReply = nullptr;
    m_isLoading = false;
    emit isLoadingChanged();

    if (reply->error() != QNetworkReply::NoError) {
        QString errorStr = reply->errorString();
        qDebug() << "[ChatService] Network error:" << errorStr;
        emit chatError(errorStr);
        reply->deleteLater();
        return;
    }

    QByteArray data = reply->readAll();
    reply->deleteLater();

    QJsonParseError parseError;
    QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError) {
        qDebug() << "[ChatService] JSON parse error:" << parseError.errorString();
        emit chatError("Failed to parse chat data: " + parseError.errorString());
        return;
    }

    QJsonObject root = doc.object();
    if (root.contains("error")) {
        QString errMsg = root["error"].toString();
        qDebug() << "[ChatService] API error:" << errMsg;
        emit chatError(errMsg);
        return;
    }

    QJsonValue dataVal = root["data"];
    if (!dataVal.isArray()) {
        qDebug() << "[ChatService] Expected 'data' array in response";
        emit chatError("Invalid response format");
        return;
    }

    QJsonArray chatsArray = dataVal.toArray();

    QVariantList result;
    for (const QJsonValue& val : chatsArray) {
        if (!val.isObject()) continue;
        result.append(parseChatItem(val.toObject()));
    }

    qDebug() << "[ChatService] Fetched" << result.size() << "chats";
    emit chatsFetched(result);
}

void ChatService::searchUsers(const QString& query) {
    if (query.trimmed().isEmpty()) {
        emit usersFound(QVariantList());
        return;
    }

    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit searchError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/users/search");
    QUrlQuery urlQuery;
    urlQuery.addQueryItem("q", query);
    url.setQuery(urlQuery);

    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            emit searchError(reply->errorString());
            return;
        }

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(reply->readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError) {
            emit searchError("Failed to parse search results: " + parseError.errorString());
            return;
        }

        QJsonObject root = doc.object();
        if (root.contains("error")) {
            emit searchError(root["error"].toString());
            return;
        }

        QVariantList result;
        for (const QJsonValue& val : root["users"].toArray()) {
            if (!val.isObject()) continue;
            QJsonObject u = val.toObject();
            QVariantMap m;
            m["id"] = u["id"].toString();
            m["email"] = u["email"].toString();
            m["username"] = u["username"].toString();
            m["displayName"] = u["display_name"].toString();
            m["avatarUrl"] = u["avatar_url"].toString();
            m["isOnline"] = u["is_online"].toBool(false);
            result.append(m);
        }
        emit usersFound(result);
    });
}

void ChatService::startDirectChat(const QString& userId, const QString& userName) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit searchError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/chats/direct/" + userId);
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, QByteArray());
    connect(reply, &QNetworkReply::finished, this, [this, reply, userName]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            emit searchError(reply->errorString());
            return;
        }

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(reply->readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError) {
            emit searchError("Failed to parse chat response: " + parseError.errorString());
            return;
        }

        QJsonObject root = doc.object();
        if (root.contains("error")) {
            emit searchError(root["error"].toString());
            return;
        }

        QString chatId = root["data"].toObject()["id"].toString();
        if (chatId.isEmpty()) {
            emit searchError("Invalid chat response from server");
            return;
        }

        emit directChatReady(chatId, userName);
        fetchChats();
    });
}

void ChatService::fetchMessages(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages/" + chatId);
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            emit messageError(reply->errorString());
            return;
        }

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(reply->readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError) {
            emit messageError("Failed to parse messages: " + parseError.errorString());
            return;
        }

        QJsonObject root = doc.object();
        if (root.contains("error")) {
            emit messageError(root["error"].toString());
            return;
        }

        // Server returns newest-first; reverse so oldest is first for display
        QJsonArray messagesArray = root["data"].toArray();
        QVariantList result;
        for (int i = messagesArray.size() - 1; i >= 0; --i) {
            if (!messagesArray[i].isObject()) continue;
            QJsonObject m = messagesArray[i].toObject();
            QJsonObject sender = m["sender"].toObject();

            QVariantMap item;
            item["id"] = m["id"].toString();
            item["senderId"] = m["sender_id"].toString();
            item["senderName"] = sender["display_name"].toString().isEmpty()
                                      ? sender["username"].toString()
                                      : sender["display_name"].toString();
            item["content"] = decryptMessage(chatId, m["content"].toString(), m["encrypted"].toBool(false));
            item["createdAt"] = m["created_at"].toString();
            result.append(item);
        }

        emit messagesFetched(chatId, result);
    });
}

void ChatService::sendMessage(const QString& chatId, const QString& text) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    // Encrypt end-to-end when we know the recipient's public key. If we don't
    // (they've never published one - e.g. still on an old build), fall back to
    // plaintext so messaging keeps working during rollout.
    QString outContent = text;
    bool encrypted = false;
    QString otherPub = m_chatOtherPub.value(chatId);
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (!otherPub.isEmpty() && !myPriv.isEmpty()) {
        QString cipher = Encryption::boxEncrypt(text, otherPub, myPriv);
        if (!cipher.isEmpty()) {
            outContent = cipher;
            encrypted = true;
        }
    }

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = "direct";
    body["content"] = outContent;
    body["encrypted"] = encrypted;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            emit messageError(reply->errorString());
            return;
        }

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(reply->readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError) {
            emit messageError("Failed to parse send response: " + parseError.errorString());
            return;
        }

        QJsonObject root = doc.object();
        if (root.contains("error")) {
            emit messageError(root["error"].toString());
            return;
        }

        QJsonObject data = root["data"].toObject();
        QVariantMap item;
        item["id"] = data["id"].toString();
        item["senderId"] = data["sender_id"].toString();
        item["content"] = decryptMessage(chatId, data["content"].toString(), data["encrypted"].toBool(false));
        item["createdAt"] = data["created_at"].toString();

        emit messageSent(chatId, item);
    });
}

QString ChatService::decryptMessage(const QString& chatId, const QString& content, bool encrypted) const {
    if (!encrypted) return content;

    QString otherPub = m_chatOtherPub.value(chatId);
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (otherPub.isEmpty() || myPriv.isEmpty()) {
        return QStringLiteral("\xF0\x9F\x94\x92 Encrypted message"); // 🔒
    }

    QString plain = Encryption::boxDecrypt(content, otherPub, myPriv);
    if (plain.isEmpty()) {
        return QStringLiteral("\xF0\x9F\x94\x92 Encrypted message");
    }
    return plain;
}

void ChatService::markAsRead(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + "/chats/" + chatId + "/read");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, QByteArray());
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) return;
        emit chatRead(chatId);
    });
}

QVariantMap ChatService::parseChatItem(const QJsonObject& obj) {
    QVariantMap item;
    item["id"] = obj["id"].toString();
    item["type"] = obj["type"].toString();
    item["name"] = obj["name"].toString();
    item["avatar_url"] = obj["avatar_url"].toString();
    item["is_online"] = obj["is_online"].toBool(false);
    item["unread_count"] = obj["unread_count"].toVariant();
    item["last_message_at"] = obj["last_message_at"].isString() ? obj["last_message_at"].toString() : QString();
    item["last_read_at"] = obj["last_read_at"].isString() ? obj["last_read_at"].toString() : QString();
    item["updated_at"] = obj["updated_at"].isString() ? obj["updated_at"].toString() : QString();

    const QString chatId = obj["id"].toString();

    if (obj.contains("other_user") && obj["other_user"].isObject()) {
        QJsonObject userObj = obj["other_user"].toObject();
        QVariantMap otherUser;
        otherUser["id"] = userObj["id"].toString();
        otherUser["email"] = userObj["email"].toString();
        otherUser["username"] = userObj["username"].toString();
        otherUser["display_name"] = userObj["display_name"].toString();
        otherUser["avatar_url"] = userObj["avatar_url"].toString();
        otherUser["is_online"] = userObj["is_online"].toBool(false);
        item["other_user"] = otherUser;

        // Remember the other participant's public key so we can encrypt to /
        // decrypt from this chat.
        QString pub = userObj["public_key"].toString();
        if (!pub.isEmpty()) m_chatOtherPub.insert(chatId, pub);
    }

    if (obj.contains("last_message") && obj["last_message"].isObject()) {
        QJsonObject msgObj = obj["last_message"].toObject();
        QVariantMap lastMessage;
        lastMessage["content"] = decryptMessage(chatId, msgObj["content"].toString(),
                                                msgObj["encrypted"].toBool(false));
        lastMessage["sender_id"] = msgObj["sender_id"].toString();
        lastMessage["content_type"] = msgObj["content_type"].toString("text");
        lastMessage["created_at"] = msgObj["created_at"].toString();
        item["last_message"] = lastMessage;
    }

    return item;
}