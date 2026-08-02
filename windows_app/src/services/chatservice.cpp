#include "chatservice.h"
#include "../crypto/encryption.h"
#include "../utils/credentialmanager.h"
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QVariantMap>
#include <QDebug>
#include <QHttpMultiPart>
#include <QHttpPart>
#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QStandardPaths>

ChatService::ChatService(AuthService* authService, GroupService* groupService, QObject* parent)
    : QObject(parent)
    , m_authService(authService)
    , m_groupService(groupService)
{
    setupNetworkManager();
    m_messageCache = new MessageCache(this);
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

    // Show the cached chat list immediately - without this, an unreachable
    // backend at launch wiped the sidebar to empty before the user could
    // ever get into a chat to see ITS cached messages either.
    const QList<QJsonObject> cachedChats = m_messageCache->loadChats();
    if (!cachedChats.isEmpty()) {
        QVariantList cachedResult;
        for (const QJsonObject& obj : cachedChats) {
            cachedResult.append(parseChatItem(obj));
        }
        emit chatsFetched(cachedResult);
    }

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

void ChatService::onChatsReplyFinished(QNetworkReply* reply) {
    // QNetworkAccessManager::finished fires for *every* request made through
    // m_networkManager (fetchMessages, sendMessage, markAsRead, etc. all
    // share it) - not just fetchChats. Without this check, an unrelated
    // request completing mid-flight would get read here as if it were the
    // chats response (and the real chats reply, when it later finishes,
    // would find m_currentReply already cleared). Each of those other
    // requests already manages its own reply's deleteLater() independently,
    // so simply ignoring anything that isn't ours is enough.
    if (reply != m_currentReply) return;

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
    QList<QJsonObject> toCache;
    for (const QJsonValue& val : chatsArray) {
        if (!val.isObject()) continue;
        QJsonObject obj = val.toObject();
        result.append(parseChatItem(obj));
        toCache.append(obj);
    }

    m_messageCache->saveChats(toCache);

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
    // A group message's decryptMessage() needs that sender's Sender Key,
    // which (unlike a direct chat's pairwise key, learned for free from the
    // chat list) has to be explicitly fetched and unwrapped first - so for a
    // group, do that before decrypting anything, cached copy included.
    // This costs the "instant cached paint" a direct chat gets - acceptable
    // since it's one extra local-network round trip, not a real delay, and
    // getting it wrong would mean showing stale placeholders that never
    // resolve until the next fetchMessages() call.
    bool isGroup = m_chatType.value(chatId) == QStringLiteral("group");

    auto doFetch = [this, chatId]() {
        // Show cached history immediately - works offline, and avoids a blank
        // chat while waiting on the network even when we're online.
        const QList<MessageCache::Entry> cached = m_messageCache->loadMessages(chatId);
        if (!cached.isEmpty()) {
            QVariantList cachedResult;
            for (const auto& e : cached) {
                bool hasFile = !e.fileUrl.isEmpty()
                               && (e.fileType == QStringLiteral("audio") || e.fileType == QStringLiteral("image")
                                   || e.fileType == QStringLiteral("file"));
                QVariantMap item;
                item["id"] = e.id;
                item["senderId"] = e.senderId;
                item["senderName"] = e.senderName;
                // A file/voice message has no text body - decrypting empty
                // content would just yield the "encrypted" placeholder for no reason.
                item["content"] = hasFile ? QString()
                                           : decryptMessage(chatId, e.content, e.encrypted, e.senderId, e.keyVersion);
                item["createdAt"] = e.createdAt;
                item["readAt"] = e.readAt;
                item["fileUrl"] = hasFile ? e.fileUrl : QString();
                item["fileType"] = e.fileType;
                item["fileName"] = e.fileName;
                item["fileSize"] = e.fileSize;
                item["durationMs"] = e.durationMs;
                item["voiceEncrypted"] = hasFile && e.encrypted;
                cachedResult.append(item);
            }
            emit messagesFetched(chatId, cachedResult);
        }

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
            QList<MessageCache::Entry> toCache;
            for (int i = messagesArray.size() - 1; i >= 0; --i) {
                if (!messagesArray[i].isObject()) continue;
                QJsonObject m = messagesArray[i].toObject();
                QJsonObject sender = m["sender"].toObject();

                QString senderName = sender["display_name"].toString().isEmpty()
                                          ? sender["username"].toString()
                                          : sender["display_name"].toString();
                QString rawContent = m["content"].toString();
                bool encrypted = m["encrypted"].toBool(false);
                QString readAt = m["read_at"].isString() ? m["read_at"].toString() : QString();
                QString createdAt = m["created_at"].toString();
                QString fileUrl = m["file_url"].toString();
                QString fileType = m["file_type"].toString();
                QString fileName = m["file_name"].toString();
                qint64 fileSize = static_cast<qint64>(m["file_size"].toDouble(0));
                qint64 durationMs = static_cast<qint64>(m["duration_ms"].toDouble(0));
                QString senderId = m["sender_id"].toString();
                int keyVersion = m["key_version"].toInt();
                bool hasFile = !fileUrl.isEmpty()
                               && (fileType == QStringLiteral("audio") || fileType == QStringLiteral("image")
                                   || fileType == QStringLiteral("file"));

                QVariantMap item;
                item["id"] = m["id"].toString();
                item["senderId"] = senderId;
                item["senderName"] = senderName;
                item["content"] = hasFile ? QString()
                                           : decryptMessage(chatId, rawContent, encrypted, senderId, keyVersion);
                item["createdAt"] = createdAt;
                item["readAt"] = readAt;
                item["fileUrl"] = hasFile ? fileUrl : QString();
                item["fileType"] = fileType;
                item["fileName"] = fileName;
                item["fileSize"] = fileSize;
                item["durationMs"] = durationMs;
                item["voiceEncrypted"] = hasFile && encrypted;
                result.append(item);

                MessageCache::Entry cacheEntry;
                cacheEntry.id = m["id"].toString();
                cacheEntry.senderId = senderId;
                cacheEntry.senderName = senderName;
                cacheEntry.content = rawContent;
                cacheEntry.encrypted = encrypted;
                cacheEntry.readAt = readAt;
                cacheEntry.createdAt = createdAt;
                cacheEntry.fileUrl = fileUrl;
                cacheEntry.fileType = fileType;
                cacheEntry.fileName = fileName;
                cacheEntry.fileSize = fileSize;
                cacheEntry.durationMs = durationMs;
                cacheEntry.keyVersion = keyVersion;
                toCache.append(cacheEntry);
            }

            m_messageCache->saveMessages(chatId, toCache);

            emit messagesFetched(chatId, result);
        });
    };

    if (isGroup) {
        fetchGroupSenderKeys(chatId, doFetch);
    } else {
        doFetch();
    }
}

