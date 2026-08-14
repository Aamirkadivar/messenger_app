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
        // File-bearing messages (voice/image/file): fileUrl points at the
        // (opaque, possibly ciphertext) payload; fileType is "audio"/"image"/
        // "file", empty for a plain text message.
        QString fileUrl;
        QString fileType;
        // fileName/fileSize apply to image/file attachments (display before
        // fetching); durationMs applies to voice notes.
        QString fileName;
        qint64 fileSize = 0;
        qint64 durationMs = 0;
        // Which of the sender's group Sender Key versions encrypted this
        // message - 0/unused outside a group chat.
        int keyVersion = 0;
        int encryptionVersion = 1;
        // Display-only forward attribution (payload is always a fresh
        // ciphertext for this chat; the server never copies source blobs).
        bool isForwarded = false;
        QString forwardedFromName;
        QString replyToId;
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

    // Drops one conversation's cached messages and chat row.
    void deleteChat(const QString& chatId);
    // Wipes every cached message and chat (e.g. a user-triggered "clear
    // cache" in Settings) and reclaims the freed space on disk.
    void clear();

    // Drops one message's cached row. Without this a message deleted while
    // online reappears on the next cold start, because the cache is painted
    // before the network refresh can correct it.
    void deleteMessage(const QString& messageId);
    // Size of the on-disk database file, for showing in Settings.
    qint64 sizeBytes() const;

private:
    void ensureSchema();

    QSqlDatabase m_db;
};
