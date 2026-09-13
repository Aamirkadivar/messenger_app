#include "messagecache.h"
#include <QSet>
#include <QSqlQuery>
#include <QSqlError>
#include <QStandardPaths>
#include <QDir>
#include <QDebug>
#include <QJsonDocument>
#include <QDateTime>
#include <QFileInfo>
#include <QFile>
#include <QCryptographicHash>

MessageCache::MessageCache(QObject* parent)
    : QObject(parent)
{
    // Deliberately empty. There is no account yet at construction time, and a
    // cache opened before anyone has authenticated is by definition shared.
    // openForAccount() is the only way in.
}

QString MessageCache::legacyQuarantinedFileName() {
    return QStringLiteral("message_cache.db");
}

QString MessageCache::databaseNameForAccount(const QString& accountId) {
    const QString canonical = accountId.trimmed();
    if (canonical.isEmpty()) {
        return QString();
    }
    const QByteArray digest = QCryptographicHash::hash(
        canonical.toUtf8(), QCryptographicHash::Sha256);
    // 16 bytes -> 32 hex chars. Hex only, so the account id can never influence
    // the path: no separators, no traversal, no reserved device names.
    return QStringLiteral("message_cache_") + QString::fromLatin1(digest.left(16).toHex()) +
           QStringLiteral(".db");
}

void MessageCache::openForAccount(const QString& accountId) {
    const QString fileName = databaseNameForAccount(accountId);
    if (fileName.isEmpty()) {
        qWarning() << "[MessageCache] refusing to open a cache for a blank account id";
        close();
        return;
    }
    if (m_db.isOpen() && m_accountId == accountId.trimmed()) {
        return; // already bound to this account
    }

    // Close the previous account BEFORE touching the new one, so two namespaces
    // are never open at once.
    close();

    QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);

    // The connection name is per-account too: QSqlDatabase connection names are
    // process-global, so reusing one name across accounts would hand the second
    // account the first one's still-registered handle.
    m_connectionName = QStringLiteral("message_cache_") +
                       QString::fromLatin1(QCryptographicHash::hash(
                           accountId.trimmed().toUtf8(), QCryptographicHash::Sha256)
                           .left(16).toHex());
    m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connectionName);
    m_db.setDatabaseName(dir + QStringLiteral("/") + fileName);

    if (!m_db.open()) {
        qWarning() << "[MessageCache] Failed to open cache database:" << m_db.lastError().text();
        m_accountId.clear();
        return;
    }
    m_accountId = accountId.trimmed();
    ensureSchema();
}

void MessageCache::close() {
    const QString name = m_connectionName;
    if (m_db.isOpen()) {
        m_db.close();
    }
    m_db = QSqlDatabase();
    m_accountId.clear();
    m_connectionName.clear();
    if (!name.isEmpty()) {
        // Must happen after every QSqlDatabase copy is out of scope, or Qt warns
        // and keeps the connection alive - which would leave the previous
        // account's handle reachable.
        QSqlDatabase::removeDatabase(name);
    }
}

bool MessageCache::ownedBy(const QString& owner) const {
    if (!m_db.isOpen() || m_accountId.isEmpty()) return false;
    return owner.trimmed() == m_accountId;
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
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN is_forwarded INTEGER NOT NULL DEFAULT 0"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN forwarded_from_name TEXT"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN reply_to_id TEXT"));
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN encryption_version INTEGER NOT NULL DEFAULT 1"));
    // v3/v4 key their ratchet session by "chatId|senderDeviceId". Without this
    // column a cached message reloaded on reopen decrypted against the wrong
    // session and rendered as a placeholder, even though the same message had
    // decrypted fine when it first arrived over the network.
    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN sender_device_id TEXT"));
    query.exec(QStringLiteral(
        "CREATE TABLE IF NOT EXISTS chats ("
        "  id TEXT PRIMARY KEY,"
        "  raw_json TEXT NOT NULL,"
        "  cached_at TEXT NOT NULL"
        ")"
    ));
    // Phase 71: the durable outbox and the per-chat sync point. See the header.
    query.exec(QStringLiteral(
        "CREATE TABLE IF NOT EXISTS outbox ("
        "  client_message_id TEXT PRIMARY KEY,"
        "  chat_id TEXT NOT NULL,"
        "  chat_type TEXT NOT NULL,"
        "  request_json TEXT NOT NULL,"
        "  state TEXT NOT NULL,"
        "  created_at INTEGER NOT NULL,"
        "  attempts INTEGER NOT NULL DEFAULT 0,"
        "  next_attempt_at INTEGER NOT NULL DEFAULT 0,"
        "  last_error TEXT,"
        "  server_message_id TEXT,"
        "  accepted_at INTEGER"
        ")"
    ));
    query.exec(QStringLiteral(
        "CREATE INDEX IF NOT EXISTS idx_outbox_due ON outbox(state, next_attempt_at)"
    ));
    query.exec(QStringLiteral(
        "CREATE TABLE IF NOT EXISTS sync_state ("
        "  chat_id TEXT PRIMARY KEY,"
        "  last_seq INTEGER NOT NULL,"
        "  backfill_before INTEGER NOT NULL DEFAULT -1,"
        "  updated_at INTEGER NOT NULL"
        ")"
    ));
}