void ChatService::sendMessage(const QString& chatId, const QString& text, const QString& chatType) {
    if (chatType == QStringLiteral("group")) {
        sendGroupTextMessage(chatId, text);
        return;
    }

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
    // (they've never published one - e.g. still on an old build - or this is
    // a group chat, where the pairwise scheme doesn't apply), fall back to
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
    body["chat_type"] = chatType;
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

QString ChatService::decryptMessage(const QString& chatId, const QString& content, bool encrypted,
                                     const QString& senderId, int keyVersion) const {
    if (!encrypted) return content;

    if (m_chatType.value(chatId) == QStringLiteral("group")) {
        QString key = groupSenderKeyFor(chatId, senderId, keyVersion);
        if (key.isEmpty()) {
            return QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message"); // 🔒 - don't have this sender's key yet
        }
        QByteArray cipher = QByteArray::fromHex(content.toUtf8());
        QByteArray plain = Encryption::secretBoxDecryptBytes(cipher, key);
        if (plain.isEmpty()) {
            return QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message");
        }
        return QString::fromUtf8(plain);
    }

    QString otherPub = m_chatOtherPub.value(chatId);
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (otherPub.isEmpty() || myPriv.isEmpty()) {
        return QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message"); // 🔒
    }

    QString plain = Encryption::boxDecrypt(content, otherPub, myPriv);
    if (plain.isEmpty()) {
        return QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message");
    }
    return plain;
}

QString ChatService::takePendingSecurityNotice(const QString& chatId) {
    return m_pendingSecurityNotices.take(chatId);
}

bool ChatService::hasKeyForChat(const QString& chatId) const {
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    return !m_chatOtherPub.value(chatId).isEmpty() && !myPriv.isEmpty();
}

bool ChatService::hasGroupSenderKey(const QString& chatId) const {
    auto it = m_mySenderKeys.find(chatId);
    if (it == m_mySenderKeys.end() || it->keyHex.isEmpty()) return false;
    return it->version >= m_groupKeyEpoch.value(chatId, 0);
}

QString ChatService::groupSenderKeyFor(const QString& chatId, const QString& senderId, int keyVersion) const {
    QString myId = m_authService ? m_authService->currentUserId() : QString();
    if (!myId.isEmpty() && senderId == myId) {
        auto it = m_mySenderKeys.find(chatId);
        if (it != m_mySenderKeys.end() && it->version == keyVersion) return it->keyHex;
        return QString();
    }
    return m_groupOtherKeys.value(QStringLiteral("%1|%2|%3").arg(chatId, senderId).arg(keyVersion));
}

void ChatService::persistMySenderKey(const QString& chatId, int version, const QString& keyHex) {
    CredentialManager::instance().saveToken(QStringLiteral("group_senderkey_%1").arg(chatId),
                                             QString::number(version) + ":" + keyHex);
}

void ChatService::loadMySenderKeyFromDisk(const QString& chatId) {
    if (m_mySenderKeys.contains(chatId)) return; // already loaded this run
    QString stored = CredentialManager::instance().getToken(QStringLiteral("group_senderkey_%1").arg(chatId));
    if (stored.isEmpty()) return;
    int sep = stored.indexOf(':');
    if (sep <= 0) return;
    bool ok = false;
    int version = stored.left(sep).toInt(&ok);
    if (!ok) return;
    SenderKeyState state;
    state.version = version;
    state.keyHex = stored.mid(sep + 1);
    m_mySenderKeys.insert(chatId, state);
}

void ChatService::ensureGroupSenderKeyReady(const QString& chatId, std::function<void()> onReady) {
    loadMySenderKeyFromDisk(chatId);

    int serverEpoch = m_groupKeyEpoch.value(chatId, 0);
    auto existing = m_mySenderKeys.find(chatId);
    if (existing != m_mySenderKeys.end() && !existing->keyHex.isEmpty() && existing->version >= serverEpoch) {
        onReady();
        return;
    }

    if (!m_groupService || !m_authService) {
        onReady();
        return;
    }

    // Fetch fresh group info (current members + the authoritative key_epoch)
    // before generating a new key - membership may have changed since the
    // cached epoch was learned, and generating against a stale member list
    // would leave a just-added member without a copy.
    auto conn = std::make_shared<QMetaObject::Connection>();
    *conn = connect(m_groupService, &GroupService::groupInfoFetched, this,
        [this, chatId, onReady, conn](const QVariantMap& group) {
            if (group.value("id").toString() != chatId) return;
            QObject::disconnect(*conn);

            int epoch = group.value("keyEpoch").toInt();
            QString newKey = Encryption::secretBoxGenerateKey();
            if (newKey.isEmpty()) {
                onReady();
                return;
            }

            QString myId = m_authService->currentUserId();
            QString myPriv = m_authService->e2eePrivateKey();
            QByteArray keyBytes = QByteArray::fromHex(newKey.toUtf8());

            QJsonArray recipients;
            const QVariantList members = group.value("members").toList();
            for (const QVariant& mv : members) {
                QVariantMap member = mv.toMap();
                QString uid = member.value("id").toString();
                if (uid.isEmpty() || uid == myId) continue;
                QString pub = member.value("publicKey").toString();
                // A member with no published key yet simply won't get this
                // version - they'll be included next time the key rotates
                // (e.g. the next membership change) once they have one.
                if (pub.isEmpty()) continue;
                QByteArray encKey = Encryption::boxEncryptBytes(keyBytes, pub, myPriv);
                if (encKey.isEmpty()) continue;
                QJsonObject r;
                r["user_id"] = uid;
                r["encrypted_key"] = QString::fromUtf8(encKey.toHex());
                recipients.append(r);
            }

            m_mySenderKeys.insert(chatId, SenderKeyState{epoch, newKey});
            m_groupKeyEpoch.insert(chatId, epoch);
            persistMySenderKey(chatId, epoch, newKey);

            if (recipients.isEmpty()) {
                // Nothing to distribute to yet (solo group, or no member has
                // published a key) - the key is adopted locally so sending
                // is consistent; nobody can read it until this gets
                // redistributed on a future rotation.
                onReady();
                return;
            }

            QString authToken = buildAuthHeader();
            if (authToken.isEmpty()) {
                onReady();
                return;
            }

            QJsonObject body;
            body["key_version"] = epoch;
            body["recipients"] = recipients;

            QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/sender-key");
            QNetworkRequest request(url);
            request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
            request.setRawHeader("Authorization", authToken.toUtf8());

            QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
            connect(reply, &QNetworkReply::finished, this, [reply, onReady]() {
                reply->deleteLater();
                // Best-effort either way: the key is already adopted locally
                // and the caller is waiting to send - a failed publish just
                // means recipients won't have this version yet.
                onReady();
            });
        });
    m_groupService->getGroupInfo(chatId);
}

void ChatService::fetchGroupSenderKeys(const QString& chatId, std::function<void()> onDone) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty() || !m_authService) {
        onDone();
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/sender-keys");
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, onDone]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            onDone();
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        QJsonArray arr = root["data"].toArray();
        QString myPriv = m_authService->e2eePrivateKey();

        for (const QJsonValue& v : arr) {
            QJsonObject entry = v.toObject();
            QString senderId = entry["sender_id"].toString();
            QString senderPub = entry["sender_public_key"].toString();
            int version = entry["key_version"].toInt();
            QString cacheKey = QStringLiteral("%1|%2|%3").arg(chatId, senderId).arg(version);
            if (m_groupOtherKeys.contains(cacheKey)) continue; // already have it
            if (senderPub.isEmpty() || myPriv.isEmpty()) continue;

            QByteArray encKeyBytes = QByteArray::fromHex(entry["encrypted_key"].toString().toUtf8());
            QByteArray keyBytes = Encryption::boxDecryptBytes(encKeyBytes, senderPub, myPriv);
            if (keyBytes.isEmpty()) continue; // corrupt/foreign entry - skip, not fatal

            m_groupOtherKeys.insert(cacheKey, QString::fromUtf8(keyBytes.toHex()));
        }
        onDone();
    });
}

