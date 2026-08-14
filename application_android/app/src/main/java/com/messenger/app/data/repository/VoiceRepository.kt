package com.messenger.app.data.repository

import android.content.Context
import android.util.Log
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * Voice note transport: encrypt, upload, download, decrypt.
 *
 * Audio is content, so it gets the same end-to-end treatment as text. The
 * server only ever holds an opaque blob it cannot decode - which is why the
 * upload endpoint deliberately doesn't validate the payload as audio.
 *
 * Direct chats use pairwise crypto_box; groups use Sender Keys (raw
 * secretbox bytes + key_version on the message), matching group text.
 */
class VoiceRepository(
    private val context: Context,
    private val chatApiService: ChatApiService,
    private val chatRepository: ChatRepository,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "VoiceRepository"
        private const val MAX_BYTES = 10 * 1024 * 1024
    }

    private fun bearer(token: String) = "Bearer $token"

    /** Where decrypted audio is cached for playback, keyed by message id. */
    private fun playbackFile(messageId: String) =
        File(context.cacheDir, "voice_play_${messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.m4a")

    data class Uploaded(val fileUrl: String, val encrypted: Boolean, val keyVersion: Int = 0, val encryptionVersion: Int = 1)

    /**
     * Encrypts (when possible) and uploads [file], returning its server path.
     * The local plaintext recording is deleted either way.
     */
    suspend fun upload(token: String, chatId: String, file: File): Result<Uploaded> =
        withContext(Dispatchers.IO) {
            try {
                val raw = file.readBytes()
                if (raw.size > MAX_BYTES) {
                    return@withContext Result.failure(
                        IOException("Voice note must be ${MAX_BYTES / (1024 * 1024)} MB or smaller")
                    )
                }

                val sealed = chatRepository.encryptBytesFor(token, chatId, raw)
                    ?: return@withContext Result.failure(IOException("Cannot encrypt voice note"))
                val payload = sealed.bytes

                val body = payload.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "voice", body)
                val response = chatApiService.uploadVoice(bearer(token), part)

                // jsonPrimitive.content, not toString(): toString() on a
                // JsonElement keeps the surrounding quotes, which ended up
                // baked into the stored file_url.
                val url = response.body()?.get("file_url")
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                if (response.isSuccessful && !url.isNullOrBlank()) {
                    Result.success(Uploaded(url, true, sealed.keyVersion, sealed.encryptionVersion))
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to send voice note")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload error", e)
                Result.failure(e)
            } finally {
                // The plaintext recording has served its purpose.
                file.delete()
            }
        }

    /**
     * Fetches a voice note and returns a playable decrypted file, caching it so
     * replaying doesn't re-download. MediaPlayer needs a real file: the remote
     * bytes are ciphertext and can't be streamed.
     */
    suspend fun fetchForPlayback(
        chatId: String,
        messageId: String,
        fileUrl: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = ""
    ): Result<File> = withContext(Dispatchers.IO) {
        val cached = playbackFile(messageId)
        if (cached.exists() && cached.length() > 0) return@withContext Result.success(cached)

        val absolute = resolveAvatarUrl(fileUrl)
            ?: return@withContext Result.failure(IOException("Voice note has no address"))

        try {
            val request = Request.Builder().url(absolute).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Could not download voice note (${response.code})")
                    )
                }
                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(IOException("Empty voice note"))

                val audio = if (encrypted) {
                    chatRepository.decryptBytesFor(chatId, bytes, senderId, keyVersion, encryptionVersion, senderDeviceId)
                        ?: return@withContext Result.failure(
                            IOException("This voice note can't be decrypted on this device")
                        )
                } else {
                    bytes
                }

                cached.writeBytes(audio)
                Result.success(cached)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchForPlayback error", e)
            Result.failure(e)
        }
    }
}
