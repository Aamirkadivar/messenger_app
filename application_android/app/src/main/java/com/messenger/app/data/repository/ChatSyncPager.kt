package com.messenger.app.data.repository

import com.messenger.app.data.model.MessageDto
import com.messenger.app.data.model.MessagesResponse

/**
 * Reconnect catch-up for one chat (Phase 71).
 *
 * The server numbers every message in a chat with a gap-free, commit-ordered `seq`, and
 * `GET /messages/{chat}?after=N` returns what came after N, oldest first. This pages that endpoint
 * from the chat's stored sync point until the server reports nothing more, ingesting each page and
 * advancing the point as it goes - so a device that was offline while any number of messages
 * arrived fetches all of them, not just the newest page.
 *
 * Without a stored point (first run after upgrade, or a chat never synced here):
 *
 *  - no local history for the chat: start at the head, exactly as the app always has - the newest
 *    page, and history beyond it is ordinary scroll-back. Nothing is bulk-downloaded.
 *  - local history exists: the device was offline for some stretch and its cache ends somewhere
 *    before the head. Page BACKWARDS from the head until reaching a message this device already
 *    holds, which closes that gap without re-downloading anything it has.
 *
 * Bounded: at most [maxPages] forward and [maxBackfillPages] backward pages per call. A call that
 * stops at a bound has saved its progress - [SyncPoint.backfillBefore] records where an unfinished
 * backward pass resumes - and the next call continues from there. Nothing is skipped.
 */
class ChatSyncPager(
    private val fetch: suspend (chatId: String, before: Long?, after: Long?, limit: Int) -> MessagesResponse,
    private val ingest: suspend (chatId: String, page: List<MessageDto>) -> Unit,
    private val isKnownLocally: suspend (messageId: String) -> Boolean,
    private val hasLocalHistory: suspend (chatId: String) -> Boolean,
    private val loadSyncPoint: suspend (chatId: String) -> SyncPoint?,
    private val saveSyncPoint: suspend (chatId: String, point: SyncPoint) -> Unit,
    private val pageSize: Int = 100,
    private val maxPages: Int = 50,
    private val maxBackfillPages: Int = 20
) {

    /**
     * [lastSeq]: every message with seq <= lastSeq that this device needs has been ingested, except
     * the range below [backfillBefore] (exclusive) down to the local cache, when that is non-null.
     */
    data class SyncPoint(val lastSeq: Long, val backfillBefore: Long? = null)

    data class Outcome(
        val chatId: String,
        val pages: Int,
        val messages: Int,
        val syncPoint: SyncPoint?,
        /** True when the chat is synchronised: no forward page left and no backfill outstanding. */
        val complete: Boolean
    )

    suspend fun sync(chatId: String): Outcome {
        val stored = loadSyncPoint(chatId)
        var pages = 0
        var total = 0

        var point = stored ?: run {
            // First sync of this chat on this device: anchor at the head.
            val head = fetch(chatId, null, null, pageSize)
            pages++
            if (head.data.isEmpty()) {
                SyncPoint(0).also { saveSyncPoint(chatId, it) }
            } else {
                val hadLocal = hasLocalHistory(chatId)
                val reachedLocal = head.data.any { isKnownLocally(it.id) }
                ingest(chatId, head.data)
                total += head.data.size
                val bottom = head.data.minOf { it.seq }
                val needsBackfill = hadLocal && !reachedLocal && head.hasMore && bottom > 1
                SyncPoint(head.data.maxOf { it.seq }, if (needsBackfill) bottom else null)
                    .also { saveSyncPoint(chatId, it) }
            }
        }

        // Forward: everything after the sync point.
        var forwardDone = false
        while (pages < maxPages) {
            val page = fetch(chatId, null, point.lastSeq, pageSize)
            pages++
            val rows = page.data
            if (rows.isNotEmpty()) {
                ingest(chatId, rows)
                total += rows.size
                val top = rows.maxOf { it.seq }
                // Never backwards: a server without seq (all 0) cannot rewind the point.
                if (top <= point.lastSeq) {
                    forwardDone = true
                    break
                }
                point = point.copy(lastSeq = top)
                saveSyncPoint(chatId, point)
            }
            if (!page.hasMore || rows.isEmpty()) {
                forwardDone = true
                break
            }
        }

        // Backward: close a gap between the local cache and where the first sync anchored.
        var backfillPages = 0
        while (point.backfillBefore != null && backfillPages < maxBackfillPages) {
            val before = point.backfillBefore!!
            val page = fetch(chatId, before, null, pageSize)
            pages++
            backfillPages++
            val rows = page.data
            val reachedLocal = rows.any { isKnownLocally(it.id) }
            if (rows.isNotEmpty()) {
                ingest(chatId, rows)
                total += rows.size
            }
            val bottom = rows.minOfOrNull { it.seq } ?: 0L
            point = if (rows.isEmpty() || reachedLocal || !page.hasMore || bottom <= 1 || bottom >= before) {
                point.copy(backfillBefore = null)
            } else {
                point.copy(backfillBefore = bottom)
            }
            saveSyncPoint(chatId, point)
        }

        return Outcome(chatId, pages, total, point, complete = forwardDone && point.backfillBefore == null)
    }
}