void ChatService::sendGroupTextMessage(const QString& chatId, const QString& text) {
    ensureGroupSenderKeyReady(chatId, [this, chatId, text]() {
        QString authToken = buildAuthHeader();
        if (authToken.isEmpty()) {
            emit messageError("Not authenticated. Please login first.");
            return;
        }

        QString outContent = text;
        bool encrypted = false;
        int keyVersion = 0;
        auto it = m_mySenderKeys.find(chatId);
        if (it != m_mySenderKeys.end() && !it->keyHex.isEmpty()) {
            QByteArray cipher = Encryption::secretBoxEncryptBytes(text.toUtf8(), it->keyHex);
            if (!cipher.isEmpty()) {
                outContent = QString::fromUtf8(cipher.toHex());
                encrypted = true;
                keyVersion = it->version;
            }
        }

        QUrl url(Config::apiBaseUrl() + "/messages");
        QNetworkRequest request(url);
        request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        request.setRawHeader("Authorization", authToken.toUtf8());

        QJsonObject body;
        body["chat_id"] = chatId;
        body["chat_type"] = "group";
        body["content"] = outContent;
        body["encrypted"] = encrypted;
        body["key_version"] = keyVersion;

        QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
        connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
            reply->deleteLater();
            if (reply->error() != QNetworkReply::NoError) {
                emit messageError(reply->errorString());
                return;
            }
            QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
            if (root.contains("error")) {
                emit messageError(root["error"].toString());
                return;
            }
            QJsonObject data = root["data"].toObject();
            QVariantMap item;
            item["id"] = data["id"].toString();
            item["senderId"] = data["sender_id"].toString();
            item["content"] = decryptMessage(chatId, data["content"].toString(), data["encrypted"].toBool(false),
                                              data["sender_id"].toString(), data["key_version"].toInt());
            item["createdAt"] = data["created_at"].toString();
            emit messageSent(chatId, item);
        });
    });
}

