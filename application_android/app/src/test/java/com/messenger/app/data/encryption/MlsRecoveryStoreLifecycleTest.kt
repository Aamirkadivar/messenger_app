package com.messenger.app.data.encryption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recovery lifecycle, to the extent a JVM test can prove it.
 *
 * `store A -> MlsClient.create() -> store B` cannot be run here: creating a
 * client calls [MlsNative.clientNew], which is a JNI `external fun` backed by
 * libmls_core.so. There is no honest JVM substitute - a mock returning a
 * hand-written "store B" would prove only that the mock was configured, not that
 * a real new store yields a new identity. The parts that DO cross into the
 * native core are named at the bottom of this file as instrumented-test work.
 *
 * What is genuinely provable here is the tag algebra the lifecycle rests on:
 * that a new signing identity produces a new tag, and that a batch's tag is a
 * property of the client that made it.
 */
class MlsRecoveryStoreLifecycleTest {

    private fun varint(n: Int): ByteArray = when {
        n < 0x40 -> byteArrayOf(n.toByte())
        n < 0x4000 -> byteArrayOf(((0x40 or (n ushr 8)).toByte()), n.toByte())
        else -> byteArrayOf(
            (0x80 or (n ushr 24)).toByte(), (n ushr 16).toByte(),
            (n ushr 8).toByte(), n.toByte()
        )
    }

    private fun vec(b: ByteArray): ByteArray = varint(b.size) + b

    /** One KeyPackage from a store whose signing identity is [identity]. */
    private fun packageFrom(identity: ByteArray, nonce: Int): ByteArray =
        byteArrayOf(0, 1, 0, 1) +
            vec(ByteArray(32) { nonce.toByte() }) +
            vec(ByteArray(32) { (nonce + 1).toByte() }) +
            vec(identity) +
            ByteArray(48) { 9 }

    /** A publish batch: several packages, all from the same store. */
    private fun batchFrom(identity: ByteArray, count: Int): List<ByteArray> =
        (1..count).map { packageFrom(identity, it) }

    private val storeA = ByteArray(32) { 0xA0.toByte() }
    private val storeB = ByteArray(32) { 0xB0.toByte() }

    @Test
    fun `a rebuilt store publishes under a different tag`() {
        val a = MlsStoreId.of(batchFrom(storeA, 10).first())
        val b = MlsStoreId.of(batchFrom(storeB, 10).first())

        assertTrue("both batches must yield a tag", a != null && b != null)
        assertNotEquals(
            "recovery replaces the signing identity, so the tag must change - " +
                "otherwise the abandoned store's packages stay claimable",
            a, b
        )
    }

    @Test
    fun `every package in a batch carries its store's tag`() {
        val tags = batchFrom(storeB, 10).map { MlsStoreId.of(it) }.toSet()
        assertEquals(
            "a batch must file under exactly one incarnation; more than one tag " +
                "would split a device's own stock across phantom stores",
            1, tags.size
        )
        assertEquals(MlsStoreId.of(packageFrom(storeB, 99)), tags.first())
    }

    @Test
    fun `no package from the new store can carry the old store's tag`() {
        val tagA = MlsStoreId.of(packageFrom(storeA, 1))
        val fromB = batchFrom(storeB, 25).map { MlsStoreId.of(it) }

        assertFalse(
            "the tag is derived from the packages themselves, so a batch built by " +
                "the recovered client cannot be published under the old tag",
            fromB.contains(tagA)
        )
    }

    /**
     * The boundary, asserted rather than asserted-about: the pieces below are
     * JNI entry points, so anything exercising a real store must be an
     * instrumented test on a device or emulator.
     */
    @Test
    fun `creating a store is a native call, so the lifecycle needs instrumentation`() {
        val native = File("src/main/java/com/messenger/app/data/encryption/MlsNative.kt").readText()
        for (entry in listOf("clientNew", "clientRestore", "keyPackage", "snapshot")) {
            assertTrue(
                "$entry must be a JNI entry point; if it stopped being one, the " +
                    "lifecycle may have become JVM-testable and this file should grow real tests",
                Regex("""external fun $entry\(""").containsMatchIn(native)
            )
        }
    }
}
