package com.messenger.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the client keeps its published KeyPackages tied to the store that made them.
 *
 * A device that rebuilds its MLS store keeps its device id but loses every
 * private init key behind what it published before. Two things then have to be
 * true, and neither is visible from the server side alone: the client must not
 * count the abandoned stock as its own, and it must republish under the new
 * incarnation - including after a recovery reset, which is the one path that
 * deliberately replaces the store.
 *
 * Source-level guards, like the recovery boundary tests next door: the behaviour
 * itself needs the native core and a Delivery Service, and is covered by the
 * database-backed tests in back-end/handlers.
 */
class MlsKeyPackageStoreTest {

    private companion object {
        val SOURCE = File("src/main/java/com/messenger/app/data/repository/MlsV2Repository.kt")
    }

    private fun source(): String {
        assertTrue(
            "${SOURCE.path} should exist (tests run from the :app module directory)",
            SOURCE.exists()
        )
        return SOURCE.readText()
    }

    /** Extracts a function body by brace matching from its declaration. */
    private fun functionBody(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        assertTrue("could not find $declaration", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("could not find the body of $declaration", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces reading $declaration")
    }

    private fun ensureKeyPackages() = functionBody(source(), "suspend fun ensureKeyPackages(")
    private fun recovery() = functionBody(source(), "private suspend fun replaceClientForRecovery(")
    private fun recreate() = functionBody(source(), "suspend fun recreateMlsGroup(")

    @Test
    fun `the stock check is scoped to the current store`() {
        val call = ensureKeyPackages().lineSequence()
            .firstOrNull { it.contains("countMlsKeyPackages(") }
        assertTrue("expected a countMlsKeyPackages call in ensureKeyPackages", call != null)
        assertTrue(
            "countMlsKeyPackages must be asked about this store only; an unscoped " +
                "count reports an abandoned store's inventory as available stock: $call",
            call!!.contains("known")
        )
    }

    @Test
    fun `an unknown store counts as empty stock`() {
        assertTrue(
            "until this store's tag is known there is nothing on the server it is " +
                "known to have published, so the stock must read as zero rather than " +
                "being taken from an unscoped count",
            Regex("""if\s*\(\s*known\s*==\s*null\s*\)\s*0\s*else""").containsMatchIn(ensureKeyPackages())
        )
    }

    @Test
    fun `the published tag is derived from the batch, not from the cache`() {
        val body = ensureKeyPackages()
        assertTrue(
            "the tag on the wire must come from the packages being published, so a " +
                "stale cache can never file them under a store that did not make them",
            body.contains("MlsStoreId.of(fresh.first())")
        )
        val item = body.lineSequence().firstOrNull { it.contains("MlsKeyPackageItem(") }
        assertTrue("expected the published item to be constructed here", item != null)
        assertFalse(
            "publishing the cached tag would reintroduce exactly that risk: $item",
            item!!.contains("known")
        )
    }

    @Test
    fun `publishing is refused when no tag can be derived`() {
        val body = ensureKeyPackages()
        val guard = body.indexOf("if (sid == null)")
        val publish = body.indexOf("publishMlsKeyPackages(")

        assertTrue("expected a null check on the derived store tag", guard >= 0)
        assertTrue("expected the publish call", publish >= 0)
        assertTrue(
            "the tag must be checked before publishing; an untagged batch lands back " +
                "in the indistinguishable pool this exists to escape",
            guard < publish
        )
        assertTrue(
            "the failure branch must return rather than fall through to the publish",
            body.substring(guard, publish).contains("return@withContext")
        )
    }

    @Test
    fun `recovery forgets the previous store tag`() {
        assertTrue(
            "a fresh client has published nothing; keeping the old tag would let the " +
                "next stock check answer for the abandoned store",
            Regex("""storeId\s*=\s*null""").containsMatchIn(recovery())
        )
    }

    @Test
    fun `recovery republishes key packages for the new store`() {
        assertTrue(
            "a recovered store owns none of the old inventory, so without a republish " +
                "the device becomes impossible to add to any future group",
            recreate().contains("ensureKeyPackages()")
        )
    }

    @Test
    fun `replenishment only happens once recovery has actually succeeded`() {
        val body = recreate()
        val guard = body.indexOf("if (recovered)")
        val replenish = body.indexOf("ensureKeyPackages()")

        assertTrue("expected the recovery outcome to be checked", guard >= 0)
        assertTrue("expected the replenishment call", replenish >= 0)
        assertTrue(
            "publishing persists, and a durable write is only permitted once a commit " +
                "has been accepted - holding group state is the evidence of that",
            guard < replenish
        )
    }

    @Test
    fun `replenishment runs after the group is established`() {
        val body = recreate()
        val create = body.indexOf("createGroupWithClient(")
        val replenish = body.indexOf("ensureKeyPackages()")

        assertTrue("expected createGroupWithClient in the reset path", create >= 0)
        assertTrue(
            "replenishing before the group exists would write the fresh snapshot over " +
                "the old one ahead of any accepted commit",
            replenish > create
        )
    }
}