QByteArray ChatService::encryptBytesForChat(const QString& chatId, const QByteArray& plain) const {
    QString otherPub = m_chatOtherPub.value(chatId);
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (otherPub.isEmpty() || myPriv.isEmpty()) return QByteArray();
    return Encryption::boxEncryptBytes(plain, otherPub, myPriv);
}

QByteArray ChatService::decryptBytesForChat(const QString& chatId, const QByteArray& payload) const {
    QString otherPub = m_chatOtherPub.value(chatId);
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (otherPub.isEmpty() || myPriv.isEmpty()) return QByteArray();
    return Encryption::boxDecryptBytes(payload, otherPub, myPriv);
}

void ChatService::sendVoiceNote(const QString& chatId, const QString& chatType,
                                 const QString& localFilePath, qint64 durationMs) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit voiceUploadError("Not authenticated. Please login first.");
        QFile::remove(localFilePath);
        return;
    }

    QFile file(localFilePath);
    if (!file.open(QIODevice::ReadOnly)) {
        emit voiceUploadError("Could not read the recorded voice note");
        return;
    }
    QByteArray raw = file.readAll();
    file.close();
    // The temp file's only purpose was getting these bytes into memory - drop
    // it now regardless of what happens to the upload below.
    QFile::remove(localFilePath);

    bool encrypted = hasKeyForChat(chatId);
    QByteArray payload = encrypted ? encryptBytesForChat(chatId, raw) : raw;
    if (encrypted && payload.isEmpty()) {
        // Encryption unexpectedly failed even though hasKeyForChat() said we
        // could - fall back to plaintext rather than silently dropping the note.
        payload = raw;
        encrypted = false;
    }

    auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart part;
    part.setHeader(QNetworkRequest::ContentTypeHeader, "application/octet-stream");
    part.setHeader(QNetworkRequest::ContentDispositionHeader,
                    QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"voice.bin\"")));
    part.setBody(payload);
    multiPart->append(part);

    QUrl url(Config::apiBaseUrl() + "/messages/voice");
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, multiPart);
    multiPart->setParent(reply);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, chatType, durationMs, encrypted]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit voiceUploadError(reply->errorString());
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        QString fileUrl = root["file_url"].toString();
        if (fileUrl.isEmpty()) {
            emit voiceUploadError("Server did not return a file URL");
            return;
        }
        sendVoiceMessage(chatId, chatType, fileUrl, durationMs, encrypted);
    });
}

