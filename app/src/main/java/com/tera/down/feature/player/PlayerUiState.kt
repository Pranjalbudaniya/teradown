package com.tera.down.feature.player

import com.tera.down.core.player.MediaSourceType
import com.tera.down.core.player.PlayerStatus

data class PlayerUiState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val title: String = "",
    val uri: String = "",
    val sourceType: MediaSourceType = MediaSourceType.LOCAL_FILE,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val areControlsVisible: Boolean = true,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val isOnline: Boolean = false,
    val isCompleted: Boolean = false
) {
    val progress: Float
        get() = if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val bufferedProgress: Float
        get() = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}
