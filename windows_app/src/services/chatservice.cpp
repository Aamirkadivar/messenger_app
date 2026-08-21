#include "chatservice.h"
#include <QCryptographicHash>
#include <QStringList>
#include "../utils/videothumbnailer.h"
#include "../crypto/encryption.h"
#include "../crypto/mlsv2.h"
#include "../crypto/doubleratchet.h"
#include "../utils/credentialmanager.h"
#include "../utils/pairingqr.h"
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QMap>
#include <QVariantMap>
#include <QDateTime>
#include <QDebug>
#include <algorithm>
#include <QHttpMultiPart>
#include <QHttpPart>
#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>
#include <QDateTime>

namespace {
qint64 chatActivityMs(const QVariantMap& item) {
    QString s;
    const QVariant lm = item.value(QStringLiteral("last_message"));
    if (lm.canConvert<QVariantMap>())
        s = lm.toMap().value(QStringLiteral("created_at")).toString();
    if (s.isEmpty()) s = item.value(QStringLiteral("last_message_at")).toString();
    if (s.isEmpty()) s = item.value(QStringLiteral("updated_at")).toString();
    QDateTime dt = QDateTime::fromString(s, Qt::ISODateWithMs);
    if (!dt.isValid()) dt = QDateTime::fromString(s, Qt::ISODate);
    return dt.isValid() ? dt.toMSecsSinceEpoch() : 0;
}

void sortChatsByActivity(QVariantList& chats) {
    std::sort(chats.begin(), chats.end(), [](const QVariant& a, const QVariant& b) {
        return chatActivityMs(a.toMap()) > chatActivityMs(b.toMap());
    });
}
}

ChatService::ChatService(AuthService* authService, GroupService* groupService, QObject* parent)
    : QObject(parent)
    , m_authService(authService)
    , m_groupService(groupService)
{
    setupNetworkManager();
    m_messageCache = new MessageCache(this);
    m_mls2 = new MlsV2Engine(this);
    m_mls2->bind(m_networkManager,
                 [this](QNetworkRequest& r) { applyAuthHeaders(r); },
                 [this]() { return m_authService ? m_authService->currentUserId() : QString(); },
                 [this]() { return m_authService ? m_authService->getOrCreateDeviceId() : QString(); });
    setupForwardSignalHooks();
    if (m_authService) {
        connect(m_authService, &AuthService::vaultRatchetsUpdated, this, [this]() {
            m_dr.clear();
        });
    }
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

void ChatService::applyAuthHeaders(QNetworkRequest& request) const {
    const QString auth = buildAuthHeader();
    if (!auth.isEmpty()) {
        request.setRawHeader("Authorization", auth.toUtf8());
    }
    if (m_authService) {
        const QString deviceId = m_authService->getOrCreateDeviceId();
        if (!deviceId.isEmpty()) {
            request.setRawHeader("X-Device-Id", deviceId.toUtf8());
        }
    }
}

void ChatService::appendForwardFields(QJsonObject& body, bool isForwarded,
                                       const QString& forwardedFromName,
                                       const QString& forwardedFromMessageId) const {
    if (!isForwarded) return;
    body[QStringLiteral("is_forwarded")] = true;
    Q_UNUSED(forwardedFromName);
    body[QStringLiteral("forwarded_from_name")] = QString();
    if (!forwardedFromMessageId.isEmpty())
        body[QStringLiteral("forwarded_from_message_id")] = forwardedFromMessageId;
}

void ChatService::setPendingReplyToId(const QString& id) {
    if (m_pendingReplyToId == id) return;
    m_pendingReplyToId = id;
    emit pendingReplyToIdChanged();
}

QString ChatService::takePendingReplyToId() {
    if (m_pendingReplyToId.isEmpty()) return {};
    const QString id = m_pendingReplyToId;
    m_pendingReplyToId.clear();
    emit pendingReplyToIdChanged();
    return id;
}

void ChatService::appendReplyField(QJsonObject& body, const QString& replyToId) const {
    if (replyToId.isEmpty()) return;
    body[QStringLiteral("reply_to_id")] = replyToId;
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
        sortChatsByActivity(cachedResult);
        emit chatsFetched(cachedResult);
    }

    m_isLoading = true;
    emit isLoadingChanged();

    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        // Cache (if any) was already pushed above. A second fetch often
        // races restoreSession(); shouting at the user is wrong.
        m_isLoading = false;
        emit isLoadingChanged();
        return;
    }

    QString baseUrl = Config::apiBaseUrl();
    QUrl url(baseUrl + "/chats");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

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

    sortChatsByActivity(result);
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
    applyAuthHeaders(request);

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
    applyAuthHeaders(request);

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
                bool hasFile = e.fileType == QStringLiteral("audio") || e.fileType == QStringLiteral("image")
                               || e.fileType == QStringLiteral("file")
                               || e.fileType == QStringLiteral("video_note");
                QVariantMap item;
                item["id"] = e.id;
                item["senderId"] = e.senderId;
                item["senderName"] = e.senderName;
                // A file/voice message has no text body - decrypting empty
                // content would just yield the "encrypted" placeholder for no reason.
                item["content"] = hasFile ? QString()
                                           : decryptMessage(chatId, e.content, e.encrypted, e.senderId, e.keyVersion, e.encryptionVersion, e.senderDeviceId);
                item["createdAt"] = e.createdAt;
                item["readAt"] = e.readAt;
                item["fileUrl"] = hasFile ? e.fileUrl : QString();
                item["fileType"] = e.fileType;
                item["fileName"] = e.fileName;
                item["fileSize"] = e.fileSize;
                item["durationMs"] = e.durationMs;
                item["voiceEncrypted"] = hasFile && e.encrypted;
                item["keyVersion"] = e.keyVersion;
                item["encryptionVersion"] = e.encryptionVersion;
                item["isForwarded"] = e.isForwarded;
                item["forwardedFromName"] = e.forwardedFromName;
                item["replyToId"] = e.replyToId;
                if (e.encrypted && !e.content.isEmpty())
                    applyEnvelopeMeta(item, openCipher(chatId, e.content, e.encrypted, e.senderId, e.keyVersion, e.encryptionVersion, e.senderDeviceId));
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
        applyAuthHeaders(request);

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
                QString thumbnailUrl = m["thumbnail_url"].toString();
                QString senderId = m["sender_id"].toString();
                int keyVersion = m["key_version"].toInt();
                int encryptionVersion = m["encryption_version"].toInt(1);
                if (encryptionVersion <= 0) encryptionVersion = 1;
                bool hasFile = fileType == QStringLiteral("audio") || fileType == QStringLiteral("image")
                               || fileType == QStringLiteral("file")
                               || fileType == QStringLiteral("video_note");

                QVariantMap item;
                item["id"] = m["id"].toString();
                item["senderId"] = senderId;
                item["senderName"] = senderName;
                // Both the ciphertext and a best-effort decryption are handed
                // over. QML decrypts from rawContent at display time so a row
                // rendered before the keys arrived fixes itself; content is
                // only a fallback for callers that do not.
                QString display = hasFile ? QString()
                                           : decryptMessage(chatId, rawContent, encrypted, senderId, keyVersion, encryptionVersion,
                                                            m[QStringLiteral("sender_device_id")].toString());
                item["content"] = display;
                item["rawContent"] = hasFile ? rawContent : rawContent;
                item["encrypted"] = encrypted;
                item["keyVersion"] = keyVersion;
                item["encryptionVersion"] = encryptionVersion;
                item["senderDeviceId"] = m[QStringLiteral("sender_device_id")].toString();
                item["createdAt"] = createdAt;
                item["readAt"] = readAt;
                item["fileUrl"] = hasFile ? fileUrl : QString();
                item["fileType"] = fileType;
                item["fileName"] = fileName;
                item["fileSize"] = fileSize;
                item["durationMs"] = durationMs;
                item["thumbnailUrl"] = thumbnailUrl;
                item["voiceEncrypted"] = hasFile && encrypted;
                item["isForwarded"] = m[QStringLiteral("is_forwarded")].toBool(false);
                item["forwardedFromName"] = m[QStringLiteral("forwarded_from_name")].toString();
                item["forwardedFromMessageId"] = m[QStringLiteral("forwarded_from_message_id")].toString();
                item["replyToId"] = m[QStringLiteral("reply_to_id")].toString();
                if (encrypted && !rawContent.isEmpty()) {
                    applyEnvelopeMeta(item, openCipher(chatId, rawContent, encrypted, senderId, keyVersion, encryptionVersion,
                                                       item["senderDeviceId"].toString()));
                }
                result.append(item);

                MessageCache::Entry cacheEntry;
                cacheEntry.id = m["id"].toString();
                cacheEntry.senderId = senderId;
                cacheEntry.senderName = senderName;
                // MLS (and ratchet) message keys are consumed on first open.
                // Store plaintext when we have it so a reopen does not have to
                // unprotect again — the sender's other device depends on this.
                const QString placeholder = QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message");
                if (encrypted && !hasFile && !display.isEmpty() && display != placeholder) {
                    cacheEntry.content = display;
                    cacheEntry.encrypted = false;
                } else {
                    cacheEntry.content = rawContent;
                    cacheEntry.encrypted = encrypted;
                }
                cacheEntry.readAt = readAt;
                cacheEntry.createdAt = createdAt;
                cacheEntry.fileUrl = item.value(QStringLiteral("fileUrl")).toString();
                cacheEntry.fileType = fileType;
                cacheEntry.fileName = fileName;
                cacheEntry.fileSize = fileSize;
                cacheEntry.durationMs = durationMs;
                cacheEntry.keyVersion = keyVersion;
                cacheEntry.encryptionVersion = encryptionVersion;
                // Required for v3/v4: the ratchet session is keyed by
                // "chatId|senderDeviceId", so a cached message without it
                // decrypts against the wrong session on reopen.
                cacheEntry.senderDeviceId = item["senderDeviceId"].toString();
                cacheEntry.isForwarded = item["isForwarded"].toBool();
                cacheEntry.forwardedFromName = item["forwardedFromName"].toString();
                cacheEntry.replyToId = item["replyToId"].toString();
                toCache.append(cacheEntry);
            }

            m_messageCache->saveMessages(chatId, toCache);

            emit messagesFetched(chatId, result);
        });
    };

    if (isGroup) {
        if (m_mls2 && m_mls2->isReady()) {
            // Join before decrypting history. The previous v1 path ran this
            // off the critical path, so the first paint was always ciphertext.
            mlsV2EnsureGroup(chatId);
        }
#ifdef HAVE_MLSPP
        else {
        mlsEnsureGroup(chatId);
        }
#endif
        fetchGroupSenderKeys(chatId, doFetch);
    } else {
        doFetch();
    }
}

void ChatService::sendMessage(const QString& chatId, const QString& text, const QString& chatType,
                               bool isForwarded, const QString& forwardedFromName,
                               const QString& forwardedFromMessageId) {
    if (chatType == QStringLiteral("group")) {
        sendGroupTextMessage(chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId);
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
    applyAuthHeaders(request);

    // Encrypt end-to-end when we know the recipient's public key. If we don't
    // (they've never published one - e.g. still on an old build - or this is
    // a group chat, where the pairwise scheme doesn't apply), fall back to
    // plaintext so messaging keeps working during rollout.
    QString otherPub = m_chatOtherPub.value(chatId);
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    // Distinguish the two failures. Conflating them sent users looking at the
    // other person's account when the missing key was their own - the usual
    // cause is signing in somewhere new (e.g. by QR) without unlocking the
    // E2EE vault on that device yet.
    if (myPriv.isEmpty()) {
        emit messageError("Encryption isn't set up on this device yet. "
                          "Unlock your encrypted messages with your password "
                          "or recovery key to send.");
        return;
    }
    if (otherPub.isEmpty()) {
        emit messageError("Cannot send: this contact hasn't published an "
                          "encryption key yet.");
        return;
    }
    const QString replyToId = takePendingReplyToId();
    refreshChatDevices(chatId, [this, request, chatId, chatType, text, otherPub, myPriv,
                                isForwarded, forwardedFromName, forwardedFromMessageId, replyToId]() mutable {
    QString outContent;
    bool encrypted = true;
    int encryptionVersion = 4;
    // v4 X3DH-lite is the default; v3 is retired for new sends (glare-broken).
    QByteArray v4 = sealDirectV4(chatId, Encryption::wrapEnvelope(text.toUtf8(), QString(), forwardedFromName));
    QString cipher;
    if (!v4.isEmpty()) {
        cipher = QString::fromLatin1(v4.toHex());
    } else {
        QByteArray inner = Encryption::wrapEnvelope(text.toUtf8(), QString(), forwardedFromName);
        cipher = QString::fromLatin1(Encryption::boxEncryptBytesEphemeral(inner, otherPub).toHex());
        encryptionVersion = 2;
    }
    if (cipher.isEmpty()) {
        QByteArray inner = Encryption::wrapEnvelope(text.toUtf8(), QString(), forwardedFromName);
        cipher = QString::fromLatin1(Encryption::boxEncryptBytes(inner, otherPub, myPriv).toHex());
        encryptionVersion = 1;
    }
    if (cipher.isEmpty()) {
        emit messageError("Cannot send: encryption failed");
        return;
    }
    outContent = cipher;

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = chatType;
    body["content"] = outContent;
    body["encrypted"] = encrypted;
    body["encryption_version"] = encryptionVersion;
    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);
    appendReplyField(body, replyToId);

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, text, forwardedFromName, replyToId]() {
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
        item["content"] = text;
        item["createdAt"] = data["created_at"].toString();
        item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);
        item["forwardedFromName"] = forwardedFromName;
        item["replyToId"] = data[QStringLiteral("reply_to_id")].toString();
        if (item["replyToId"].toString().isEmpty()) item["replyToId"] = replyToId;

        // Cache our OWN message as plaintext. A sender cannot decrypt its own
        // v2/v3/v4 ciphertext - the sending chain does not retain the message
        // keys it just used - so if we only ever cached the server's copy, our
        // own messages turned into "Encrypted message" the moment the chat was
        // reopened and re-read from cache. Storing the plaintext we already
        // have is what every client does for its own sends.
        if (m_messageCache) {
            MessageCache::Entry sentEntry;
            sentEntry.id = item["id"].toString();
            sentEntry.senderId = item["senderId"].toString();
            sentEntry.content = text;          // plaintext
            sentEntry.encrypted = false;       // already open; do not re-decrypt
            sentEntry.createdAt = item["createdAt"].toString();
            sentEntry.isForwarded = item["isForwarded"].toBool();
            sentEntry.forwardedFromName = forwardedFromName;
            sentEntry.replyToId = item["replyToId"].toString();
            sentEntry.encryptionVersion = 1;   // stored open, no scheme needed
            m_messageCache->saveMessages(chatId, { sentEntry });
        }

        emit messageSent(chatId, item);
    });
    });
}

bool ChatService::loadDr(const QString& chatId, DoubleRatchet::State& st, bool sending) const {
    if (m_dr.contains(chatId)) {
        st = m_dr.value(chatId);
        return true;
    }
    const QString json = CredentialManager::instance().getToken(QStringLiteral("dr3_%1").arg(chatId));
    if (!json.isEmpty() && DoubleRatchet::State::fromJson(json, st)) {
        m_dr.insert(chatId, st);
        return true;
    }
    const QByteArray other = QByteArray::fromHex(alicePeerPubForSession(chatId).toUtf8());
    const QByteArray myPriv = QByteArray::fromHex(m_authService ? m_authService->e2eePrivateKey().toUtf8() : QByteArray());
    const QByteArray myPub = QByteArray::fromHex(m_authService ? m_authService->e2eePublicKey().toUtf8() : QByteArray());
    if (sending) {
        if (!DoubleRatchet::initAlice(other, st)) return false;
    } else {
        if (!DoubleRatchet::initBob(myPub, myPriv, st)) return false;
    }
    m_dr.insert(chatId, st);
    return true;
}

void ChatService::saveDr(const QString& chatId, DoubleRatchet::State& st) const {
    st.seq++;
    m_dr.insert(chatId, st);
    CredentialManager::instance().saveToken(QStringLiteral("dr3_%1").arg(chatId), st.toJson());
    if (m_authService) m_authService->refreshVaultContents();
}

