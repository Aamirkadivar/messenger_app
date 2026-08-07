package com.messenger.app.data.roundvideo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Turns a raw camera capture into the small square clip that actually gets
 * sent, and pulls a poster frame out of it.
 *
 * media3-transformer would normally do this in a few lines, but it is not
 * available offline on this machine (see the note in app/build.gradle.kts), so
 * the pipeline is built directly on MediaCodec: extract -> decode to a GPU
 * texture -> centre-square crop, rotate and scale on the GPU -> encode -> mux.
 * The result is upright with no orientation metadata, so no consumer has to
 * honour a hint. Audio is remuxed untouched rather than re-encoded, which is
 * both faster and lossless.
 */
@Singleton
class VideoCompressor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Default, not IO: transcoding is CPU/GPU-bound, and putting it on the
     * unbounded IO pool would let several run at once and thrash the encoder.
     * Not a constructor parameter because Hilt has no binding for a bare
     * CoroutineDispatcher - a default value does not satisfy Dagger.
     */
    private val io: CoroutineDispatcher = Dispatchers.Default

    companion object {
        private const val TAG = "VideoCompressor"

        /** Telegram's round videos sit around here; big enough for a 200dp circle. */
        const val OUTPUT_SIZE = 384

        /** ~1Mbps keeps a 60s note near 7MB, well inside the server's cap. */
        const val VIDEO_BITRATE = 1_000_000
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL = 1

        private const val TIMEOUT_US = 10_000L
        private const val MIME_VIDEO = "video/avc"
    }

    /**
     * Compresses [source] to a centre-cropped square clip.
     *
     * The front camera is deliberately NOT mirrored: CameraX records what the
     * lens actually sees (only the preview is mirrored), and flipping it here
     * would reverse any text in shot and send the recipient a reversed view.
     * This matches what Telegram sends.
     *
     * [onProgress] reports 0..1 based on presentation time, so the UI can show
     * real progress rather than a spinner. Cancelling the calling coroutine
     * aborts promptly and cleans up.
     */
    suspend fun compressToSquare(
        source: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(io) {
        val output = File(context.cacheDir, "round_c_${System.currentTimeMillis()}.mp4")
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglCore? = null
        var renderer: TextureRenderer? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var encoderSurface: Surface? = null

        try {
            extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
            val videoTrack = extractor.firstTrack("video/")
            require(videoTrack >= 0) { "no video track" }
            val inFormat = extractor.getTrackFormat(videoTrack)

            val srcW = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = inFormat.optInt(MediaFormat.KEY_ROTATION, 0)
            val durationUs = inFormat.optLong(MediaFormat.KEY_DURATION, 0L)

            // ---- encoder (must be configured before its input surface exists)
            val outFormat = MediaFormat.createVideoFormat(MIME_VIDEO, OUTPUT_SIZE, OUTPUT_SIZE).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            encoder = MediaCodec.createEncoderByType(MIME_VIDEO)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = encoder.createInputSurface()
            encoder.start()

            // ---- GPU bridge between decoder output and encoder input
            egl = EglCore(encoderSurface)
            egl.makeCurrent()
            renderer = TextureRenderer().apply {
                setUp()
                configure(srcW, srcH, rotation)
            }
            val frameAvailable = Object()
            var hasFrame = false
            surfaceTexture = SurfaceTexture(renderer.textureId).apply {
                setOnFrameAvailableListener {
                    synchronized(frameAvailable) {
                        hasFrame = true
                        frameAvailable.notifyAll()
                    }
                }
            }
            decoderSurface = Surface(surfaceTexture)

            // ---- decoder
            extractor.selectTrack(videoTrack)
            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFormat, decoderSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Deliberately NO setOrientationHint: the rotation is baked into
            // the pixels above. Relying on the hint meant every consumer had
            // to honour it, and ours do not - ExoPlayer only applies rotation
            // through PlayerView, not the bare TextureView the round bubble
            // uses, and MediaMetadataRetriever.getFrameAtTime ignores it too,
            // so both the video and its poster frame came out sideways.

            var muxerVideoTrack = -1
            var muxerStarted = false
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!encodeDone) {
                coroutineContext.ensureActive()

                // feed the decoder
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // decoder -> texture -> encoder surface
                if (!decodeDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val render = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (render) {
                            awaitFrame(frameAvailable) { hasFrame }.also { hasFrame = false }
                            surfaceTexture.updateTexImage()
                            renderer.drawFrame(surfaceTexture)
                            egl.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            egl.swapBuffers()
                            if (durationUs > 0) {
                                onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decodeDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // drain the encoder
                val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "encoder format changed twice" }
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        // Audio is copied straight across - re-encoding voice
                        // that is already AAC would only lose quality.
                        val audioTrack = copyAudioTrackTo(muxer, source)
                        muxer.start()
                        muxerStarted = true
                        if (audioTrack != null) writeAudio(muxer, source, audioTrack)
                    }

                    encIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(encIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encodeDone = true
                        }
                    }
                }
            }

            onProgress(1f)
            output
        } catch (e: Exception) {
            Log.e(TAG, "compress failed, sending the original", e)
            output.delete()
            // A failed transcode must not cost the user their recording - the
            // raw capture is still a perfectly valid (just larger) message.
            source
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            renderer?.release()
            egl?.release()
            decoderSurface?.release()
            encoderSurface?.release()
            surfaceTexture?.release()
        }
    }

    /**
     * Writes a JPEG poster frame so the recipient sees a filled circle while
     * the video itself is still downloading.
     */
    suspend fun extractThumbnail(video: File): File? = withContext(io) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null
            val square = frame.centreSquare()
            val out = File(context.cacheDir, "round_t_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { square.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (square !== frame) square.recycle()
            frame.recycle()
            out
        } catch (e: Exception) {
            Log.e(TAG, "thumbnail failed", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Length of a captured clip, used as the message's duration_ms. */
    fun durationMs(video: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- helpers

    private fun awaitFrame(lock: Object, hasFrame: () -> Boolean) {
        synchronized(lock) {
            var waited = 0L
            while (!hasFrame() && waited < 2_000) {
                lock.wait(50)
                waited += 50
            }
        }
    }

    private fun MediaExtractor.firstTrack(prefix: String): Int {
        for (i in 0 until trackCount) {
            val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun MediaFormat.optInt(key: String, fallback: Int) =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.optLong(key: String, fallback: Long) =
        if (containsKey(key)) getLong(key) else fallback

    private fun copyAudioTrackTo(muxer: MediaMuxer, source: File): Int? {
        val ex = MediaExtractor().apply { setDataSource(source.absolutePath) }
        return try {
            val idx = ex.firstTrack("audio/")
            if (idx < 0) null else muxer.addTrack(ex.getTrackFormat(idx))
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    private fun writeAudio(muxer: MediaMuxer, source: File, muxerTrack: Int) {
        val ex = MediaExtractor().apply { setDataSource(source.absolutePath) }
        try {
            val idx = ex.firstTrack("audio/")
            if (idx < 0) return
            ex.selectTrack(idx)
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
                ex.advance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio remux failed", e)
        } finally {
            ex.release()
        }
    }

    private fun Bitmap.centreSquare(): Bitmap {
        if (width == height) return this
        val side = minOf(width, height)
        return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
    }
}
