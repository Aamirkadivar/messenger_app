package com.messenger.app.data.roundvideo

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out ExoPlayer instances for round video bubbles.
 *
 * A chat can hold dozens of video messages, and an ExoPlayer holds a hardware
 * video decoder - most devices allow only a handful at once, so one player per
 * bubble exhausts the decoders and every later bubble silently fails to
 * render. Bubbles therefore borrow from a small pool, and only the bubble
 * that is actually playing holds one.
 *
 * Telegram behaves the same way: scrolling a chat auto-plays the visible round
 * video and quietly releases the others.
 */
@Singleton
class RoundVideoPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /**
         * One playing bubble plus one being prepared as it scrolls into view.
         * Higher than this buys nothing and starts competing for decoders with
         * the rest of the app.
         */
        private const val MAX_PLAYERS = 2
    }

    private val idle = ArrayDeque<ExoPlayer>()
    private val lent = mutableSetOf<ExoPlayer>()

    /** Message id of the bubble currently allowed to play, or null for none. */
    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

    /**
     * Marks one bubble as the active one. Any other bubble observing this
     * stops itself, so two round videos never play over each other.
     */
    fun setActive(messageId: String?) {
        _activeMessageId.value = messageId
    }

    @Synchronized
    fun acquire(): ExoPlayer {
        val player = idle.removeFirstOrNull() ?: ExoPlayer.Builder(context).build()
        lent.add(player)
        return player
    }

    @Synchronized
    fun release(player: ExoPlayer) {
        lent.remove(player)
        player.stop()
        player.clearMediaItems()
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1f
        if (idle.size < MAX_PLAYERS) {
            idle.addLast(player)
        } else {
            player.release()
        }
    }

    /**
     * Points a borrowed player at a cached file and loops it.
     *
     * Round videos auto-loop like Telegram's, and they start muted in the
     * chat list - a chat that shouts at you when you scroll past is exactly
     * the behaviour everyone hates.
     */
    fun prepareLooping(player: ExoPlayer, file: File, muted: Boolean) {
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = if (muted) 0f else 1f
        player.prepare()
    }

    @Synchronized
    fun releaseAll() {
        (idle + lent).forEach { runCatching { it.release() } }
        idle.clear()
        lent.clear()
        _activeMessageId.value = null
    }
}
