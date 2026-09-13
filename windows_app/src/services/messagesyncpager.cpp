#include "messagesyncpager.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>

MessageSyncPager::MessageSyncPager(Config cfg, QObject* parent)
    : QObject(parent)
    , m_cfg(std::move(cfg))
{
}

void MessageSyncPager::syncChats(const QStringList& chatIds) {
    for (const QString& id : chatIds) {
        if (!id.isEmpty() && id != m_chat && !m_queue.contains(id)) m_queue << id;
    }
    if (!m_busy) startNextChat();
}

void MessageSyncPager::startNextChat() {
    if (m_queue.isEmpty() || !m_cfg.cache || !m_cfg.network) {
        m_busy = false;
        m_chat.clear();
        emit idle();
        return;
    }
    m_busy = true;
    m_chat = m_queue.takeFirst();
    m_owner = m_cfg.owner ? m_cfg.owner() : QString();
    m_pages = 0;
    m_backfillPages = 0;
    m_messages = 0;
    m_point = MessageCache::SyncPoint{};
    m_stage = m_cfg.cache->loadSyncPoint(m_chat, &m_point) ? Stage::Forward : Stage::Head;
    m_hadLocal = m_cfg.cache->hasMessagesInChat(m_chat);

    const QString chat = m_chat;
    auto go = [this, chat]() {
        if (m_chat != chat) return;
        requestPage();
    };
    if (m_cfg.prepare) m_cfg.prepare(chat, go);
    else go();
}

void MessageSyncPager::requestPage() {
    QUrl url(m_cfg.apiBase() + QStringLiteral("/messages/") + m_chat);
    QUrlQuery q;
    q.addQueryItem(QStringLiteral("limit"), QString::number(m_cfg.pageSize));
    if (m_stage == Stage::Forward) q.addQueryItem(QStringLiteral("after"), QString::number(m_point.lastSeq));
    if (m_stage == Stage::Backfill) q.addQueryItem(QStringLiteral("before"), QString::number(m_point.backfillBefore));
    url.setQuery(q);
    QNetworkRequest request(url);
    if (m_cfg.applyHeaders) m_cfg.applyHeaders(request);
    QNetworkReply* reply = m_cfg.network->get(request);
    ++m_pages;
    const QString chat = m_chat;
    connect(reply, &QNetworkReply::finished, this, [this, reply, chat]() {
        reply->deleteLater();
        if (chat != m_chat) return;
        const QVariant status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute);
        const QJsonObject root = QJsonDocument::fromJson(reply->readAll()).object();
        onPage(status.isValid() ? status.toInt() : 0, root, reply->errorString());
    });
}

void MessageSyncPager::onPage(int status, const QJsonObject& root, const QString& error) {
    if (status < 200 || status >= 300) {
        // Progress so far is saved; the next pass resumes from it.
        emit chatSyncFailed(m_chat, status ? QStringLiteral("HTTP %1").arg(status) : error);
        finishChat(false);
        return;
    }
    // A pass must never write one account's sync point into another's cache.
    if ((m_cfg.owner ? m_cfg.owner() : QString()) != m_owner) {
        finishChat(false);
        return;
    }
    const QJsonArray rows = root.value(QStringLiteral("data")).toArray();
    const bool hasMore = root.value(QStringLiteral("has_more")).toBool(false);
    qint64 top = -1, bottom = -1;
    bool reachedLocal = false;
    for (const QJsonValue& v : rows) {
        const QJsonObject o = v.toObject();
        const qint64 seq = o.value(QStringLiteral("seq")).toVariant().toLongLong();
        if (top < 0 || seq > top) top = seq;
        if (bottom < 0 || seq < bottom) bottom = seq;
        // Decided BEFORE ingesting, or every row would look known.
        if (!reachedLocal && m_cfg.cache->hasMessage(o.value(QStringLiteral("id")).toString())) reachedLocal = true;
    }
    if (!rows.isEmpty() && m_cfg.ingest) {
        m_cfg.ingest(m_chat, rows);
        m_messages += rows.size();
    }

    switch (m_stage) {
    case Stage::Head: {
        if (rows.isEmpty()) {
            m_point = MessageCache::SyncPoint{0, -1};
        } else {
            const bool needsBackfill = m_hadLocal && !reachedLocal && hasMore && bottom > 1;
            m_point = MessageCache::SyncPoint{top, needsBackfill ? bottom : -1};
        }
        m_cfg.cache->saveSyncPoint(m_chat, m_point, m_owner);
        m_stage = Stage::Forward;
        requestPage();
        return;
    }
    case Stage::Forward: {
        bool forwardDone = !hasMore || rows.isEmpty();
        if (!rows.isEmpty()) {
            if (top <= m_point.lastSeq) {
                forwardDone = true; // a server without seq cannot rewind the point
            } else {
                m_point.lastSeq = top;
                m_cfg.cache->saveSyncPoint(m_chat, m_point, m_owner);
            }
        }
        if (!forwardDone) {
            if (m_pages < m_cfg.maxPages) {
                requestPage();
            } else {
                finishChat(false);
            }
            return;
        }
        if (m_point.backfillBefore >= 0 && m_backfillPages < m_cfg.maxBackfillPages) {
            m_stage = Stage::Backfill;
            ++m_backfillPages;
            requestPage();
            return;
        }
        finishChat(m_point.backfillBefore < 0);
        return;
    }
    case Stage::Backfill: {
        const qint64 before = m_point.backfillBefore;
        if (rows.isEmpty() || reachedLocal || !hasMore || bottom <= 1 || bottom >= before) {
            m_point.backfillBefore = -1;
        } else {
            m_point.backfillBefore = bottom;
        }
        m_cfg.cache->saveSyncPoint(m_chat, m_point, m_owner);
        if (m_point.backfillBefore >= 0 && m_backfillPages < m_cfg.maxBackfillPages) {
            ++m_backfillPages;
            requestPage();
            return;
        }
        finishChat(m_point.backfillBefore < 0);
        return;
    }
    }
}

void MessageSyncPager::finishChat(bool complete) {
    const QString chat = m_chat;
    const int messages = m_messages;
    m_chat.clear();
    emit chatSynced(chat, messages, complete);
    startNextChat();
}
