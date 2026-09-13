package com.messenger.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 48 - an archive that arrives during a fetch must be openable in that same fetch.
 *
 * WHAT WENT WRONG. Inside one `cacheMessages` pass the order was fixed and wrong for the case
 * Layer B exists to serve:
 *
 *     restoreFromArchive(...)   // reads the row's archive - which is not there yet
 *     insertMessages(...)
 *     applyRemoteArchives(...)  // downloads and pins the archive
 *
 * A device that had just recovered its history keyring therefore met its archive one step too late.
 * The first fetch left the message as the encrypted placeholder, and only a later refresh could
 * open it. Nothing was lost - the ciphertext is durable - but new-device recovery silently required
 * a second refresh, which is a latency defect in precisely the scenario Phases 45-47 built toward.
 *
 * WHY THE FIX IS A SECOND PASS AND NOT A REORDERING. `applyRemoteArchives` pins an archive onto an
 * existing row and skips any record whose message row is absent. Running it before `insertMessages`
 * would therefore DROP the archive for a brand-new message - the exact case that matters - instead
 * of merely delaying it. So the download stays where it is and a restore pass follows it, scoped to
 * the ids that were actually pinned.
 *
 * WHY THIS TEST IS SHAPED LIKE THIS. The ordering lives inside a private method of ChatRepository
 * whose reachable paths run Argon2id and XChaCha20 through lazysodium's Android native library,
 * which does not load in a plain JVM unit test - no JVM test in this module touches VaultCrypto for
 * that reason. Asserting the invariant in the source is what is actually expressible here; the
 * behavioural proof is the real-device run. What this catches is the regression that created the
 * defect: a restore pass that does not follow the download.
 */
class ArchiveFirstFetchOrderingTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/messenger/app/data/repository/ChatRepository.kt")
        assertTrue("ChatRepository.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** Body of a named private method, brace-matched from its signature. */
    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        var i = source.indexOf('{', start)
        val begin = i
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return source.substring(begin, i + 1) }
            }
            i++
        }
        throw AssertionError("unbalanced braces in $signature")
    }

    @Test
    fun `download is followed by a restore pass in the same fetch`() {
        val body = bodyOf("private suspend fun cacheMessages(")
        val download = body.indexOf("applyRemoteArchives(owner, chatId)")
        val followUp = body.indexOf("restoreNewlyArchived(")
        assertTrue("cacheMessages must still download archives", download >= 0)
        assertTrue(
            "cacheMessages must restore archives that arrived during this fetch - " +
                "without this a recovered device needs a second refresh",
            followUp >= 0
        )
        assertTrue(
            "the restore pass must run AFTER the download, or it sees no archive at all",
            download < followUp
        )
    }

    @Test
    fun `the follow-up pass is scoped to archives pinned in this fetch`() {
        // Not a chat-wide rescan: applyRemoteArchives reports what it pinned and only those rows
        // are revisited. This is what keeps the fix from becoming a bulk restore.
        assertTrue(
            "applyRemoteArchives must report the ids it pinned",
            source.contains("private suspend fun applyRemoteArchives(owner: String, chatId: String): List<String>")
        )
        val body = bodyOf("private suspend fun cacheMessages(")
        assertTrue(
            "the pinned ids must feed the restore pass",
            Regex("val newlyArchived = applyRemoteArchives\\(owner, chatId\\)[\\s\\S]{0,400}?restoreNewlyArchived\\(owner, chatId, newlyArchived\\)")
                .containsMatchIn(body)
        )
    }

    @Test
    fun `the follow-up pass performs no MLS decrypt`() {
        // The ratchet secret is already spent - that is why the archive is being consulted. A
        // decrypt attempt here would be the double-decrypt the cache-first branch exists to avoid.
        val body = bodyOf("private suspend fun restoreNewlyArchived(")
        for (mls in listOf("openTextOrNull", "mls?", "decrypt(")) {
            assertTrue(
                "restoreNewlyArchived must not attempt any MLS decrypt (found: $mls)",
                !body.contains(mls)
            )
        }
    }

    @Test
    fun `the follow-up pass cannot downgrade a readable row`() {
        val body = bodyOf("private suspend fun restoreNewlyArchived(")
        assertTrue(
            "a row that is already readable must be skipped, never rewritten",
            body.contains("hasReadableCachedText(row.isEncrypted, row.content)") &&
                body.contains("continue")
        )
        assertTrue(
            "write-back must use setPlaintext, which touches only content and isEncrypted",
            body.contains("messageDao.setPlaintext(owner, messageId, text)")
        )
    }

    @Test
    fun `the follow-up pass reuses the existing restore helper`() {
        // Phase 47 established the caller already exists. This phase must not add a second one.
        val body = bodyOf("private suspend fun restoreNewlyArchived(")
        assertTrue(
            "must go through restoreFromArchive, not call the archiver directly",
            body.contains("restoreFromArchive(uid, chatId, messageId, row)")
        )
        assertTrue(
            "must not introduce a second MessageArchiver.open() caller",
            !body.contains("onArchiveOpen(")
        )
        // onArchiveOpen is still consumed in exactly one place: restoreFromArchive.
        assertEquals(
            "onArchiveOpen must still have exactly one consumer",
            1,
            Regex("val open = onArchiveOpen").findAll(source).count()
        )
    }

    @Test
    fun `normal decrypt still bypasses the archive entirely`() {
        // The first-pass structure is unchanged: cache, then MLS, and only then Layer B.
        val body = bodyOf("private suspend fun cacheMessages(")
        val cacheHit = body.indexOf("hasReadableCachedText(existing.isEncrypted, existing.content)")
        val mls = body.indexOf("openTextOrNull(chatId, dto)")
        val fallback = body.indexOf("restoreFromArchive(archiveUserId, chatId, dto.id, existing)")
        assertTrue("cache-first branch missing", cacheHit >= 0)
        assertTrue("MLS attempt missing", mls >= 0)
        assertTrue("archive fallback missing", fallback >= 0)
        assertTrue("cache must be consulted before MLS", cacheHit < mls)
        assertTrue("the archive is a fallback AFTER MLS, never a replacement for it", mls < fallback)
    }
}
