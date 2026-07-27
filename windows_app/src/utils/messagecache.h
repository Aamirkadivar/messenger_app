#pragma once

#include <QObject>
#include <QString>
#include <QList>
#include <QSqlDatabase>
#include <QJsonObject>

// Local on-disk cache of message history and the chat list (SQLite), so the
// app shows something instantly on open - and still works at all when
// offline - instead of always waiting on a fresh network round-trip.
//
// Stores the raw pre-decryption payload only (ciphertext, or plaintext for
// messages that were never encrypted) exactly as the server sent it, never
// the decrypted text - so a locally readable cache file on disk doesn't
// quietly undo the point of end-to-end encryption. Callers decrypt on load,
// the same way they already do for a fresh network response.
class MessageCache : public QObject {
    Q_OBJECT

public:
    struct Entry {
        QString id;
        QString senderId;
        QString senderName;
        QString content;
        bool encrypted = false;
        QString readAt;
        QString createdAt;
    };

    explicit MessageCache(QObject* parent = nullptr);

    // Upserts by message id. `entries` should be oldest-first (matches the
    // order ChatService already builds for display).
    void saveMessages(const QString& chatId, const QList<Entry>& entries);

    // Returns cached messages for a chat, oldest-first, most recent `limit`.
    QList<Entry> loadMessages(const QString& chatId, int limit = 50) const;

    // Chat list cache: stored as the raw per-chat JSON object exactly as the
    // server sent it (same reasoning as above - the last-message preview
    // inside it may be ciphertext), keyed by chat id. Callers re-run it
    // through the same parsing/decryption path used for a fresh response.
    void saveChats(const QList<QJsonObject>& chats);
    QList<QJsonObject> loadChats() const;

private:
    void ensureSchema();

    QSqlDatabase m_db;
};