void ChatService::preparePlayableVoice(const QString& chatId, const QString& messageId,
                                        const QString& fileUrl, bool encrypted) {
    QString cacheDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + "/voice";
    QDir().mkpath(cacheDir);
    QString localPath = cacheDir + "/" + messageId + ".m4a";

    if (QFile::exists(localPath)) {
        emit voiceReadyForPlayback(messageId, localPath);
        return;
    }

    QUrl url(Config::resolveServerUrl(fileUrl));
    QNetworkRequest request(url);
    // Unauthenticated on purpose: the server serves /uploads with no token
    // requirement (filenames are unguessable UUIDs), matching how the avatar
    // images already load.
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, messageId, localPath, encrypted]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit voicePlaybackError(messageId, reply->errorString());
            return;
        }
        QByteArray raw = reply->readAll();
        QByteArray playable = raw;
        if (encrypted) {
            playable = decryptBytesForChat(chatId, raw);
            if (playable.isEmpty()) {
                emit voicePlaybackError(messageId, "Could not decrypt voice note");
                return;
            }
        }
        QFile out(localPath);
        if (!out.open(QIODevice::WriteOnly)) {
            emit voicePlaybackError(messageId, "Could not save voice note locally");
            return;
        }
        out.write(playable);
        out.close();
        emit voiceReadyForPlayback(messageId, localPath);
    });
}

void ChatService::sendVoiceMessage(const QString& chatId, const QString& chatType,
                                    const QString& fileUrl, qint64 durationMs, bool encrypted) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = chatType;
    // The bubble renders from file_url, so content stays empty rather than a
    // bogus placeholder string a future/other client might try to display.
    body["content"] = "";
    body["content_type"] = "audio";
    body["encrypted"] = encrypted;
    body["file_url"] = fileUrl;
    body["file_type"] = "audio";
    body["duration_ms"] = durationMs;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit messageError(reply->errorString());
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        if (root.contains("error")) {
            emit messageError(root["error"].toString());
            return;
        }
        QJsonObject data = root["data"].toObject();
        QVariantMap item;
        item["id"] = data["id"].toString();
        item["senderId"] = data["sender_id"].toString();
        item["fileUrl"] = data["file_url"].toString();
        item["fileType"] = data["file_type"].toString();
        item["durationMs"] = data["duration_ms"].toVariant();
        item["encrypted"] = data["encrypted"].toBool(false);
        item["createdAt"] = data["created_at"].toString();
        emit voiceMessageSent(chatId, item);
    });
}

