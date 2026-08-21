#include "mlsv2.h"
#include "mlscore.h"
#include "../utils/config.h"
#include "../utils/credentialmanager.h"

#include <QCryptographicHash>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QList>
#include <QLoggingCategory>
#include <QNetworkReply>
#include <QPair>
#include <QSet>
#include <QUrl>
#include <functional>

namespace {

const int kCipherSuite = 1;
const int kKpTarget = 10;
const int kKpLowWater = 3;
const char* kSnapshotKey = "mls2_snapshot";

QPair<int, QJsonObject> httpJson(QNetworkAccessManager* nam,
                                 std::function<void(QNetworkRequest&)> applyAuth,
                                 const QString& path,
                                 const QByteArray& body,
                                 const char* method) {
    if (!nam) return {0, {}};
    QNetworkRequest req(QUrl(Config::apiBaseUrl() + path));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    if (applyAuth) applyAuth(req);
    QNetworkReply* reply = nullptr;
    if (qstrcmp(method, "POST") == 0) {
        reply = nam->post(req, body);
    } else if (qstrcmp(method, "GET") == 0) {
        reply = nam->get(req);
    } else {
        return {0, {}};
    }
    QEventLoop loop;
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
    reply->deleteLater();
    return {status, obj};
}

} // namespace

MlsV2Engine::MlsV2Engine(QObject* parent) : QObject(parent) {}

MlsV2Engine::~MlsV2Engine() {
    if (m_handle) {
        persist();
        MlsCore::instance().clientFree(m_handle);
        m_handle = nullptr;
    }
}

void MlsV2Engine::bind(QNetworkAccessManager* nam,
                       std::function<void(QNetworkRequest&)> applyAuth,
                       std::function<QString()> userId,
                       std::function<QString()> deviceId) {
    m_nam = nam;
    m_applyAuth = std::move(applyAuth);
    m_userId = std::move(userId);
    m_deviceId = std::move(deviceId);
}

bool MlsV2Engine::isReady() const {
    return MlsCore::instance().clientApiReady();
}

bool MlsV2Engine::ensureClient() {
    if (m_handle) return true;
    if (!MlsCore::instance().clientApiReady()) {
        if (!MlsCore::instance().load() || !MlsCore::instance().clientApiReady()) {
            qWarning() << "[mls-v2] core client API unavailable:" << MlsCore::instance().lastError();
            return false;
        }
    }
    const QString user = m_userId ? m_userId() : QString();
    const QString device = m_deviceId ? m_deviceId() : QString();
    if (user.isEmpty() || device.isEmpty()) return false;

    const QString b64 = CredentialManager::instance().getToken(QString::fromUtf8(kSnapshotKey));
    if (!b64.isEmpty()) {
        const QByteArray blob = QByteArray::fromBase64(b64.toLatin1());
        m_handle = MlsCore::instance().clientRestore(blob, user, device);
        if (!m_handle) {
            qWarning() << "[mls-v2] snapshot restore failed; creating a fresh client";
        }
    }
    if (!m_handle) {
        m_handle = MlsCore::instance().clientNew(user, device);
        // A fresh store has published nothing. Carrying the previous tag would
        // make the next stock check answer for the store we just abandoned.
        m_storeId.clear();
    }
    return m_handle != nullptr;
}

void MlsV2Engine::persist() {
    if (!m_handle) return;
    const QByteArray blob = MlsCore::instance().snapshot(m_handle);
    if (blob.isEmpty()) return;
    CredentialManager::instance().saveToken(
        QString::fromUtf8(kSnapshotKey),
        QString::fromLatin1(blob.toBase64()));
}

bool MlsV2Engine::loadGroup(const QString& chatId) {
    if (!ensureClient()) return false;
    if (m_liveGroups.contains(chatId)) return true;
    const qint64 e = MlsCore::instance().loadGroup(m_handle, gidOf(chatId));
    if (e < 0) return false;
    m_liveGroups.insert(chatId);
    return true;
}

bool MlsV2Engine::hasGroup(const QString& chatId) {
    return loadGroup(chatId);
}

qint64 MlsV2Engine::epochOf(const QString& chatId) {
    if (!loadGroup(chatId) || !m_handle) return -1;
    return MlsCore::instance().epoch(m_handle, gidOf(chatId));
}

void MlsV2Engine::forget(const QString& chatId) {
    if (m_handle) MlsCore::instance().dropGroup(m_handle, gidOf(chatId));
    m_liveGroups.remove(chatId);
    persist();
}

QByteArray MlsV2Engine::encrypt(const QString& chatId, const QByteArray& plaintext) {
    if (!loadGroup(chatId) || !m_handle) return {};
    const QByteArray ct = MlsCore::instance().encrypt(m_handle, gidOf(chatId), plaintext);
    if (!ct.isEmpty()) persist();
    return ct;
}

