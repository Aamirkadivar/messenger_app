package com.messenger.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The invite path must survive one unusable KeyPackage.
 *
 * A live JOIN failed with "invalid key package: A key package extension is not
 * supported in the leaf's capabilities" - a legacy mlspp package in another
 * device's pool. stageAdd is all-or-nothing, so that one package rejected the
 * whole Add, and every package in the batch had already been consumed
 * server-side. One stale device kept a healthy device out of the group, and four
 * KeyPackages were burned for nothing.
 *
 * The behaviour itself is pinned by real tests in mls-core/tests/ds_contract.rs
 * (`one_unusable_package_must_not_keep_valid_devices_out` and friends), which
 * drive actual MLS clients. These are the source-level guards for the Kotlin
 * wiring that has no JVM-testable behaviour: the native core is a JNI boundary.
 */
class MlsInviteIsolationTest {

    private companion object {
        val SOURCE = File("src/main/java/com/messenger/app/data/repository/MlsV2Repository.kt")
        val CLIENT = File("src/main/java/com/messenger/app/data/encryption/MlsClient.kt")
        val NATIVE = File("src/main/java/com/messenger/app/data/encryption/MlsNative.kt")
    }

    private fun functionBody(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        assertTrue("could not find $declaration", start >= 0)
        val open = src.indexOf('{', start)
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

    private fun invite() =
        functionBody(SOURCE.readText(), "suspend fun inviteMissingDevices(")

    @Test
    fun `validation and identity both come from the native core`() {
        val native = NATIVE.readText()
        assertTrue(
            "the core must be the authority on what it can accept - never a " +
                "byte-size, GREASE or cipher-suite heuristic in Kotlin",
            native.contains("external fun keyPackageSignatureKey(")
        )
        assertTrue(
            "the group's existing signature keys must come from the core too",
            native.contains("external fun groupSignatureKeys(")
        )
        val client = CLIENT.readText()
        assertTrue(client.contains("fun signatureKeyOf("))
        assertTrue(client.contains("fun memberSignatureKeys("))
    }

    @Test
    fun `every candidate is vetted before it can reach a commit`() {
        val body = invite()
        val check = body.indexOf("signatureKeyOf(")
        val stage = body.indexOf("stageAdd(")

        assertTrue("the invite path must vet candidates with canAdd", check >= 0)
        assertTrue("expected stageAdd in the invite path", stage >= 0)
        assertTrue(
            "vetting must happen BEFORE staging; stageAdd is all-or-nothing, so an " +
                "unvetted batch is exactly the failure this guards against",
            check < stage
        )
    }

    @Test
    fun `an unusable candidate is skipped rather than aborting the invite`() {
        val body = invite()
        val guard = Regex("""if\s*\(\s*signatureKey\s*==\s*null\s*\)\s*\{""")
        assertTrue(
            "the failure branch must be a per-candidate guard on the derived key",
            guard.containsMatchIn(body)
        )
        val after = body.substring(body.indexOf("signatureKeyOf("))
        assertTrue(
            "an unusable candidate must `continue` to the next device, not return: " +
                "returning would let one stale device block every healthy one",
            after.substringBefore("claimedIds.add").contains("continue")
        )
        assertFalse(
            "the invite must not bail out entirely when a candidate is rejected",
            Regex("""signatureKey == null\)\s*\{[^}]*return@withContext""").containsMatchIn(body)
        )
    }

    @Test
    fun `survivors are still added in a single commit`() {
        val body = invite()
        assertTrue(
            "one Add for all survivors - per-device commits would multiply " +
                "epoch conflicts in a multi-device group",
            body.contains("stageAdd(chatId, claimedKps)")
        )
        assertFalse(
            "stageAdd must not be called from inside the per-device loop",
            Regex("""for \(d in devices\)[\s\S]*?stageAdd\([\s\S]*?\n        \}""")
                .containsMatchIn(body.substringBefore("if (claimedKps.isEmpty())"))
        )
    }

    @Test
    fun `duplicate MLS identities are filtered against the group and the batch`() {
        val body = invite()
        assertTrue(
            "the filter must be seeded from the group's existing members, or a " +
                "candidate already in the tree would still be proposed",
            body.contains("c.memberSignatureKeys(gidOf(chatId))")
        )
        // Presence is not enough: `seenSignatureKeys.add(...)` followed by a dead
        // branch would leave the filter in the source while removing its effect.
        // The add must BE the condition, so its result actually gates the skip.
        assertTrue(
            "the set insertion must gate the branch - expected `if (!seenSignatureKeys.add(`",
            Regex("""if\s*\(\s*!\s*seenSignatureKeys\.add\(""").containsMatchIn(body)
        )
        assertFalse(
            "the duplicate condition must not be widened or short-circuited",
            Regex("""if\s*\(\s*(false|true)\s*\)""").containsMatchIn(body)
        )
        val dup = body.indexOf("if (!seenSignatureKeys.add(")
        val stage = body.indexOf("stageAdd(")
        assertTrue("the duplicate check must precede staging", dup in 0 until stage)
        assertTrue(
            "a duplicate must be skipped, not fatal",
            body.substring(dup).substringBefore("claimedIds.add").contains("continue")
        )
    }

    @Test
    fun `only vetted packages are carried into the commit`() {
        val body = invite()
        val vet = body.indexOf("signatureKeyOf(")
        val add = body.indexOf("claimedKps.add(")
        assertTrue("expected candidates to be collected", add >= 0)
        assertTrue(
            "a candidate must be vetted before it joins the batch, or an unusable " +
                "package could still reach stageAdd",
            vet < add
        )
    }
}
