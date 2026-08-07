package com.messenger.app.data.repository

import android.content.Context
import android.util.Log
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.roundvideo.VideoCompressor
import com.messenger.app.ui.components.resolveAvatarUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round video transport: compress, encrypt, upload, download, decrypt, cache.
 *
 * Mirrors [VoiceRepository]'s end-to-end treatment - the server only ever
 * holds a blob it cannot decode - with the extra steps a video needs: a
 * transcode pass before upload, a separate poster frame, byte-level progress
 * for both, and a retry, because a multi-megabyte upload on a phone fails far
 * more often than a 40KB voice note does.
 */
@Singleton
class RoundVideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatApiService: ChatApiService,
    private val chatRepository: ChatRepository,
    private val okHttpClient: OkHttpClient,
    private val compressor: VideoCompressor
) {
    companion object {
        private const val TAG = "RoundVideoRepository"
        private const val MAX_BYTES = 25 * 1024 * 1024
        private const val UPLOAD_ATTEMPTS = 3
    }

    private fun bearer(token: String) = "Bearer $token"

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Decrypted video cached for replay, keyed by message id. */
    fun playbackFile(messageId: String) = File(context.cacheDir, "round_play_${safe(messageId)}.mp4")

    /** Decrypted poster frame cached alongside it. */
    fun thumbFile(messageId: String) = File(context.cacheDir, "round_thumb_${safe(messageId)}.jpg")

    /** Where a send has got to, so the composer can show real progress. */
    sealed interface SendStage {
        data class Compressing(val fraction: Float) : SendStage
        data class Uploading(val fraction: Float) : SendStage
        data object Finishing : SendStage
    }

    data class Prepared(
        val fileUrl: String,
        val thumbnailUrl: String,
        val durationMs: Long,
        val encrypted: Boolean,
        val keyVersion: Int = 0
    )

    /**
     * Compresses [capture], uploads it and its poster frame, and returns what
     * the message needs. The local plaintext files are deleted either way.
     */
    suspend fun prepareAndUpload(
        token: String,
        chatId: String,
        capture: File,
        onStage: (SendStage) -> Unit = {}
    ): Result<Prepared> = withContext(Dispatchers.IO) {
        var compressed: File? = null
        var thumb: File? = null
        try {
            compressed = compressor.compressToSquare(capture) { f ->
                onStage(SendStage.Compressing(f))
            }
            val durationMs = compressor.durationMs(compressed)
            thumb = compressor.extractThumbnail(compressed)

            val raw = compressed.readBytes()
            if (raw.size > MAX_BYTES) {
                return@withContext Result.failure(
                    IOException("Video message must be ${MAX_BYTES / (1024 * 1024)} MB or smaller")
                )
            }

            val sealed = chatRepository.encryptBytesFor(token, chatId, raw)
            if (sealed == null) Log.w(TAG, "No key for chat $chatId - uploading video note unencrypted")
            val payload = sealed?.bytes ?: raw

            val videoUrl = uploadWithRetry(
                name = "video",
                payload = payload,
                onProgress = { f -> onStage(SendStage.Uploading(f)) }
            ) { part ->
                chatApiService.uploadVideoNote(bearer(token), part)
            }.getOrElse { return@withContext Result.failure(it) }

            onStage(SendStage.Finishing)

            // The poster frame gets the same encryption as the video: it is a
            // frame *of* the video, so leaving it in the clear would leak the
            // content the encryption exists to protect.
            var thumbUrl = ""
            thumb?.let { t ->
                val thumbBytes = t.readBytes()
                val sealedThumb = chatRepository.encryptBytesFor(token, chatId, thumbBytes)?.bytes
                    ?: thumbBytes
                thumbUrl = uploadWithRetry("thumb", sealedThumb, key = "thumbnail_url") { part ->
                    chatApiService.uploadVideoThumb(bearer(token), part)
                }.getOrDefault("")
            }

            Result.success(Prepared(videoUrl, thumbUrl, durationMs, sealed != null, sealed?.keyVersion ?: 0))
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndUpload failed", e)
            Result.failure(e)
        } finally {
            capture.delete()
            // compressToSquare returns the source itself when a transcode
            // fails, so only delete the compressed copy if it is a new file.
            if (compressed != null && compressed.absolutePath != capture.absolutePath) compressed.delete()
            thumb?.delete()
        }
    }

    /**
     * Uploads with a bounded retry.
     *
     * A phone loses its connection mid-upload often enough that failing the
     * whole recording on the first hiccup would be the single most annoying
     * thing about this feature.
     */
    private suspend fun uploadWithRetry(
        name: String,
        payload: ByteArray,
        key: String = "file_url",
        onProgress: (Float) -> Unit = {},
        call: suspend (MultipartBody.Part) -> retrofit2.Response<Map<String, kotlinx.serialization.json.JsonElement>>
    ): Result<String> {
        var last: Exception? = null
        repeat(UPLOAD_ATTEMPTS) { attempt ->
            try {
                val body = ProgressRequestBody(
                    payload,
                    "application/octet-stream".toMediaTypeOrNull(),
                    onProgress
                )
                val part = MultipartBody.Part.createFormData("file", name, body)
                val response = call(part)
                // jsonPrimitive.content, not toString(): toString() keeps the
                // surrounding quotes and they end up baked into the stored URL.
                val url = response.body()?.get(key)
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                if (response.isSuccessful && !url.isNullOrBlank()) return Result.success(url)
                last = IOException("Upload failed (${response.code()})")
            } catch (e: Exception) {
                last = e
            }
            if (attempt < UPLOAD_ATTEMPTS - 1) delay(1000L * (attempt + 1))
        }
        return Result.failure(last ?: IOException("Upload failed"))
    }

    /**
     * Downloads, decrypts and caches a round video, reporting byte progress.
     *
     * Deliberately not streamed. An E2EE payload is a single sealed box: the
     * bytes are meaningless until all of them have arrived and been decrypted,
     * so there is nothing a player could usefully start on early. Progressive
     * *rendering* is achieved instead by showing the poster frame immediately
     * (see [fetchThumbnail]) while this runs. Once cached, replay is instant.
     */
    suspend fun fetchForPlayback(
        chatId: String,
        messageId: String,
        fileUrl: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val cached = playbackFile(messageId)
        if (cached.exists() && cached.length() > 0) {
            onProgress(1f)
            return@withContext Result.success(cached)
        }
        download(chatId, fileUrl, encrypted, senderId, keyVersion, onProgress)
            .mapCatching { bytes -> cached.also { it.writeBytes(bytes) } }
    }

    /** Same, for the much smaller poster frame - fetched first so the bubble fills in fast. */
    suspend fun fetchThumbnail(
        chatId: String,
        messageId: String,
        thumbnailUrl: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0
    ): Result<File> = withContext(Dispatchers.IO) {
        val cached = thumbFile(messageId)
        if (cached.exists() && cached.length() > 0) return@withContext Result.success(cached)
        download(chatId, thumbnailUrl, encrypted, senderId, keyVersion) {}
            .mapCatching { bytes -> cached.also { it.writeBytes(bytes) } }
    }

    private suspend fun download(
        chatId: String,
        url: String,
        encrypted: Boolean,
        senderId: String,
        keyVersion: Int,
        onProgress: (Float) -> Unit
    ): Result<ByteArray> {
        val absolute = resolveAvatarUrl(url)
            ?: return Result.failure(IOException("Video message has no address"))
        return try {
            val request = Request.Builder().url(absolute).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("Could not download video (${response.code})"))
                }
                val body = response.body ?: return Result.failure(IOException("Empty video"))
                val total = body.contentLength()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var soFar = 0L
                body.byteStream().use { input ->
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        soFar += read
                        if (total > 0) onProgress((soFar.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                val bytes = out.toByteArray()
                val plain = if (encrypted) {
                    chatRepository.decryptBytesFor(chatId, bytes, senderId, keyVersion)
                        ?: return Result.failure(
                            IOException("This video message can't be decrypted on this device")
                        )
                } else {
                    bytes
                }
                onProgress(1f)
                Result.success(plain)
            }
        } catch (e: Exception) {
            Log.e(TAG, "download error", e)
            Result.failure(e)
        }
    }
}

/**
 * A RequestBody that reports how much has been written.
 *
 * OkHttp has no built-in upload progress, and a round video is large enough
 * that a send with no visible progress reads as a hang.
 */
internal class ProgressRequestBody(
    private val payload: ByteArray,
    private val contentType: MediaType?,
    private val onProgress: (Float) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = contentType
    override fun contentLength(): Long = payload.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val chunk = 64 * 1024
        var written = 0
        while (written < payload.size) {
            val size = minOf(chunk, payload.size - written)
            sink.write(payload, written, size)
            written += size
            onProgress(written.toFloat() / payload.size)
        }
    }
}
