package com.messenger.app.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.encryption.MlsClient
import com.messenger.app.data.encryption.MlsProcessed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * TEST-ONLY. Windows(mlspp) <-> Android(OpenMLS) MLS interop.
 *
 * One test method, one app lifetime, one MlsClient. That matters: the client
 * that mints the KeyPackage is the client that consumes the Welcome and then
 * encrypts the reply, so no private key ever has to leave the device. Nothing
 * is fabricated and no validation is skipped - the Welcome is produced by real
 * mlspp from this exact KeyPackage while the test waits.
 *
 * Handshake with the out-of-band mlspp harness, via /data/local/tmp, public
 * protocol bytes only:
 *   test -> logcat "KP:<hex>"     real Android KeyPackage
 *   host -> p6_welcome/gid/appmsg real Windows Welcome + app message
 *   host -> p6_ready              written last; signals the set is complete
 *   test -> logcat "CT:<hex>"     real Android application ciphertext
 *
 * Not runnable unattended: it blocks up to 240s on the harness. Drive it with
 *   gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *       com.messenger.app.interop.P5WindowsInteropTest
 * and run the harness in parallel. See the Phase 5/6 reports.
 */
@RunWith(AndroidJUnit4::class)
class P5WindowsInteropTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Ignore("needs the out-of-band mlspp harness; see the header note")
    @Test
    fun windowsAndroidBidirectionalMlsInterop() {
        val client = MlsClient.create("p6-android", "emu5556")

        // --- Android mints a real KeyPackage through the real JNI ----------
        val kp = client.keyPackage()
        android.util.Log.i("P6", "ANDROID_KP_BEGIN")
        kp.hex().chunked(900).forEach { android.util.Log.i("P6", "KP:$it") }
        android.util.Log.i("P6", "ANDROID_KP_END size=${kp.size}")

        // --- wait for the real Windows Welcome ----------------------------
        val ready = File("/data/local/tmp/p6_ready")
        val deadline = System.currentTimeMillis() + 240_000
        while (!ready.exists() && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
        assertTrue("timed out waiting for the Windows Welcome", ready.exists())

        val welcome = File("/data/local/tmp/p6_welcome.hex").readText().unhex()
        val expectedGid = File("/data/local/tmp/p6_gid.hex").readText().unhex()
        val winMsg = File("/data/local/tmp/p6_appmsg.hex").readText().unhex()

        // --- production join path -----------------------------------------
        val gid = client.joinFromWelcome(welcome)
        android.util.Log.i("P6", "JOINED gid=${gid.hex()}")
        assertTrue("group id must match the Windows group", gid.contentEquals(expectedGid))

        val epoch = client.epoch(gid)
        val roster = client.roster(gid)
        android.util.Log.i("P6", "EPOCH=$epoch MEMBERS=${roster.size} ROSTER=$roster")
        assertEquals("one Add commit past group creation", 1L, epoch)
        assertEquals("creator + this device", 2, roster.size)

        // --- Windows -> Android -------------------------------------------
        val processed = client.process(gid, winMsg)
        assertTrue("expected an application message, got $processed",
            processed is MlsProcessed.Application)
        assertEquals("hello from win",
            String((processed as MlsProcessed.Application).plaintext))
        android.util.Log.i("P6", "WIN_TO_ANDROID_OK")

        // --- Android -> Windows: real ciphertext from the real MLS stack ---
        val plaintext = "hello from android phase6"
        val ct = client.encrypt(gid, plaintext.toByteArray())
        assertTrue("ciphertext must be non-empty", ct.isNotEmpty())
        android.util.Log.i("P6", "CT_BEGIN len=${ct.size} epoch=${client.epoch(gid)}")
        ct.hex().chunked(900).forEach { android.util.Log.i("P6", "CT:$it") }
        android.util.Log.i("P6", "CT_END")

        // Encrypting must not have moved the epoch.
        assertEquals("application encryption must not advance the epoch",
            1L, client.epoch(gid))

        client.close()
    }
}