void ChatService::refreshChatDevices(const QString& chatId, std::function<void()> done) {
    if (chatId.isEmpty() || !m_networkManager) {
        if (done) done();
        return;
    }
    QUrl url(Config::apiBaseUrl() + QStringLiteral("/e2ee/chats/%1/devices").arg(chatId));
    QNetworkRequest req(url);
    applyAuthHeaders(req);
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, done]() {
        reply->deleteLater();
        if (reply->error() == QNetworkReply::NoError) {
            const QJsonArray arr = QJsonDocument::fromJson(reply->readAll()).object().value(QStringLiteral("devices")).toArray();
            QList<QPair<QString, QString>> devices;
            devices.reserve(arr.size());
            for (const QJsonValue& v : arr) {
                const QJsonObject o = v.toObject();
                const QString uid = o.value(QStringLiteral("user_id")).toString();
                const QString did = o.value(QStringLiteral("device_id")).toString();
                if (!did.isEmpty()) devices.append(qMakePair(uid, did));
            }
            m_chatDevices.insert(chatId, devices);
        }
        if (done) done();
    });
}

QString ChatService::alicePeerPubForSession(const QString& sessionKey) const {
    const QString chatId = sessionKey.section(QLatin1Char('|'), 0, 0);
    const QString did = sessionKey.contains(QLatin1Char('|')) ? sessionKey.section(QLatin1Char('|'), 1) : QString();
    const QString me = m_authService ? m_authService->currentUserId() : QString();
    if (!did.isEmpty()) {
        const auto devices = m_chatDevices.value(chatId);
        for (const auto& p : devices) {
            if (p.second != did) continue;
            if (!me.isEmpty() && p.first == me && m_authService)
                return m_authService->e2eePublicKey();
            break;
        }
    }
    return m_chatOtherPub.value(chatId);
}

QByteArray ChatService::sealDirectV3(const QString& chatId, const QByteArray& inner) const {
    const QString me = m_authService ? m_authService->currentUserId() : QString();
    const QString myPub = m_authService ? m_authService->e2eePublicKey() : QString();
    const QString otherPub = m_chatOtherPub.value(chatId);
    QMap<QString, QString> targets;
    const auto devices = m_chatDevices.value(chatId);
    for (const auto& p : devices) {
        if (p.second.isEmpty()) continue;
        const QString pub = (p.first == me) ? myPub : otherPub;
        if (!pub.isEmpty()) targets.insert(p.second, pub);
    }
    if (targets.size() <= 1)
        return encryptDirectV3(chatId, inner);
    QList<QPair<QString, QByteArray>> parts;
    for (auto it = targets.constBegin(); it != targets.constEnd(); ++it) {
        const QByteArray blob = encryptDirectV3(chatId + QLatin1Char('|') + it.key(), inner);
        if (blob.isEmpty()) return {};
        parts.append(qMakePair(it.key(), blob));
    }
    return Encryption::wrapFanout(parts);
}

QByteArray ChatService::encryptDirectV3(const QString& chatId, const QByteArray& plain) const {
    DoubleRatchet::State st;
    if (!loadDr(chatId, st, true)) return {};
    if (st.cks.size() != 32) {
        const QByteArray other = QByteArray::fromHex(alicePeerPubForSession(chatId).toUtf8());
        if (!DoubleRatchet::initAlice(other, st)) return {};
    }
    const QByteArray out = DoubleRatchet::encrypt(st, plain);
    if (out.isEmpty()) return {};
    saveDr(chatId, st);
    return out;
}

QByteArray ChatService::decryptDirectV3(const QString& chatId, const QByteArray& payload) const {
    DoubleRatchet::State st;
    if (!loadDr(chatId, st, false)) return {};
    const QByteArray plain = DoubleRatchet::decrypt(st, payload);
    if (plain.isEmpty()) return {};
    saveDr(chatId, st);
    return plain;
}

void ChatService::mlsV2EnsureGroup(const QString& chatId) {
    if (!m_mls2) return;
    m_mls2->ensureGroup(chatId);
}

void ChatService::mlsV2OnCommit(const QString& chatId) {
    if (!m_mls2) return;
    m_mls2->processWelcomes();
    m_mls2->syncHandshakes(chatId);
}

void ChatService::mlsV2Bootstrap() {
    if (!m_mls2 || !m_mls2->isReady()) return;
    m_mls2->ensureKeyPackages();
    m_mls2->processWelcomes();
}

bool ChatService::mlsV2Ready() const {
    return m_mls2 && m_mls2->isReady();
}

#ifdef HAVE_MLSPP
// ---- MLS (RFC 9420) group messaging, encryption_version 5 ----
// A chat only uses MLS once it has group state here; otherwise the existing
// Sender Key path runs untouched. That keeps the cutover per-group rather than
// a flag day, so a half-migrated group can never emit ciphertext the other
// members cannot read.

bool ChatService::mlsHasGroup(const QString& chatId) const {
    return m_mlsGroups.contains(chatId) && m_mlsGroups.value(chatId) != nullptr;
}

QByteArray ChatService::mlsProtect(const QString& chatId, const QByteArray& plain) const {
    if (!mlsHasGroup(chatId)) return {};
    return MlsGroupCrypto::protect(m_mlsGroups.value(chatId), plain);
}

QByteArray ChatService::mlsUnprotect(const QString& chatId, const QByteArray& payload) const {
    if (!mlsHasGroup(chatId)) return {};
    return MlsGroupCrypto::unprotect(m_mlsGroups.value(chatId), payload);
}
#endif

#ifdef HAVE_MLSPP
// ---- MLS Delivery Service client ----
// The server is an untrusted relay: it stores single-use KeyPackages, enforces
// one accepted commit per epoch, and forwards Welcome/commit blobs. Every
// cryptographic step happens here.

static QString mlsRefHash(const QByteArray& kpWire) {
    return QString::fromLatin1(
        QCryptographicHash::hash(kpWire, QCryptographicHash::Sha256).toHex());
}

void ChatService::mlsPersistKeyPackage(const QString& refHash,
                                       MlsGroupCrypto::PublishedKeyPackage* kp,
                                       MlsGroupCrypto::Identity* id) const {
    QJsonObject o;
    o.insert(QStringLiteral("kp"), MlsGroupCrypto::encodePublishedKeyPackage(kp));
    o.insert(QStringLiteral("identity"), MlsGroupCrypto::encodeIdentity(id));
    CredentialManager::instance().saveToken(
        QStringLiteral("mls1_kp/%1").arg(refHash),
        QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact)));
}

void ChatService::mlsPersistGroupBundle(const QString& chatId,
                                        MlsGroupCrypto::Identity* id,
                                        const QByteArray& welcome,
                                        MlsGroupCrypto::PublishedKeyPackage* kp) const {
    QJsonObject o;
    o.insert(QStringLiteral("identity"), MlsGroupCrypto::encodeIdentity(id));
    if (!welcome.isEmpty())
        o.insert(QStringLiteral("welcome"), QString::fromLatin1(welcome.toBase64()));
    if (kp) o.insert(QStringLiteral("kp"), MlsGroupCrypto::encodePublishedKeyPackage(kp));
    CredentialManager::instance().saveToken(
        QStringLiteral("mls1_group/%1").arg(chatId),
        QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact)));
}

// MIGRATION RULE (v5 mlspp -> v6 OpenMLS), enforced here rather than at the
// call sites so it cannot be bypassed:
//
//   Once mls-core is usable on this device, v6 OWNS the KeyPackage namespace
//   and the legacy mlspp publisher must publish nothing.
//
// Both stacks POST to the same /e2ee/mls/keypackages with the same
// cipher_suite, so the server cannot tell them apart. A v6 peer that claims an
// mlspp package cannot parse it, the Add is rejected, and the device can never
// join - which is exactly what happened in production. main.cpp already gated
// the startup path; two ensure-group call sites did not, so the guard now lives
// in the publisher itself.
//
// A device where mls-core is genuinely unavailable keeps publishing v5, so
// legacy-only installs stay functional. Existing published packages are never
// touched.
void ChatService::mlsPublishKeyPackages(int count) {
    if (mlsV2Ready()) {
        qInfo() << "[mls] v6 owns the key package namespace; legacy publish skipped";
        return;
    }
    if (buildAuthHeader().isEmpty() || !m_authService) return;
    const QString userId = m_authService->currentUserId();
    const QString deviceId = m_authService->getOrCreateDeviceId();

    QJsonArray items;
    for (int i = 0; i < count; ++i) {
        MlsGroupCrypto::Identity* id = MlsGroupCrypto::newIdentity(mlsCredential(userId, deviceId));
        MlsGroupCrypto::PublishedKeyPackage* kp =
            id ? MlsGroupCrypto::newKeyPackage(id) : nullptr;
        if (!id || !kp) {
            MlsGroupCrypto::destroyIdentity(id);
            continue;
        }
        const QByteArray wire = MlsGroupCrypto::keyPackageBytes(kp);
        const QString hash = mlsRefHash(wire);
        // Retain and persist the init key immediately: it is what opens a
        // Welcome addressed to this KeyPackage, and the invite may only arrive
        // after a restart.
        m_mlsKeyPackages.insert(hash, kp);
        m_mlsIdentities.insert(hash, id);
        mlsPersistKeyPackage(hash, kp, id);

        QJsonObject item;
        item["device_id"] = deviceId;
        item["cipher_suite"] = 1;
        item["key_package_b64"] = QString::fromLatin1(wire.toBase64());
        item["ref_hash"] = hash;
        items.append(item);
    }
    if (items.isEmpty()) return;

    QNetworkRequest req(QUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/mls/keypackages")));
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(req);
    QJsonObject body;
    body["key_packages"] = items;
    QNetworkReply* reply =
        m_networkManager->post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError)
            qWarning() << "[mls] publishKeyPackages:" << reply->errorString();
    });
}

void ChatService::mlsEnsureKeyPackages() {
    // Same ownership rule as mlsPublishKeyPackages: do not even ask for the
    // count when v6 owns the namespace - the answer could only lead to a
    // publish that must not happen.
    if (mlsV2Ready()) return;
    if (buildAuthHeader().isEmpty()) return;
    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
                             QStringLiteral("/e2ee/mls/keypackages/count")));
    applyAuthHeaders(req);
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) return;
        const int available = QJsonDocument::fromJson(reply->readAll())
                                  .object().value("available").toInt(0);
        if (available < 3) mlsPublishKeyPackages(10 - available);
    });
}

void ChatService::mlsCreateGroup(const QString& chatId) {
    mlsCreateGroupThen(chatId, nullptr);
}

void ChatService::mlsCreateGroupThen(const QString& chatId, std::function<void()> done) {
    if (buildAuthHeader().isEmpty() || !m_authService) return;
    MlsGroupCrypto::Identity* id = MlsGroupCrypto::newIdentity(
        mlsCredential(m_authService->currentUserId(), m_authService->getOrCreateDeviceId()));
    if (!id) return;
    MlsGroupCrypto::Group* group = MlsGroupCrypto::createGroup(chatId.toUtf8(), id);
    if (!group) {
        MlsGroupCrypto::destroyIdentity(id);
        return;
    }
    m_mlsIdentities.insert(chatId, id);
    m_mlsGroups.insert(chatId, group);
    mlsPersistGroupBundle(chatId, id, QByteArray(), nullptr);

    QNetworkRequest req(QUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/mls/groups")));
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(req);
    QJsonObject body;
    body["chat_id"] = chatId;
    body["group_id_b64"] = QString::fromLatin1(chatId.toUtf8().toBase64());
    body["cipher_suite"] = 1;
    QNetworkReply* reply =
        m_networkManager->post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, done]() {
        reply->deleteLater();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        // 409: another device already registered the real group. Keeping this
        // local tree would make us encrypt to an audience of one.
        if (status == 409) {
            qWarning() << "[mls] createGroup 409 for" << chatId
                       << "- dropping local orphan, joining via Welcome";
            mlsForgetGroup(chatId);
            mlsProcessWelcomesThen([this, chatId, done]() {
                if (mlsHasGroup(chatId) && done) done();
            });
            return;
        }
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[mls] createGroup:" << reply->errorString();
            mlsForgetGroup(chatId);
            return;
        }
        // Self-welcome so a restart can rebuild this creator from a Welcome
        // instead of holding an unpersistable leaf.
        const QString me = m_authService ? m_authService->currentUserId() : QString();
        const QString meDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
        mlsAddMember(chatId, me, meDev, [done](bool, bool) {
            if (done) done();
        });
    });
}

void ChatService::mlsEnsureGroup(const QString& chatId) {
    if (buildAuthHeader().isEmpty() || chatId.isEmpty()) return;
    // A just-created group may not be in m_chatType yet (fetchChats still in
    // flight); only bail when the chat is positively known to be direct.
    const QString type = m_chatType.value(chatId);
    qInfo() << "[mls-ensure]" << chatId << "type=" << (type.isEmpty() ? QStringLiteral("(unknown)") : type)
            << "hasGroup=" << mlsHasGroup(chatId);
    if (!type.isEmpty() && type != QStringLiteral("group")) {
        qInfo() << "[mls-ensure]" << chatId << "SKIP: not a group";
        return;
    }
        if (mlsHasGroup(chatId)) {
        mlsSyncHandshakes(chatId);
        auto after = [this, chatId]() {
            mlsFetchCoverageThen(chatId, [this, chatId]() {
                mlsLogState(chatId, "ensure-has-group");
            });
        };
        if (!mlsBundleHasWelcome(chatId) && m_authService) {
            mlsAddMember(chatId, m_authService->currentUserId(),
                         m_authService->getOrCreateDeviceId(),
                         [this, chatId, after](bool, bool) {
                mlsInviteMissingDevicesThen(chatId, after);
            });
            return;
        }
        mlsInviteMissingDevicesThen(chatId, after);
        return;
    }
    // Someone may already have registered it; a Welcome for this device may be
    // waiting from a previous session.
    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
                             QStringLiteral("/e2ee/mls/groups/%1").arg(chatId)));
    applyAuthHeaders(req);
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (reply->error() == QNetworkReply::NoError) {
            mlsProcessWelcomesThen([this, chatId]() {
                if (mlsHasGroup(chatId)) {
                    mlsSyncHandshakes(chatId);
                    mlsInviteMissingDevicesThen(chatId, [this, chatId]() {
                        mlsFetchCoverageThen(chatId, [this, chatId]() {
                            mlsLogState(chatId, "ensure-joined-welcome");
                        });
                    });
                } else {
                    mlsEnsureKeyPackages();
                    mlsFetchCoverageThen(chatId, [this, chatId]() {
                        mlsLogState(chatId, "ensure-awaiting-welcome");
                    });
                }
            });
            return;
        }
        if (status != 404) {
            qInfo() << "[mls-ensure]" << chatId << "group GET status" << status
                    << "- not creating, not a member yet";
            return;
        }
        mlsFetchCoverageThen(chatId, [this, chatId]() {
            if (!mlsIsElectedCreator(chatId)) {
                qInfo() << "[mls-ensure]" << chatId << "not elected creator; awaiting Welcome";
                mlsEnsureKeyPackages();
                mlsLogState(chatId, "ensure-not-creator");
                return;
            }
            mlsCreateGroupThen(chatId, [this, chatId]() {
                mlsPublishGroupInfo(chatId);
                mlsInviteMissingDevicesThen(chatId, nullptr);
                mlsLogState(chatId, "ensure-created");
            });
        });
    });
}

void ChatService::mlsAddMembers(const QString& chatId, const QVariantList& userIds) {
    QList<QPair<QString, QString>> pending;
    for (const QVariant& v : userIds) {
        const QString uid = v.toString();
        if (!uid.isEmpty()) pending.append(qMakePair(uid, QString()));
    }
    mlsInviteNext(chatId, pending, 0);
}

void ChatService::mlsAddMember(const QString& chatId, const QString& userId) {
    mlsAddMember(chatId, userId, QString(), nullptr);
}

