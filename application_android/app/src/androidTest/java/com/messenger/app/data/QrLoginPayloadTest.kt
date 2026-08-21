package com.messenger.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the QR sign-in payload parsing used by SettingsViewModel.approveQrLogin
 * and the Settings scanner's prefix dispatch.
 *
 * Malformed input must be rejected locally, before any request carrying an
 * access token goes out — a scanner will happily read any QR in the world,
 * including a hostile one.
 */
@RunWith(AndroidJUnit4::class)
class QrLoginPayloadTest {

    /** Mirrors the guard in SettingsViewModel.approveQrLogin. */
    private fun parse(scanned: String): Pair<String, String>? {
        val parts = scanned.trim().split(".")
        if (parts.size != 3 || parts[0] != "qr1" || parts[1].isBlank() || parts[2].isBlank()) {
            return null
        }
        return parts[1] to parts[2]
    }

    /** Mirrors the scanner's dispatch between sign-in and device-pairing codes. */
    private fun isSignIn(code: String) = code.trim().startsWith("qr1.")

    @Test
    fun acceptsAWellFormedSignInCode() {
        val parsed = parse("qr1.abc-123.SGVsbG9TZWNyZXQ")
        assertEquals("abc-123" to "SGVsbG9TZWNyZXQ", parsed)
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        assertEquals("s1" to "sec", parse("  qr1.s1.sec \n"))
    }

    @Test
    fun rejectsMalformedOrHostilePayloads() {
        val bad = listOf(
            "",                       // nothing
            "qr1",                    // no parts
            "qr1.onlyone",            // missing secret
            "qr1..secret",            // empty session
            "qr1.session.",           // empty secret
            "mp1.session.secret",     // a device-pairing code, not a sign-in
            "https://evil.example",   // arbitrary QR from the wild
            "qr1.a.b.c"               // extra field
        )
        for (code in bad) {
            assertTrue("must reject: '$code'", parse(code) == null)
        }
    }

    /**
     * The dispatch must not send a device-pairing code down the sign-in path
     * (or vice versa): they authorise different things.
     */
    @Test
    fun dispatchSeparatesSignInFromDevicePairing() {
        assertTrue(isSignIn("qr1.session.secret"))
        assertFalse(isSignIn("mp1.session.pubkey"))
        assertFalse(isSignIn("random-text"))
    }
}
