package com.messenger.app.security

import android.content.Context

/**
 * Interface for Android Keystore operations.
 * Used to securely store encryption keys and tokens.
 */
interface KeyStoreManager {
    suspend fun generateEncryptionKey(): Result<Unit>
    suspend fun generateAuthKey(): Result<Unit>
    suspend fun encryptData(plaintext: String, alias: String): Result<String>
    suspend fun decryptData(encryptedData: String, alias: String): Result<String>
    suspend fun containsKey(alias: String): Result<Boolean>
    suspend fun deleteKey(alias: String): Result<Unit>
    fun getPublicKey(alias: String): java.security.PublicKey?
    fun getPrivateKey(alias: String): java.security.PrivateKey?
}