QSet<QString> ChatService::mlsLoadInvitedDevices(const QString& chatId) const {
    QSet<QString> out;
    const QString raw = CredentialManager::instance().getToken(
        QStringLiteral("mls1_invited/%1").arg(chatId));
    const QStringList parts = raw.split(QLatin1Char(','), Qt::SkipEmptyParts);
    for (const QString& p : parts) out.insert(p.trimmed());
    return out;
}

void ChatService::mlsMarkInvitedDevice(const QString& chatId, const QString& deviceId) {
    if (deviceId.isEmpty()) return;
    QSet<QString> ids = mlsLoadInvitedDevices(chatId);
    ids.insert(deviceId);
    CredentialManager::instance().saveToken(
        QStringLiteral("mls1_invited/%1").arg(chatId),
        QStringList(ids.values()).join(QLatin1Char(',')));
}

void ChatService::mlsInviteOtherMembersThen(const QString& chatId, std::function<void()> done) {
    mlsInviteMissingDevicesThen(chatId, done);
}

bool ChatService::mlsIsElectedCreator(const QString& chatId) const {
    const QString me = m_authService ? m_authService->currentUserId() : QString();
    const QString meDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
    const QString meKey = me + QLatin1Char('|') + meDev;
    QStringList keys;
    const MlsCoverage cov = m_mlsCoverage.value(chatId);
    if (!cov.live.isEmpty()) {
        for (const auto& p : cov.live) {
            if (p.second.isEmpty()) continue;
            const QString key = p.first + QLatin1Char('|') + p.second;
            if (cov.claimable.contains(key) || p.second == meDev) keys.append(key);
        }
    } else {
        const auto devices = m_chatDevices.value(chatId);
        for (const auto& p : devices) {
            if (p.second.isEmpty()) continue;
            keys.append(p.first + QLatin1Char('|') + p.second);
        }
    }
    if (keys.isEmpty()) return true;
    keys.sort();
    return keys.first() == meKey;
}

bool ChatService::mlsIsElectedAdder(const QString& chatId) const {
    if (!mlsHasGroup(chatId) || !m_authService) return false;
    const QString meCred = mlsCredential(m_authService->currentUserId(),
                                         m_authService->getOrCreateDeviceId());
    const QStringList roster = MlsGroupCrypto::memberIdentities(m_mlsGroups.value(chatId));
    if (meCred.isEmpty() || roster.isEmpty() || !roster.contains(meCred)) return false;
    QStringList sorted = roster;
    sorted.sort();
    return sorted.first() == meCred;
}

bool ChatService::mlsBundleHasWelcome(const QString& chatId) const {
    const QString json = CredentialManager::instance().getToken(
        QStringLiteral("mls1_group/%1").arg(chatId));
    if (json.isEmpty()) return false;
    return !QJsonDocument::fromJson(json.toUtf8()).object()
                .value(QStringLiteral("welcome")).toString().isEmpty();
}

void ChatService::mlsFetchCoverageThen(const QString& chatId, std::function<void()> done) {
    if (buildAuthHeader().isEmpty() || chatId.isEmpty()) {
        if (done) done();
        return;
    }
    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
        QStringLiteral("/e2ee/mls/groups/%1/coverage").arg(chatId)));
    applyAuthHeaders(req);
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, done]() {
        reply->deleteLater();
        if (reply->error() == QNetworkReply::NoError) {
            const QJsonObject o = QJsonDocument::fromJson(reply->readAll()).object();
            MlsCoverage cov;
            cov.exists = o.value(QStringLiteral("exists")).toBool(false);
            cov.epoch = static_cast<qint64>(o.value(QStringLiteral("epoch")).toDouble());
            const QJsonArray live = o.value(QStringLiteral("live_devices")).toArray();
            for (const QJsonValue& v : live) {
                const QJsonObject d = v.toObject();
                const QString uid = d.value(QStringLiteral("user_id")).toString();
                const QString did = d.value(QStringLiteral("device_id")).toString();
                if (did.isEmpty()) continue;
                cov.live.append(qMakePair(uid, did));
                if (d.value(QStringLiteral("claimable")).toBool(false))
                    cov.claimable.insert(uid + QLatin1Char('|') + did);
            }
            const QJsonArray acked = o.value(QStringLiteral("acked_device_ids")).toArray();
            for (const QJsonValue& v : acked) cov.acked.insert(v.toString());
            const QJsonArray pending = o.value(QStringLiteral("pending_device_ids")).toArray();
            for (const QJsonValue& v : pending) cov.pending.insert(v.toString());
            m_mlsCoverage.insert(chatId, cov);
        } else {
            qWarning() << "[mls-coverage]" << chatId << reply->errorString();
        }
        if (done) done();
    });
}

bool ChatService::mlsReadyToSend(const QString& chatId) const {
    if (!mlsHasGroup(chatId)) return false;
    const MlsCoverage cov = m_mlsCoverage.value(chatId);
    if (!cov.exists) return false;
    const qint64 local = qint64(MlsGroupCrypto::epoch(m_mlsGroups.value(chatId)));
    if (local != cov.epoch) return false;
    const QString meDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
    if (cov.live.isEmpty()) return false;
    for (const auto& p : cov.live) {
        if (p.second.isEmpty() || p.second == meDev) continue;
        if (!cov.acked.contains(p.second)) return false;
    }
    return true;
}

bool ChatService::mlsRebuildFromBundle(const QString& chatId) {
    const QString json = CredentialManager::instance().getToken(
        QStringLiteral("mls1_group/%1").arg(chatId));
    if (json.isEmpty()) return false;
    const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
    const QByteArray welcome =
        QByteArray::fromBase64(o.value(QStringLiteral("welcome")).toString().toLatin1());
    MlsGroupCrypto::PublishedKeyPackage* kp =
        MlsGroupCrypto::decodePublishedKeyPackage(o.value(QStringLiteral("kp")).toString());
    MlsGroupCrypto::Identity* id =
        MlsGroupCrypto::decodeIdentity(o.value(QStringLiteral("identity")).toString());
    if (welcome.isEmpty() || !kp || !id) {
        MlsGroupCrypto::destroyKeyPackage(kp);
        MlsGroupCrypto::destroyIdentity(id);
        return false;
    }
    MlsGroupCrypto::Group* g = MlsGroupCrypto::joinFromWelcome(kp, id, welcome);
    MlsGroupCrypto::destroyKeyPackage(kp);
    if (!g) {
        MlsGroupCrypto::destroyIdentity(id);
        return false;
    }
    if (MlsGroupCrypto::Group* old = m_mlsGroups.take(chatId))
        MlsGroupCrypto::destroyGroup(old);
    m_mlsGroups.insert(chatId, g);
    m_mlsIdentities.insert(chatId, id);
    qInfo() << "[mls]" << chatId << "rebuilt from Welcome at epoch"
            << MlsGroupCrypto::epoch(g);
    return true;
}

void ChatService::mlsLogState(const QString& chatId, const char* where) const {
    const MlsCoverage cov = m_mlsCoverage.value(chatId);
    QStringList roster;
    qint64 epoch = -1;
    if (mlsHasGroup(chatId)) {
        roster = MlsGroupCrypto::memberIdentities(m_mlsGroups.value(chatId));
        epoch = qint64(MlsGroupCrypto::epoch(m_mlsGroups.value(chatId)));
    }
    QStringList missing;
    const QString meDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
    for (const auto& p : cov.live) {
        if (p.second.isEmpty() || p.second == meDev) continue;
        if (!cov.acked.contains(p.second)) missing.append(p.second.left(8));
    }
    qInfo() << "[mls-state] where=" << where << "chat=" << chatId
            << "hasGroup=" << mlsHasGroup(chatId)
            << "epoch=" << epoch << "serverEpoch=" << cov.epoch
            << "roster=" << roster
            << "acked=" << cov.acked.values()
            << "pending=" << cov.pending.values()
            << "missing=" << missing
            << "ready=" << mlsReadyToSend(chatId)
            << "adder=" << mlsIsElectedAdder(chatId);
}

void ChatService::mlsInviteOwnOtherDevices(const QString& chatId) {
    mlsInviteMissingDevicesThen(chatId, nullptr);
}

void ChatService::mlsInviteMissingDevicesThen(const QString& chatId, std::function<void()> done) {
    if (!mlsHasGroup(chatId)) {
        qInfo() << "[mls-invite]" << chatId << "SKIP: no local group";
        if (done) done();
        return;
    }
    if (!mlsIsElectedAdder(chatId)) {
        qInfo() << "[mls-invite]" << chatId << "SKIP: not elected adder";
        mlsLogState(chatId, "invite-not-adder");
        if (done) done();
        return;
    }
    if (buildAuthHeader().isEmpty()) {
        qInfo() << "[mls-invite]" << chatId << "SKIP: no auth header";
        if (done) done();
        return;
    }
    refreshChatDevices(chatId, [this, chatId, done]() {
        const QString meDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
        const QStringList roster =
            MlsGroupCrypto::memberIdentities(m_mlsGroups.value(chatId));
        const auto devices = m_chatDevices.value(chatId);
        qInfo() << "[mls-invite]" << chatId << "meDev=" << meDev.left(8)
                << "epoch=" << MlsGroupCrypto::epoch(m_mlsGroups.value(chatId))
                << "roster=" << roster << "chatDevices=" << devices.size();
        QList<QPair<QString, QString>> pending;
        for (const auto& p : devices) {
            if (p.second.isEmpty() || p.second == meDev) {
                qInfo() << "  [mls-invite] skip" << p.second.left(8) << "(self/blank)";
                continue;
            }
            const QString cred = mlsCredential(p.first, p.second);
            if (roster.contains(cred)) {
                qInfo() << "  [mls-invite] skip" << p.second.left(8) << "(already a leaf)";
                continue;
            }
            qInfo() << "  [mls-invite] WILL INVITE" << p.second.left(8) << "cred=" << cred;
            pending.append(p);
        }
        qInfo() << "[mls-invite]" << chatId << "inviting" << pending.size() << "device(s)";
        mlsInviteNext(chatId, pending, 0, done);
    });
}

void ChatService::mlsInviteNext(const QString& chatId,
                               const QList<QPair<QString, QString>>& pending,
                               int index,
                               std::function<void()> done) {
    if (index >= pending.size()) {
        if (done) done();
        return;
    }
    const QString userId = pending.at(index).first;
    const QString deviceId = pending.at(index).second;
    mlsAddMember(chatId, userId, deviceId, [this, chatId, pending, index, deviceId, done](bool ok, bool stopQueue) {
        if (ok && !deviceId.isEmpty()) mlsMarkInvitedDevice(chatId, deviceId);
        if (stopQueue) {
            if (done) done();
            return;
        }
        mlsInviteNext(chatId, pending, index + 1, done);
    });
}

void ChatService::mlsAddMember(const QString& chatId, const QString& userId, const QString& deviceId,
                              std::function<void(bool ok, bool stopQueue)> done) {
    if (buildAuthHeader().isEmpty() || !mlsHasGroup(chatId)) {
        if (done) done(false, false);
        return;
    }

    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
                             QStringLiteral("/e2ee/mls/keypackages/claim")));
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(req);
    QJsonObject body;
    body["user_id"] = userId;
    if (!deviceId.isEmpty()) body["device_id"] = deviceId;
    QNetworkReply* reply =
        m_networkManager->post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, userId, deviceId, done]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "[mls] no key package available for" << userId << deviceId;
            if (done) done(false, false);
            return;
        }
        const QJsonObject o = QJsonDocument::fromJson(reply->readAll()).object();
        const QByteArray kpWire =
            QByteArray::fromBase64(o.value("key_package_b64").toString().toLatin1());

        MlsGroupCrypto::Group* group = m_mlsGroups.value(chatId);
        if (!group) {
            if (done) done(false, false);
            return;
        }
        const quint64 observed = MlsGroupCrypto::epoch(group);
        MlsGroupCrypto::AddResult added = MlsGroupCrypto::addMember(group, kpWire);
        if (!added.group) {
            qWarning() << "[mls] addMember failed for" << chatId;
            if (done) done(false, false);
            return;
        }
        MlsGroupCrypto::destroyGroup(group);
        m_mlsGroups.insert(chatId, added.group);

        QNetworkRequest creq(QUrl(Config::apiBaseUrl() +
            QStringLiteral("/e2ee/mls/groups/%1/commit").arg(chatId)));
        creq.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        applyAuthHeaders(creq);
        QJsonObject cbody;
        cbody["expected_epoch"] = qint64(observed);
        cbody["commit_b64"] = QString::fromLatin1(added.commit.toBase64());
        cbody["sender_device_id"] =
            m_authService ? m_authService->getOrCreateDeviceId() : QString();
        QJsonObject w;
        const QString target = o.value("user_id").toString();
        w["user_id"] = target.isEmpty() ? userId : target;
        const QString claimedDev = o.value("device_id").toString();
        w["device_id"] = claimedDev.isEmpty() ? deviceId : claimedDev;
        w["welcome_b64"] = QString::fromLatin1(added.welcome.toBase64());
        QJsonArray welcomes;
        welcomes.append(w);
        cbody["welcomes"] = welcomes;

        QNetworkReply* creply =
            m_networkManager->post(creq, QJsonDocument(cbody).toJson(QJsonDocument::Compact));
        const QByteArray welcomeCopy = added.welcome;
        const QString kpHash = mlsRefHash(kpWire);
        connect(creply, &QNetworkReply::finished, this,
                [this, creply, chatId, done, welcomeCopy, kpHash]() {
            creply->deleteLater();
            const int status =
                creply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            if (status == 409) {
                qWarning() << "[mls] commit rejected (stale epoch) for" << chatId
                           << "- rebuilding from Welcome";
                if (!mlsRebuildFromBundle(chatId)) {
                    if (MlsGroupCrypto::Group* g = m_mlsGroups.take(chatId))
                        MlsGroupCrypto::destroyGroup(g);
                }
                mlsSyncHandshakes(chatId);
                if (done) done(false, true);
                return;
            }
            if (creply->error() != QNetworkReply::NoError) {
                qWarning() << "[mls] submitCommit:" << creply->errorString();
                if (done) done(false, false);
                return;
            }
            if (m_mlsKeyPackages.contains(kpHash)) {
                mlsPersistGroupBundle(chatId, m_mlsIdentities.value(kpHash),
                                      welcomeCopy, m_mlsKeyPackages.value(kpHash));
            }
            mlsPublishGroupInfo(chatId);
            if (done) done(true, false);
        });
    });
}

// Leaf credential format, shared with Android. Several devices of one account
// are separate leaves, so the credential must name the device or the roster
// cannot tell them apart.
QString ChatService::mlsCredential(const QString& userId, const QString& deviceId) {
    return deviceId.isEmpty() ? userId : userId + QLatin1Char('|') + deviceId;
}

void ChatService::mlsAckWelcomes(const QJsonArray& ids) {
    if (ids.isEmpty() || buildAuthHeader().isEmpty()) return;
    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
                             QStringLiteral("/e2ee/mls/welcomes/ack")));
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(req);
    QJsonObject body;
    body["ids"] = ids;
    QNetworkReply* reply =
        m_networkManager->post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError)
            qWarning() << "[mls] welcome ack:" << reply->errorString();
    });
}

void ChatService::mlsForgetGroup(const QString& chatId) {
    if (MlsGroupCrypto::Group* g = m_mlsGroups.take(chatId)) {
        MlsGroupCrypto::destroyGroup(g);
    }
    m_mlsIdentities.remove(chatId);
    CredentialManager::instance().deleteToken(
        QStringLiteral("mls1_group/%1").arg(chatId));
    // The invited-device set is only meaningful for the group we just dropped.
    // Keeping it would make us skip re-inviting those devices into the NEW
    // group, and since they now wait to be added rather than external-joining,
    // they would wait forever.
    CredentialManager::instance().deleteToken(
        QStringLiteral("mls1_invited/%1").arg(chatId));
    // The caller re-stamps this from the server's current value; a stale entry
    // would make the next comparison agree with a group we left behind.
    CredentialManager::instance().deleteToken(
        QStringLiteral("mls1_instance/%1").arg(chatId));
    emit cryptoRevisionChanged();
}