void ChatService::sendAttachment(const QString& chatId, const QString& chatType,
                                  const QString& localFileUrl, const QString& contentType) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit attachmentUploadError("Not authenticated. Please login first.");
        return;
    }

    QString localFilePath = QUrl(localFileUrl).isLocalFile() ? QUrl(localFileUrl).toLocalFile() : localFileUrl;
    QString fileName = QFileInfo(localFilePath).fileName();

    // Unlike a voice note's temp recording, this file is the user's own -
    // read it, but never delete it.
    QFile file(localFilePath);
    if (!file.open(QIODevice::ReadOnly)) {
        emit attachmentUploadError("Could not read the selected file");
        return;
    }
    QByteArray raw = file.readAll();
    file.close();

    bool encrypted = hasKeyForChat(chatId);
    QByteArray payload = encrypted ? encryptBytesForChat(chatId, raw) : raw;
    if (encrypted && payload.isEmpty()) {
        payload = raw;
        encrypted = false;
    }

    auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart part;
    part.setHeader(QNetworkRequest::ContentTypeHeader, "application/octet-stream");
    part.setHeader(QNetworkRequest::ContentDispositionHeader,
                    QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"attachment.bin\"")));
    part.setBody(payload);
    multiPart->append(part);

    QUrl url(Config::apiBaseUrl() + "/messages/attachment");
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, multiPart);
    multiPart->setParent(reply);
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, chatId, chatType, fileName, contentType, encrypted]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit attachmentUploadError(reply->errorString());
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        QString fileUrl = root["file_url"].toString();
        if (fileUrl.isEmpty()) {
            emit attachmentUploadError("Server did not return a file URL");
            return;
        }
        qint64 fileSize = static_cast<qint64>(root["file_size"].toDouble(0));
        sendAttachmentMessage(chatId, chatType, fileUrl, fileName, contentType, fileSize, encrypted);
    });
}

void ChatService::sendAttachmentMessage(const QString& chatId, const QString& chatType,
                                         const QString& fileUrl, const QString& fileName,
                                         const QString& contentType, qint64 fileSize, bool encrypted) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = chatType;
    body["content"] = "";
    body["content_type"] = contentType;
    body["encrypted"] = encrypted;
    body["file_url"] = fileUrl;
    body["file_type"] = contentType;
    body["file_name"] = fileName;
    body["file_size"] = fileSize;

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit messageError(reply->errorString());
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        if (root.contains("error")) {
            emit messageError(root["error"].toString());
            return;
        }
        QJsonObject data = root["data"].toObject();
        QVariantMap item;
        item["id"] = data["id"].toString();
        item["senderId"] = data["sender_id"].toString();
        item["fileUrl"] = data["file_url"].toString();
        item["fileType"] = data["file_type"].toString();
        item["fileName"] = data["file_name"].toString();
        item["fileSize"] = data["file_size"].toVariant();
        item["encrypted"] = data["encrypted"].toBool(false);
        item["createdAt"] = data["created_at"].toString();
        emit attachmentMessageSent(chatId, item);
    });
}

