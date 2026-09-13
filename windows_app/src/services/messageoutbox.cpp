#include "messageoutbox.h"

#include <QDateTime>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QtGlobal>

MessageOutbox::MessageOutbox(Config cfg, QObject* parent)
    : QObject(parent)
    , m_cfg(std::move(cfg))
{
    m_wake.setSingleShot(true);
    connect(&m_wake, &QTimer::timeout, this, &MessageOutbox::drain);
}

qint64 MessageOutbox::now() const {
    return m_cfg.clock ? m_cfg.clock() : QDateTime::currentMSecsSinceEpoch();
}

qint64 MessageOutbox::backoffMs(int attempts) {
    // 2s, 4s, 8s, ... capped at 60s.
    return qMin<qint64>(60000, 1000LL << qMin(attempts, 6));
}

bool MessageOutbox::isRetryableHttp(int status) {
    return status == 408 || status == 425 || status == 429 || status >= 500;
}

bool MessageOutbox::enqueue(const MessageCache::OutboxItem& item) {
    if (!m_cfg.cache || item.state != QStringLiteral("PENDING")) return false;
    const QString owner = m_cfg.owner ? m_cfg.owner() : QString();
    return m_cfg.cache->insertOutbox(item, owner);
}

bool MessageOutbox::retry(const QString& clientMessageId) {
    if (!m_cfg.cache) return false;
    const QString owner = m_cfg.owner ? m_cfg.owner() : QString();
    const bool reset = m_cfg.cache->resetOutboxForRetry(clientMessageId, now(), owner);
    if (reset) drain();
    return reset;
}

void MessageOutbox::drain() {
    if (m_running) {
        m_again = true;
        return;
    }
    if (!m_cfg.cache || !m_cfg.network) return;
    m_passOwner = m_cfg.owner ? m_cfg.owner() : QString();
    if (m_passOwner.isEmpty()) return;
    m_running = true;
    m_accepted = m_retrying = m_failed = 0;
    const qint64 start = now();
    m_cfg.cache->pruneAcceptedOutbox(start - kAcceptedRetentionMs, m_passOwner);
    m_queue = m_cfg.cache->dueOutbox(start);
    postNext();
}

void MessageOutbox::postNext() {
    // Every item is re-read: an explicit retry or the account itself may have
    // changed since the listing. A pass never writes for a different account.
    MessageCache::OutboxItem item;
    for (;;) {
        if (m_queue.isEmpty()) {
            finishPass();
            return;
        }
        const QString id = m_queue.takeFirst().clientMessageId;
        const QString owner = m_cfg.owner ? m_cfg.owner() : QString();
        if (owner != m_passOwner) {
            m_queue.clear();
            continue;
        }
        if (m_cfg.cache->outboxItem(id, &item) && item.state == QStringLiteral("PENDING")) break;
    }

    QNetworkRequest request(QUrl(m_cfg.apiBase() + QStringLiteral("/messages")));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    if (m_cfg.applyHeaders) m_cfg.applyHeaders(request);
    QNetworkReply* reply = m_cfg.network->post(request, item.requestJson.toUtf8());

    connect(reply, &QNetworkReply::finished, this, [this, reply, item]() {
        reply->deleteLater();
        const QVariant statusAttr = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute);
        const QByteArray body = reply->readAll();
        const QString owner = m_passOwner;

        if (!statusAttr.isValid()) {
            // No HTTP answer at all: the outcome is unknown, so the only safe
            // move is to resend the same client_message_id later.
            const int attempts = item.attempts + 1;
            const QString reason = reply->errorString();
            m_cfg.cache->markOutboxRetry(item.clientMessageId, attempts, now() + backoffMs(attempts), reason, owner);
            ++m_retrying;
            emit retrying(item.chatId, item.clientMessageId, reason);
            postNext();
            return;
        }

        const int status = statusAttr.toInt();
        if (status >= 200 && status < 300) {
            const QJsonObject data = QJsonDocument::fromJson(body).object().value(QStringLiteral("data")).toObject();
            const QString serverId = data.value(QStringLiteral("id")).toString();
            if (serverId.isEmpty()) {
                // Accepted but unreadable: treat as unknown and resend - the
                // server answers a repeat with the stored message.
                const int attempts = item.attempts + 1;
                m_cfg.cache->markOutboxRetry(item.clientMessageId, attempts, now() + backoffMs(attempts),
                                             QStringLiteral("unreadable acceptance"), owner);
                ++m_retrying;
                emit retrying(item.chatId, item.clientMessageId, QStringLiteral("unreadable acceptance"));
            } else {
                m_cfg.cache->markOutboxAccepted(item.clientMessageId, serverId, now(), owner);
                ++m_accepted;
                emit accepted(item.chatId, item.clientMessageId, data);
            }
            postNext();
            return;
        }

        if (status == 401) {
            // Every other item would be refused the same way until re-auth.
            m_cfg.cache->markOutboxRetry(item.clientMessageId, item.attempts, now() + 2000,
                                         QStringLiteral("unauthorized"), owner);
            ++m_retrying;
            emit retrying(item.chatId, item.clientMessageId, QStringLiteral("unauthorized"));
            m_queue.clear();
            postNext();
            return;
        }

        const QString reason = QStringLiteral("HTTP %1").arg(status);
        if (isRetryableHttp(status)) {
            const int attempts = item.attempts + 1;
            if (attempts >= kMaxServerAttempts) {
                m_cfg.cache->markOutboxFailed(item.clientMessageId, attempts, reason, owner);
                ++m_failed;
                emit failed(item.chatId, item.clientMessageId, reason);
            } else {
                m_cfg.cache->markOutboxRetry(item.clientMessageId, attempts, now() + backoffMs(attempts), reason, owner);
                ++m_retrying;
                emit retrying(item.chatId, item.clientMessageId, reason);
            }
        } else {
            m_cfg.cache->markOutboxFailed(item.clientMessageId, item.attempts + 1, reason, owner);
            ++m_failed;
            emit failed(item.chatId, item.clientMessageId, reason);
        }
        postNext();
    });
}

void MessageOutbox::finishPass() {
    m_running = false;
    emit passFinished(m_accepted, m_retrying, m_failed);
    if (m_again) {
        m_again = false;
        drain();
        return;
    }
    scheduleWake();
}

void MessageOutbox::scheduleWake() {
    if (!m_cfg.autoWake || !m_cfg.cache) return;
    const qint64 next = m_cfg.cache->earliestPendingOutbox();
    if (next < 0) {
        m_wake.stop();
        return;
    }
    const qint64 wait = qBound(qint64(250), next - now(), qint64(60000));
    m_wake.start(static_cast<int>(wait));
}