void ChatService::mlsReconcileGroup(const QString& chatId) {
    if (buildAuthHeader().isEmpty()) return;
    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
        QStringLiteral("/e2ee/mls/groups/%1").arg(chatId)));
    applyAuthHeaders(req);
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        const int status =
            reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        // 404: the Delivery Service has no such group. Our restored bundle then
        // describes a group of one that nobody else can ever join, and every
        // send would be encrypted to an audience of ourselves. Drop it so the
        // next open establishes a fresh group.
        if (status == 404) {
            qWarning() << "[mls] server has no group for" << chatId
                       << "- dropping stale local state";
            mlsForgetGroup(chatId);
            return;
        }
        if (status < 200 || status >= 300) return;

        // Existence is not enough: a group that was deleted and recreated
        // reuses the same chat_id, so a 200 can describe a DIFFERENT tree than
        // the one our bundle belongs to. Holding that state made every
        // unprotect fail the AEAD check ("mac check in GCM failed") with no
        // other symptom.
        const QString serverInstance =
            QJsonDocument::fromJson(reply->readAll()).object()
                .value(QStringLiteral("instance_id")).toString();
        if (!serverInstance.isEmpty()) {
            const QString key = QStringLiteral("mls1_instance/%1").arg(chatId);
            const QString known = CredentialManager::instance().getToken(key);
            // An empty stamp counts as a mismatch: a bundle written before
            // stamping existed has unknown provenance, and blessing it simply
            // wrote the current id onto state from a deleted group - the state
            // that fails "mac check in GCM".
            if (m_mlsGroups.contains(chatId) && known != serverInstance) {
                qWarning() << "[mls] group" << chatId << "was recreated"
                           << known.left(8) << "->" << serverInstance.left(8)
                           << "- dropping stale local state";
                mlsForgetGroup(chatId);
            }
            CredentialManager::instance().saveToken(key, serverInstance);
        }

        // Caught up with whatever happened while this device was down.
        mlsSyncHandshakes(chatId);
    });
}

void ChatService::mlsPublishGroupInfo(const QString& chatId) {
    if (!mlsHasGroup(chatId) || buildAuthHeader().isEmpty()) return;
    MlsGroupCrypto::Group* group = m_mlsGroups.value(chatId);
    const QByteArray info = MlsGroupCrypto::exportGroupInfo(group);
    if (info.isEmpty()) return;

    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
        QStringLiteral("/e2ee/mls/groups/%1/group-info").arg(chatId)));
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(req);
    QJsonObject body;
    body["epoch"] = qint64(MlsGroupCrypto::epoch(group));
    body["group_info_b64"] = QString::fromLatin1(info.toBase64());
    QNetworkReply* reply =
        m_networkManager->post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply]() { reply->deleteLater(); });
}

void ChatService::mlsProcessWelcomes() {
    mlsProcessWelcomesThen(nullptr);
}

void ChatService::mlsProcessWelcomesThen(std::function<void()> done) {
    if (buildAuthHeader().isEmpty() || !m_authService) {
        if (done) done();
        return;
    }
    QNetworkRequest req(QUrl(Config::apiBaseUrl() + QStringLiteral("/e2ee/mls/welcomes")));
    applyAuthHeaders(req);
    req.setRawHeader("X-Device-Id", m_authService->getOrCreateDeviceId().toUtf8());
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, done]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            if (done) done();
            return;
        }
        const QJsonArray arr =
            QJsonDocument::fromJson(reply->readAll()).object().value("welcomes").toArray();
        QJsonArray ackIds;
        QStringList joinedChats;
        for (const QJsonValue& v : arr) {
            const QJsonObject w = v.toObject();
            const QString chatId = w.value("chat_id").toString();
            const QString welcomeId = w.value("id").toString();
            if (mlsHasGroup(chatId)) {
                qInfo() << "[mls] already a member of" << chatId
                        << "- acking Welcome without rejoin";
                if (!welcomeId.isEmpty()) ackIds.append(welcomeId);
                continue;
            }
            const QByteArray welcome =
                QByteArray::fromBase64(w.value("welcome_b64").toString().toLatin1());
            // Only the KeyPackage the inviter claimed can open this Welcome, so
            // try each one we still hold.
            bool joined = false;
            for (auto it = m_mlsKeyPackages.constBegin();
                 it != m_mlsKeyPackages.constEnd(); ++it) {
                MlsGroupCrypto::Identity* id = m_mlsIdentities.value(it.key());
                if (!id) continue;
                MlsGroupCrypto::Group* g =
                    MlsGroupCrypto::joinFromWelcome(it.value(), id, welcome);
                if (!g) continue;
                m_mlsGroups.insert(chatId, g);
                m_mlsIdentities.insert(chatId, id);
                mlsPersistGroupBundle(chatId, id, welcome, it.value());
                joined = true;
                emit cryptoRevisionChanged();
                break;
            }
            // Retire the Welcome only once the join actually worked; one that
            // could not be opened stays pending so a transient failure is
            // retried instead of locking this device out of the group.
            if (joined) {
                joinedChats.append(chatId);
                const QString id = w.value("id").toString();
                if (!id.isEmpty()) ackIds.append(id);
            } else {
                qWarning() << "[mls] no key package opened welcome for" << chatId
                           << "- leaving it pending";
            }
        }
        if (!ackIds.isEmpty()) mlsAckWelcomes(ackIds);
        for (const QString& id : joinedChats)
            mlsInviteMissingDevicesThen(id, nullptr);
        if (done) done();
    });
}

void ChatService::mlsSyncHandshakes(const QString& chatId) {
    if (!mlsHasGroup(chatId) || buildAuthHeader().isEmpty()) return;
    const quint64 since = MlsGroupCrypto::epoch(m_mlsGroups.value(chatId));
    QNetworkRequest req(QUrl(Config::apiBaseUrl() +
        QStringLiteral("/e2ee/mls/groups/%1/handshakes?since_epoch=%2")
            .arg(chatId).arg(since)));
    applyAuthHeaders(req);
    QNetworkReply* reply = m_networkManager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) return;
        const QJsonArray arr =
            QJsonDocument::fromJson(reply->readAll()).object().value("handshakes").toArray();
        MlsGroupCrypto::Group* group = m_mlsGroups.value(chatId);
        if (!group) return;
        bool advanced = false;
        for (const QJsonValue& v : arr) {
            const QJsonObject h = v.toObject();
            const quint64 epoch = quint64(h.value("epoch").toDouble());
            if (epoch <= MlsGroupCrypto::epoch(group)) continue;
            const QByteArray payload =
                QByteArray::fromBase64(h.value("payload_b64").toString().toLatin1());
            MlsGroupCrypto::Group* next = MlsGroupCrypto::applyCommit(group, payload);
            if (!next) {
                qWarning() << "[mls] stopping handshake replay at epoch" << epoch
                           << "for" << chatId;
                break;
            }
            MlsGroupCrypto::destroyGroup(group);
            group = next;
            m_mlsGroups.insert(chatId, group);
            advanced = true;
        }
        // Republish GroupInfo whenever our epoch moved. Without this the
        // GroupInfo on the DS still describes the epoch we were at before the
        // commits, and Android's external rejoin refuses to build against a
        // stale tree ("published group info is stale"), so that device can
        // never join and silently falls back to v1 Sender Keys.
        if (advanced) mlsPublishGroupInfo(chatId);
        emit cryptoRevisionChanged();
    });
}

void ChatService::mlsRestoreGroups() {
    // Re-arm published KeyPackages first, so a Welcome that arrived while this
    // device was down can still be opened.
    const QMap<QString, QString> keys =
        CredentialManager::instance().tokensWithPrefix(QStringLiteral("mls1_kp/"));
    for (auto kit = keys.constBegin(); kit != keys.constEnd(); ++kit) {
        const QString key = kit.key();
        const QString json = kit.value();
        if (json.isEmpty()) continue;
        const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
        MlsGroupCrypto::PublishedKeyPackage* kp =
            MlsGroupCrypto::decodePublishedKeyPackage(o.value("kp").toString());
        MlsGroupCrypto::Identity* id =
            MlsGroupCrypto::decodeIdentity(o.value("identity").toString());
        if (!kp || !id) {
            MlsGroupCrypto::destroyKeyPackage(kp);
            MlsGroupCrypto::destroyIdentity(id);
            continue;
        }
        const QString hash = key.mid(QStringLiteral("mls1_kp/").length());
        m_mlsKeyPackages.insert(hash, kp);
        m_mlsIdentities.insert(hash, id);
    }

    const QMap<QString, QString> gkeys =
        CredentialManager::instance().tokensWithPrefix(QStringLiteral("mls1_group/"));
    for (auto git = gkeys.constBegin(); git != gkeys.constEnd(); ++git) {
        const QString key = git.key();
        const QString chatId = key.mid(QStringLiteral("mls1_group/").length());
        const QString json = git.value();
        if (json.isEmpty()) continue;
        const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
        MlsGroupCrypto::Identity* id =
            MlsGroupCrypto::decodeIdentity(o.value("identity").toString());
        if (!id) continue;
        const QString welcomeB64 = o.value("welcome").toString();
        const QString kpJson = o.value("kp").toString();
        if (welcomeB64.isEmpty() || kpJson.isEmpty()) {
            // A group we created: no Welcome is addressed to us and mlspp
            // cannot serialize state, so recovery needs an external-commit
            // rejoin against the published GroupInfo.
            qWarning() << "[mls] cannot rebuild self-created group" << chatId;
            MlsGroupCrypto::destroyIdentity(id);
            continue;
        }
        MlsGroupCrypto::PublishedKeyPackage* kp =
            MlsGroupCrypto::decodePublishedKeyPackage(kpJson);
        if (!kp) {
            MlsGroupCrypto::destroyIdentity(id);
            continue;
        }
        MlsGroupCrypto::Group* g = MlsGroupCrypto::joinFromWelcome(
            kp, id, QByteArray::fromBase64(welcomeB64.toLatin1()));
        if (!g) {
            MlsGroupCrypto::destroyIdentity(id);
            MlsGroupCrypto::destroyKeyPackage(kp);
            continue;
        }
        m_mlsGroups.insert(chatId, g);
        m_mlsIdentities.insert(chatId, id);
        // Verify the group still exists on the server before trusting this
        // restored state; mlsReconcileGroup() also replays handshakes when it
        // does, so no separate sync is needed here.
        mlsReconcileGroup(chatId);
    }
    emit cryptoRevisionChanged();
}
#endif

// ---- Direct v4 (X3DH-lite two-root ratchet) ----
// Role-free: initSession is symmetric from the two identity keys, so glare (both
// sides sending first) can no longer break the session. Stored under a "dr4_"
// credential prefix so v3 entries are never clobbered.

bool ChatService::loadDrV4(const QString& sessionKey, DoubleRatchetV4::Session& st,
                           const QString& peerPubHint) const {
    // The identity this session must pair with. Sessions created by earlier
    // builds' failed decrypt attempts were initialized with the *other user's*
    // key even for our own account's traffic; their rk0 can never open those
    // blobs. Any stored session whose peerIdent disagrees is discarded and
    // re-derived from the right identity.
    const QString expectedHex = !peerPubHint.isEmpty() ? peerPubHint
                                                       : alicePeerPubForSession(sessionKey);
    const QByteArray expected = QByteArray::fromHex(expectedHex.toUtf8());
    const auto identityOk = [&](const DoubleRatchetV4::Session& s) {
        return expected.size() != 32 || s.peerIdent == expected;
    };
    if (m_drV4.contains(sessionKey)) {
        st = m_drV4.value(sessionKey);
        if (identityOk(st)) return true;
    }
    const QString json = CredentialManager::instance().getToken(QStringLiteral("dr4_%1").arg(sessionKey));
    if (!json.isEmpty() && DoubleRatchetV4::Session::fromJson(json, st) && identityOk(st)) {
        m_drV4.insert(sessionKey, st);
        return true;
    }
    const QByteArray peer = expected.size() == 32
        ? expected
        : QByteArray::fromHex(alicePeerPubForSession(sessionKey).toUtf8());
    const QByteArray myPub = QByteArray::fromHex(m_authService ? m_authService->e2eePublicKey().toUtf8() : QByteArray());
    const QByteArray myPriv = QByteArray::fromHex(m_authService ? m_authService->e2eePrivateKey().toUtf8() : QByteArray());
    if (!DoubleRatchetV4::initSession(myPub, myPriv, peer, st)) return false;
    m_drV4.insert(sessionKey, st);
    return true;
}

void ChatService::saveDrV4(const QString& sessionKey, DoubleRatchetV4::Session& st) const {
    st.seq++;
    m_drV4.insert(sessionKey, st);
    CredentialManager::instance().saveToken(QStringLiteral("dr4_%1").arg(sessionKey), st.toJson());
    if (m_authService) m_authService->refreshVaultContents();
}

QByteArray ChatService::encryptDirectV4(const QString& sessionKey, const QByteArray& plain) const {
    DoubleRatchetV4::Session st;
    if (!loadDrV4(sessionKey, st)) return {};
    const QByteArray out = DoubleRatchetV4::encrypt(st, plain);
    if (out.isEmpty()) return {};
    saveDrV4(sessionKey, st);
    return out;
}

QByteArray ChatService::decryptDirectV4(const QString& sessionKey, const QByteArray& payload,
                                        const QString& peerPubHint) const {
    DoubleRatchetV4::Session st;
    if (!loadDrV4(sessionKey, st, peerPubHint)) return {};
    const QByteArray plain = DoubleRatchetV4::decrypt(st, payload);
    if (plain.isEmpty()) return {};
    saveDrV4(sessionKey, st);
    return plain;
}

// Seal a copy for THIS device with a throwaway self-session. Using the stored
// chatId|myDev session poisoned it: decrypting our own blob on refetch flipped
// its recvFirst/turnPending, after which every later send became a NORMAL-type
// message whose DH (eph with itself) can never be reproduced — those rows were
// "🔒 Encrypted message" forever. A fresh session per send always emits an
// INITIAL-type message, which decryptInitialStateless() can re-read any number
// of times from just rk0 + the identity key.
QByteArray ChatService::sealSelfV4(const QByteArray& inner) const {
    if (!m_authService) return {};
    const QByteArray pk = QByteArray::fromHex(m_authService->e2eePublicKey().toUtf8());
    const QByteArray sk = QByteArray::fromHex(m_authService->e2eePrivateKey().toUtf8());
    DoubleRatchetV4::Session tmp;
    if (!DoubleRatchetV4::initSession(pk, sk, pk, tmp)) return {};
    return DoubleRatchetV4::encrypt(tmp, inner);
}

QByteArray ChatService::sealDirectV4(const QString& chatId, const QByteArray& inner) const {
    const QString me = m_authService ? m_authService->currentUserId() : QString();
    const QString myPub = m_authService ? m_authService->e2eePublicKey() : QString();
    const QString myDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
    const QString otherPub = m_chatOtherPub.value(chatId);
    QMap<QString, QString> targets;
    const auto devices = m_chatDevices.value(chatId);
    for (const auto& p : devices) {
        if (p.second.isEmpty()) continue;
        const QString pub = (p.first == me) ? myPub : otherPub;
        if (!pub.isEmpty()) targets.insert(p.second, pub);
    }
    // Always seal a copy for THIS device, even when the server registry omits
    // it (fresh install that hasn't registered yet); otherwise our own history
    // is sealed only for other devices and can never be read back here.
    if (!myDev.isEmpty() && !myPub.isEmpty() && !targets.isEmpty())
        targets.insert(myDev, myPub);
    if (targets.size() <= 1)
        return encryptDirectV4(chatId, inner);
    QList<QPair<QString, QByteArray>> parts;
    for (auto it = targets.constBegin(); it != targets.constEnd(); ++it) {
        const QByteArray blob = (it.key() == myDev)
            ? sealSelfV4(inner)
            : encryptDirectV4(chatId + QLatin1Char('|') + it.key(), inner);
        if (blob.isEmpty()) return {};
        parts.append(qMakePair(it.key(), blob));
    }
    return Encryption::wrapFanout(parts);
}

