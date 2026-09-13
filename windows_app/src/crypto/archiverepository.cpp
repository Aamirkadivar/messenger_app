#include "archiverepository.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QUrlQuery>

ArchiveRepository::ArchiveRepository(ExchangeFn exchange) : m_exchange(std::move(exchange)) {}

ArchiveRepository::Outcome ArchiveRepository::classify(int status) {
    if (status == 200 || status == 201) return Outcome::Ok;
    if (status == 400) return Outcome::Rejected;
    if (status == 401 || status == 403) return Outcome::Unauthorized;
    if (status == 404) return Outcome::NotFound;
    if (status == 413) return Outcome::TooLarge;
    return Outcome::TransportError;
}

ArchiveRepository::SaveResult ArchiveRepository::save(const ArchiveRecord& record) const {
    SaveResult r;
    if (!m_exchange) return r;

    // Refuse locally rather than sending something the server will certainly reject. This is the
    // record's own completeness rule, not an extra validation layer.
    if (!record.isValid()) {
        r.outcome = Outcome::Rejected;
        return r;
    }

    // chat_id is deliberately absent: the server derives it from the message row. Sending one would
    // offer a value the server must then ignore, and inviting it to be trusted later.
    QJsonObject body;
    body.insert(QStringLiteral("message_id"), record.messageId);
    body.insert(QStringLiteral("root_version"), record.rootVersion);
    body.insert(QStringLiteral("protocol_version"), record.protocolVersion);
    // Forwarded exactly as the archiver produced it - never decoded and re-encoded.
    body.insert(QStringLiteral("ciphertext_b64"), record.ciphertextB64);

    const QPair<int, QJsonObject> resp = m_exchange(
        QString::fromLatin1(PATH), QJsonDocument(body).toJson(QJsonDocument::Compact), "POST");
    r.outcome = classify(resp.first);
    if (r.outcome != Outcome::Ok) return r;

    const QJsonObject& o = resp.second;
    if (!o.contains(QStringLiteral("message_id"))) {
        r.outcome = Outcome::Malformed;
        return r;
    }
    r.messageId = o.value(QStringLiteral("message_id")).toString();
    r.chatId = o.value(QStringLiteral("chat_id")).toString();
    r.stored = o.value(QStringLiteral("stored")).toBool();
    return r;
}

ArchiveRepository::ListResult ArchiveRepository::list(const QString& chatId,
                                                     const QString& since,
                                                     const QString& sinceId) const {
    ListResult r;
    if (!m_exchange) return r;

    QString path = QString::fromLatin1(PATH);
    QUrlQuery q;
    if (!chatId.isEmpty()) q.addQueryItem(QStringLiteral("chat_id"), chatId);
    if (!since.isEmpty()) {
        // Percent-encoded explicitly. An RFC3339 zone offset contains '+', which a query encoder
        // may legitimately leave alone and a receiver then reads as a space. The server repairs
        // that case, but sending it correctly is the client's job, not something to lean on.
        q.addQueryItem(QStringLiteral("since"),
                       QString::fromLatin1(QUrl::toPercentEncoding(since)));
    }
    if (!sinceId.isEmpty()) q.addQueryItem(QStringLiteral("since_id"), sinceId);
    if (!q.isEmpty()) path += QStringLiteral("?") + q.toString(QUrl::FullyEncoded);

    const QPair<int, QJsonObject> resp = m_exchange(path, {}, "GET");
    r.outcome = classify(resp.first);
    if (r.outcome != Outcome::Ok) return r;

    const QJsonObject& o = resp.second;
    if (!o.contains(QStringLiteral("archives"))) {
        r.outcome = Outcome::Malformed;
        return r;
    }
    const QJsonArray arr = o.value(QStringLiteral("archives")).toArray();
    r.records.reserve(arr.size());
    for (const QJsonValue& v : arr) {
        const QJsonObject a = v.toObject();
        ArchiveRecord rec;
        rec.messageId = a.value(QStringLiteral("message_id")).toString();
        rec.chatId = a.value(QStringLiteral("chat_id")).toString();
        rec.rootVersion = a.value(QStringLiteral("root_version")).toInt();
        rec.protocolVersion = a.value(QStringLiteral("protocol_version")).toInt();
        rec.ciphertextB64 = a.value(QStringLiteral("ciphertext_b64")).toString();
        // Every field here is server-supplied and therefore untrusted. A row that is not a complete
        // record is dropped rather than repaired: a half-formed record would fail to open later,
        // far from the point where it arrived malformed.
        if (!rec.isValid()) {
            r.outcome = Outcome::Malformed;
            r.records.clear();
            return r;
        }
        r.records.append(rec);
        // Cursor components come from the row as sent, not from the decoded record: created_at is
        // transport ordering and is deliberately not part of ArchiveRecord.
        r.nextSince = a.value(QStringLiteral("created_at")).toString();
        r.nextSinceId = rec.messageId;
    }
    r.count = o.value(QStringLiteral("count")).toInt(r.records.size());
    // A row without a usable created_at cannot continue the sequence. Report no cursor rather than
    // a partial one: the caller must stop and say so, not guess.
    if (r.nextSince.isEmpty() || r.nextSinceId.isEmpty()) {
        r.nextSince.clear();
        r.nextSinceId.clear();
    }
    return r;
}