void MessageCache::saveMessages(const QString& chatId, const QList<Entry>& entries,
                                const QString& owner) {
    if (entries.isEmpty()) return;
    if (!ownedBy(owner)) {
        // Either nobody is signed in, or this reply outlived the session that
        // issued it. Dropping the rows loses a cache entry; writing them would
        // put one account's history into another's database.
        qWarning() << "[MessageCache] dropping" << entries.size()
                   << "cached message(s): the owning session is no longer active";
        return;
    }

    QSqlQuery query(m_db);
    query.prepare(QStringLiteral(
        "INSERT OR REPLACE INTO messages "
        "(id, chat_id, sender_id, sender_name, content, encrypted, read_at, created_at, "
        " file_url, file_type, duration_ms, file_name, file_size, key_version, "
        " is_forwarded, forwarded_from_name, reply_to_id, encryption_version, "
        " sender_device_id) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    ));

    // Rows we already hold in the clear (our own sends, cached as plaintext
    // because a sender cannot decrypt its own ciphertext) must not be replaced
    // by the server's encrypted copy on the next fetch - that would turn them
    // back into placeholders.
    QSet<QString> keepPlaintext;
    {
        QSqlQuery probe(m_db);
        probe.prepare(QStringLiteral(
            "SELECT id FROM messages WHERE chat_id = ? AND encrypted = 0"));
        probe.addBindValue(chatId);
        if (probe.exec()) {
            while (probe.next()) keepPlaintext.insert(probe.value(0).toString());
        }
    }

    m_db.transaction();
    for (const Entry& e : entries) {
        if (e.encrypted && keepPlaintext.contains(e.id)) continue;
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
        query.addBindValue(e.isForwarded ? 1 : 0);
        query.addBindValue(e.forwardedFromName);
        query.addBindValue(e.replyToId);
        query.addBindValue(e.encryptionVersion <= 0 ? 1 : e.encryptionVersion);
        query.addBindValue(e.senderDeviceId);
        if (!query.exec()) {
            qWarning() << "[MessageCache] Failed to save message:" << query.lastError().text();
        }
    }
    m_db.commit();
}

MessageCache::Entry MessageCache::withRecoveredPlaintext(const Entry& source,
                                                         const QString& plaintext) {
    Entry e = source;
    e.content = plaintext;
    e.encrypted = false;
    return e;
}

QList<MessageCache::Entry> MessageCache::loadMessages(const QString& chatId, int limit) const {
    QList<Entry> result;
    if (!m_db.isOpen()) return result;

    QSqlQuery query(m_db);
    query.prepare(QStringLiteral(
        "SELECT id, sender_id, sender_name, content, encrypted, read_at, created_at, "
        "       file_url, file_type, duration_ms, file_name, file_size, key_version, "
        "       is_forwarded, forwarded_from_name, reply_to_id, encryption_version, "
        "       sender_device_id "
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
        e.isForwarded = query.value(13).toInt() != 0;
        e.forwardedFromName = query.value(14).toString();
        e.replyToId = query.value(15).toString();
        e.senderDeviceId = query.value(17).toString();
        e.encryptionVersion = query.value(16).toInt();
        if (e.encryptionVersion <= 0) e.encryptionVersion = 1;
        result.prepend(e); // rows came back newest-first; flip to oldest-first
    }
    return result;
}

void MessageCache::saveChats(const QList<QJsonObject>& chats, const QString& owner) {
    if (chats.isEmpty()) return;
    if (!ownedBy(owner)) {
        qWarning() << "[MessageCache] dropping cached chat list: the owning session is no longer active";
        return;
    }

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

    // "id", not "chat_id": the chats table's primary key is id (see the
    // CREATE TABLE above). The old column name matched nothing, so this query
    // silently failed and a deleted chat reappeared from cache on the next
    // cold start.
    query.prepare(QStringLiteral("DELETE FROM chats WHERE id = ?"));
    query.addBindValue(chatId);
    query.exec();
    // Its history is gone from this device, so its sync point is too: the next
    // catch-up re-anchors at the head instead of believing everything up to the
    // old point is still here. Unsent messages in the outbox are NOT touched.
    query.prepare(QStringLiteral("DELETE FROM sync_state WHERE chat_id = ?"));
    query.addBindValue(chatId);
    query.exec();
    // No VACUUM, unlike clear() below: reclaiming pages rewrites the entire
    // database file, which is far too heavy for dropping one conversation.
}

void MessageCache::deleteMessage(const QString& messageId) {
    if (!m_db.isOpen()) return;
    QSqlQuery query(m_db);
    query.prepare(QStringLiteral("DELETE FROM messages WHERE id = ?"));
    query.addBindValue(messageId);
    if (!query.exec()) {
        qWarning() << "[MessageCache] Failed to delete message:" << query.lastError().text();
    }
}

void MessageCache::clear() {
    if (!m_db.isOpen()) return;
    QSqlQuery query(m_db);
    query.exec(QStringLiteral("DELETE FROM messages"));
    query.exec(QStringLiteral("DELETE FROM chats"));
    // Sync points describe the history just deleted. The outbox is deliberately
    // kept: clearing a cache must never lose a message that has not been sent.
    query.exec(QStringLiteral("DELETE FROM sync_state"));
    // Reclaims the space the deleted rows occupied - without this, SQLite
    // keeps the file at its previous size until something else writes over
    // the freed pages, so "clear cache" wouldn't actually shrink anything.
    query.exec(QStringLiteral("VACUUM"));
}

qint64 MessageCache::sizeBytes() const {
    return QFileInfo(m_db.databaseName()).size();
}

// ------------------------------------------------------------------ Phase 71

namespace {
const char* kOutboxColumns =
    "client_message_id, chat_id, chat_type, request_json, state, created_at, attempts, "
    "next_attempt_at, last_error, server_message_id, accepted_at";

MessageCache::OutboxItem outboxRow(const QSqlQuery& q) {
    MessageCache::OutboxItem it;
    it.clientMessageId = q.value(0).toString();
    it.chatId = q.value(1).toString();
    it.chatType = q.value(2).toString();
    it.requestJson = q.value(3).toString();
    it.state = q.value(4).toString();
    it.createdAt = q.value(5).toLongLong();
    it.attempts = q.value(6).toInt();
    it.nextAttemptAt = q.value(7).toLongLong();
    it.lastError = q.value(8).toString();
    it.serverMessageId = q.value(9).toString();
    it.acceptedAt = q.value(10).toLongLong();
    return it;
}
} // namespace

bool MessageCache::insertOutbox(const OutboxItem& item, const QString& owner) {
    if (!ownedBy(owner)) {
        qWarning() << "[MessageCache] refusing an outbox write: the owning session is no longer active";
        return false;
    }
    QSqlQuery q(m_db);
    // Plain INSERT, never REPLACE: a client_message_id is minted once.
    q.prepare(QStringLiteral("INSERT INTO outbox (%1) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                  .arg(QLatin1String(kOutboxColumns)));
    q.addBindValue(item.clientMessageId);
    q.addBindValue(item.chatId);
    q.addBindValue(item.chatType);
    q.addBindValue(item.requestJson);
    q.addBindValue(item.state);
    q.addBindValue(item.createdAt);
    q.addBindValue(item.attempts);
    q.addBindValue(item.nextAttemptAt);
    q.addBindValue(item.lastError.isEmpty() ? QVariant() : QVariant(item.lastError));
    q.addBindValue(item.serverMessageId.isEmpty() ? QVariant() : QVariant(item.serverMessageId));
    q.addBindValue(item.acceptedAt > 0 ? QVariant(item.acceptedAt) : QVariant());
    if (!q.exec()) {
        qWarning() << "[MessageCache] outbox insert refused:" << q.lastError().text();
        return false;
    }
    return true;
}

bool MessageCache::outboxItem(const QString& clientMessageId, OutboxItem* out) const {
    if (!m_db.isOpen()) return false;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("SELECT %1 FROM outbox WHERE client_message_id = ?")
                  .arg(QLatin1String(kOutboxColumns)));
    q.addBindValue(clientMessageId);
    if (!q.exec() || !q.next()) return false;
    if (out) *out = outboxRow(q);
    return true;
}

QList<MessageCache::OutboxItem> MessageCache::dueOutbox(qint64 now) const {
    QList<OutboxItem> rows;
    if (!m_db.isOpen()) return rows;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("SELECT %1 FROM outbox WHERE state = 'PENDING' AND next_attempt_at <= ? "
                             "ORDER BY created_at, client_message_id").arg(QLatin1String(kOutboxColumns)));
    q.addBindValue(now);
    if (q.exec()) while (q.next()) rows << outboxRow(q);
    return rows;
}

QList<MessageCache::OutboxItem> MessageCache::unacceptedOutbox(const QString& chatId) const {
    QList<OutboxItem> rows;
    if (!m_db.isOpen()) return rows;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("SELECT %1 FROM outbox WHERE chat_id = ? AND state != 'ACCEPTED' "
                             "ORDER BY created_at, client_message_id").arg(QLatin1String(kOutboxColumns)));
    q.addBindValue(chatId);
    if (q.exec()) while (q.next()) rows << outboxRow(q);
    return rows;
}

qint64 MessageCache::earliestPendingOutbox() const {
    if (!m_db.isOpen()) return -1;
    QSqlQuery q(m_db);
    if (!q.exec(QStringLiteral("SELECT MIN(next_attempt_at) FROM outbox WHERE state = 'PENDING'"))
        || !q.next() || q.value(0).isNull()) {
        return -1;
    }
    return q.value(0).toLongLong();
}

void MessageCache::markOutboxAccepted(const QString& clientMessageId, const QString& serverId, qint64 at,
                                      const QString& owner) {
    if (!ownedBy(owner)) return;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("UPDATE outbox SET state = 'ACCEPTED', server_message_id = ?, accepted_at = ?, "
                             "last_error = NULL WHERE client_message_id = ?"));
    q.addBindValue(serverId);
    q.addBindValue(at);
    q.addBindValue(clientMessageId);
    q.exec();
}

void MessageCache::markOutboxRetry(const QString& clientMessageId, int attempts, qint64 nextAttemptAt,
                                   const QString& error, const QString& owner) {
    if (!ownedBy(owner)) return;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? "
                             "WHERE client_message_id = ? AND state = 'PENDING'"));
    q.addBindValue(attempts);
    q.addBindValue(nextAttemptAt);
    q.addBindValue(error);
    q.addBindValue(clientMessageId);
    q.exec();
}