QByteArray ChatService::decryptToBytes(const QString& chatId, const QByteArray& payload,
                                        const QString& senderId, int keyVersion, int encryptionVersion,
                                        const QString& senderDeviceId) const {
    if (encryptionVersion == MlsV2Engine::kEncryptionVersion && m_mls2) {
        const QByteArray opened = m_mls2->decrypt(chatId, payload);
        if (opened.isEmpty())
            qWarning() << "[mls-v2] no group state / open failed for" << chatId;
        return opened;
    }
#ifdef HAVE_MLSPP
    // MLS group message: TreeKEM state, not Sender Keys.
    if (encryptionVersion == 5) {
        const QByteArray opened = mlsUnprotect(chatId, payload);
        if (opened.isEmpty())
            qWarning() << "[mls] no group state / open failed for" << chatId;
        return opened;
    }
#endif
    if (m_chatType.value(chatId) == QStringLiteral("group")) {
        QString key = groupSenderKeyFor(chatId, senderId, keyVersion);
        if (key.isEmpty()) return {};
        return Encryption::secretBoxDecryptBytes(payload, key);
    }
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (myPriv.isEmpty()) {
        qWarning() << "[DecryptTrace] FAIL no private key chat=" << chatId;
        return {};
    }
    if (encryptionVersion <= 0) encryptionVersion = 1;
    // Temporary diagnostics: which scheme, which session, what we hold.
    qDebug() << "[DecryptTrace] try chat=" << chatId
             << "encV=" << encryptionVersion
             << "payload=" << payload.size()
             << "senderDev=" << (senderDeviceId.isEmpty() ? QStringLiteral("EMPTY") : senderDeviceId.left(8))
             << "otherPub=" << (m_chatOtherPub.value(chatId).isEmpty() ? QStringLiteral("MISSING") : QStringLiteral("ok"))
             << "myDev=" << (m_authService ? m_authService->getOrCreateDeviceId().left(8) : QString());
    if (encryptionVersion == 4) {
        QString myDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
        // Empty when the payload is a fan-out with no blob for this device;
        // the loop below then tries the other parts statelessly.
        QByteArray blob = Encryption::pickFanout(payload, myDev);
        if (blob.isEmpty() && !Encryption::isFanout(payload)) blob = payload;
        const QString sess = senderDeviceId.isEmpty() ? chatId : (chatId + QLatin1Char('|') + senderDeviceId);
        // A message from this account's own devices rides a self-session whose
        // root pairs our identity with itself; the peer's key would derive the
        // wrong rk0 (and reject/replace the stored session as stale).
        const QString me = m_authService ? m_authService->currentUserId() : QString();
        const QString peerHint = (!senderId.isEmpty() && !me.isEmpty() && senderId == me)
            ? m_authService->e2eePublicKey()
            : m_chatOtherPub.value(chatId);
        QByteArray plain;
        if (!blob.isEmpty()) {
            plain = decryptDirectV4(sess, blob, peerHint);
            if (plain.isEmpty() && sess != chatId) {
                qWarning() << "[DecryptTrace] v4 session" << sess << "failed; retrying bare chatId";
                // No hint here: the bare session legitimately pairs with the
                // other user and must not be re-derived for own-account traffic.
                plain = decryptDirectV4(chatId, blob);
            }
        }
        if (plain.isEmpty()) {
            // No blob for this device id (or it failed): this install may be
            // missing from the registry the sender fanned out to. INITIAL
            // blobs open statelessly with just the account identity, so try
            // the copies addressed to other devices — wrong ones fail auth.
            const auto parts = Encryption::listFanout(payload);
            for (const auto& part : parts) {
                if (part.first == myDev) continue;
                plain = decryptDirectV4(sess, part.second, peerHint);
                if (!plain.isEmpty()) break;
            }
        }
        if (plain.isEmpty())
            qWarning() << "[DecryptTrace] FAIL v4 chat=" << chatId << "sess=" << sess;
        return plain;
    }
    if (encryptionVersion == 3) {
        QString myDev = m_authService ? m_authService->getOrCreateDeviceId() : QString();
        QByteArray blob = Encryption::pickFanout(payload, myDev);
        if (blob.isEmpty() && Encryption::isFanout(payload)) return {};
        if (blob.isEmpty()) blob = payload;
        const QString sess = senderDeviceId.isEmpty() ? chatId : (chatId + QLatin1Char('|') + senderDeviceId);
        QByteArray plain = decryptDirectV3(sess, blob);
        if (plain.isEmpty() && sess != chatId) plain = decryptDirectV3(chatId, blob);
        return plain;
    }
    if (encryptionVersion >= 2) {
        return Encryption::boxDecryptBytesEphemeral(payload, myPriv);
    }
    QString otherPub = m_chatOtherPub.value(chatId);
    if (otherPub.isEmpty()) return {};
    return Encryption::boxDecryptBytes(payload, otherPub, myPriv);
}

Encryption::MessageEnvelope ChatService::openCipher(const QString& chatId, const QString& content, bool encrypted,
                                                     const QString& senderId, int keyVersion, int encryptionVersion,
                                                     const QString& senderDeviceId) const {
    Encryption::MessageEnvelope env;
    if (!encrypted) {
        env.payload = content.toUtf8();
        return env;
    }
    QByteArray inner = decryptToBytes(chatId, QByteArray::fromHex(content.toUtf8()), senderId, keyVersion, encryptionVersion, senderDeviceId);
    if (inner.isEmpty()) return env;
    return Encryption::unwrapEnvelope(inner);
}

void ChatService::applyEnvelopeMeta(QVariantMap& item, const Encryption::MessageEnvelope& env) const {
    if (item.value(QStringLiteral("fileName")).toString().isEmpty() && !env.fileName.isEmpty())
        item[QStringLiteral("fileName")] = env.fileName;
    if (item.value(QStringLiteral("forwardedFromName")).toString().isEmpty() && !env.forwardedFrom.isEmpty())
        item[QStringLiteral("forwardedFromName")] = env.forwardedFrom;
    if (item.value(QStringLiteral("durationMs")).toLongLong() <= 0 && env.durationMs > 0)
        item[QStringLiteral("durationMs")] = env.durationMs;
    if (item.value(QStringLiteral("fileSize")).toLongLong() <= 0 && env.fileSize > 0)
        item[QStringLiteral("fileSize")] = env.fileSize;
    if (item.value(QStringLiteral("thumbnailUrl")).toString().isEmpty() && !env.thumbnailUrl.isEmpty())
        item[QStringLiteral("thumbnailUrl")] = env.thumbnailUrl;
    if (item.value(QStringLiteral("fileUrl")).toString().isEmpty() && !env.fileUrl.isEmpty())
        item[QStringLiteral("fileUrl")] = env.fileUrl;
}

QString ChatService::decryptMessage(const QString& chatId, const QString& content, bool encrypted,
                                     const QString& senderId, int keyVersion, int encryptionVersion,
                                     const QString& senderDeviceId) const {
    if (!encrypted) return content;
    Encryption::MessageEnvelope env = openCipher(chatId, content, encrypted, senderId, keyVersion, encryptionVersion, senderDeviceId);
    if (env.payload.isEmpty()) {
        return QString::fromUtf8("\xF0\x9F\x94\x92 Encrypted message");
    }
    return QString::fromUtf8(env.payload);
}

QVariantMap ChatService::peekEnvelope(const QString& chatId, const QString& content, bool encrypted,
                                      const QString& senderId, int keyVersion, int encryptionVersion,
                                      const QString& senderDeviceId) const {
    QVariantMap m;
    Encryption::MessageEnvelope env = openCipher(chatId, content, encrypted, senderId, keyVersion, encryptionVersion, senderDeviceId);
    m[QStringLiteral("thumbnailUrl")] = env.thumbnailUrl;
    m[QStringLiteral("durationMs")] = env.durationMs;
    m[QStringLiteral("fileName")] = env.fileName;
    m[QStringLiteral("fileSize")] = env.fileSize;
    m[QStringLiteral("forwardedFrom")] = env.forwardedFrom;
    m[QStringLiteral("fileUrl")] = env.fileUrl;
    return m;
}

void ChatService::deleteMessage(const QString& chatId, const QString& messageId,
                                 bool forEveryone) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageDeleteError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages/" + chatId + "/" + messageId);
    if (forEveryone) {
        QUrlQuery q;
        q.addQueryItem(QStringLiteral("for_everyone"), QStringLiteral("true"));
        url.setQuery(q);
    }

    QNetworkRequest request(url);
    applyAuthHeaders(request);

    QNetworkReply* reply = m_networkManager->sendCustomRequest(request, "DELETE");
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, messageId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit messageDeleteError(reply->errorString());
            return;
        }
        // Drop the local cached copy too, or it reappears on the next cold
        // start from the cache-first paint.
        if (m_messageCache) m_messageCache->deleteMessage(messageId);
        emit messageDeleted(chatId, messageId);
        refreshChatListPreview(chatId);
    });
}

void ChatService::noteMessageDeleted(const QString& chatId, const QString& messageId) {
    if (m_messageCache) m_messageCache->deleteMessage(messageId);
    refreshChatListPreview(chatId);
}

void ChatService::notifyChatPreview(const QString& chatId, const QString& preview) {
    emit chatLastMessageChanged(chatId, preview);
}

void ChatService::refreshChatListPreview(const QString& chatId) {
    emit chatLastMessageChanged(chatId, computeLastMessagePreview(chatId));
}

QString ChatService::computeLastMessagePreview(const QString& chatId) const {
    if (!m_messageCache) return {};
    // loadMessages returns oldest-first; the newest remaining row is last.
    const QList<MessageCache::Entry> msgs = m_messageCache->loadMessages(chatId, 50);
    if (msgs.isEmpty()) return {};
    const MessageCache::Entry& e = msgs.last();
    if (e.fileType == QStringLiteral("audio"))
        return QString::fromUtf8("\xF0\x9F\x8E\xA4 Voice message"); // 🎤
    if (e.fileType == QStringLiteral("image"))
        return QString::fromUtf8("\xF0\x9F\x93\xB7 Photo"); // 📷
    if (e.fileType == QStringLiteral("file"))
        return QString::fromUtf8("\xF0\x9F\x93\x8E ") + (e.fileName.isEmpty() ? QStringLiteral("File") : e.fileName); // 📎
    if (e.fileType == QStringLiteral("video_note"))
        return QString::fromUtf8("\xF0\x9F\x93\xB9 Video message"); // 📹
    return decryptMessage(chatId, e.content, e.encrypted, e.senderId, e.keyVersion, e.encryptionVersion, e.senderDeviceId);
}

QString ChatService::takePendingSecurityNotice(const QString& chatId) {
    return m_pendingSecurityNotices.take(chatId);
}

QString ChatService::safetyNumberForChat(const QString& chatId) const {
    if (!m_authService) return {};
    const QString mine = m_authService->e2eePublicKey();
    QString peer = m_chatOtherPub.value(chatId);
    if (peer.isEmpty()) {
        peer = CredentialManager::instance().getToken(
            QStringLiteral("known_pubkey_%1").arg(chatId));
    }
    return Encryption::safetyNumber(mine, peer);
}

bool ChatService::hasPendingPeerKeyChange(const QString& chatId) const {
    return m_pendingPeerPub.contains(chatId)
        || !CredentialManager::instance().getToken(QStringLiteral("pending_pubkey_%1").arg(chatId)).isEmpty();
}

void ChatService::applyPeerIdentity(const QString& chatId, const QString& serverPub, const QString& contactName) {
    const QString knownKey = QStringLiteral("known_pubkey_%1").arg(chatId);
    const QString pendingKey = QStringLiteral("pending_pubkey_%1").arg(chatId);
    QString knownPub = CredentialManager::instance().getToken(knownKey);
    if (knownPub.isEmpty()) {
        CredentialManager::instance().saveToken(knownKey, serverPub);
        CredentialManager::instance().deleteToken(pendingKey);
        m_pendingPeerPub.remove(chatId);
        m_chatOtherPub.insert(chatId, serverPub);
        if (m_authService) m_authService->refreshVaultContents();
        return;
    }
    if (knownPub.compare(serverPub, Qt::CaseInsensitive) == 0) {
        CredentialManager::instance().deleteToken(pendingKey);
        m_pendingPeerPub.remove(chatId);
        m_chatOtherPub.insert(chatId, knownPub);
        return;
    }
    CredentialManager::instance().saveToken(pendingKey, serverPub);
    m_pendingPeerPub.insert(chatId, serverPub);
    m_chatOtherPub.insert(chatId, knownPub);
    m_pendingSecurityNotices.insert(chatId, contactName);
    emit securityCodeChanged(chatId, contactName);
    emit peerKeyChangePendingChanged(chatId);
    CredentialManager::instance().deleteToken(QStringLiteral("verified_pubkey_%1").arg(chatId));
}

void ChatService::acceptPeerKeyChange(const QString& chatId) {
    QString pending = m_pendingPeerPub.value(chatId);
    if (pending.isEmpty()) {
        pending = CredentialManager::instance().getToken(QStringLiteral("pending_pubkey_%1").arg(chatId));
    }
    if (pending.isEmpty()) return;
    CredentialManager::instance().saveToken(QStringLiteral("known_pubkey_%1").arg(chatId), pending);
    CredentialManager::instance().deleteToken(QStringLiteral("pending_pubkey_%1").arg(chatId));
    m_pendingPeerPub.remove(chatId);
    m_pendingSecurityNotices.remove(chatId);
    m_chatOtherPub.insert(chatId, pending);
    quint64 oldSeq = 0;
    if (m_dr.contains(chatId)) oldSeq = m_dr.value(chatId).seq;
    m_dr.remove(chatId);
    CredentialManager::instance().deleteToken(QStringLiteral("dr3_%1").arg(chatId));
    DoubleRatchet::State st;
    if (DoubleRatchet::initAlice(QByteArray::fromHex(pending.toUtf8()), st)) {
        st.seq = oldSeq;
        saveDr(chatId, st);
    }
    ++m_cryptoRevision;
    emit cryptoRevisionChanged();
    emit peerKeyChangePendingChanged(chatId);
    CredentialManager::instance().deleteToken(QStringLiteral("verified_pubkey_%1").arg(chatId));
}

QString ChatService::safetyNumberQrUrl(const QString& chatId) const {
    const QString payload = Encryption::safetyNumberQrPayload(safetyNumberForChat(chatId));
    if (payload.isEmpty()) return {};
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    const QString path = QDir(dir).filePath(QStringLiteral("messenger-safety-qr.png"));
    if (!PairingQr::savePng(payload, path, 6)) return {};
    return QUrl::fromLocalFile(path).toString();
}

bool ChatService::hasVerifiedSafetyNumber(const QString& chatId) const {
    const QString known = CredentialManager::instance().getToken(QStringLiteral("known_pubkey_%1").arg(chatId));
    const QString verified = CredentialManager::instance().getToken(QStringLiteral("verified_pubkey_%1").arg(chatId));
    return !known.isEmpty() && known.compare(verified, Qt::CaseInsensitive) == 0;
}

QString ChatService::compareSafetyNumberScan(const QString& chatId, const QString& scanned) {
    const QString local = Encryption::parseSafetyNumberQr(
        Encryption::safetyNumberQrPayload(safetyNumberForChat(chatId)));
    const QString remote = Encryption::parseSafetyNumberQr(scanned);
    if (local.isEmpty() || remote.isEmpty()) return QStringLiteral("invalid");
    if (local.compare(remote, Qt::CaseInsensitive) != 0) return QStringLiteral("mismatch");
    const QString known = CredentialManager::instance().getToken(QStringLiteral("known_pubkey_%1").arg(chatId));
    if (!known.isEmpty())
        CredentialManager::instance().saveToken(QStringLiteral("verified_pubkey_%1").arg(chatId), known);
    return QStringLiteral("match");
}

bool ChatService::hasKeyForChat(const QString& chatId) const {
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    return !m_chatOtherPub.value(chatId).isEmpty() && !myPriv.isEmpty();
}

bool ChatService::hasGroupSenderKey(const QString& chatId) const {
    const QMap<int, QString> versions = m_mySenderKeys.value(chatId);
    if (versions.isEmpty()) return false;
    return versions.lastKey() >= m_groupKeyEpoch.value(chatId, 0);
}

