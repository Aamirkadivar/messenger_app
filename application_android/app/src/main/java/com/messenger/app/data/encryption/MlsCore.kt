package com.messenger.app.data.encryption

import android.util.Log

/** Error from mls-core, carrying the numeric FFI code. */
class MlsException(message: String) : Exception(message)

/**
 * Thin bridge to mls-core (Rust/OpenMLS) — the same core the Windows client
 * calls through its C ABI. See docs/mls-v2-architecture.md.
 *
 * This object holds NO cryptographic logic and must never parse an MLS
 * structure. The previous design ran BouncyCastle here and mlspp on Windows;
 * they had to agree byte-for-byte and did not, which is what this replaces.
 *
 * Phase 0 contains no MLS: it proves library loading, the JNI marshalling
 * contract, panic containment and the log channel before any crypto exists.
 */
object MlsCore {
    private const val TAG = "MlsCore"

    /** ABI this client is written against; a core reporting anything else is refused. */
    private const val EXPECTED_ABI = 1

    /** True when the .so loaded AND its ABI matches. */
    @Volatile
    var isAvailable: Boolean = false
        private set

    var coreVersion: String = ""
        private set

    var lastError: String = ""
        private set

    init {
        isAvailable = runCatching {
            System.loadLibrary("mls_core")
            // Install the log sink first: without it a failure inside the core
            // is silent, and a silent diagnostic channel is exactly what made
            // the previous design undebuggable.
            nativeInstallLogger()

            val abi = nativeAbiVersion()
            if (abi != EXPECTED_ABI) {
                // Refuse rather than call. A silent ABI mismatch is memory
                // corruption that surfaces as an unrelated crash elsewhere.
                lastError = "ABI mismatch: core reports $abi, client expects $EXPECTED_ABI"
                Log.e(TAG, lastError)
                return@runCatching false
            }
            coreVersion = nativeVersion()
            Log.i(TAG, "mls-core loaded, version=$coreVersion abi=$abi")
            true
        }.getOrElse { e ->
            // Non-fatal: group chats stay on the existing path until cutover.
            lastError = "mls-core unavailable: ${e.message}"
            Log.w(TAG, lastError)
            false
        }
    }

    /**
     * Phase 0 round-trip. Returns the core's reply.
     *
     * Binary data crosses as ByteArray by convention, never String — the old
     * code base had UTF-16/base64 corruption bugs from passing bytes as text.
     */
    fun ping(name: String): String {
        check(isAvailable) { "mls-core not available: $lastError" }
        return String(nativePing(name), Charsets.UTF_8)
    }

    /**
     * Dev check mirroring the Windows selfTest(): load, verify ABI, round-trip,
     * and confirm the error path throws instead of returning null.
     */
    fun selfTest(): Boolean {
        if (!isAvailable) {
            Log.e(TAG, "selftest FAILED: $lastError")
            return false
        }
        val reply = runCatching { ping("android-jni") }.getOrElse {
            Log.e(TAG, "selftest FAILED: ping threw ${it.message}")
            return false
        }
        if (!reply.endsWith(": android-jni")) {
            Log.e(TAG, "selftest FAILED: unexpected reply '$reply'")
            return false
        }
        Log.i(TAG, "selftest: round-trip ok -> $reply")

        // A panic in Rust must arrive as an exception, not kill the process.
        val threw = runCatching { ping("__panic__") }.isFailure
        if (!threw) {
            Log.e(TAG, "selftest FAILED: panic was not surfaced as an exception")
            return false
        }
        Log.i(TAG, "selftest: panic contained as exception")
        Log.i(TAG, "selftest PASSED")
        return true
    }

    // ---- native declarations (implemented in mls-core/src/jni_api.rs) ----
    private external fun nativeAbiVersion(): Int
    private external fun nativeVersion(): String
    private external fun nativeInstallLogger()
    private external fun nativePing(name: String): ByteArray

    /** Reserved for Phase 1's opaque client handle. */
    @Suppress("unused")
    private external fun nativeClientFree(handle: Long)
}
