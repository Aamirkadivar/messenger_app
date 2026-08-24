package com.messenger.app.data.encryption

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the external-commit reply framing.
 *
 * The on-device path needs the native library, so this covers the one part that
 * can be verified without a device: mls-core answers `externalJoin` with
 * `len(gid) || gid || commit`, and a mis-decode here would either truncate a
 * commit or read past the buffer.
 */
class MlsExternalJoinDecodeTest {

    private fun framed(gid: ByteArray, commit: ByteArray): ByteArray {
        val out = ByteArray(4 + gid.size + commit.size)
        val n = gid.size
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        gid.copyInto(out, 4)
        commit.copyInto(out, 4 + gid.size)
        return out
    }

    @Test
    fun `splits gid and commit at the prefix`() {
        val gid = byteArrayOf(1, 2, 3, 4, 5)
        val commit = byteArrayOf(9, 8, 7)
        val r = MlsClient.decodeExternalJoin(framed(gid, commit))
        assertArrayEquals(gid, r.groupId)
        assertArrayEquals(commit, r.commit)
    }

    @Test
    fun `handles a realistic 32-byte group id`() {
        val gid = ByteArray(32) { it.toByte() }
        val commit = ByteArray(695) { (it % 251).toByte() }
        val r = MlsClient.decodeExternalJoin(framed(gid, commit))
        assertArrayEquals(gid, r.groupId)
        assertArrayEquals(commit, r.commit)
        assertEquals(695, r.commit.size)
    }

    @Test
    fun `an empty commit is preserved rather than mistaken for the gid`() {
        val gid = byteArrayOf(7, 7, 7)
        val r = MlsClient.decodeExternalJoin(framed(gid, ByteArray(0)))
        assertArrayEquals(gid, r.groupId)
        assertEquals(0, r.commit.size)
    }

    @Test
    fun `rejects a buffer too short to hold a prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            MlsClient.decodeExternalJoin(byteArrayOf(0, 0, 1))
        }
    }

    @Test
    fun `rejects a prefix that overruns the buffer`() {
        // claims a 64-byte gid but carries only 8 bytes
        val blob = byteArrayOf(0, 0, 0, 64) + ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) {
            MlsClient.decodeExternalJoin(blob)
        }
    }

    @Test
    fun `rejects a prefix whose high bit would read as negative`() {
        // 0xFFFFFFFF must not decode to -1 and slip past the bounds check
        val blob = byteArrayOf(-1, -1, -1, -1) + ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) {
            MlsClient.decodeExternalJoin(blob)
        }
    }

    @Test
    fun `result equality compares contents, not array identity`() {
        val a = MlsClient.decodeExternalJoin(framed(byteArrayOf(1, 2), byteArrayOf(3)))
        val b = MlsClient.decodeExternalJoin(framed(byteArrayOf(1, 2), byteArrayOf(3)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