void ChatService::prepareAttachment(const QString& chatId, const QString& messageId,
                                     const QString& fileUrl, bool encrypted, const QString& fileName) {
    QString cacheDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + "/attachments";
    QDir().mkpath(cacheDir);
    // Keep the original extension (from the sender's filename) so the OS
    // recognizes the file type when opened, and so an Image element can
    // actually decode it - "messageId.bin" would defeat both.
    QString suffix = QFileInfo(fileName).suffix();
    QString localPath = cacheDir + "/" + messageId + (suffix.isEmpty() ? QString() : "." + suffix);

    if (QFile::exists(localPath)) {
        emit attachmentReady(messageId, localPath);
        return;
    }

    QUrl url(Config::resolveServerUrl(fileUrl));
    QNetworkRequest request(url);
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, messageId, localPath, encrypted]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit attachmentError(messageId, reply->errorString());
            return;
        }
        QByteArray raw = reply->readAll();
        QByteArray plain = raw;
        if (encrypted) {
            plain = decryptBytesForChat(chatId, raw);
            if (plain.isEmpty()) {
                emit attachmentError(messageId, "Could not decrypt attachment");
                return;
            }
        }
        QFile out(localPath);
        if (!out.open(QIODevice::WriteOnly)) {
            emit attachmentError(messageId, "Could not save attachment locally");
            return;
        }
        out.write(plain);
        out.close();
        emit attachmentReady(messageId, localPath);
    });
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

void ChatService::deleteChat(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + "/chats/" + chatId);
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->deleteResource(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit chatDeleteError(reply->errorString());
            return;
        }

        // Drop the local copy too. Left behind, its messages keep counting
        // towards Settings' storage figure and would repopulate the thread
        // from cache if the chat ever came back.
        if (m_messageCache) m_messageCache->deleteChat(chatId);

        m_chatOtherPub.remove(chatId);
        m_chatType.remove(chatId);
        m_groupKeyEpoch.remove(chatId);
        m_mySenderKeys.remove(chatId);
        m_pendingSecurityNotices.remove(chatId);

        emit chatDeleted(chatId);
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

    QString chatType = obj["type"].toString();
    if (!chatType.isEmpty()) m_chatType.insert(chatId, chatType);
    if (chatType == QStringLiteral("group")) {
        m_groupKeyEpoch.insert(chatId, obj["key_epoch"].toInt(0));
    }

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
        if (!pub.isEmpty()) {
            m_chatOtherPub.insert(chatId, pub);

            // WhatsApp-style security notice: a contact's public key only
            // ever changes if they reinstalled/reset the app (new keypair
            // generated) or someone is intercepting the connection - either
            // way the user should see it happened, the same reason WhatsApp
            // surfaces "security code changed" instead of silently
            // re-encrypting to the new key. Nothing is flagged the first
            // time a key is ever seen for this chat - only on a genuine change.
            QString knownKeyToken = QStringLiteral("known_pubkey_%1").arg(chatId);
            QString knownPub = CredentialManager::instance().getToken(knownKeyToken);
            if (!knownPub.isEmpty() && knownPub != pub) {
                QString name = userObj["display_name"].toString();
                if (name.isEmpty()) name = userObj["username"].toString();
                m_pendingSecurityNotices.insert(chatId, name);
                emit securityCodeChanged(chatId, name);
            }
            if (knownPub != pub) {
                CredentialManager::instance().saveToken(knownKeyToken, pub);
            }
        }
    }

    if (obj.contains("last_message") && obj["last_message"].isObject()) {
        QJsonObject msgObj = obj["last_message"].toObject();
        QString contentType = msgObj["content_type"].toString("text");
        QVariantMap lastMessage;
        // A voice/image/file message's "content" is empty (the payload lives
        // at file_url), so decrypting it would just yield the "encrypted"
        // placeholder - show a real preview label instead, same as any other
        // messaging app does for a non-text last message.
        lastMessage["content"] = contentType == QStringLiteral("audio")
            ? QString::fromUtf8("\xF0\x9F\x8E\xA4 Voice message") // 🎤
            : contentType == QStringLiteral("image")
            ? QString::fromUtf8("\xF0\x9F\x93\xB7 Photo") // 📷
            : contentType == QStringLiteral("file")
            ? QString::fromUtf8("\xF0\x9F\x93\x8E ") + (msgObj["file_name"].toString().isEmpty() ? QStringLiteral("File") : msgObj["file_name"].toString()) // 📎
            : decryptMessage(chatId, msgObj["content"].toString(), msgObj["encrypted"].toBool(false),
                              msgObj["sender_id"].toString(), msgObj["key_version"].toInt());
        lastMessage["sender_id"] = msgObj["sender_id"].toString();
        lastMessage["content_type"] = contentType;
        lastMessage["created_at"] = msgObj["created_at"].toString();
        item["last_message"] = lastMessage;
    }

    return item;
}