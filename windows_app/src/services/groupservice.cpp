#include "groupservice.h"
#include "../utils/config.h"
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QHttpMultiPart>
#include <QHttpPart>
#include <QFile>
#include <QFileInfo>
#include <QMimeDatabase>
#include <QUrl>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonValue>
#include <QDebug>

GroupService::GroupService(AuthService* authService, QObject* parent)
    : QObject(parent)
    , m_authService(authService)
{
    m_networkManager = new QNetworkAccessManager(this);
}

QString GroupService::buildAuthHeader() const {
    if (m_authService) {
        QString token = m_authService->authToken();
        if (!token.isEmpty()) return "Bearer " + token;
    }
    return {};
}

QString GroupService::errorMessageFrom(QNetworkReply* reply, const QByteArray& body) const {
    QJsonParseError parseError;
    QJsonDocument doc = QJsonDocument::fromJson(body, &parseError);
    if (parseError.error == QJsonParseError::NoError && doc.isObject()) {
        QString msg = doc.object().value("message").toString();
        if (!msg.isEmpty()) return msg;
        QString err = doc.object().value("error").toString();
        if (!err.isEmpty()) return err;
    }
    return reply ? reply->errorString() : QStringLiteral("Request failed");
}

QVariantMap GroupService::parseGroup(const QJsonObject& obj) {
    QVariantMap group;
    group["id"] = obj["id"].toString();
    group["name"] = obj["name"].toString();
    group["avatarUrl"] = obj["avatar_url"].toString();
    group["description"] = obj["description"].toString();
    // owner_id may come back as a bare UUID string.
    group["ownerId"] = obj["owner_id"].toVariant().toString();
    group["memberCount"] = obj["member_count"].toInt(obj["members"].toArray().size());

    QVariantList members;
    for (const QJsonValue& v : obj["members"].toArray()) {
        if (!v.isObject()) continue;
        QJsonObject m = v.toObject();
        QVariantMap member;
        member["id"] = m["id"].toVariant().toString();
        member["email"] = m["email"].toString();
        member["username"] = m["username"].toString();
        member["displayName"] = m["display_name"].toString();
        member["avatarUrl"] = m["avatar_url"].toString();
        member["role"] = m["role"].toString("member");
        QString name = member["displayName"].toString();
        if (name.isEmpty()) name = member["username"].toString();
        if (name.isEmpty()) name = member["email"].toString();
        member["bestName"] = name.isEmpty() ? QStringLiteral("Unknown") : name;
        members.append(member);
    }
    group["members"] = members;

    return group;
}

void GroupService::createGroup(const QString& name, const QString& description, const QVariantList& memberIds) {
    QString trimmed = name.trimmed();
    if (trimmed.isEmpty()) {
        emit groupError("Group name is required");
        return;
    }
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QJsonArray ids;
    for (const QVariant& v : memberIds) ids.append(v.toString());

    QJsonObject body;
    body["name"] = trimmed;
    body["description"] = description;
    body["member_ids"] = ids;

    QUrl url(Config::apiBaseUrl() + "/groups/");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        QJsonDocument doc = QJsonDocument::fromJson(data);
        QJsonObject dataObj = doc.object()["data"].toObject();
        if (dataObj.isEmpty()) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit groupCreated(parseGroup(dataObj));
    });
}

void GroupService::getGroupInfo(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId);
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        QJsonObject dataObj = QJsonDocument::fromJson(data).object()["data"].toObject();
        if (dataObj.isEmpty()) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit groupInfoFetched(parseGroup(dataObj));
    });
}

void GroupService::updateGroup(const QString& chatId, const QString& name, const QString& description) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QJsonObject body;
    body["name"] = name;
    body["description"] = description;

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId);
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->sendCustomRequest(
        request, "PUT", QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit groupUpdated(chatId);
    });
}

void GroupService::addMembers(const QString& chatId, const QVariantList& memberIds) {
    if (memberIds.isEmpty()) return;
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QJsonArray ids;
    for (const QVariant& v : memberIds) ids.append(v.toString());
    QJsonObject body;
    body["member_ids"] = ids;

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/members");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit membersAdded(chatId);
    });
}

void GroupService::removeMember(const QString& chatId, const QString& memberId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/members/" + memberId);
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->sendCustomRequest(request, "DELETE");
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, memberId]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit memberRemoved(chatId, memberId);
    });
}

void GroupService::setMemberRole(const QString& chatId, const QString& memberId, const QString& role) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QJsonObject body;
    body["role"] = role;

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/members/" + memberId + "/role");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->sendCustomRequest(
        request, "PUT", QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId, memberId, role]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit memberRoleChanged(chatId, memberId, role);
    });
}

void GroupService::deleteGroup(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId);
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->sendCustomRequest(request, "DELETE");
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit groupDeleted(chatId);
    });
}

void GroupService::leaveGroup(const QString& chatId) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/leave");
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, QByteArray());
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit groupLeft(chatId);
    });
}

void GroupService::uploadGroupAvatar(const QString& chatId, const QString& filePath) {
    QString authToken = buildAuthHeader();
    if (authToken.isEmpty()) {
        emit groupError("Not authenticated. Please login first.");
        return;
    }

    QString localPath = QUrl(filePath).isLocalFile() ? QUrl(filePath).toLocalFile() : filePath;
    QFile* file = new QFile(localPath);
    if (!file->open(QIODevice::ReadOnly)) {
        emit groupError("Could not open the selected image");
        delete file;
        return;
    }

    auto* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart imagePart;
    QString mime = QMimeDatabase().mimeTypeForFile(localPath).name();
    imagePart.setHeader(QNetworkRequest::ContentTypeHeader, mime.isEmpty() ? "application/octet-stream" : mime);
    imagePart.setHeader(QNetworkRequest::ContentDispositionHeader,
                         QVariant(QStringLiteral("form-data; name=\"file\"; filename=\"%1\"")
                                      .arg(QFileInfo(localPath).fileName())));
    imagePart.setBodyDevice(file);
    file->setParent(multiPart);
    multiPart->append(imagePart);

    QUrl url(Config::apiBaseUrl() + "/groups/" + chatId + "/avatar");
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->post(request, multiPart);
    multiPart->setParent(reply);
    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {
        reply->deleteLater();
        QByteArray data = reply->readAll();
        if (reply->error() != QNetworkReply::NoError) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        QJsonObject root = QJsonDocument::fromJson(data).object();
        QString avatarUrl = root["avatar_url"].toString();
        if (avatarUrl.isEmpty()) {
            emit groupError(errorMessageFrom(reply, data));
            return;
        }
        emit groupAvatarUploaded(chatId, avatarUrl);
    });
}
