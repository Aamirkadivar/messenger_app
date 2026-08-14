package com.messenger.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Generic file/image attachment transport: encrypt, upload, download, decrypt.
 *
 * Mirrors [VoiceRepository]'s pattern exactly - an attachment is content, so it
 * gets the same end-to-end treatment as a voice note or text message. Direct
 * chats use pairwise crypto_box; groups use Sender Keys (raw secretbox +
 * key_version), matching group text.
 */
class AttachmentRepository(
    private val context: Context,
    private val chatApiService: ChatApiService,
    private val chatRepository: ChatRepository,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "AttachmentRepository"
        private const val MAX_BYTES = 25 * 1024 * 1024

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    }

    private fun bearer(token: String) = "Bearer $token"

    /** Where a decrypted attachment is cached for viewing, keyed by message id. */
    private fun cacheFile(messageId: String, fileName: String): File {
        val ext = fileName.substringAfterLast('.', "").take(10)
        val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val suffix = if (ext.isNotBlank()) ".$ext" else ""
        return File(context.cacheDir, "attachment_$safeId$suffix")
    }

    /** "image" or "file", based on the picked file's extension - matches the Windows client's classifier. */
    fun classify(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext in IMAGE_EXTENSIONS) ChatRepository.IMAGE_CONTENT_TYPE else ChatRepository.FILE_CONTENT_TYPE
    }

    data class PickedFile(val name: String, val size: Long, val bytes: ByteArray)

    /** Reads a content:// Uri's bytes and display name/size via the ContentResolver. */
    fun readPickedFile(uri: Uri): Result<PickedFile> {
        return try {
            var name = "file"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IOException("Could not open file"))
            if (bytes.size > MAX_BYTES) {
                return Result.failure(IOException("File must be ${MAX_BYTES / (1024 * 1024)} MB or smaller"))
            }
            Result.success(PickedFile(name, if (size > 0) size else bytes.size.toLong(), bytes))
        } catch (e: Exception) {
            Log.e(TAG, "readPickedFile error", e)
            Result.failure(e)
        }
    }

    data class Uploaded(val fileUrl: String, val fileSize: Long, val encrypted: Boolean, val keyVersion: Int = 0, val encryptionVersion: Int = 1)

    /** Encrypts (when possible) and uploads [file]'s bytes, returning its server path. */
    suspend fun upload(token: String, chatId: String, file: PickedFile): Result<Uploaded> =
        withContext(Dispatchers.IO) {
            try {
                val sealed = chatRepository.encryptBytesFor(
                    token, chatId, file.bytes, fileName = file.name, fileSize = file.size
                )
                    ?: return@withContext Result.failure(IOException("Cannot encrypt attachment"))
                val payload = sealed.bytes

                val body = payload.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "attachment", body)
                val response = chatApiService.uploadAttachment(bearer(token), part)

                val url = response.body()?.get("file_url")
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                val size = response.body()?.get("file_size")
                    ?.let { runCatching { it.jsonPrimitive.content.toLong() }.getOrNull() }
                    ?: payload.size.toLong()
                if (response.isSuccessful && !url.isNullOrBlank()) {
                    Result.success(Uploaded(url, size, true, sealed.keyVersion, sealed.encryptionVersion))
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to send attachment")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload error", e)
                Result.failure(e)
            }
        }

    /**
     * Fetches an attachment and returns a decrypted local file ready to view
     * or open, caching it so re-opening doesn't re-download.
     */
    suspend fun fetchForView(
        chatId: String,
        messageId: String,
        fileUrl: String,
        fileName: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = ""
    ): Result<File> = withContext(Dispatchers.IO) {
        var dest = cacheFile(messageId, fileName)
        if (dest.exists() && dest.length() > 0) return@withContext Result.success(dest)

        val absolute = resolveAvatarUrl(fileUrl)
            ?: return@withContext Result.failure(IOException("Attachment has no address"))

        try {
            val request = Request.Builder().url(absolute).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Could not download attachment (${response.code})")
                    )
                }
                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(IOException("Empty attachment"))

                val env = if (encrypted) {
                    chatRepository.openBytesFor(chatId, bytes, senderId, keyVersion, encryptionVersion, senderDeviceId)
                        ?: return@withContext Result.failure(
                            IOException("This attachment can't be decrypted on this device")
                        )
                } else {
                    com.messenger.app.data.encryption.E2ECrypto.unwrapEnvelope(bytes)
                }
                val name = fileName.ifBlank { env.fileName }
                dest = cacheFile(messageId, name)
                if (dest.exists() && dest.length() > 0) return@withContext Result.success(dest)
                dest.writeBytes(env.payload)
                Result.success(dest)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchForView error", e)
            Result.failure(e)
        }
    }
}

/** MIME type guess for [android.content.Intent.setDataAndType], from a filename's extension. */
fun guessMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
