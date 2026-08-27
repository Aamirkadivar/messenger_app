package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT E - local retraction, JVM.
 *
 * Covers the retraction rules themselves and the races between retraction, archiving, and refresh.
 * The archiving side is exercised through the real [OutboundArchivePolicy], so "a refresh cannot
 * resurrect a retracted message" is tested against the actual guard rather than a restatement of it.
 */
class ArchiveRetractionPolicyTest {

    private companion object {
        const val PLACEHOLDER = "[encrypted]"
        const val TEXT = "checkpoint-e canary"
    }

    // ------------------------------------------------------------------ the rules

    @Test
    fun everyNonTerminalStateRetracts() {
        for (s in listOf(ArchiveState.NONE, ArchiveState.PENDING, ArchiveState.SEALED)) {
            val plan = ArchiveRetractionPolicy.plan(s)
            assertTrue("$s must retract", plan.write)
            assertEquals(ArchiveState.RETRACTED, plan.newState)
            assertTrue("$s must drop its ciphertext", plan.clearCiphertext)
        }
    }

    /** Duplicate delete notifications are common: a socket replay must be a no-op. */
    @Test
    fun retractingAnAlreadyRetractedRowIsANoOp() {
        val plan = ArchiveRetractionPolicy.plan(ArchiveState.RETRACTED)
        assertFalse("must not write again", plan.write)
    }

    @Test
    fun repeatedRetractionConverges() {
        var state = ArchiveState.SEALED
        repeat(5) {
            val plan = ArchiveRetractionPolicy.plan(state)
            if (plan.write) state = plan.newState
        }
        assertEquals(ArchiveState.RETRACTED, state)
        assertFalse(ArchiveRetractionPolicy.plan(state).write)
    }

    /** Deleting a message that was never archived must be harmless, and must leave a tombstone. */
    @Test
    fun deletingANeverArchivedMessageIsHarmlessAndStillTombstones() {
        val plan = ArchiveRetractionPolicy.plan(null)
        assertTrue(plan.write)
        assertEquals(ArchiveState.RETRACTED, plan.newState)
    }

    @Test
    fun unknownStoredValuesAreTreatedAsNeverArchived() {
        assertTrue(ArchiveRetractionPolicy.plan("nonsense-from-the-future").write)
        assertTrue(ArchiveRetractionPolicy.plan("").write)
    }

    // ------------------------------------------------------------------ races

    private fun sealer(calls: MutableList<String> = mutableListOf()):
        suspend (String, String, String, String) -> SealedArchive? = { _, _, m, _ ->
        calls.add(m); SealedArchive("Y2lwaGVy", 1)
    }

    private suspend fun archive(
        prior: OutboundArchivePolicy.Fields,
        seal: suspend (String, String, String, String) -> SealedArchive? = sealer(),
    ) = OutboundArchivePolicy.archiveFor("u", "c", "m", TEXT, prior, PLACEHOLDER, seal)

    /** archive → delete: the sealed row retracts and its ciphertext goes. */
    @Test
    fun archiveThenDelete() = runBlocking {
        val sealed = archive(OutboundArchivePolicy.Fields.NONE)
        assertEquals(ArchiveState.SEALED.wire, sealed.state)

        val plan = ArchiveRetractionPolicy.plan(sealed.state)
        assertTrue(plan.write)
        assertTrue(plan.clearCiphertext)
    }

    /** delete → archive: the tombstone must prevent the archive from ever being created. */
    @Test
    fun deleteThenArchiveNeverSeals() = runBlocking {
        val calls = mutableListOf<String>()
        val retracted = OutboundArchivePolicy.Fields(null, 0, ArchiveState.RETRACTED.wire)
        val after = archive(retracted, sealer(calls))
        assertSame("the retracted row must come back untouched", retracted, after)
        assertTrue("sealing must not be attempted", calls.isEmpty())
        assertNull(after.ciphertext)
    }

    /** delete → REST refresh: a later refresh must not turn RETRACTED back into SEALED. */
    @Test
    fun refreshAfterDeleteCannotResurrect() = runBlocking {
        var row = OutboundArchivePolicy.Fields(null, 0, ArchiveState.RETRACTED.wire)
        // Three refresh passes, each of which would archive an eligible row.
        repeat(3) { row = archive(row) }
        assertEquals(ArchiveState.RETRACTED.wire, row.state)
        assertNull(row.ciphertext)
    }

    /** REST refresh → delete: an archive created by a refresh still retracts normally. */
    @Test
    fun deleteAfterRefreshRetractsTheFreshArchive() = runBlocking {
        val refreshed = archive(OutboundArchivePolicy.Fields.NONE)
        assertEquals(ArchiveState.SEALED.wire, refreshed.state)
        assertTrue(ArchiveRetractionPolicy.plan(refreshed.state).write)
    }

    @Test
    fun duplicateDeleteAfterArchiveIsIdempotent() = runBlocking {
        val sealed = archive(OutboundArchivePolicy.Fields.NONE)
        val first = ArchiveRetractionPolicy.plan(sealed.state)
        assertTrue(first.write)
        // Row is now RETRACTED; the duplicate event finds nothing to do.
        assertFalse(ArchiveRetractionPolicy.plan(first.newState).write)
    }

    /**
     * The full state machine, asserted as one matrix so adding a state forces a decision about how
     * retraction treats it.
     */
    @Test
    fun retractionMatrixIsExactlyAsSpecified() {
        val writes = ArchiveState.entries.filter { ArchiveRetractionPolicy.plan(it).write }.toSet()
        assertEquals(
            setOf(ArchiveState.NONE, ArchiveState.PENDING, ArchiveState.SEALED),
            writes
        )
        assertTrue(
            "every write must land on RETRACTED",
            ArchiveState.entries.filter { ArchiveRetractionPolicy.plan(it).write }
                .all { ArchiveRetractionPolicy.plan(it).newState == ArchiveState.RETRACTED }
        )
    }

    /** Retraction must respect the state machine defined in Checkpoint B. */
    @Test
    fun everyRetractionIsAValidTransition() {
        for (s in ArchiveState.entries) {
            val plan = ArchiveRetractionPolicy.plan(s)
            if (plan.write) {
                assertTrue(
                    "$s -> ${plan.newState} must be a legal transition",
                    ArchiveState.isValidTransition(s, plan.newState)
                )
            }
        }
    }
}
