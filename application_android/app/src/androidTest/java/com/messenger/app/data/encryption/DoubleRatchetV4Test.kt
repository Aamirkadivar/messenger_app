package com.messenger.app.data.encryption

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented (native LazySodium) verification that the Kotlin v4 ratchet
 * matches the Go reference. The canonical vectors are embedded from
 * test-vectors/e2ee/x3dh-*.json (regenerate with
 * `E2EE_GENVEC=1 go test ./e2ee/ -run TestX3DHVectors`).
 */
@RunWith(AndroidJUnit4::class)
class DoubleRatchetV4Test {

    private val aPk = hex("a19126c482d962e1bd6250037cbb70db92cc906ecdbc1018fc37fb8c6061522e")
    private val aSk = hex("a0a9aeb7bc858a9398e1e6eff4fdc2cbd0d9de272c353a030811161f646d727b")
    private val bPk = hex("a825f4f280990025a16e35322c14affe12bf4fbebd85704d0064b6357b2f573c")
    private val bSk = hex("b3babda4af9699808bf2f5fce7eed1d8c3cacd343f2629101b02050c777e6168")
    private val rk0 = "4a635dfcf3f667ad0ec5421ff2face9f1b3192b7489647a6a5e05af45b1af57f"

    // Frozen A->B transcript, delivered out of order (m0,m2,m4,m1,m3).
    private val transcript = listOf(
        "m0" to "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc390000000000000000a11859ccb6674f7c6c3965a7b14688388238e6d6fb8a0565b03b0b0c282216236e080d342788b40666f9",
        "m2" to "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc390000000200000000eba77f90d77589b14dea2cd098c582b7c18dce2282c2564ee3f695f3b76a3704802dda4939779af53419",
        "m4" to "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc39000000040000000041412aed12416d14e2cf57ddd08d4b37f13da123e108252383d0d8fe8bf05c0cd7f2ac0b7cfe67c9a6da",
        "m1" to "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc3900000001000000007b2d134ac20055192f56a0843ec7c93c99e6a12d926cdf2b1d41b14aa49dc8f8d0897e18288cffb18dd5",
        "m3" to "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc39000000030000000082972a77ddecd52dfb2b715fab9bda2cd758fcc16901e144735dcb7f33bcafbb76eb1480f7df3dc88838",
    )

    @Test
    fun rootMatchesGoVector() {
        val fromA = DoubleRatchetV4.symmetricRoot(aSk, aPk, bPk)
        val fromB = DoubleRatchetV4.symmetricRoot(bSk, bPk, aPk)
        assertNotNull(fromA); assertNotNull(fromB)
        assertEquals(rk0, E2ECrypto.toHex(fromA!!))
        assertEquals(rk0, E2ECrypto.toHex(fromB!!))
    }

    @Test
    fun decryptsGoTranscript() {
        val b = DoubleRatchetV4.initSession(bPk, bSk, aPk)
        assertNotNull(b)
        for ((plain, ctHex) in transcript) {
            val got = b!!.decrypt(hex(ctHex))
            assertNotNull("decrypt $plain returned null", got)
            assertEquals(plain, String(got!!))
        }
    }

    @Test
    fun glareConverges() {
        val (a, b) = pair()
        val a1 = a.encrypt("A1".toByteArray())!!
        val b1 = b.encrypt("B1".toByteArray())!!
        assertEquals("A1", String(b.decrypt(a1)!!))
        assertEquals("B1", String(a.decrypt(b1)!!))
        // Keep talking post-glare, both directions.
        assertEquals("A2", String(b.decrypt(a.encrypt("A2".toByteArray())!!)!!))
        assertEquals("B2", String(a.decrypt(b.encrypt("B2".toByteArray())!!)!!))
    }

    @Test
    fun longInterleaved() {
        val (a, b) = pair()
        for (i in 0 until 50) {
            assertEquals("a$i", String(b.decrypt(a.encrypt("a$i".toByteArray())!!)!!))
            assertEquals("b$i", String(a.decrypt(b.encrypt("b$i".toByteArray())!!)!!))
        }
    }

    @Test
    fun burstsThenReorder() {
        val (a, b) = pair()
        val aCts = (0 until 5).map { a.encrypt("A$it".toByteArray())!! }
        for (i in 4 downTo 0) assertEquals("A$i", String(b.decrypt(aCts[i])!!))
        val bCts = (0 until 5).map { b.encrypt("B$it".toByteArray())!! }
        for (i in 4 downTo 0) assertEquals("B$i", String(a.decrypt(bCts[i])!!))
    }

    @Test
    fun deepGlare() {
        val (a, b) = pair()
        val aCts = ArrayList<ByteArray>()
        val bCts = ArrayList<ByteArray>()
        for (i in 0 until 4) {
            aCts.add(a.encrypt("A$i".toByteArray())!!)
            bCts.add(b.encrypt("B$i".toByteArray())!!)
        }
        for (i in 0 until 4) {
            assertEquals("A$i", String(b.decrypt(aCts[i])!!))
            assertEquals("B$i", String(a.decrypt(bCts[i])!!))
        }
        assertEquals("after", String(b.decrypt(a.encrypt("after".toByteArray())!!)!!))
    }

    @Test
    fun malformedFailsSafe() {
        val (a, b) = pair()
        val good = a.encrypt("real".toByteArray())!!
        val garbage = listOf(
            ByteArray(0),
            byteArrayOf(0x01),
            good.copyOfRange(0, 10),
            good.copyOfRange(0, 41),
            good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xff).toByte() },
            good.copyOf().also { it[0] = 0x02 },
        )
        for ((i, c) in garbage.withIndex()) {
            assertNull("garbage $i should not open", b.decrypt(c))
        }
        // State intact after garbage: the real message still opens.
        assertEquals("real", String(b.decrypt(good)!!))
    }

    @Test
    fun serializationRoundTrips() {
        val (a, b) = pair()
        val a1 = a.encrypt("hello".toByteArray())!!
        assertEquals("hello", String(b.decrypt(a1)!!))
        // Persist and restore B, then continue.
        val restored = DoubleRatchetV4.Session.fromJson(b.toJson())
        assertNotNull(restored)
        val a2 = a.encrypt("world".toByteArray())!!
        assertEquals("world", String(restored!!.decrypt(a2)!!))
    }

    private fun pair(): Pair<DoubleRatchetV4.Session, DoubleRatchetV4.Session> {
        val a = DoubleRatchetV4.initSession(aPk, aSk, bPk)!!
        val b = DoubleRatchetV4.initSession(bPk, bSk, aPk)!!
        return a to b
    }

    private fun hex(s: String) = E2ECrypto.fromHex(s)!!
}
