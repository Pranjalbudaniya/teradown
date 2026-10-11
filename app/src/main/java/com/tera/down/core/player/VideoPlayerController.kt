package com.tera.down.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

interface PlayerEventListener {
    fun onPlaybackStateChanged(status: PlayerStatus)
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onPositionDiscontinuity(positionMs: Long)
    fun onPlayerError(errorMessage: String, canRetry: Boolean)
    fun onDurationChanged(durationMs: Long)
}

interface VideoPlayerController {
    val currentPositionMs: Long
    val durationMs: Long
    val bufferedPositionMs: Long
    val isPlaying: Boolean
    val playbackSpeed: Float
    val exoPlayer: Player?

    fun prepare(uri: String, initialPositionMs: Long = 0L)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
    fun addListener(listener: PlayerEventListener)
    fun removeListener(listener: PlayerEventListener)
}

/**
 * Production implementation of [VideoPlayerController] using AndroidX Media3 ExoPlayer.
 * Enforces Android audio focus management, proper resource release, and safe error mapping.
 */
class ExoPlayerController(
    private val context: Context
) : VideoPlayerController {

    private var player: ExoPlayer? = null
    private val listeners = mutableListOf<PlayerEventListener>()
    private var currentSpeed: Float = 1.0f

    override val exoPlayer: Player?
        get() {
            if (player == null) {
                initPlayer()
            }
            return player
        }

    override val currentPositionMs: Long
        get() = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    override val durationMs: Long
        get() {
            val d = player?.duration ?: C.TIME_UNSET
            return if (d == C.TIME_UNSET || d < 0L) 0L else d
        }

    override val bufferedPositionMs: Long
        get() = player?.bufferedPosition?.coerceAtLeast(0L) ?: 0L

    override val isPlaying: Boolean
        get() = player?.isPlaying ?: false

    override val playbackSpeed: Float
        get() = currentSpeed

    private fun initPlayer(): ExoPlayer {
        val newPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
            // Respect Android audio focus
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)

            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val status = when (playbackState) {
                        Player.STATE_IDLE -> PlayerStatus.IDLE
                        Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
                        Player.STATE_READY -> PlayerStatus.READY
                        Player.STATE_ENDED -> PlayerStatus.ENDED
                        else -> PlayerStatus.IDLE
                    }
                    notifyStateChanged(status)
                    val dur = duration
                    if (dur != C.TIME_UNSET && dur > 0L) {
                        notifyDurationChanged(dur)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    notifyIsPlayingChanged(isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    val (message, canRetry) = mapPlaybackError(error)
                    notifyPlayerError(message, canRetry)
                }
            })
        }
        player = newPlayer
        return newPlayer
    }

    override fun prepare(uri: String, initialPositionMs: Long) {
        val p = player ?: initPlayer()
        val mediaItem = MediaItem.fromUri(Uri.parse(uri))
        p.setMediaItem(mediaItem)
        if (initialPositionMs > 0L) {
            p.seekTo(initialPositionMs)
        }
        p.prepare()
        p.playWhenReady = true
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.25f, 3.0f)
        player?.playbackParameters = PlaybackParameters(currentSpeed)
    }

    override fun release() {
        player?.let {
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        player = null
    }

    override fun addListener(listener: PlayerEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeListener(listener: PlayerEventListener) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged(status: PlayerStatus) {
        listeners.forEach { it.onPlaybackStateChanged(status) }
    }

    private fun notifyIsPlayingChanged(isPlaying: Boolean) {
        listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    private fun notifyDurationChanged(durationMs: Long) {
        listeners.forEach { it.onDurationChanged(durationMs) }
    }

    private fun notifyPlayerError(message: String, canRetry: Boolean) {
        listeners.forEach { it.onPlayerError(message, canRetry) }
    }

    private fun mapPlaybackError(error: PlaybackException): Pair<String, Boolean> {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Network connection lost during playback. Please check your internet connection." to true
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "The media stream URL returned an HTTP error or has expired." to false
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The video file was not found on local storage." to false
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "The video format or codec is not supported by your device's hardware decoder." to false
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "The media container is corrupted or invalid." to false
            PlaybackException.ERROR_CODE_TIMEOUT ->
                "The media server timed out while loading the stream." to true
            else ->
                (error.localizedMessage ?: "An unexpected playback error occurred.") to true
        }
    }
}
