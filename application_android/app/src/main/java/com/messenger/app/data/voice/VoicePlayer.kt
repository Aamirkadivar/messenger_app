package com.messenger.app.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Which voice note is playing, and how far through it is. */
data class VoicePlaybackState(
    val messageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

/**
 * Plays voice notes, one at a time.
 *
 * Application-scoped singleton rather than per-screen: starting a second note
 * must stop the first, which only works if a single object owns the player.
 *
 * Playback is from a decrypted temp file, not a URL - the bytes on the server
 * are ciphertext, so there is nothing streamable to hand MediaPlayer.
 */
@Singleton
class VoicePlayer @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "VoicePlayer"
    }

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    /** Starts [file]; if [messageId] is already playing, toggles pause instead. */
    fun toggle(messageId: String, file: File) {
        val current = _state.value
        if (current.messageId == messageId && player != null) {
            if (current.isPlaying) pause() else resume()
            return
        }
        play(messageId, file)
    }

    private fun play(messageId: String, file: File) {
        stop()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _state.value = VoicePlaybackState(
                        messageId = messageId,
                        isPlaying = false,
                        positionMs = 0,
                        durationMs = duration
                    )
                }
                start()
            }
            player = mp
            currentFile = file
            _state.value = VoicePlaybackState(messageId, true, 0, mp.duration)
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
            stop()
        }
    }

    fun pause() {
        try {
            player?.pause()
            _state.value = _state.value.copy(
                isPlaying = false,
                positionMs = player?.currentPosition ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "pause failed", e)
        }
    }

    private fun resume() {
        try {
            player?.start()
            _state.value = _state.value.copy(isPlaying = true)
        } catch (e: Exception) {
            Log.e(TAG, "resume failed", e)
        }
    }

    /** Called on a timer while playing, to advance the progress bar. */
    fun syncPosition() {
        val mp = player ?: return
        if (_state.value.isPlaying) {
            _state.value = _state.value.copy(positionMs = mp.currentPosition)
        }
    }

    fun stop() {
        try {
            player?.release()
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
        player = null
        currentFile = null
        _state.value = VoicePlaybackState()
    }
}