QString ChatService::groupSenderKeyFor(const QString& chatId, const QString& senderId, int keyVersion) const {
    QString myId = m_authService ? m_authService->currentUserId() : QString();
    if (!myId.isEmpty() && senderId == myId) {
        const QString mine = m_mySenderKeys.value(chatId).value(keyVersion);
        if (!mine.isEmpty()) return mine;
    }
    const QString cacheKey = QStringLiteral("%1|%2|%3").arg(chatId, senderId).arg(keyVersion);
    const QString cached = m_groupOtherKeys.value(cacheKey);
    if (!cached.isEmpty()) return cached;
    const QString stored = CredentialManager::instance().getToken(
        QStringLiteral("peer_senderkey_%1").arg(cacheKey));
    if (!stored.isEmpty()) {
        m_groupOtherKeys.insert(cacheKey, stored);
        return stored;
    }
    return QString();
}

void ChatService::loadPeerSenderKeysFromDisk(const QString& chatId) {
    const QString prefix = QStringLiteral("peer_senderkey_%1|").arg(chatId);
    const QMap<QString, QString> stored = CredentialManager::instance().tokensWithPrefix(prefix);
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        if (it.value().isEmpty()) continue;
        const QString rest = it.key().mid(prefix.size());
        m_groupOtherKeys.insert(QStringLiteral("%1|%2").arg(chatId, rest), it.value());
    }
}

void ChatService::persistMySenderKey(const QString& chatId, int version, const QString& keyHex) {
    QMap<int, QString> versions = m_mySenderKeys.value(chatId);
    versions.insert(version, keyHex);
    const QList<int> keys = versions.keys();
    if (keys.size() > 64) {
        for (int i = 0; i < keys.size() - 64; ++i) {
            versions.remove(keys.at(i));
            CredentialManager::instance().deleteToken(
                QStringLiteral("group_senderkey_%1|%2").arg(chatId).arg(keys.at(i)));
        }
    }
    m_mySenderKeys.insert(chatId, versions);
    CredentialManager::instance().saveToken(
        QStringLiteral("group_senderkey_%1|%2").arg(chatId).arg(version), keyHex);
    CredentialManager::instance().saveToken(QStringLiteral("group_senderkey_%1").arg(chatId),
                                             QString::number(version) + ":" + keyHex);
    if (m_authService) m_authService->refreshVaultContents();
}

void ChatService::loadMySenderKeyFromDisk(const QString& chatId) {
    if (!m_mySenderKeys.value(chatId).isEmpty()) return;
    QMap<int, QString> versions;
    const QString prefix = QStringLiteral("group_senderkey_%1").arg(chatId);
    const QMap<QString, QString> stored = CredentialManager::instance().tokensWithPrefix(prefix);
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QString rest = it.key().mid(prefix.size());
        if (rest.startsWith(QLatin1Char('|'))) {
            bool ok = false;
            const int version = rest.mid(1).toInt(&ok);
            if (ok && !it.value().isEmpty()) versions.insert(version, it.value());
            continue;
        }
        if (!rest.isEmpty()) continue;
        const int sep = it.value().indexOf(':');
        if (sep <= 0) continue;
        bool ok = false;
        const int version = it.value().left(sep).toInt(&ok);
        if (ok && !versions.contains(version)) versions.insert(version, it.value().mid(sep + 1));
    }
    if (!versions.isEmpty()) m_mySenderKeys.insert(chatId, versions);
}

void ChatService::ensureGroupSenderKeyReady(const QString& chatId, std::function<void()> onReady) {
#ifdef HAVE_MLSPP
    std::function<void()> finish = [this, chatId, onReady]() {
        mlsFetchCoverageThen(chatId, onReady);
    };
#else
    std::function<void()> finish = onReady;
#endif
    loadMySenderKeyFromDisk(chatId);

    int serverEpoch = m_groupKeyEpoch.value(chatId, 0);
    const QMap<int, QString> existing = m_mySenderKeys.value(chatId);
    if (!existing.isEmpty() && existing.lastKey() >= serverEpoch) {
        finish();
        return;
    }

    if (!m_groupService || !m_authService) {
        finish();
        return;
    }

    // Fetch fresh group info (current members + the authoritative key_epoch)
    // before generating a new key - membership may have changed since the
    // cached epoch was learned, and generating against a stale member list
    // would leave a just-added member without a copy.
    auto conn = std::make_shared<QMetaObject::Connection>();
    *conn = connect(m_groupService, &GroupService::groupInfoFetched, this,
        [this, chatId, finish, conn](const QVariantMap& group) {
            if (group.value("id").toString() != chatId) return;
            QObject::disconnect(*conn);

            int epoch = group.value("keyEpoch").toInt();
            QString newKey = Encryption::secretBoxGenerateKey();
            if (newKey.isEmpty()) {
                finish();
                return;
            }

            QString myPriv = m_authService->e2eePrivateKey();
            QByteArray keyBytes = QByteArray::fromHex(newKey.toUtf8());

            QJsonArray recipients;
            const QVariantList members = group.value("members").toList();
            for (const QVariant& mv : members) {
                QVariantMap member = mv.toMap();
                QString uid = member.value("id").toString();
                if (uid.isEmpty()) continue;
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

            QMap<int, QString> versions = m_mySenderKeys.value(chatId);
            versions.insert(epoch, newKey);
            m_mySenderKeys.insert(chatId, versions);
            m_groupKeyEpoch.insert(chatId, epoch);
            persistMySenderKey(chatId, epoch, newKey);

            if (recipients.isEmpty()) {
                // Nothing to distribute to yet (solo group, or no member has
                // published a key) - the key is adopted locally so sending
                // is consistent; nobody can read it until this gets
                // redistributed on a future rotation.
                finish();
                return;
            }

            QString authToken = buildAuthHeader();
            if (authToken.isEmpty()) {
                finish();
                return;
            }

            QJsonObject body;
            body["key_version"] = epoch;
            body["recipients"] = recipients;

            QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/sender-key");
            QNetworkRequest request(url);
            request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
            applyAuthHeaders(request);

            QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
            connect(reply, &QNetworkReply::finished, this, [reply, finish]() {
                reply->deleteLater();
                // Best-effort either way: the key is already adopted locally
                // and the caller is waiting to send - a failed publish just
                // means recipients won't have this version yet.
                finish();
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
    loadPeerSenderKeysFromDisk(chatId);

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/sender-keys");
    QNetworkRequest request(url);
    applyAuthHeaders(request);

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
            CredentialManager::instance().saveToken(
                QStringLiteral("peer_senderkey_%1").arg(cacheKey),
                QString::fromUtf8(keyBytes.toHex()));
        }
        onDone();
    });
}

void ChatService::sendGroupTextMessage(const QString& chatId, const QString& text,
                                        bool isForwarded, const QString& forwardedFromName,
                                        const QString& forwardedFromMessageId) {
    ensureGroupSenderKeyReady(chatId, [this, chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId]() {
        QString authToken = buildAuthHeader();
        if (authToken.isEmpty()) {
            emit messageError("Not authenticated. Please login first.");
            return;
        }

        const QByteArray innerGroup =
            Encryption::wrapEnvelope(text.toUtf8(), QString(), forwardedFromName);

        QString outContent;
        bool encrypted = true;
        int keyVersion = 0;
        int encryptionVersion = 1;

        if (m_mls2 && m_mls2->isReady()) {
            if (m_mls2->hasGroup(chatId) || m_mls2->ensureGroup(chatId)) {
                const QByteArray sealed = m_mls2->encrypt(chatId, innerGroup);
                if (!sealed.isEmpty()) {
                    outContent = QString::fromUtf8(sealed.toHex());
                    encryptionVersion = MlsV2Engine::kEncryptionVersion;
                    keyVersion = 0;
                } else {
                    qWarning() << "[mls-v2] protect failed; Sender Keys fallback";
                }
            }
        }
#ifdef HAVE_MLSPP
        qInfo() << "[mls-send]" << chatId
                << "ready=" << mlsReadyToSend(chatId)
                << "hasGroup=" << mlsHasGroup(chatId);
        if (encryptionVersion != MlsV2Engine::kEncryptionVersion && mlsReadyToSend(chatId)) {
            const QByteArray sealed = mlsProtect(chatId, innerGroup);
            if (!sealed.isEmpty()) {
                outContent = QString::fromUtf8(sealed.toHex());
                encryptionVersion = 5;
                keyVersion = 0;
            } else {
                qWarning() << "[mls-send]" << chatId << "protect failed; Sender Keys fallback";
            }
        }
        if (encryptionVersion == 1) {
#endif
#ifndef HAVE_MLSPP
        if (encryptionVersion == 1) {
#endif
        const QMap<int, QString> versions = m_mySenderKeys.value(chatId);
        if (versions.isEmpty() || versions.last().isEmpty()) {
            emit messageError("Cannot send: group encryption key is not ready");
            return;
        }
        QByteArray cipher = Encryption::secretBoxEncryptBytes(innerGroup, versions.last());
        if (cipher.isEmpty()) {
            emit messageError("Cannot send: group encryption failed");
            return;
        }
        outContent = QString::fromUtf8(cipher.toHex());
        keyVersion = versions.lastKey();
#ifdef HAVE_MLSPP
        }
#endif
#ifndef HAVE_MLSPP
        }
#endif

        QUrl url(Config::apiBaseUrl() + "/messages");
        QNetworkRequest request(url);
        request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        applyAuthHeaders(request);

        QJsonObject body;
        body["chat_id"] = chatId;
        body["chat_type"] = "group";
        body["content"] = outContent;
        body["encrypted"] = encrypted;
        body["key_version"] = keyVersion;
        // Tells the receiver which scheme opened it: 5 = MLS, 1 = Sender Key.
        body["encryption_version"] = encryptionVersion;
        appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);
    appendReplyField(body, takePendingReplyToId());

        QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
        connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, text]() {
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
            // Use the plaintext we just sent. Re-decrypting our own ciphertext
            // cannot work under MLS or v4: the sending ratchet does not retain
            // the message key it just consumed, so this returned the "Encrypted
            // message" placeholder for our own group messages. (It also passed a
            // hardcoded encryption_version of 1, which was wrong for both.)
            item["content"] = text;
            item["createdAt"] = data["created_at"].toString();
            item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);
            item["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();

            // Cache it open, for the same reason - otherwise reopening the chat
            // re-reads the server's ciphertext and shows a placeholder again.
            if (m_messageCache) {
                MessageCache::Entry sentEntry;
                sentEntry.id = item["id"].toString();
                sentEntry.senderId = item["senderId"].toString();
                sentEntry.content = text;
                sentEntry.encrypted = false;
                sentEntry.createdAt = item["createdAt"].toString();
                sentEntry.isForwarded = item["isForwarded"].toBool();
                sentEntry.forwardedFromName = item["forwardedFromName"].toString();
                sentEntry.encryptionVersion = 1;
                m_messageCache->saveMessages(chatId, { sentEntry });
            }
            emit messageSent(chatId, item);
        });
    });
}

QByteArray ChatService::encryptBytesForChat(const QString& chatId, const QByteArray& plain) const {
    return encryptBytesForChat(chatId, plain, nullptr, nullptr);
}

QByteArray ChatService::encryptBytesForChat(const QString& chatId, const QByteArray& plain,
                                             int* outKeyVersion, int* outEncVersion,
                                             const QString& fileName, const QString& forwardedFrom,
                                             qint64 durationMs, qint64 fileSize,
                                             const QString& thumbnailUrl,
                                             const QString& mediaFileUrl) const {
    if (outKeyVersion) *outKeyVersion = 0;
    if (outEncVersion) *outEncVersion = 1;
    const QByteArray inner = Encryption::wrapEnvelope(plain, fileName, forwardedFrom, durationMs, fileSize, thumbnailUrl, mediaFileUrl);
    if (m_chatType.value(chatId).compare(QStringLiteral("group"), Qt::CaseInsensitive) == 0) {
#ifdef HAVE_MLSPP
        if (mlsReadyToSend(chatId)) {
            const QByteArray sealed = mlsProtect(chatId, inner);
            if (!sealed.isEmpty()) {
                if (outEncVersion) *outEncVersion = 5;
                if (outKeyVersion) *outKeyVersion = 0;
                return sealed;
            }
        }
#endif
        const QMap<int, QString> versions = m_mySenderKeys.value(chatId);
        if (versions.isEmpty() || versions.last().isEmpty()) return QByteArray();
        QByteArray cipher = Encryption::secretBoxEncryptBytes(inner, versions.last());
        if (!cipher.isEmpty() && outKeyVersion) *outKeyVersion = versions.lastKey();
        return cipher;
    }
    QString otherPub = m_chatOtherPub.value(chatId);
    if (otherPub.isEmpty()) return QByteArray();
    QByteArray v4 = sealDirectV4(chatId, inner);
    if (!v4.isEmpty()) {
        if (outEncVersion) *outEncVersion = 4;
        return v4;
    }
    QByteArray eph = Encryption::boxEncryptBytesEphemeral(inner, otherPub);
    if (!eph.isEmpty()) {
        if (outEncVersion) *outEncVersion = 2;
        return eph;
    }
    QString myPriv = m_authService ? m_authService->e2eePrivateKey() : QString();
    if (myPriv.isEmpty()) return QByteArray();
    return Encryption::boxEncryptBytes(inner, otherPub, myPriv);
}

QByteArray ChatService::decryptBytesForChat(const QString& chatId, const QByteArray& payload,
                                             const QString& senderId, int keyVersion,
                                             int encryptionVersion) const {
    QByteArray inner = decryptToBytes(chatId, payload, senderId, keyVersion, encryptionVersion);
    if (inner.isEmpty()) return {};
    return Encryption::unwrapEnvelope(inner).payload;
}

void ChatService::sendVoiceNote(const QString& chatId, const QString& chatType,
                                 const QString& localFilePath, qint64 durationMs,
                                 bool isForwarded, const QString& forwardedFromName,
                                 const QString& forwardedFromMessageId) {
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

    auto upload = [this, chatId, chatType, raw, durationMs, isForwarded, forwardedFromName, forwardedFromMessageId]() {
        int keyVersion = 0;
        int encryptionVersion = 1;
        QByteArray sealed = encryptBytesForChat(chatId, raw, &keyVersion, &encryptionVersion, QString(), forwardedFromName, durationMs);
        if (sealed.isEmpty()) {
            emit voiceUploadError("Cannot encrypt voice note");
            return;
        }
        bool encrypted = true;
        QByteArray payload = sealed;

        auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
        QHttpPart part;
        part.setHeader(QNetworkRequest::ContentTypeHeader, "application/octet-stream");
        part.setHeader(QNetworkRequest::ContentDispositionHeader,
                        QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"voice.bin\"")));
        part.setBody(payload);
        multiPart->append(part);

        QUrl url(Config::apiBaseUrl() + "/messages/voice");
        QNetworkRequest request(url);
        applyAuthHeaders(request);

        QNetworkReply* reply = m_networkManager->post(request, multiPart);
        multiPart->setParent(reply);
        connect(reply, &QNetworkReply::finished, this,
                [this, reply, chatId, chatType, durationMs, encrypted, keyVersion, encryptionVersion,
                 isForwarded, forwardedFromName, forwardedFromMessageId]() {
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
            QByteArray meta = encryptBytesForChat(chatId, QByteArray(), nullptr, nullptr, QString(), forwardedFromName,
                                                  durationMs, 0, QString(), fileUrl);
            if (meta.isEmpty()) {
                emit voiceUploadError("Cannot encrypt voice note");
                return;
            }
            sendVoiceMessage(chatId, chatType, fileUrl, durationMs, encrypted, keyVersion, encryptionVersion,
                             isForwarded, forwardedFromName, forwardedFromMessageId,
                             QString::fromLatin1(meta.toHex()));
        });
    };

    if (m_chatType.value(chatId).compare(QStringLiteral("group"), Qt::CaseInsensitive) == 0) {
        ensureGroupSenderKeyReady(chatId, upload);
    } else {
        refreshChatDevices(chatId, upload);
    }
}

