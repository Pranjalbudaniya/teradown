package com.tera.down.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tera.down.TeradownApplication
import com.tera.down.core.player.ExoPlayerController
import com.tera.down.core.player.MediaSourceType
import com.tera.down.core.player.PlayableMedia
import com.tera.down.core.player.PlayerEventListener
import com.tera.down.core.player.PlayerMediaValidator
import com.tera.down.core.player.PlayerStatus
import com.tera.down.core.player.VideoPlayerController
import com.tera.down.feature.queue.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(
    val playerController: VideoPlayerController,
    private val positionTracker: PlaybackPositionTracker,
    val libraryRepository: LibraryRepository? = null,
    private val coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val activeScope: CoroutineScope
        get() = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var activeMedia: PlayableMedia? = null
    private var progressTrackerJob: Job? = null
    private var controlsTimeoutJob: Job? = null

    private val playerListener = object : PlayerEventListener {
        override fun onPlaybackStateChanged(status: PlayerStatus) {
            _uiState.update { current ->
                val isEnded = status == PlayerStatus.ENDED
                current.copy(
                    status = status,
                    isCompleted = isEnded,
                    durationMs = playerController.durationMs.takeIf { it > 0 } ?: current.durationMs
                )
            }
            if (status == PlayerStatus.READY) {
                startProgressTracker()
                resetControlsTimer()
            } else if (status == PlayerStatus.ENDED) {
                // Video finished: reset position to 0
                activeMedia?.libraryId?.let { libId ->
                    activeScope.launch {
                        positionTracker.resetPosition(libId)
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressTracker()
                resetControlsTimer()
            } else {
                progressTrackerJob?.cancel()
                flushPosition()
            }
        }

        override fun onPositionDiscontinuity(positionMs: Long) {
            _uiState.update { it.copy(currentPositionMs = positionMs) }
        }

        override fun onPlayerError(errorMessage: String, canRetry: Boolean) {
            progressTrackerJob?.cancel()
            _uiState.update {
                it.copy(
                    status = PlayerStatus.ERROR,
                    isPlaying = false,
                    errorMessage = errorMessage,
                    canRetry = canRetry
                )
            }
        }

        override fun onDurationChanged(durationMs: Long) {
            _uiState.update { it.copy(durationMs = durationMs) }
        }
    }

    init {
        playerController.addListener(playerListener)
    }

    /**
     * Initializes and prepares playback for a local file or authorized direct media URL.
     */
    fun loadMedia(
        rawUri: String,
        title: String,
        isOnline: Boolean,
        mediaId: String? = null
    ) {
        if (activeMedia != null && activeMedia?.uri == rawUri) {
            // Already loaded, don't restart playback
            return
        }

        activeScope.launch {
            _uiState.update {
                it.copy(
                    status = PlayerStatus.BUFFERING,
                    title = title,
                    uri = rawUri,
                    isOnline = isOnline,
                    sourceType = if (isOnline) MediaSourceType.ONLINE_DIRECT else MediaSourceType.LOCAL_FILE,
                    errorMessage = null
                )
            }

            val validationResult = if (isOnline) {
                PlayerMediaValidator.validateOnlineUrl(rawUri, title, mediaId)
            } else {
                PlayerMediaValidator.validateLocalFile(rawUri, title, mediaId)
            }

            validationResult.fold(
                onSuccess = { validated ->
                    activeMedia = validated
                    val initialPos = if (!isOnline) {
                        positionTracker.getInitialPosition(
                            libraryId = validated.libraryId ?: mediaId,
                            filePath = rawUri,
                            knownDurationMs = validated.durationMs
                        )
                    } else {
                        0L
                    }

                    _uiState.update {
                        it.copy(
                            title = validated.title,
                            currentPositionMs = initialPos,
                            durationMs = validated.durationMs ?: 0L
                        )
                    }

                    playerController.prepare(validated.uri, initialPos)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            status = PlayerStatus.ERROR,
                            isPlaying = false,
                            errorMessage = error.message ?: "Failed to validate media for playback.",
                            canRetry = isOnline
                        )
                    }
                }
            )
        }
    }

    fun play() {
        if (_uiState.value.isCompleted) {
            seekTo(0L)
        }
        playerController.play()
    }

    fun pause() {
        playerController.pause()
        progressTrackerJob?.cancel()
        flushPosition()
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, _uiState.value.durationMs.coerceAtLeast(0L))
        _uiState.update { it.copy(currentPositionMs = target, isCompleted = false) }
        playerController.seekTo(target)
        positionTracker.updatePosition(
            libraryId = activeMedia?.libraryId,
            positionMs = target,
            durationMs = _uiState.value.durationMs,
            forceImmediatePersist = true
        )
        resetControlsTimer()
    }

    fun seekForward(offsetMs: Long = 10_000L) {
        val current = playerController.currentPositionMs
        val target = (current + offsetMs).coerceAtMost(_uiState.value.durationMs)
        seekTo(target)
    }

    fun seekBackward(offsetMs: Long = 10_000L) {
        val current = playerController.currentPositionMs
        val target = (current - offsetMs).coerceAtLeast(0L)
        seekTo(target)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerController.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleControlsVisibility() {
        val nextVisible = !_uiState.value.areControlsVisible
        _uiState.update { it.copy(areControlsVisible = nextVisible) }
        if (nextVisible) {
            resetControlsTimer()
        } else {
            controlsTimeoutJob?.cancel()
        }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update { it.copy(areControlsVisible = visible) }
        if (visible) {
            resetControlsTimer()
        } else {
            controlsTimeoutJob?.cancel()
        }
    }

    fun retryPlayback() {
        val media = activeMedia
        if (media != null) {
            _uiState.update { it.copy(status = PlayerStatus.BUFFERING, errorMessage = null) }
            playerController.prepare(media.uri, _uiState.value.currentPositionMs)
        }
    }

    fun pauseOnBackground() {
        if (_uiState.value.isPlaying) {
            pause()
        }
        flushPosition()
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        if (!playerController.isPlaying) return
        progressTrackerJob = activeScope.launch {
            while (isActive && playerController.isPlaying) {
                val pos = playerController.currentPositionMs
                val dur = playerController.durationMs
                val buf = playerController.bufferedPositionMs

                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = if (dur > 0) dur else it.durationMs,
                        bufferedPositionMs = buf
                    )
                }

                activeMedia?.libraryId?.let { libId ->
                    positionTracker.updatePosition(
                        libraryId = libId,
                        positionMs = pos,
                        durationMs = dur
                    )
                }
                delay(250L)
            }
        }
    }

    private fun resetControlsTimer() {
        controlsTimeoutJob?.cancel()
        controlsTimeoutJob = activeScope.launch {
            delay(3500L)
            if (_uiState.value.isPlaying) {
                _uiState.update { it.copy(areControlsVisible = false) }
            }
        }
    }

    private fun flushPosition() {
        val media = activeMedia ?: return
        val pos = playerController.currentPositionMs
        val dur = playerController.durationMs
        activeScope.launch {
            positionTracker.flush(media.libraryId, pos, dur)
        }
    }

    fun release() {
        progressTrackerJob?.cancel()
        controlsTimeoutJob?.cancel()
        flushPosition()
        playerController.removeListener(playerListener)
        playerController.release()
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }

    companion object {
        fun provideFactory(
            playerController: VideoPlayerController,
            positionTracker: PlaybackPositionTracker,
            libraryRepository: LibraryRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(playerController, positionTracker, libraryRepository) as T
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? TeradownApplication
                val context = app?.applicationContext
                    ?: throw IllegalStateException("ApplicationContext required for PlayerViewModel")
                val libRepo = app.container.libraryRepository
                val tracker = app.container.playbackPositionTracker
                val controller = ExoPlayerController(context)
                PlayerViewModel(controller, tracker, libRepo)
            }
        }
    }
}
