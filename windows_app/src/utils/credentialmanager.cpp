#pragma once

#include "credentialmanager.h"

#include <QSettings>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QByteArray>
#include <QCryptographicHash>
#include <QDebug>
#include <QStandardPaths>

#ifdef Q_OS_WIN
#include <windows.h>
// advapi32.lib linked via CMakeLists.txt for CryptProtectData/CryptUnprotectData
#endif

// Global instance
static CredentialManager* g_instance = nullptr;

CredentialManager& CredentialManager::instance() {
    if (g_instance == nullptr) {
        g_instance = new CredentialManager();
    }
    return *g_instance;
}

CredentialManager::CredentialManager()
    : m_localStoreLoaded(false)
{
    // Get app-specific data storage location
    m_dataPath = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(m_dataPath);

    // Load local store
    loadLocalStore();
}

CredentialManager::~CredentialManager() {
    saveLocalStore();
}

QString CredentialManager::getToken(const QString& key) const {
    m_mutex.lock();
    QString value = m_tokens.value(key);
    m_mutex.unlock();
    return value;
}

QMap<QString, QString> CredentialManager::tokensWithPrefix(const QString& prefix) const {
    m_mutex.lock();
    QMap<QString, QString> out;
    for (auto it = m_tokens.constBegin(); it != m_tokens.constEnd(); ++it) {
        if (it.key().startsWith(prefix)) out.insert(it.key(), it.value());
    }
    m_mutex.unlock();
    return out;
}

bool CredentialManager::saveToken(const QString& key, const QString& value) {
    m_mutex.lock();
    m_tokens[key] = value;
    m_localStoreLoaded = true;
    m_mutex.unlock();

    saveLocalStore();
    emit tokenChanged();
    return true;
}

void CredentialManager::removeToken(const QString& key) {
    m_mutex.lock();
    m_tokens.remove(key);
    m_localStoreLoaded = true;
    m_mutex.unlock();

    saveLocalStore();
}

bool CredentialManager::deleteToken(const QString& key) {
    m_mutex.lock();
    bool removed = m_tokens.remove(key);
    m_localStoreLoaded = true;
    m_mutex.unlock();

    if (removed) {
        removeFromOS(key);
        saveLocalStore();
        emit tokenChanged();
    }
    return removed;
}

QString CredentialManager::getPersistentData(const QString& key) const {
    // Use a separate file for persistent data
    QString dataPath = m_dataPath + "/persistent";
    QFile file(dataPath);
    if (!file.exists()) {
        QDir().mkpath(dataPath);
        return QString();
    }
    
    QString filePath = dataPath + "/" + QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha256).toHex().left(16) + ".dat";
    QFile dataFile(filePath);
    if (dataFile.open(QIODevice::ReadOnly)) {
        QByteArray data = dataFile.readAll();
        dataFile.close();
        return QString::fromUtf8(data);
    }
    return QString();
}

void CredentialManager::savePersistentData(const QString& key, const QString& value) {
    QString dataPath = m_dataPath + "/persistent";
    QDir().mkpath(dataPath);
    
    QString filePath = dataPath + "/" + QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha256).toHex().left(16) + ".dat";
    QFile dataFile(filePath);
    if (dataFile.open(QIODevice::WriteOnly)) {
        dataFile.write(value.toUtf8());
        dataFile.close();
    }
}

void CredentialManager::clearAllTokens() {
    m_mutex.lock();
    m_tokens.clear();
    m_localStoreLoaded = true;
    m_mutex.unlock();
    
    saveLocalStore();
    emit tokenChanged();
}

bool CredentialManager::saveUser(const QString& userId, const QString& username, const QString& pubKey) {
    QString pubKeyKey = QString("pubkey_%1").arg(userId);
    QString usernameKey = QString("username_%1").arg(userId);

    saveToken(pubKeyKey, pubKey);
    saveToken(usernameKey, username);
    removeToken(QString("privkey_%1").arg(userId));

    // Save user metadata as persistent data
    QString userDataPath = m_dataPath + "/users";
    QDir().mkpath(userDataPath);
    
    QString filePath = userDataPath + "/" + userId + ".json";
    QJsonObject userObj;
    userObj["id"] = userId;
    userObj["username"] = username;
    userObj["pubKey"] = pubKey;
    
    QJsonDocument doc(userObj);
    QFile dataFile(filePath);
    if (dataFile.open(QIODevice::WriteOnly)) {
        dataFile.write(doc.toJson(QJsonDocument::Indented));
        dataFile.close();
    }
    
    return true;
}

