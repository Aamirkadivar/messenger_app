package com.messenger.app.data.roundvideo

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Which way the camera is pointing. Front is the default for a video message. */
enum class RoundLens { FRONT, BACK }

data class RoundRecorderState(
    val isPreviewing: Boolean = false,
    val isRecording: Boolean = false,
    val lens: RoundLens = RoundLens.FRONT,
    val elapsedMs: Long = 0,
    /** Set when the camera or encoder failed, for the UI to surface. */
    val error: String? = null
)

/**
 * Captures a Telegram-style round video message.
 *
 * Capture is deliberately *not* square: no camera reliably produces a square
 * stream, so this records the sensor's own SD frame and the square crop is
 * applied later ([VideoCompressor]). The circle the user sees while recording
 * is a UI mask over a normal preview - the same trick Telegram uses, and the
 * reason the preview can stay perfectly circular without the encoder having to
 * cooperate.
 *
 * Owns no UI. The overlay drives it through [bindPreview]/[start]/[stop] and
 * observes [state]; the recorder knows nothing about gestures, locking, or
 * animation, which keeps the capture pipeline testable on its own.
 */
@Singleton
class RoundVideoRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RoundVideoRecorder"

        /** Shorter than this and it was a mis-tap, not a message. */
        const val MIN_DURATION_MS = 900L

        /** Telegram caps round videos at 60s; longer belongs in a file. */
        const val MAX_DURATION_MS = 60_000L
    }

    private val _state = MutableStateFlow(RoundRecorderState())
    val state: StateFlow<RoundRecorderState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var recording: Recording? = null

    private var outputFile: File? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val mainExecutor by lazy { ContextCompat.getMainExecutor(context) }

    /**
     * Opens the camera and starts the preview. Safe to call again to rebind
     * after a lens switch.
     */
    suspend fun bindPreview(owner: LifecycleOwner, provider: Preview.SurfaceProvider) {
        lifecycleOwner = owner
        surfaceProvider = provider
        val cam = cameraProvider ?: awaitCameraProvider().also { cameraProvider = it }
        bindUseCases(cam, owner, provider, _state.value.lens)
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { e ->
                        Log.e(TAG, "camera provider unavailable", e)
                        _state.update { it.copy(error = "Camera unavailable") }
                    }
            }, mainExecutor)
        }

    @SuppressLint("RestrictedApi")
    private fun bindUseCases(
        cam: ProcessCameraProvider,
        owner: LifecycleOwner,
        surface: Preview.SurfaceProvider,
        lens: RoundLens
    ) {
        try {
            // SD is the smallest quality every device is required to offer, and
            // it is already larger than the circle is ever drawn at. Asking for
            // HD here would only mean encoding pixels that the square crop and
            // the 384px output then throw away.
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            val capture = VideoCapture.withOutput(recorder)
            val prev = Preview.Builder().build().apply { setSurfaceProvider(surface) }

            val selector = if (lens == RoundLens.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cam.unbindAll()
            cam.bindToLifecycle(owner, selector, prev, capture)

            preview = prev
            videoCapture = capture
            _state.update { it.copy(isPreviewing = true, lens = lens, error = null) }
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            _state.update { it.copy(isPreviewing = false, error = "Could not open camera") }
        }
    }

    /**
     * Flips between front and back.
     *
     * Rebinding tears the capture session down, so an in-flight recording
     * cannot survive it - the caller is expected to only offer this before
     * recording starts, or to accept that the take restarts. Kept here rather
     * than in the UI so the lens is tracked in one place.
     */
    fun switchCamera() {
        val cam = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val surface = surfaceProvider ?: return
        val next = if (_state.value.lens == RoundLens.FRONT) RoundLens.BACK else RoundLens.FRONT
        bindUseCases(cam, owner, surface, next)
    }

    /** Begins recording to a fresh file in cacheDir. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val capture = videoCapture ?: return false
        if (recording != null) return false

        return try {
            // cacheDir, like voice notes: the plaintext capture should live on
            // disk for as little time as possible and is deleted once encrypted.
            val file = File.createTempFile("round_", ".mp4", context.cacheDir)
            outputFile = file

            val options = FileOutputOptions.Builder(file).build()
            recording = capture.output
                .prepareRecording(context, options)
                .withAudioEnabled()
                .start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start ->
                            _state.update { it.copy(isRecording = true, elapsedMs = 0, error = null) }

                        is VideoRecordEvent.Status -> {
                            val ms = event.recordingStats.recordedDurationNanos / 1_000_000
                            _state.update { it.copy(elapsedMs = ms) }
                            if (ms >= MAX_DURATION_MS) stop()
                        }

                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "recording error ${event.error}", event.cause)
                                _state.update { it.copy(isRecording = false, error = "Recording failed") }
                            } else {
                                _state.update { it.copy(isRecording = false) }
                            }
                        }
                    }
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            _state.update { it.copy(error = "Could not start recording") }
            false
        }
    }

    /**
     * Stops recording and returns the captured file, or null if the take was
     * too short to be a real message.
     *
     * Finalize arrives asynchronously, so the file is not guaranteed complete
     * the instant this returns - [RoundVideoRepository] awaits finalisation
     * before reading it.
     */
    fun stop(): File? {
        val rec = recording ?: return null
        val elapsed = _state.value.elapsedMs
        recording = null
        runCatching { rec.stop() }
        _state.update { it.copy(isRecording = false) }

        val file = outputFile
        if (elapsed < MIN_DURATION_MS) {
            file?.delete()
            outputFile = null
            return null
        }
        return file
    }

    /** Aborts the take and deletes the partial file (slide-left-to-cancel). */
    fun cancel() {
        recording?.let { runCatching { it.stop() } }
        recording = null
        outputFile?.delete()
        outputFile = null
        _state.update { it.copy(isRecording = false, elapsedMs = 0) }
    }

    /** Releases the camera. Called when the overlay closes. */
    fun release() {
        cancel()
        runCatching { cameraProvider?.unbindAll() }
        preview = null
        videoCapture = null
        lifecycleOwner = null
        surfaceProvider = null
        _state.update { it.copy(isPreviewing = false, elapsedMs = 0) }
    }
}
