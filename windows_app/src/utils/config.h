#pragma once

#include <QString>
#include <QNetworkAccessManager>
#include <QStandardPaths>
#include <QDir>

class Config {
public:
    static QString apiBaseUrl() {
        return QStringLiteral("http://localhost:8080/api");
    }

    static QString wsUrl() {
        return QStringLiteral("ws://localhost:8080/ws");
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
    static constexpr int reconnectDelayMs() { return 3000; }
    static constexpr int maxReconnectAttempts() { return 10; }
    static constexpr int tokenRefreshThreshold() { return 300; } // seconds before expiry
};