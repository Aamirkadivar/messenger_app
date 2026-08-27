package com.messenger.app.data.encryption.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT B - archive state model, JVM.
 *
 * The transition rules here are what stop a retracted message from being resurrected by a later
 * archiving pass, so they are tested exhaustively rather than by example.
 */
class ArchiveStateTest {

    @Test
    fun wireValuesAreStableAndDistinct() {
        // These strings are persisted in `messages.archiveState`. Changing one silently
        // reinterprets existing rows, so they are pinned.
        assertEquals("none", ArchiveState.NONE.wire)
        assertEquals("pending", ArchiveState.PENDING.wire)
        assertEquals("sealed", ArchiveState.SEALED.wire)
        assertEquals("retracted", ArchiveState.RETRACTED.wire)
        assertEquals(
            "wire values must be unique",
            ArchiveState.entries.size,
            ArchiveState.entries.map { it.wire }.toSet().size
        )
    }

    @Test
    fun wireRoundTripsForEveryState() {
        for (s in ArchiveState.entries) {
            assertEquals(s, ArchiveState.fromWire(s.wire))
        }
    }

    /** Every pre-v8 row has a NULL archiveState and must read as NONE, not as an error. */
    @Test
    fun nullAndUnknownReadAsNone() {
        assertEquals(ArchiveState.NONE, ArchiveState.fromWire(null))
        assertEquals(ArchiveState.NONE, ArchiveState.fromWire(""))
        assertEquals(ArchiveState.NONE, ArchiveState.fromWire("SEALED"))
        assertEquals(ArchiveState.NONE, ArchiveState.fromWire("something-from-the-future"))
    }

    /**
     * The invariant behind having this column at all: once retracted, a row can never carry an
     * archive again. Without it, a retracted message looks exactly like one that was never
     * archived, and re-archiving would resurrect it.
     */
    @Test
    fun retractedIsTerminal() {
        for (to in ArchiveState.entries) {
            val allowed = ArchiveState.isValidTransition(ArchiveState.RETRACTED, to)
            if (to == ArchiveState.RETRACTED) {
                assertTrue("self-transition stays idempotent", allowed)
            } else {
                assertFalse("RETRACTED -> $to must be refused", allowed)
            }
        }
    }

    @Test
    fun anyStateMayBeRetracted() {
        for (from in ArchiveState.entries) {
            assertTrue(
                "$from -> RETRACTED must be allowed so a deletion is always recordable",
                ArchiveState.isValidTransition(from, ArchiveState.RETRACTED)
            )
        }
    }

    @Test
    fun forwardTransitionsAreAllowed() {
        assertTrue(ArchiveState.isValidTransition(ArchiveState.NONE, ArchiveState.PENDING))
        assertTrue(ArchiveState.isValidTransition(ArchiveState.NONE, ArchiveState.SEALED))
        assertTrue(ArchiveState.isValidTransition(ArchiveState.PENDING, ArchiveState.SEALED))
    }

    @Test
    fun backwardTransitionsAreRefused() {
        assertFalse(ArchiveState.isValidTransition(ArchiveState.SEALED, ArchiveState.PENDING))
        assertFalse(ArchiveState.isValidTransition(ArchiveState.SEALED, ArchiveState.NONE))
        assertFalse(ArchiveState.isValidTransition(ArchiveState.PENDING, ArchiveState.NONE))
    }

    @Test
    fun selfTransitionsAreIdempotent() {
        for (s in ArchiveState.entries) {
            assertTrue("$s -> $s must be allowed", ArchiveState.isValidTransition(s, s))
        }
    }

    /** Guards the full matrix, so adding a state forces a deliberate decision about its edges. */
    @Test
    fun transitionMatrixIsExactlyAsSpecified() {
        val allowed = mutableSetOf<Pair<ArchiveState, ArchiveState>>()
        for (from in ArchiveState.entries) {
            for (to in ArchiveState.entries) {
                if (ArchiveState.isValidTransition(from, to)) allowed.add(from to to)
            }
        }
        val expected = setOf(
            ArchiveState.NONE to ArchiveState.NONE,
            ArchiveState.NONE to ArchiveState.PENDING,
            ArchiveState.NONE to ArchiveState.SEALED,
            ArchiveState.NONE to ArchiveState.RETRACTED,
            ArchiveState.PENDING to ArchiveState.PENDING,
            ArchiveState.PENDING to ArchiveState.SEALED,
            ArchiveState.PENDING to ArchiveState.RETRACTED,
            ArchiveState.SEALED to ArchiveState.SEALED,
            ArchiveState.SEALED to ArchiveState.RETRACTED,
            ArchiveState.RETRACTED to ArchiveState.RETRACTED,
        )
        assertEquals(expected, allowed)
    }
}