void MessageCache::markOutboxFailed(const QString& clientMessageId, int attempts, const QString& error,
                                    const QString& owner) {
    if (!ownedBy(owner)) return;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("UPDATE outbox SET state = 'FAILED', attempts = ?, last_error = ? "
                             "WHERE client_message_id = ? AND state = 'PENDING'"));
    q.addBindValue(attempts);
    q.addBindValue(error);
    q.addBindValue(clientMessageId);
    q.exec();
}

bool MessageCache::resetOutboxForRetry(const QString& clientMessageId, qint64 now, const QString& owner) {
    if (!ownedBy(owner)) return false;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("UPDATE outbox SET state = 'PENDING', attempts = 0, next_attempt_at = ?, "
                             "last_error = NULL WHERE client_message_id = ? AND state = 'FAILED'"));
    q.addBindValue(now);
    q.addBindValue(clientMessageId);
    return q.exec() && q.numRowsAffected() > 0;
}

void MessageCache::pruneAcceptedOutbox(qint64 olderThan, const QString& owner) {
    if (!ownedBy(owner)) return;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("DELETE FROM outbox WHERE state = 'ACCEPTED' AND accepted_at < ?"));
    q.addBindValue(olderThan);
    q.exec();
}

bool MessageCache::loadSyncPoint(const QString& chatId, SyncPoint* out) const {
    if (!m_db.isOpen()) return false;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("SELECT last_seq, backfill_before FROM sync_state WHERE chat_id = ?"));
    q.addBindValue(chatId);
    if (!q.exec() || !q.next()) return false;
    if (out) {
        out->lastSeq = q.value(0).toLongLong();
        out->backfillBefore = q.value(1).toLongLong();
    }
    return true;
}

void MessageCache::saveSyncPoint(const QString& chatId, const SyncPoint& point, const QString& owner) {
    if (!ownedBy(owner)) return;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("INSERT OR REPLACE INTO sync_state (chat_id, last_seq, backfill_before, updated_at) "
                             "VALUES (?, ?, ?, ?)"));
    q.addBindValue(chatId);
    q.addBindValue(point.lastSeq);
    q.addBindValue(point.backfillBefore);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.exec();
}

bool MessageCache::hasMessage(const QString& messageId) const {
    if (!m_db.isOpen()) return false;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("SELECT 1 FROM messages WHERE id = ? LIMIT 1"));
    q.addBindValue(messageId);
    return q.exec() && q.next();
}

bool MessageCache::hasMessagesInChat(const QString& chatId) const {
    if (!m_db.isOpen()) return false;
    QSqlQuery q(m_db);
    q.prepare(QStringLiteral("SELECT 1 FROM messages WHERE chat_id = ? LIMIT 1"));
    q.addBindValue(chatId);
    return q.exec() && q.next();
}