QByteArray MlsV2Engine::decrypt(const QString& chatId, const QByteArray& ciphertext) {
    if (!ensureClient()) return {};
    if (!loadGroup(chatId)) {
        processWelcomes();
        if (!loadGroup(chatId)) return {};
    }
    auto open = [this, &chatId, &ciphertext]() -> QByteArray {
        const QByteArray out = MlsCore::instance().process(m_handle, gidOf(chatId), ciphertext);
        if (out.size() >= 1 && quint8(out[0]) == 1) {
            persist();
            return out.mid(1);
        }
        return {};
    };
    QByteArray pt = open();
    if (!pt.isEmpty()) return pt;
    syncHandshakes(chatId);
    return open();
}

// ---------------------------------------------------------------- store id
//
// The opaque tag naming one incarnation of this device's MLS store, derived the
// same way Android derives it: SHA-256 of the leaf signature public key carried
// in a KeyPackage this store just produced.
//
// A device keeps its device id across a reinstall or an account switch, but its
// MLS store does not; everything the previous store published stays on the
// Delivery Service with private init keys that no longer exist. The tag lets the
// server keep the incarnations apart WITHOUT parsing MLS - it compares an opaque
// string and nothing more.
//
// PUBLIC MATERIAL ONLY. The signature public key is already inside every
// published KeyPackage; hashing it reveals nothing a claimer could not compute.
// This walks the RFC 9420 framing far enough to reach that field and does no
// cryptography.
namespace {

// RFC 9420 §2.1.2 variable-length integer. The 0b11 prefix is reserved and is
// rejected rather than guessed at.
bool readVarint(const QByteArray& b, int off, int* value, int* next) {
    if (off < 0 || off >= b.size()) return false;
    const quint8 first = static_cast<quint8>(b[off]);
    switch (first >> 6) {
    case 0:
        *value = first & 0x3F;
        *next = off + 1;
        return true;
    case 1:
        if (off + 1 >= b.size()) return false;
        *value = ((first & 0x3F) << 8) | static_cast<quint8>(b[off + 1]);
        *next = off + 2;
        return true;
    case 2:
        if (off + 3 >= b.size()) return false;
        *value = ((first & 0x3F) << 24) | (static_cast<quint8>(b[off + 1]) << 16) |
                 (static_cast<quint8>(b[off + 2]) << 8) | static_cast<quint8>(b[off + 3]);
        *next = off + 4;
        return true;
    default:
        return false;
    }
}

bool readVector(const QByteArray& b, int off, QByteArray* out, int* next) {
    int len = 0, start = 0;
    if (!readVarint(b, off, &len, &start)) return false;
    if (len < 0 || start + len > b.size()) return false;
    if (out) *out = b.mid(start, len);
    *next = start + len;
    return true;
}

/// protocol_version, cipher_suite, init_key, leaf.encryption_key,
/// leaf.signature_key -> the third vector is what identifies the store.
QString storeIdOf(const QByteArray& keyPackage) {
    int o = 4;  // protocol_version (u16) + cipher_suite (u16)
    if (!readVector(keyPackage, o, nullptr, &o)) return {};   // init_key
    if (!readVector(keyPackage, o, nullptr, &o)) return {};   // encryption_key
    QByteArray signatureKey;
    if (!readVector(keyPackage, o, &signatureKey, &o)) return {};
    if (signatureKey.isEmpty()) return {};
    return QString::fromLatin1(
        QCryptographicHash::hash(signatureKey, QCryptographicHash::Sha256).toHex());
}

}  // namespace

