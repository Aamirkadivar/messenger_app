#pragma once

#include <QString>
#include <QNetworkAccessManager>
#include <QStandardPaths>
#include <QDir>

class Config {
public:
    static QString apiBaseUrl() {
        return QStringLiteral("http://192.168.1.52:3000/api/v1");
    }

    // Server origin with no /api/v1 suffix - avatar/voice paths come back from
    // the backend as root-relative ("/uploads/avatars/<uuid>.jpg") because the
    // server's host/IP can change between networks, so clients resolve them
    // against whatever base URL they're already using instead of the server
    // baking in an absolute one.
    static QString serverOrigin() {
        QString base = apiBaseUrl();
        int idx = base.indexOf(QStringLiteral("/api/"));
        return idx >= 0 ? base.left(idx) : base;
    }

    static QString resolveServerUrl(const QString& path) {
        if (path.isEmpty()) return {};
        if (path.startsWith(QStringLiteral("http://")) || path.startsWith(QStringLiteral("https://")))
            return path;
        return serverOrigin() + (path.startsWith(QChar('/')) ? path : QStringLiteral("/") + path);
    }

    static QString wsUrl() {
        return QStringLiteral("ws://192.168.1.52:3000/ws");
    }

    static QString websocketUrl() {
        return wsUrl();
    }

    static QString configFilePath() {
        return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) +
               QStringLiteral("/config.json");
    }

    static QString appDataDir() {
        QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
        QDir().mkpath(dir);
        return dir;
    }

    static QString logFilePath() {
        return appDataDir() + QStringLiteral("/app.log");
    }

    static constexpr int messagesPageSize() { return 30; }
    static constexpr int presencePingInterval() { return 30000; } // ms
    static constexpr int typingDebounceMs() { return 500; }
    static constexpr int reconnectDelayMs() { return 1000; }
    // Unused: the socket retries forever with exponential backoff.
    static constexpr int tokenRefreshThreshold() { return 300; } // seconds before expiry
};