package com.messenger.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.messenger.app.data.remote.api.ChatApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Uploads profile and group pictures.
 *
 * Reads the picked content through the ContentResolver rather than assuming a
 * file path: the photo picker hands back a content:// URI that usually has no
 * filesystem path we're allowed to open directly.
 */
class AvatarRepository(
    private val context: Context,
    private val chatApiService: ChatApiService
) {
    companion object {
        private const val TAG = "AvatarRepository"
        /** Matches the server's cap; fail here rather than after a slow upload. */
        private const val MAX_BYTES = 5 * 1024 * 1024
    }

    private fun bearer(token: String) = "Bearer $token"

    private fun readImage(uri: Uri): Result<Pair<ByteArray, String>> = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read the selected image")
        if (bytes.size > MAX_BYTES) {
            throw IOException("Image must be ${MAX_BYTES / (1024 * 1024)} MB or smaller")
        }
        bytes to mime
    }

    private fun part(bytes: ByteArray, mime: String): MultipartBody.Part {
        // The server regenerates the stored filename, so this one is only a
        // multipart formality - it is never used as a path.
        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("file", "avatar", body)
    }

    /** Returns the new avatar path, e.g. "/uploads/avatars/<uuid>.jpg". */
    suspend fun uploadMyAvatar(token: String, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            val (bytes, mime) = readImage(uri).getOrElse { return@withContext Result.failure(it) }
            try {
                val response = chatApiService.uploadMyAvatar(bearer(token), part(bytes, mime))
                val url = response.body()?.get("avatar_url")
                if (response.isSuccessful && !url.isNullOrBlank()) {
                    Result.success(url)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to upload picture")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadMyAvatar error", e)
                Result.failure(e)
            }
        }

    suspend fun uploadGroupAvatar(token: String, chatId: String, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            val (bytes, mime) = readImage(uri).getOrElse { return@withContext Result.failure(it) }
            try {
                val response = chatApiService.uploadGroupAvatar(
                    bearer(token), chatId, part(bytes, mime)
                )
                val url = response.body()?.get("avatar_url")
                if (response.isSuccessful && !url.isNullOrBlank()) {
                    Result.success(url)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to upload picture")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadGroupAvatar error", e)
                Result.failure(e)
            }
        }
}