void MlsV2Engine::ensureKeyPackages() {
    if (!ensureClient() || !m_nam) return;
    const QString device = m_deviceId ? m_deviceId() : QString();

    // Only this store's own packages count as stock. Anything an earlier
    // incarnation published is unusable to us - its private init keys went with
    // it - so counting it would report a full shelf and suppress the republish
    // this store actually needs. Until the tag is known there is nothing on the
    // server this store is known to have made, so the stock is zero.
    int available = 0;
    if (!m_storeId.isEmpty()) {
        const auto count = httpJson(m_nam, m_applyAuth,
            QStringLiteral("/e2ee/mls/keypackages/count?store_id=") + m_storeId, {}, "GET");
        available = count.second.value(QStringLiteral("available")).toInt(0);
    }
    if (available >= kKpLowWater) return;

    QList<QByteArray> fresh;
    for (int i = 0; i < kKpTarget - available; ++i) {
        const QByteArray kp = MlsCore::instance().keyPackage(m_handle);
        if (kp.isEmpty()) continue;
        persist();
        fresh.append(kp);
    }
    if (fresh.isEmpty()) return;

    // Derived from the packages actually being published, so the tag can never
    // describe a store other than the one that made them.
    const QString storeId = storeIdOf(fresh.first());
    if (storeId.isEmpty()) {
        // Publishing untagged would drop these back into the indistinguishable
        // pool the tag exists to escape.
        qWarning() << "[mls-v2] could not derive a store id; not publishing key packages";
        return;
    }
    m_storeId = storeId;

    QJsonArray items;
    for (const QByteArray& kp : fresh) {
        QJsonObject item;
        item[QStringLiteral("device_id")] = device;
        item[QStringLiteral("cipher_suite")] = kCipherSuite;
        item[QStringLiteral("key_package_b64")] = QString::fromLatin1(kp.toBase64());
        item[QStringLiteral("ref_hash")] = QString::fromLatin1(
            QCryptographicHash::hash(kp, QCryptographicHash::Sha256).toHex());
        item[QStringLiteral("store_id")] = storeId;
        items.append(item);
    }
    QJsonObject body;
    body[QStringLiteral("key_packages")] = items;
    httpJson(m_nam, m_applyAuth, QStringLiteral("/e2ee/mls/keypackages"),
             QJsonDocument(body).toJson(QJsonDocument::Compact), "POST");
    qInfo() << "[mls-v2] published" << items.size() << "key packages for store"
            << storeId.left(8);
}

void MlsV2Engine::processWelcomes() {
    if (!ensureClient() || !m_nam) return;
    const auto resp = httpJson(m_nam, m_applyAuth, QStringLiteral("/e2ee/mls/welcomes"), {}, "GET");
    const QJsonArray welcomes = resp.second.value(QStringLiteral("welcomes")).toArray();
    QJsonArray acked;
    for (const QJsonValue& v : welcomes) {
        const QJsonObject w = v.toObject();
        const QByteArray blob = QByteArray::fromBase64(
            w.value(QStringLiteral("welcome_b64")).toString().toLatin1());
        if (blob.isEmpty()) continue;
        const QByteArray gid = MlsCore::instance().joinWelcome(m_handle, blob);
        if (gid.isEmpty()) continue;
        const QString chatId = QString::fromUtf8(gid);
        m_liveGroups.insert(chatId);
        persist();
        const QString id = w.value(QStringLiteral("id")).toString();
        if (!id.isEmpty()) acked.append(id);
        qInfo() << "[mls-v2] joined" << chatId;
    }
    if (!acked.isEmpty()) {
        QJsonObject body;
        body[QStringLiteral("ids")] = acked;
        httpJson(m_nam, m_applyAuth, QStringLiteral("/e2ee/mls/welcomes/ack"),
                 QJsonDocument(body).toJson(QJsonDocument::Compact), "POST");
    }
}

void MlsV2Engine::syncHandshakes(const QString& chatId) {
    if (!loadGroup(chatId) || !m_nam) return;
    const qint64 since = epochOf(chatId);
    if (since < 0) return;
    const auto resp = httpJson(m_nam, m_applyAuth,
        QStringLiteral("/e2ee/mls/groups/%1/handshakes?since_epoch=%2").arg(chatId).arg(since),
        {}, "GET");
    const QJsonArray hs = resp.second.value(QStringLiteral("handshakes")).toArray();
    for (const QJsonValue& v : hs) {
        const QByteArray payload = QByteArray::fromBase64(
            v.toObject().value(QStringLiteral("payload_b64")).toString().toLatin1());
        if (payload.isEmpty()) continue;
        const QByteArray out = MlsCore::instance().process(m_handle, gidOf(chatId), payload);
        if (out.size() >= 1 && quint8(out[0]) == 2) persist();
    }
}