void CredentialManager::loadLocalStore() {
    if (m_localStoreLoaded) return;

    QFile file(m_dataPath + "/credentials.json");
    if (!file.exists()) {
        m_localStoreLoaded = true;
        return;
    }

    bool migratedPlaintext = false;
    if (file.open(QIODevice::ReadOnly)) {
        QByteArray data = file.readAll();
        file.close();

        QByteArray jsonBytes;
        if (data.startsWith("DP1\n")) {
            jsonBytes = dpapiDecrypt(data.mid(4));
            if (jsonBytes.isEmpty()) {
                qWarning() << "[CredentialManager] DPAPI decrypt of credentials.json failed";
                m_localStoreLoaded = true;
                return;
            }
        } else {
            jsonBytes = data;
            migratedPlaintext = true;
        }

        QJsonDocument doc = QJsonDocument::fromJson(jsonBytes);
        if (doc.isObject()) {
            QJsonObject obj = doc.object();
            QJsonObject tokens = obj["tokens"].toObject();
            for (auto it = tokens.begin(); it != tokens.end(); ++it) {
                m_tokens[it.key()] = it.value().toString();
            }
        }
    }
    m_localStoreLoaded = true;
    if (migratedPlaintext && !m_tokens.isEmpty()) {
        saveLocalStore();
    }
}

void CredentialManager::saveLocalStore() {
    QDir().mkpath(m_dataPath);

    QJsonObject obj;
    QJsonObject tokens;
    for (auto it = m_tokens.begin(); it != m_tokens.end(); ++it) {
        tokens[it.key()] = it.value();
    }
    obj["tokens"] = tokens;

    const QByteArray json = QJsonDocument(obj).toJson(QJsonDocument::Compact);
    const QByteArray sealed = dpapiEncrypt(json);
    if (sealed.isEmpty()) {
        qWarning() << "[CredentialManager] DPAPI encrypt failed; not writing plaintext credentials.json";
        return;
    }

    QFile file(m_dataPath + "/credentials.json");
    if (file.open(QIODevice::WriteOnly)) {
        file.write("DP1\n");
        file.write(sealed);
        file.close();
    }
}

void CredentialManager::persistToOS(const QString& key, const QString& value, bool isSecret) {
#ifdef Q_OS_WIN
    // Use Windows Data Protection API (DPAPI)
    QByteArray utf8Value = value.toUtf8();
    
    QByteArray encrypted = dpapiEncrypt(utf8Value);
    if (!encrypted.isEmpty()) {
        // Store in Windows Registry as encrypted blob
        QString regPath = "HKEY_CURRENT_USER\\Software\\MessengerApp\\Credentials";
        QSettings settings(regPath, QSettings::NativeFormat);
        settings.setValue(key, QByteArray::fromBase64(encrypted.toBase64()));
    }
#endif
    Q_UNUSED(isSecret);
}

QString CredentialManager::retrieveFromOS(const QString& key) const {
#ifdef Q_OS_WIN
    QString regPath = "HKEY_CURRENT_USER\\Software\\MessengerApp\\Credentials";
    QSettings settings(regPath, QSettings::NativeFormat);
    
    if (settings.contains(key)) {
        QByteArray encrypted = settings.value(key).toByteArray();
        QByteArray decrypted = dpapiDecrypt(encrypted);
        if (!decrypted.isEmpty()) {
            return QString::fromUtf8(decrypted);
        }
    }
#endif
    return QString();
}

void CredentialManager::removeFromOS(const QString& key) const {
#ifdef Q_OS_WIN
    QString regPath = "HKEY_CURRENT_USER\\Software\\MessengerApp\\Credentials";
    QSettings settings(regPath, QSettings::NativeFormat);
    settings.remove(key);
#endif
    Q_UNUSED(key);
}

QByteArray CredentialManager::dpapiEncrypt(const QByteArray& data) {
#ifdef Q_OS_WIN
    DATA_BLOB inData, outData;
    inData.pbData = const_cast<BYTE*>(reinterpret_cast<const BYTE*>(data.data()));
    inData.cbData = data.size();
    
    QByteArray output;
    if (CryptProtectData(&inData, L"MessengerAppCredential", nullptr, nullptr, nullptr,
                         CRYPTPROTECT_UI_FORBIDDEN, &outData)) {
        output = QByteArray(reinterpret_cast<char*>(outData.pbData), outData.cbData);
        LocalFree(outData.pbData);
    }
    
    return output;
#else
    // For non-Windows platforms, use simple encoding (not secure, just for portability)
    return QCryptographicHash::hash(data, QCryptographicHash::Sha256).toBase64();
#endif
}

QByteArray CredentialManager::dpapiDecrypt(const QByteArray& data) const {
#ifdef Q_OS_WIN
    DATA_BLOB inData, outData;
    inData.pbData = const_cast<BYTE*>(reinterpret_cast<const BYTE*>(data.data()));
    inData.cbData = data.size();
    
    QByteArray output;
    if (CryptUnprotectData(&inData, nullptr, nullptr, nullptr, nullptr,
                           CRYPTPROTECT_UI_FORBIDDEN, &outData)) {
        output = QByteArray(reinterpret_cast<char*>(outData.pbData), outData.cbData);
        LocalFree(outData.pbData);
    }
    
    return output;
#else
    // For non-Windows, reverse the simple encoding
    QByteArray decoded = QByteArray::fromBase64(data);
    return QCryptographicHash::hash(decoded, QCryptographicHash::Sha256);
#endif
}