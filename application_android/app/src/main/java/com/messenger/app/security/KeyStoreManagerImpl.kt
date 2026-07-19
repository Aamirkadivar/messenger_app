package com.messenger.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of KeyStoreManager using Android Keystore.
 * Handles AES-256-GCM encryption and RSA key pair generation.
 */
class KeyStoreManagerImpl(private val context: Context) : KeyStoreManager {

    companion object {
        private const val KEY_STORE_NAME = "AndroidKeyStore"
        private const val KEY_ALIAS_ENCRYPTION = "messenger_encryption_key"
        private const val KEY_ALIAS_AUTH = "messenger_auth_key"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val KEY_BLOCK_SIZE = 256
        private const val IV_SIZE = 12 // GCM IV size in bytes
        private const val TAG_SIZE = 128 // GCM tag size in bits
    }

    private val keyStore: javax.security.cert.KeyStore = run {
        val ks = javax.security.cert.KeyStore.getInstance(KEY_STORE_NAME)
        ks.load(null)
        ks
    }

    // Use the standard Java KeyStore
    private val javaKeyStore: java.security.KeyStore by lazy {
        java.security.KeyStore.getInstance(KEY_STORE_NAME).apply {
            load(null)
        }
    }

    @SuppressLint("NewApi")
    override suspend fun generateEncryptionKey(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (javaKeyStore.containsAlias(KEY_ALIAS_ENCRYPTION)) {
                return@withContext Result.success(Unit)
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEY_STORE_NAME
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS_ENCRYPTION,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_GCM)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setInvalidatedByBiometricEnrollment(false)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("NewApi")
    override suspend fun generateAuthKey(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (javaKeyStore.containsAlias(KEY_ALIAS_AUTH)) {
                return@withContext Result.success(Unit)
            }

            val keyPairGenerator = java.security.KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEY_STORE_NAME
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS_AUTH,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT or
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .build()

            keyPairGenerator.init(spec)
            keyPairGenerator.generateKeyPair()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("NewApi")
    override suspend fun encryptData(plaintext: String, alias: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val key = javaKeyStore.getKey(alias, null) as javax.crypto.SecretKey

                val cipher = Cipher.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES + "/" +
                            KeyProperties.BLOCK_MODE_GCM + "/" +
                            KeyProperties.ENCRYPTION_PADDING_GCM
                )

                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv

                val encrypted = cipher.doFinal(plaintext.toByteArray())

                // Combine IV + encrypted data
                val combined = iv + encrypted
                Result.success(java.util.Base64.getEncoder().encodeToString(combined))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    @SuppressLint("NewApi")
    override suspend fun decryptData(encryptedData: String, alias: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val key = javaKeyStore.getKey(alias, null) as javax.crypto.SecretKey

                val combined = java.util.Base64.getDecoder().decode(encryptedData)

                val iv = combined.copyOfRange(0, IV_SIZE)
                val encrypted = combined.copyOfRange(IV_SIZE, combined.size)

                val cipher = Cipher.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES + "/" +
                            KeyProperties.BLOCK_MODE_GCM + "/" +
                            KeyProperties.ENCRYPTION_PADDING_GCM
                )

                val spec = GCMParameterSpec(TAG_SIZE, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)

                val decrypted = cipher.doFinal(encrypted)
                Result.success(String(decrypted))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun containsKey(alias: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Result.success(javaKeyStore.containsAlias(alias))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteKey(alias: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            javaKeyStore.deleteEntry(alias)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getPublicKey(alias: String): java.security.PublicKey? {
        return try {
            val key = javaKeyStore.getKey(alias, null)
            if (key is java.security.KeyPair) {
                key.public
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getPrivateKey(alias: String): java.security.PrivateKey? {
        return try {
            val key = javaKeyStore.getKey(alias, null)
            if (key is java.security.KeyPair) {
                key.private
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}