void MlsV2Engine::inviteMissingDevices(const QString& chatId) {
    if (!loadGroup(chatId) || !m_nam || !m_handle) return;
    const QString meDev = m_deviceId ? m_deviceId() : QString();
    const QByteArray rosterBytes = MlsCore::instance().roster(m_handle, gidOf(chatId));
    const QStringList current = QString::fromUtf8(rosterBytes).split(QChar('\n'), Qt::SkipEmptyParts);
    const QSet<QString> roster(current.begin(), current.end());

    const auto devResp = httpJson(m_nam, m_applyAuth,
        QStringLiteral("/e2ee/chats/%1/devices").arg(chatId), {}, "GET");
    const QJsonArray devices = devResp.second.value(QStringLiteral("devices")).toArray();

    QList<QPair<QString, QString>> claimedIds;
    QList<QByteArray> claimedKps;
    for (const QJsonValue& v : devices) {
        const QJsonObject d = v.toObject();
        const QString uid = d.value(QStringLiteral("user_id")).toString();
        const QString did = d.value(QStringLiteral("device_id")).toString();
        if (did.isEmpty() || did == meDev) continue;
        if (roster.contains(uid + QLatin1Char('|') + did)) continue;
        QJsonObject claim;
        claim[QStringLiteral("user_id")] = uid;
        claim[QStringLiteral("device_id")] = did;
        claim[QStringLiteral("cipher_suite")] = kCipherSuite;
        const auto cr = httpJson(m_nam, m_applyAuth, QStringLiteral("/e2ee/mls/keypackages/claim"),
            QJsonDocument(claim).toJson(QJsonDocument::Compact), "POST");
        const QString kpB64 = cr.second.value(QStringLiteral("key_package_b64")).toString();
        if (kpB64.isEmpty()) {
            qWarning() << "[mls-v2] no key package for" << did.left(8);
            continue;
        }
        claimedIds.append(qMakePair(uid, did));
        claimedKps.append(QByteArray::fromBase64(kpB64.toLatin1()));
    }
    if (claimedKps.isEmpty()) return;

    const QByteArray packed = MlsCore::instance().groupAdd(m_handle, gidOf(chatId), claimedKps);
    if (packed.size() < 4) return;
    const quint32 commitLen =
        (quint32(quint8(packed[0])) << 24) | (quint32(quint8(packed[1])) << 16) |
        (quint32(quint8(packed[2])) << 8) | quint32(quint8(packed[3]));
    if (int(4 + commitLen) > packed.size()) return;
    const QByteArray commit = packed.mid(4, int(commitLen));
    const QByteArray welcome = packed.mid(4 + int(commitLen));

    QJsonArray welcomes;
    for (const auto& id : claimedIds) {
        QJsonObject w;
        w[QStringLiteral("user_id")] = id.first;
        w[QStringLiteral("device_id")] = id.second;
        w[QStringLiteral("welcome_b64")] = QString::fromLatin1(welcome.toBase64());
        welcomes.append(w);
    }
    QJsonObject body;
    body[QStringLiteral("expected_epoch")] = epochOf(chatId);
    body[QStringLiteral("commit_b64")] = QString::fromLatin1(commit.toBase64());
    body[QStringLiteral("sender_device_id")] = meDev;
    body[QStringLiteral("welcomes")] = welcomes;
    const auto resp = httpJson(m_nam, m_applyAuth,
        QStringLiteral("/e2ee/mls/groups/%1/commit").arg(chatId),
        QJsonDocument(body).toJson(QJsonDocument::Compact), "POST");
    if (resp.first >= 200 && resp.first < 300) {
        MlsCore::instance().mergePending(m_handle, gidOf(chatId));
        persist();
        qInfo() << "[mls-v2]" << chatId << "added" << claimedKps.size() << "device(s)";
    } else if (resp.first == 409) {
        qInfo() << "[mls-v2]" << chatId << "409, discarding staged commit";
        MlsCore::instance().clearPending(m_handle, gidOf(chatId));
        persist();
        syncHandshakes(chatId);
    } else {
        qWarning() << "[mls-v2]" << chatId << "commit rejected" << resp.first;
        MlsCore::instance().clearPending(m_handle, gidOf(chatId));
        persist();
    }
}

bool MlsV2Engine::ensureGroup(const QString& chatId) {
    if (!ensureClient() || !m_nam) return false;
    ensureKeyPackages();
    processWelcomes();
    if (hasGroup(chatId)) {
        syncHandshakes(chatId);
        inviteMissingDevices(chatId);
        return true;
    }

    const auto existing = httpJson(m_nam, m_applyAuth,
        QStringLiteral("/e2ee/mls/groups/%1").arg(chatId), {}, "GET");
    if (existing.first >= 200 && existing.first < 300) {
        qInfo() << "[mls-v2]" << chatId << "awaiting Welcome";
        return false;
    }

    if (!MlsCore::instance().groupCreate(m_handle, gidOf(chatId))) return false;
    m_liveGroups.insert(chatId);

    QJsonObject create;
    create[QStringLiteral("chat_id")] = chatId;
    create[QStringLiteral("group_id_b64")] = QString::fromLatin1(gidOf(chatId).toBase64());
    create[QStringLiteral("cipher_suite")] = kCipherSuite;
    const auto registered = httpJson(m_nam, m_applyAuth, QStringLiteral("/e2ee/mls/groups"),
        QJsonDocument(create).toJson(QJsonDocument::Compact), "POST");
    if (registered.first < 200 || registered.first >= 300) {
        qWarning() << "[mls-v2]" << chatId << "DS refused registration" << registered.first;
        forget(chatId);
        if (registered.first == 409) {
            processWelcomes();
            if (hasGroup(chatId)) {
                syncHandshakes(chatId);
                return true;
            }
        }
        return false;
    }
    persist();
    inviteMissingDevices(chatId);
    return true;
}
