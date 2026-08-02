#include "messagecache.h"
#include <QSqlQuery>
#include <QSqlError>
#include <QStandardPaths>
#include <QDir>
#include <QDebug>
#include <QJsonDocument>
#include <QDateTime>
#include <QFileInfo>

MessageCache::MessageCache(QObject* parent)
    : QObject(parent)
{
    QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);

    // A named (non-default) connection, since QSqlDatabase's default
    // connection is process-global and other code may open its own handles.
    m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), QStringLiteral("message_cache"));
    m_db.setDatabaseName(dir + QStringLiteral("/message_cache.db"));

    if (!m_db.open()) {
        qWarning() << "[MessageCache] Failed to open cache database:" << m_db.lastError().text();
        return;
    }
    ensureSchema();
}

void MessageCache::ensureSchema() {
    QSqlQuery query(m_db);
    query.exec(QStringLiteral(
        "CREATE TABLE IF NOT EXISTS messages ("
        "  id TEXT PRIMARY KEY,"
        "  chat_id TEXT NOT NULL,"
        "  sender_id TEXT NOT NULL,"
        "  sender_name TEXT,"
        "  content TEXT NOT NULL,"
        "  encrypted INTEGER NOT NULL,"
        "  read_at TEXT,"
        "  created_at TEXT NOT NULL"
        ")"
    ));
    query.exec(QStringLiteral(
        "CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, created_at)"
    ));
    // Added after the table already shipped, so existing installs need these
    // columns bolted on rather than created fresh. ALTER TABLE ADD COLUMN
    // fails harmlessly (caught, not asserted) once the column already exists -
    // this runs on every startup, so that failure path is the common case,
    // not an error worth logging.
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN file_url TEXT"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN file_type TEXT"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN file_name TEXT"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN key_version INTEGER NOT NULL DEFAULT 0"));
    query.exec(QStringLiteral(
        "CREATE TABLE IF NOT EXISTS chats ("
        "  id TEXT PRIMARY KEY,"
        "  raw_json TEXT NOT NULL,"
        "  cached_at TEXT NOT NULL"
        ")"
    ));
}

void MessageCache::saveMessages(const QString& chatId, const QList<Entry>& entries) {
    if (!m_db.isOpen() || entries.isEmpty()) return;

    QSqlQuery query(m_db);
    query.prepare(QStringLiteral(
        "INSERT OR REPLACE INTO messages "
        "(id, chat_id, sender_id, sender_name, content, encrypted, read_at, created_at, "
        " file_url, file_type, duration_ms, file_name, file_size, key_version) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    ));

    m_db.transaction();
    for (const Entry& e : entries) {
        query.addBindValue(e.id);
        query.addBindValue(chatId);
        query.addBindValue(e.senderId);
        query.addBindValue(e.senderName);
        query.addBindValue(e.content);
        query.addBindValue(e.encrypted ? 1 : 0);
        query.addBindValue(e.readAt);
        query.addBindValue(e.createdAt);
        query.addBindValue(e.fileUrl);
        query.addBindValue(e.fileType);
        query.addBindValue(e.durationMs);
        query.addBindValue(e.fileName);
        query.addBindValue(e.fileSize);
        query.addBindValue(e.keyVersion);
        if (!query.exec()) {
            qWarning() << "[MessageCache] Failed to save message:" << query.lastError().text();
        }
    }
    m_db.commit();
}

QList<MessageCache::Entry> MessageCache::loadMessages(const QString& chatId, int limit) const {
    QList<Entry> result;
    if (!m_db.isOpen()) return result;

    QSqlQuery query(m_db);
    query.prepare(QStringLiteral(
        "SELECT id, sender_id, sender_name, content, encrypted, read_at, created_at, "
        "       file_url, file_type, duration_ms, file_name, file_size, key_version "
        "FROM messages WHERE chat_id = ? ORDER BY created_at DESC LIMIT ?"
    ));
    query.addBindValue(chatId);
    query.addBindValue(limit);

    if (!query.exec()) {
        qWarning() << "[MessageCache] Failed to load messages:" << query.lastError().text();
        return result;
    }

    while (query.next()) {
        Entry e;
        e.id = query.value(0).toString();
        e.senderId = query.value(1).toString();
        e.senderName = query.value(2).toString();
        e.content = query.value(3).toString();
        e.encrypted = query.value(4).toInt() != 0;
        e.readAt = query.value(5).toString();
        e.createdAt = query.value(6).toString();
        e.fileUrl = query.value(7).toString();
        e.fileType = query.value(8).toString();
        e.durationMs = query.value(9).toLongLong();
        e.fileName = query.value(10).toString();
        e.fileSize = query.value(11).toLongLong();
        e.keyVersion = query.value(12).toInt();
        result.prepend(e); // rows came back newest-first; flip to oldest-first
    }
    return result;
}

void MessageCache::saveChats(const QList<QJsonObject>& chats) {
    if (!m_db.isOpen() || chats.isEmpty()) return;

    QSqlQuery query(m_db);
    query.prepare(QStringLiteral(
        "INSERT OR REPLACE INTO chats (id, raw_json, cached_at) VALUES (?, ?, ?)"
    ));

    m_db.transaction();
    for (const QJsonObject& obj : chats) {
        QString id = obj[QStringLiteral("id")].toString();
        if (id.isEmpty()) continue;
        query.addBindValue(id);
        query.addBindValue(QString::fromUtf8(QJsonDocument(obj).toJson(QJsonDocument::Compact)));
        query.addBindValue(QDateTime::currentDateTimeUtc().toString(Qt::ISODate));
        if (!query.exec()) {
            qWarning() << "[MessageCache] Failed to save chat:" << query.lastError().text();
        }
    }
    m_db.commit();
}

QList<QJsonObject> MessageCache::loadChats() const {
    QList<QJsonObject> result;
    if (!m_db.isOpen()) return result;

    QSqlQuery query(m_db);
    if (!query.exec(QStringLiteral("SELECT raw_json FROM chats ORDER BY cached_at DESC"))) {
        qWarning() << "[MessageCache] Failed to load chats:" << query.lastError().text();
        return result;
    }

    while (query.next()) {
        QJsonDocument doc = QJsonDocument::fromJson(query.value(0).toString().toUtf8());
        if (doc.isObject()) result.append(doc.object());
    }
    return result;
}

void MessageCache::deleteChat(const QString& chatId) {
    if (!m_db.isOpen()) return;
    QSqlQuery query(m_db);
    query.prepare(QStringLiteral("DELETE FROM messages WHERE chat_id = ?"));
    query.addBindValue(chatId);
    query.exec();

    query.prepare(QStringLiteral("DELETE FROM chats WHERE chat_id = ?"));
    query.addBindValue(chatId);
    query.exec();
    // No VACUUM, unlike clear() below: reclaiming pages rewrites the entire
    // database file, which is far too heavy for dropping one conversation.
}

void MessageCache::clear() {
    if (!m_db.isOpen()) return;
    QSqlQuery query(m_db);
    query.exec(QStringLiteral("DELETE FROM messages"));
    query.exec(QStringLiteral("DELETE FROM chats"));
    // Reclaims the space the deleted rows occupied - without this, SQLite
    // keeps the file at its previous size until something else writes over
    // the freed pages, so "clear cache" wouldn't actually shrink anything.
    query.exec(QStringLiteral("VACUUM"));
}

qint64 MessageCache::sizeBytes() const {
    return QFileInfo(m_db.databaseName()).size();
}