void ChatService::sendVideoNote(const QString& chatId, const QString& chatType,
                                 const QString& localFilePath, qint64 durationMs,
                                 bool isForwarded, const QString& forwardedFromName,
                                 const QString& forwardedFromMessageId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit videoNoteUploadError("Not authenticated. Please login first.");
        QFile::remove(localFilePath);
        return;
    }

    QFile file(localFilePath);
    if (!file.open(QIODevice::ReadOnly)) {
        emit videoNoteUploadError("Could not read the recorded video message");
        return;
    }
    QByteArray raw = file.readAll();
    file.close();

    if (raw.isEmpty()) {
        QFile::remove(localFilePath);
        emit videoNoteUploadError("The recording was empty");
        return;
    }

    // Grab a poster frame BEFORE the temp recording is deleted - it is the only
    // local copy of the video. The upload waits on this so the message can
    // carry thumbnail_url from the start; otherwise the recipient sees an empty
    // circle until the whole video has downloaded.
    auto* thumbnailer = new VideoThumbnailer(this);
    connect(thumbnailer, &VideoThumbnailer::ready, this,
            [this, chatId, chatType, raw, durationMs, localFilePath,
             isForwarded, forwardedFromName, forwardedFromMessageId](const QString& jpegPath) {
        QFile::remove(localFilePath);
        uploadVideoNote(chatId, chatType, raw, durationMs, jpegPath,
                        isForwarded, forwardedFromName, forwardedFromMessageId);
    });
    connect(thumbnailer, &VideoThumbnailer::failed, this,
            [this, chatId, chatType, raw, durationMs, localFilePath,
             isForwarded, forwardedFromName, forwardedFromMessageId]() {
        // A missing poster degrades cleanly, so it is not worth failing a send.
        QFile::remove(localFilePath);
        uploadVideoNote(chatId, chatType, raw, durationMs, QString(),
                        isForwarded, forwardedFromName, forwardedFromMessageId);
    });
    thumbnailer->grab(localFilePath);
}

void ChatService::uploadVideoNote(const QString& chatId, const QString& chatType,
                                   const QByteArray& raw, qint64 durationMs,
                                   const QString& thumbnailPath,
                                   bool isForwarded, const QString& forwardedFromName,
                                   const QString& forwardedFromMessageId) {
    auto upload = [this, chatId, chatType, raw, durationMs, thumbnailPath,
                   isForwarded, forwardedFromName, forwardedFromMessageId]() {
        QString authToken = buildAuthHeader();
        if (authToken.isEmpty()) {
            emit videoNoteUploadError("Not authenticated. Please login first.");
            return;
        }

        int keyVersion = 0;
        int encryptionVersion = 1;
        QByteArray sealed = encryptBytesForChat(chatId, raw, &keyVersion, &encryptionVersion, QString(), forwardedFromName, durationMs);
        if (sealed.isEmpty()) {
            emit videoNoteUploadError("Cannot encrypt video note");
            return;
        }
        bool encrypted = true;
        QByteArray payload = sealed;

        auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
        QHttpPart part;
        part.setHeader(QNetworkRequest::ContentTypeHeader, "application/octet-stream");
        part.setHeader(QNetworkRequest::ContentDispositionHeader,
                        QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"video.bin\"")));
        part.setBody(payload);
        multiPart->append(part);

        QUrl url(Config::apiBaseUrl() + "/messages/video-note");
        QNetworkRequest request(url);
        applyAuthHeaders(request);

        QNetworkReply* reply = m_networkManager->post(request, multiPart);
        multiPart->setParent(reply);
        connect(reply, &QNetworkReply::uploadProgress, this, [this](qint64 sent, qint64 total) {
            emit videoNoteUploadProgress(sent, total);
        });
        connect(reply, &QNetworkReply::finished, this,
                [this, reply, chatId, chatType, durationMs, encrypted, keyVersion, encryptionVersion, thumbnailPath,
                 isForwarded, forwardedFromName, forwardedFromMessageId]() {
            reply->deleteLater();
            if (reply->error() != QNetworkReply::NoError) {
                emit videoNoteUploadError(reply->errorString());
                return;
            }
            QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
            QString fileUrl = root["file_url"].toString();
            if (fileUrl.isEmpty()) {
                emit videoNoteUploadError("Server did not return a file URL");
                return;
            }
            uploadVideoNoteThumbnail(chatId, chatType, fileUrl, durationMs, encrypted, keyVersion, encryptionVersion, thumbnailPath,
                                     isForwarded, forwardedFromName, forwardedFromMessageId,
                                     QString());
        });
    };

    if (m_chatType.value(chatId).compare(QStringLiteral("group"), Qt::CaseInsensitive) == 0) {
        ensureGroupSenderKeyReady(chatId, upload);
    } else {
        refreshChatDevices(chatId, upload);
    }
}

void ChatService::uploadVideoNoteThumbnail(const QString& chatId, const QString& chatType,
                                            const QString& fileUrl, qint64 durationMs,
                                            bool encrypted, int keyVersion, int encryptionVersion,
                                            const QString& thumbnailPath,
                                            bool isForwarded, const QString& forwardedFromName,
                                            const QString& forwardedFromMessageId,
                                            const QString& sealedContent) {
    QFile thumb(thumbnailPath);
    if (thumbnailPath.isEmpty() || !thumb.open(QIODevice::ReadOnly)) {
        QByteArray meta = encryptBytesForChat(chatId, QByteArray(), nullptr, nullptr, QString(), forwardedFromName, durationMs, 0, QString(), fileUrl);
        if (meta.isEmpty()) {
            emit videoNoteUploadError("Cannot encrypt video note");
            return;
        }
        sendVideoNoteMessage(chatId, chatType, fileUrl, QString(), durationMs, encrypted, keyVersion, encryptionVersion,
                             isForwarded, forwardedFromName, forwardedFromMessageId,
                             QString::fromLatin1(meta.toHex()));
        return;
    }
    QByteArray thumbBytes = thumb.readAll();
    thumb.close();
    QFile::remove(thumbnailPath);

    // The poster gets the same encryption as the video. It is a frame *of* the
    // video, so leaving it in the clear would leak exactly what the encryption
    // is protecting.
    QByteArray payload;
    if (encrypted) {
        payload = encryptBytesForChat(chatId, thumbBytes);
        if (payload.isEmpty()) {
            emit videoNoteUploadError("Cannot encrypt video thumbnail");
            return;
        }
    } else {
        payload = thumbBytes;
    }

    auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart part;
    part.setHeader(QNetworkRequest::ContentTypeHeader, "application/octet-stream");
    part.setHeader(QNetworkRequest::ContentDispositionHeader,
                    QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"thumb.bin\"")));
    part.setBody(payload);
    multiPart->append(part);

    QUrl url(Config::apiBaseUrl() + "/messages/video-thumb");
    QNetworkRequest request(url);
    applyAuthHeaders(request);

    QNetworkReply* reply = m_networkManager->post(request, multiPart);
    multiPart->setParent(reply);
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, chatId, chatType, fileUrl, durationMs, encrypted, keyVersion, encryptionVersion,
             isForwarded, forwardedFromName, forwardedFromMessageId, sealedContent]() {
        reply->deleteLater();
        QString thumbUrl;
        if (reply->error() == QNetworkReply::NoError) {
            QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
            thumbUrl = root["thumbnail_url"].toString();
            if (thumbUrl.isEmpty()) thumbUrl = root["file_url"].toString();
        }
        // A failed poster upload must not lose the video message itself.
        QByteArray meta = encryptBytesForChat(chatId, QByteArray(), nullptr, nullptr, QString(), forwardedFromName,
                                              durationMs, 0, thumbUrl, fileUrl);
        if (meta.isEmpty()) {
            emit videoNoteUploadError("Cannot encrypt video note");
            return;
        }
        sendVideoNoteMessage(chatId, chatType, fileUrl, thumbUrl, durationMs, encrypted, keyVersion, encryptionVersion,
                             isForwarded, forwardedFromName, forwardedFromMessageId,
                             QString::fromLatin1(meta.toHex()));
    });
}

void ChatService::preparePlayableVideoNote(const QString& chatId, const QString& messageId,
                                            const QString& fileUrl, bool encrypted,
                                            const QString& senderId, int keyVersion,
                                            int encryptionVersion) {
    QString cacheDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + "/roundvideo";
    QDir().mkpath(cacheDir);
    QString localPath = cacheDir + "/" + messageId + ".mp4";

    if (QFile::exists(localPath)) {
        emit videoNoteReadyForPlayback(messageId, localPath);
        return;
    }

    QUrl url(Config::resolveServerUrl(fileUrl));
    QNetworkRequest request(url);
    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, chatId, messageId, localPath, encrypted, senderId, keyVersion, encryptionVersion]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit videoNotePlaybackError(messageId, reply->errorString());
            return;
        }
        QByteArray raw = reply->readAll();
        QByteArray playable = raw;
        if (encrypted) {
            playable = decryptBytesForChat(chatId, raw, senderId, keyVersion, encryptionVersion);
            if (playable.isEmpty()) {
                emit videoNotePlaybackError(messageId, "Could not decrypt video message");
                return;
            }
        }
        QFile out(localPath);
        if (!out.open(QIODevice::WriteOnly)) {
            emit videoNotePlaybackError(messageId, "Could not save video message locally");
            return;
        }
        out.write(playable);
        out.close();
        emit videoNoteReadyForPlayback(messageId, localPath);
    });
}

void ChatService::prepareVideoNoteThumbnail(const QString& chatId, const QString& messageId,
                                             const QString& thumbnailUrl, bool encrypted,
                                             const QString& senderId, int keyVersion,
                                             int encryptionVersion) {
    if (thumbnailUrl.isEmpty()) return;

    QString cacheDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + "/roundvideo";
    QDir().mkpath(cacheDir);
    QString localPath = cacheDir + "/" + messageId + "_thumb.jpg";

    if (QFile::exists(localPath)) {
        emit videoNoteThumbnailReady(messageId, localPath);
        return;
    }

    QUrl url(Config::resolveServerUrl(thumbnailUrl));
    QNetworkReply* reply = m_networkManager->get(QNetworkRequest(url));
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, chatId, messageId, localPath, encrypted, senderId, keyVersion, encryptionVersion]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) return;  // a missing poster is not worth surfacing

        QByteArray raw = reply->readAll();
        QByteArray image = encrypted ? decryptBytesForChat(chatId, raw, senderId, keyVersion, encryptionVersion) : raw;
        if (image.isEmpty()) return;

        QFile out(localPath);
        if (!out.open(QIODevice::WriteOnly)) return;
        out.write(image);
        out.close();
        emit videoNoteThumbnailReady(messageId, localPath);
    });
}

void ChatService::preparePlayableVoice(const QString& chatId, const QString& messageId,
                                        const QString& fileUrl, bool encrypted,
                                        const QString& senderId, int keyVersion,
                                        int encryptionVersion) {
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
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, chatId, messageId, localPath, encrypted, senderId, keyVersion, encryptionVersion]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit voicePlaybackError(messageId, reply->errorString());
            return;
        }
        QByteArray raw = reply->readAll();
        QByteArray playable = raw;
        if (encrypted) {
            playable = decryptBytesForChat(chatId, raw, senderId, keyVersion, encryptionVersion);
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
                                    const QString& fileUrl, qint64 durationMs, bool encrypted,
                                    int keyVersion, int encryptionVersion,
                                    bool isForwarded, const QString& forwardedFromName,
                                    const QString& forwardedFromMessageId,
                                    const QString& sealedContent) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = chatType;
    // The bubble renders from file_url, so content stays empty rather than a
    // bogus placeholder string a future/other client might try to display.
    body["content"] = sealedContent;
    body["content_type"] = "audio";
    body["encrypted"] = encrypted;
    body["file_url"] = QString();
    body["file_type"] = "audio";
    body["duration_ms"] = 0;
    if (keyVersion > 0) body["key_version"] = keyVersion;
    body["encryption_version"] = encryptionVersion <= 0 ? 1 : encryptionVersion;
    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);
    appendReplyField(body, takePendingReplyToId());

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, durationMs, fileUrl]() {
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
        item["fileUrl"] = fileUrl;
        item["fileType"] = data["file_type"].toString();
        item["durationMs"] = durationMs;
        item["encrypted"] = data["encrypted"].toBool(false);
        item["keyVersion"] = data["key_version"].toInt(0);
        item["encryptionVersion"] = data["encryption_version"].toInt(1);
        item["createdAt"] = data["created_at"].toString();
        item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);
        item["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();
        item["replyToId"] = data[QStringLiteral("reply_to_id")].toString();
        applyEnvelopeMeta(item, openCipher(chatId, data["content"].toString(), data["encrypted"].toBool(false),
                                           data["sender_id"].toString(), data["key_version"].toInt(),
                                           data["encryption_version"].toInt(1)));
        emit voiceMessageSent(chatId, item);
    });
}

void ChatService::sendVideoNoteMessage(const QString& chatId, const QString& chatType,
                                        const QString& fileUrl, const QString& thumbnailUrl,
                                        qint64 durationMs, bool encrypted, int keyVersion,
                                        int encryptionVersion,
                                        bool isForwarded, const QString& forwardedFromName,
                                        const QString& forwardedFromMessageId,
                                        const QString& sealedContent) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = chatType;
    // Empty for the same reason as a voice note: the bubble renders from
    // file_url, and a placeholder string would be displayed by any client that
    // does not understand round videos.
    body["content"] = sealedContent;
    body["content_type"] = "video_note";
    body["encrypted"] = encrypted;
    body["file_url"] = QString();
    body["file_type"] = "video_note";
    body["duration_ms"] = 0;
    body["thumbnail_url"] = QString();
    if (keyVersion > 0) body["key_version"] = keyVersion;
    body["encryption_version"] = encryptionVersion <= 0 ? 1 : encryptionVersion;
    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);
    appendReplyField(body, takePendingReplyToId());

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, durationMs, thumbnailUrl, fileUrl]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit videoNoteUploadError(reply->errorString());
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        if (root.contains("error")) {
            emit videoNoteUploadError(root["error"].toString());
            return;
        }
        QJsonObject data = root["data"].toObject();
        QVariantMap item;
        item["id"] = data["id"].toString();
        item["senderId"] = data["sender_id"].toString();
        item["fileUrl"] = fileUrl.isEmpty() ? data["file_url"].toString() : fileUrl;
        item["fileType"] = data["file_type"].toString();
        item["thumbnailUrl"] = thumbnailUrl;
        item["durationMs"] = durationMs;
        item["encrypted"] = data["encrypted"].toBool(false);
        item["keyVersion"] = data["key_version"].toInt(0);
        item["createdAt"] = data["created_at"].toString();
        item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);
        item["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();
        item["replyToId"] = data[QStringLiteral("reply_to_id")].toString();
        applyEnvelopeMeta(item, openCipher(chatId, data["content"].toString(), data["encrypted"].toBool(false),
                                           data["sender_id"].toString(), data["key_version"].toInt(),
                                           data["encryption_version"].toInt(1)));
        emit videoNoteMessageSent(chatId, item);
    });
}

