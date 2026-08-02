package com.messenger.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the system ringtone (looping) plus a repeating vibration for an
 * incoming call.
 *
 * A notification's own channel sound plays exactly once and stops, which is
 * fine for a message but not for a call - a call has to keep ringing until it
 * is answered or gives up. So the ring is driven here instead and the calls
 * channel is left silent, otherwise the first second would be doubled.
 *
 * The device's ringer mode is honoured the way the dialer honours it: silent
 * means neither sound nor vibration, vibrate means vibration only.
 */
class Ringer(private val context: Context) {

    companion object {
        private const val TAG = "Ringer"
        /** ~1s buzz, ~1s pause - the usual incoming-call cadence. */
        private val PATTERN = longArrayOf(0, 1000, 1000)
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    fun start() {
        if (player != null || vibrator != null) return // already ringing

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return

        startVibration()
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) startRingtone()
    }

    fun stop() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }.onFailure { Log.w(TAG, "stopping ringtone failed", it) }
        player = null

        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startRingtone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                // NOTIFICATION_RINGTONE routes to the ring stream, so the ring
                // follows the user's ringer volume rather than media volume.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "ringtone playback failed", it)
            player = null
        }
    }

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        runCatching {
            // repeat = 0 restarts the pattern from index 0, i.e. buzzes until cancelled.
            vib.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
            vibrator = vib
        }.onFailure { Log.w(TAG, "vibration failed", it) }
    }
}
