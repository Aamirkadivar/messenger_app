package com.messenger.app.data.encryption

/**
 * Raw JNI declarations for mls-core. Nothing but signatures lives here —
 * [MlsClient] is the API the rest of the app uses.
 *
 * Every method throws [MlsException] on failure; none returns null silently. A
 * silent null from the old BouncyCastle join path cost an entire debugging
 * session, so the convention is now enforced on the Rust side.
 *
 * Binary data crosses as ByteArray, never String — the previous code base had
 * UTF-16/base64 corruption bugs from passing bytes as text.
 */
internal object MlsNative {
    external fun clientNew(userId: String, deviceId: String): Long
    external fun clientRestore(blob: ByteArray, userId: String, deviceId: String): Long
    external fun clientFree(handle: Long)

    external fun snapshot(handle: Long): ByteArray
    external fun keyPackage(handle: Long): ByteArray

    /**
     * The signature public key a KeyPackage commits to, or an empty array
     * when it is unusable. Validates first; touches no group.
     */
    external fun keyPackageSignatureKey(handle: Long, keyPackage: ByteArray): ByteArray

    /** Signature public keys of the group's members, 4-byte length-prefixed. */
    external fun groupSignatureKeys(handle: Long, gid: ByteArray): ByteArray

    external fun groupCreate(handle: Long, gid: ByteArray)
    external fun groupAdd(handle: Long, gid: ByteArray, keyPackages: ByteArray): ByteArray
    external fun mergePending(handle: Long, gid: ByteArray): Long
    external fun clearPending(handle: Long, gid: ByteArray)
    external fun dropGroup(handle: Long, gid: ByteArray)
    external fun joinWelcome(handle: Long, welcome: ByteArray): ByteArray

    external fun encrypt(handle: Long, gid: ByteArray, plaintext: ByteArray): ByteArray
    external fun process(handle: Long, gid: ByteArray, msg: ByteArray): ByteArray

    external fun epoch(handle: Long, gid: ByteArray): Long
    external fun loadGroup(handle: Long, gid: ByteArray): Long
    external fun roster(handle: Long, gid: ByteArray): ByteArray
}