void ChatService::sendAttachment(const QString& chatId, const QString& chatType,
                                  const QString& localFileUrl, const QString& contentType,
                                  bool isForwarded, const QString& forwardedFromName,
                                  const QString& forwardedFromMessageId) {
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

    auto upload = [this, chatId, chatType, contentType, fileName, raw,
                   isForwarded, forwardedFromName, forwardedFromMessageId]() {
        int keyVersion = 0;
        int encryptionVersion = 1;
        QByteArray sealed = encryptBytesForChat(chatId, raw, &keyVersion, &encryptionVersion, fileName, forwardedFromName, 0, raw.size());
        if (sealed.isEmpty()) {
            emit attachmentUploadError("Cannot encrypt attachment");
            return;
        }
        bool encrypted = true;
        QByteArray payload = sealed;

        auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
        QHttpPart part;
        part.setHeader(QNetworkRequest::ContentTypeHeader, "application/octet-stream");
        part.setHeader(QNetworkRequest::ContentDispositionHeader,
                        QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"attachment.bin\"")));
        part.setBody(payload);
        multiPart->append(part);

        QUrl url(Config::apiBaseUrl() + "/messages/attachment");
        QNetworkRequest request(url);
        applyAuthHeaders(request);

        QNetworkReply* reply = m_networkManager->post(request, multiPart);
        multiPart->setParent(reply);
        connect(reply, &QNetworkReply::finished, this,
                [this, reply, chatId, chatType, fileName, contentType, encrypted, keyVersion, encryptionVersion,
                 isForwarded, forwardedFromName, forwardedFromMessageId, raw]() {
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
            QByteArray meta = encryptBytesForChat(chatId, QByteArray(), nullptr, nullptr, fileName, forwardedFromName, 0, raw.size(), QString(), fileUrl);
            if (meta.isEmpty()) {
                emit attachmentUploadError("Cannot encrypt attachment");
                return;
            }
            sendAttachmentMessage(chatId, chatType, fileUrl, fileName, contentType, fileSize, encrypted, keyVersion, encryptionVersion,
                                  isForwarded, forwardedFromName, forwardedFromMessageId,
                                  QString::fromLatin1(meta.toHex()));
        });
    };

    if (m_chatType.value(chatId).compare(QStringLiteral("group"), Qt::CaseInsensitive) == 0) {
        ensureGroupSenderKeyReady(chatId, upload);
    } else {
        refreshChatDevices(chatId, upload);
    }
}

void ChatService::sendAttachmentMessage(const QString& chatId, const QString& chatType,
                                         const QString& fileUrl, const QString& fileName,
                                         const QString& contentType, qint64 fileSize, bool encrypted,
                                         int keyVersion, int encryptionVersion,
                                         bool isForwarded, const QString& forwardedFromName,
                                         const QString& forwardedFromMessageId,
                                         const QString& sealedContent) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit messageError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/messages");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

    QJsonObject body;
    body["chat_id"] = chatId;
    body["chat_type"] = chatType;
    body["content"] = sealedContent;
    body["content_type"] = contentType;
    body["encrypted"] = encrypted;
    body["file_url"] = QString();
    body["file_type"] = contentType;
    body["file_name"] = QString();
    body["file_size"] = 0;
    if (keyVersion > 0) body["key_version"] = keyVersion;
    body["encryption_version"] = encryptionVersion <= 0 ? 1 : encryptionVersion;
    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);
    appendReplyField(body, takePendingReplyToId());

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, fileSize, fileName, fileUrl]() {
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
        item["fileUrl"] = fileUrl;
        item["fileType"] = data["file_type"].toString();
        item["fileName"] = fileName;
        item["fileSize"] = fileSize;
        item["encrypted"] = data["encrypted"].toBool(false);
        item["keyVersion"] = data["key_version"].toInt(0);
        item["createdAt"] = data["created_at"].toString();
        item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);
        item["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();
        item["replyToId"] = data[QStringLiteral("reply_to_id")].toString();
        applyEnvelopeMeta(item, openCipher(chatId, data["content"].toString(), data["encrypted"].toBool(false),
                                           data["sender_id"].toString(), data["key_version"].toInt(),
                                           data["encryption_version"].toInt(1)));
        emit attachmentMessageSent(chatId, item);
    });
}

void ChatService::prepareAttachment(const QString& chatId, const QString& messageId,
                                      const QString& fileUrl, bool encrypted,
                                      const QString& fileName,
                                      const QString& senderId, int keyVersion,
                                      int encryptionVersion) {
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
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, chatId, messageId, cacheDir, fileName, encrypted, senderId, keyVersion, encryptionVersion]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit attachmentError(messageId, reply->errorString());
            return;
        }
        QByteArray raw = reply->readAll();
        QByteArray plain = raw;
        QString resolvedName = fileName;
        if (encrypted) {
            QByteArray inner = decryptToBytes(chatId, raw, senderId, keyVersion, encryptionVersion);
            if (inner.isEmpty()) {
                emit attachmentError(messageId, "Could not decrypt attachment");
                return;
            }
            Encryption::MessageEnvelope env = Encryption::unwrapEnvelope(inner);
            plain = env.payload;
            if (resolvedName.isEmpty()) resolvedName = env.fileName;
        }
        QString suffix = QFileInfo(resolvedName).suffix();
        QString outPath = cacheDir + "/" + messageId + (suffix.isEmpty() ? QString() : "." + suffix);
        if (QFile::exists(outPath)) {
            emit attachmentReady(messageId, outPath);
            return;
        }
        QFile out(outPath);
        if (!out.open(QIODevice::WriteOnly)) {
            emit attachmentError(messageId, "Could not save attachment locally");
            return;
        }
        out.write(plain);
        out.close();
        emit attachmentReady(messageId, outPath);
    });
}

void ChatService::setupForwardSignalHooks() {
    // Advance the forward queue when the current item's prepare or send finishes.
    // Only acts while m_forwardPhase is set - normal sends leave the phase Idle.
    auto onSendOk = [this](const QString&, const QVariantMap&) {
        if (m_forwardPhase == ForwardPhase::WaitingSend)
            finishCurrentForward(true);
    };
    connect(this, &ChatService::messageSent, this, onSendOk);
    connect(this, &ChatService::voiceMessageSent, this, onSendOk);
    connect(this, &ChatService::attachmentMessageSent, this, onSendOk);
    connect(this, &ChatService::videoNoteMessageSent, this, onSendOk);

    auto onSendFail = [this](const QString&) {
        if (m_forwardPhase == ForwardPhase::WaitingSend)
            finishCurrentForward(false);
    };
    connect(this, &ChatService::messageError, this, onSendFail);
    connect(this, &ChatService::voiceUploadError, this, onSendFail);
    connect(this, &ChatService::attachmentUploadError, this, onSendFail);
    connect(this, &ChatService::videoNoteUploadError, this, onSendFail);

    auto onMediaReady = [this](const QString& messageId, const QString& localPath) {
        if (m_forwardPhase != ForwardPhase::PreparingMedia) return;
        if (messageId != m_forwardAwaitingMessageId) return;
        if (m_forwardQueue.isEmpty()) return;

        const ForwardItem item = m_forwardQueue.first();
        // Voice/video send paths delete their local file after reading - copy
        // so the playback cache for the source message stays intact.
        const QString sendPath = (item.contentType == QStringLiteral("audio")
                                  || item.contentType == QStringLiteral("video_note"))
            ? copyForForwardSend(localPath)
            : localPath;
        if (sendPath.isEmpty()) {
            finishCurrentForward(false);
            return;
        }

        m_forwardPhase = ForwardPhase::WaitingSend;
        if (item.contentType == QStringLiteral("audio")) {
            sendVoiceNote(m_forwardTargetChatId, m_forwardTargetChatType, sendPath, item.durationMs,
                          true, item.forwardedFromName, item.messageId);
        } else if (item.contentType == QStringLiteral("video_note")) {
            sendVideoNote(m_forwardTargetChatId, m_forwardTargetChatType, sendPath, item.durationMs,
                          true, item.forwardedFromName, item.messageId);
        } else {
            // image / file - sendAttachment never deletes the source path
            const QString url = QUrl::fromLocalFile(sendPath).toString();
            const QString ct = item.contentType.isEmpty() ? QStringLiteral("file") : item.contentType;
            sendAttachment(m_forwardTargetChatId, m_forwardTargetChatType, url, ct,
                           true, item.forwardedFromName, item.messageId);
        }
    };
    connect(this, &ChatService::voiceReadyForPlayback, this, onMediaReady);
    connect(this, &ChatService::attachmentReady, this, onMediaReady);
    connect(this, &ChatService::videoNoteReadyForPlayback, this, onMediaReady);

    auto onMediaFail = [this](const QString& messageId, const QString&) {
        if (m_forwardPhase != ForwardPhase::PreparingMedia) return;
        if (messageId != m_forwardAwaitingMessageId) return;
        finishCurrentForward(false);
    };
    connect(this, &ChatService::voicePlaybackError, this, onMediaFail);
    connect(this, &ChatService::attachmentError, this, onMediaFail);
    connect(this, &ChatService::videoNotePlaybackError, this, onMediaFail);
}

QString ChatService::copyForForwardSend(const QString& localPath) const {
    if (localPath.isEmpty() || !QFile::exists(localPath)) return {};
    QString dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation)
                  + QStringLiteral("/messenger_forward");
    QDir().mkpath(dir);
    QString suffix = QFileInfo(localPath).completeSuffix();
    QString dest = dir + QLatin1Char('/') + QUuid::createUuid().toString(QUuid::WithoutBraces);
    if (!suffix.isEmpty()) dest += QLatin1Char('.') + suffix;
    if (!QFile::copy(localPath, dest)) return {};
    return dest;
}

void ChatService::forwardMessages(const QString& sourceChatId,
                                   const QString& targetChatId,
                                   const QString& targetChatType,
                                   const QVariantList& items) {
    if (m_forwardPhase != ForwardPhase::Idle) {
        qWarning() << "[ChatService] forwardMessages ignored - a forward is already in progress";
        return;
    }
    if (sourceChatId.isEmpty() || targetChatId.isEmpty() || items.isEmpty()) {
        emit forwardFinished(0, 0);
        return;
    }

    m_forwardQueue.clear();
    for (const QVariant& v : items) {
        const QVariantMap m = v.toMap();
        ForwardItem item;
        item.messageId = m.value(QStringLiteral("messageId")).toString();
        item.contentType = m.value(QStringLiteral("contentType")).toString();
        item.text = m.value(QStringLiteral("text")).toString();
        item.fileUrl = m.value(QStringLiteral("fileUrl")).toString();
        item.encrypted = m.value(QStringLiteral("encrypted")).toBool();
        item.senderId = m.value(QStringLiteral("senderId")).toString();
        item.keyVersion = m.value(QStringLiteral("keyVersion")).toInt();
        item.encryptionVersion = m.value(QStringLiteral("encryptionVersion")).toInt();
        if (item.encryptionVersion <= 0) item.encryptionVersion = 1;
        item.fileName = m.value(QStringLiteral("fileName")).toString();
        item.fileSize = m.value(QStringLiteral("fileSize")).toLongLong();
        item.durationMs = m.value(QStringLiteral("durationMs")).toLongLong();
        item.thumbnailUrl = m.value(QStringLiteral("thumbnailUrl")).toString();
        item.forwardedFromName = m.value(QStringLiteral("forwardedFromName")).toString();
        if (item.messageId.isEmpty()) continue;
        m_forwardQueue.append(item);
    }

    m_forwardSourceChatId = sourceChatId;
    m_forwardTargetChatId = targetChatId;
    m_forwardTargetChatType = targetChatType.isEmpty() ? QStringLiteral("direct") : targetChatType;
    m_forwardSuccess = 0;
    m_forwardFail = 0;
    m_forwardAwaitingMessageId.clear();

    if (m_forwardQueue.isEmpty()) {
        emit forwardFinished(0, 0);
        return;
    }
    processNextForward();
}

void ChatService::processNextForward() {
    if (m_forwardQueue.isEmpty()) {
        m_forwardPhase = ForwardPhase::Idle;
        m_forwardAwaitingMessageId.clear();
        const int ok = m_forwardSuccess;
        const int fail = m_forwardFail;
        m_forwardSourceChatId.clear();
        m_forwardTargetChatId.clear();
        m_forwardTargetChatType.clear();
        emit forwardFinished(ok, fail);
        return;
    }

    const ForwardItem& item = m_forwardQueue.first();
    const bool isMedia = item.contentType == QStringLiteral("audio")
                         || item.contentType == QStringLiteral("image")
                         || item.contentType == QStringLiteral("file")
                         || item.contentType == QStringLiteral("video_note");

    if (!isMedia) {
        m_forwardPhase = ForwardPhase::WaitingSend;
        sendMessage(m_forwardTargetChatId, item.text, m_forwardTargetChatType,
                    true, item.forwardedFromName, item.messageId);
        return;
    }

    if (item.fileUrl.isEmpty()) {
        finishCurrentForward(false);
        return;
    }

    m_forwardPhase = ForwardPhase::PreparingMedia;
    m_forwardAwaitingMessageId = item.messageId;

    if (item.contentType == QStringLiteral("audio")) {
        preparePlayableVoice(m_forwardSourceChatId, item.messageId, item.fileUrl, item.encrypted,
                             item.senderId, item.keyVersion, item.encryptionVersion);
    } else if (item.contentType == QStringLiteral("video_note")) {
        preparePlayableVideoNote(m_forwardSourceChatId, item.messageId, item.fileUrl, item.encrypted,
                                 item.senderId, item.keyVersion, item.encryptionVersion);
    } else {
        prepareAttachment(m_forwardSourceChatId, item.messageId, item.fileUrl, item.encrypted,
                          item.fileName, item.senderId, item.keyVersion, item.encryptionVersion);
    }
}

void ChatService::finishCurrentForward(bool success) {
    if (m_forwardPhase == ForwardPhase::Idle) return;
    if (success) ++m_forwardSuccess;
    else ++m_forwardFail;
    if (!m_forwardQueue.isEmpty())
        m_forwardQueue.removeFirst();
    m_forwardAwaitingMessageId.clear();
    // Defer so nested signal handlers (e.g. messageSent during processNext)
    // finish before the next item starts.
    QMetaObject::invokeMethod(this, [this]() { processNextForward(); }, Qt::QueuedConnection);
}

void ChatService::markAsRead(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + "/chats/" + chatId + "/read");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

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
    applyAuthHeaders(request);

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

void ChatService::blockUser(const QString& userId, const QString& chatId) {
    if (userId.isEmpty()) {
        emit blockUserError(QStringLiteral("Cannot block this user"));
        return;
    }
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + "/users/" + userId + "/block");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

    QNetworkReply* reply = m_networkManager->post(request, QByteArray());
    connect(reply, &QNetworkReply::finished, this, [this, reply, userId, chatId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit blockUserError(reply->errorString());
            return;
        }
        // Keep the chat in the list — right-click switches to Unblock.
        emit userBlocked(userId, chatId);
    });
}

void ChatService::unblockUser(const QString& userId) {
    if (userId.isEmpty()) return;
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) return;

    QUrl url(Config::apiBaseUrl() + "/users/" + userId + "/block");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

    QNetworkReply* reply = m_networkManager->deleteResource(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, userId]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit blockUserError(reply->errorString());
            return;
        }
        emit userUnblocked(userId);
    });
}

void ChatService::fetchBlockedUsers() {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit blockedUsersFetched(QVariantList());
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/users/me/blocks");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    applyAuthHeaders(request);

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            emit blockUserError(reply->errorString());
            emit blockedUsersFetched(QVariantList());
            return;
        }

        const QJsonDocument doc = QJsonDocument::fromJson(reply->readAll());
        const QJsonArray arr = doc.object().value(QStringLiteral("users")).toArray();
        QVariantList out;
        out.reserve(arr.size());
        for (const QJsonValue& v : arr) {
            const QJsonObject o = v.toObject();
            QVariantMap m;
            m[QStringLiteral("id")] = o.value(QStringLiteral("id")).toString();
            m[QStringLiteral("username")] = o.value(QStringLiteral("username")).toString();
            m[QStringLiteral("display_name")] = o.value(QStringLiteral("display_name")).toString();
            m[QStringLiteral("avatar_url")] = o.value(QStringLiteral("avatar_url")).toString();
            out.append(m);
        }
        emit blockedUsersFetched(out);
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
            QString name = userObj["display_name"].toString();
            if (name.isEmpty()) name = userObj["username"].toString();
            const QString previous = m_chatOtherPub.value(chatId);
            applyPeerIdentity(chatId, pub, name);
            if (previous != m_chatOtherPub.value(chatId)) {
                ++m_cryptoRevision;
                emit cryptoRevisionChanged();
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
                              msgObj["sender_id"].toString(), msgObj["key_version"].toInt(),
                              msgObj["encryption_version"].toInt(1));
        lastMessage["sender_id"] = msgObj["sender_id"].toString();
        lastMessage["content_type"] = contentType;
        lastMessage["created_at"] = msgObj["created_at"].toString();
        item["last_message"] = lastMessage;
    }

    return item;
}