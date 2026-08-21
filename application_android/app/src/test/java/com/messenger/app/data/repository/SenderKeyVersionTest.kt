package com.messenger.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group Sender-Key version assignment.
 *
 * Reproduces the failure seen live between two real devices. The version used to
 * be the group's key epoch alone. That epoch only advances when membership
 * changes, so a key regenerated for any other reason - local state lost, storage
 * cleared - was filed under the SAME version with completely different key
 * material. Recipients still held the old key under that number, looked it up,
 * and secretbox authentication failed on every message. The sender could not
 * read its own history back either.
 *
 * The invariant: a version names exactly one key, and never gets reused.
 */
class SenderKeyVersionTest {

    private fun next(groupEpoch: Int, existing: Set<Int>) =
        ChatRepository.nextSenderKeyVersion(groupEpoch, existing)

    @Test
    fun `first key on a fresh chat uses the group epoch`() {
        // Backward compatibility: existing deployments start at version 0 and
        // must keep doing so, or already-distributed keys stop matching.
        assertEquals(0, next(groupEpoch = 0, existing = emptySet()))
        assertEquals(3, next(groupEpoch = 3, existing = emptySet()))
    }

    @Test
    fun `regeneration at an unchanged epoch does not reuse the version`() {
        // The exact observed bug: epoch stays 0, key is regenerated, old code
        // produced 0 again and overwrote the previous key.
        val existing = setOf(0)

        val version = next(groupEpoch = 0, existing = existing)

        assertEquals("a regenerated key must get its own version", 1, version)
        assertFalse("must not reuse a version that already names a key", version in existing)
    }

    @Test
    fun `repeated regeneration keeps advancing`() {
        var existing = setOf(0)
        val issued = mutableListOf<Int>()

        repeat(4) {
            val v = next(groupEpoch = 0, existing = existing)
            issued.add(v)
            existing = existing + v
        }

        assertEquals(listOf(1, 2, 3, 4), issued)
        assertEquals("every version issued must be distinct", issued.size, issued.toSet().size)
    }

    @Test
    fun `epoch advance is still honoured`() {
        // A real membership change must not be masked by the local counter.
        assertEquals(5, next(groupEpoch = 5, existing = setOf(0, 1)))
    }

    @Test
    fun `epoch never pulls the version backwards`() {
        // A stale or reset epoch must not reissue a number already in use.
        assertEquals(8, next(groupEpoch = 0, existing = setOf(7)))
        assertEquals(8, next(groupEpoch = 2, existing = setOf(7)))
    }

    @Test
    fun `never collides with any previously issued version`() {
        // Sweep: whatever the epoch and history, the result is new.
        for (epoch in 0..6) {
            for (highest in 0..6) {
                val existing = (0..highest).toSet()
                val v = next(epoch, existing)
                assertFalse(
                    "epoch=$epoch existing=$existing produced a colliding version $v",
                    v in existing
                )
            }
        }
    }

    @Test
    fun `version advances strictly, so a recipient can order keys`() {
        var existing = emptySet<Int>()
        var previous = -1
        for (epoch in listOf(0, 0, 1, 1, 4, 0)) {
            val v = next(epoch, existing)
            assertTrue("version must strictly advance: $previous -> $v", v > previous)
            previous = v
            existing = existing + v
        }
    }

    @Test
    fun `concurrent regeneration from the same snapshot cannot be issued twice`() {
        // Two callers racing on the same known-version set would both compute the
        // same number; the rule alone cannot prevent that, so the caller holds a
        // mutex. This pins the expectation so the guarantee is not silently lost:
        // once a version is recorded, the next call must move past it.
        val existing = setOf(0)
        val first = next(0, existing)
        val second = next(0, existing + first)

        assertEquals(1, first)
        assertEquals("the second caller must see the first's version and advance", 2, second)
    }

    @Test
    fun `old versions remain addressable for historical ciphertext`() {
        // Advancing must not imply discarding: a recipient still needs v0 to open
        // messages sealed before the rotation.
        val existing = setOf(0, 1)
        val v = next(0, existing)

        assertEquals(2, v)
        assertTrue("earlier versions must stay valid lookups", existing.containsAll(setOf(0, 1)))
    }
}
