package com.messenger.app.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import javax.inject.Inject

/**
 * Records a voice note to a temporary AAC/M4A file.
 *
 * The file lives in cacheDir and is deleted as soon as its bytes have been
 * encrypted and uploaded - the plaintext audio should exist on disk for as
 * little time as possible, and cacheDir is also what "Clear cache" reclaims.
 */
class VoiceRecorder @Inject constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        /** Below this, a recording is almost certainly an accidental tap. */
        const val MIN_DURATION_MS = 800L
        const val MAX_DURATION_MS = 5 * 60 * 1000L
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts recording. Returns false if the microphone could not be opened. */
    fun start(): Boolean {
        if (recorder != null) return false
        return try {
            val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
            @Suppress("DEPRECATION") // the Context ctor is API 31+
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Voice, not music: 32kbps mono keeps notes small enough that
                // encrypting and uploading them stays quick.
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setMaxDuration(MAX_DURATION_MS.toInt())
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            cleanup()
            false
        }
    }

    /** Current recording length, for the live timer. */
    fun elapsedMs(): Long =
        if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    /**
     * Peak amplitude 0..1, for the level meter. MediaRecorder reports this
     * relative to a 32767 maximum.
     */
    fun amplitude(): Float = try {
        (recorder?.maxAmplitude ?: 0) / 32767f
    } catch (e: Exception) {
        0f
    }

    data class Recording(val file: File, val durationMs: Long)

    /**
     * Stops and returns the finished recording, or null if it was too short or
     * the encoder produced nothing usable.
     */
    fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = outputFile
        val duration = elapsedMs()
        return try {
            // stop() throws if the recording was too brief for the encoder to
            // produce a valid MPEG-4 container; treat that as "discard".
            rec.stop()
            rec.release()
            recorder = null
            startedAt = 0L
            if (file == null || !file.exists() || file.length() == 0L ||
                duration < MIN_DURATION_MS
            ) {
                file?.delete()
                outputFile = null
                null
            } else {
                outputFile = null
                Recording(file, duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            cleanup()
            null
        }
    }

    /** Aborts and deletes the partial file. */
    fun cancel() = cleanup()

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
        startedAt = 0L
    }
}
