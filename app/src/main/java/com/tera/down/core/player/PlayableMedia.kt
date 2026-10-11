package com.tera.down.core.player

/**
 * Origin type of the media being played.
 */
enum class MediaSourceType {
    LOCAL_FILE,
    ONLINE_DIRECT
}

enum class PlayerStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

/**
 * Validated media item ready for playback in AndroidX Media3 ExoPlayer.
 */
data class PlayableMedia(
    val uri: String,
    val title: String,
    val sourceType: MediaSourceType,
    val initialPositionMs: Long = 0L,
    val durationMs: Long? = null,
    val libraryId: String? = null,
    val fileSize: Long? = null
)

/**
 * Typed validation errors preventing invalid or insecure playback attempts.
 */
sealed class MediaValidationError(val userMessage: String) : Throwable(userMessage) {
    data class MissingFile(val path: String) :
        MediaValidationError("The requested video file does not exist at: $path")

    data class InaccessibleFile(val path: String) :
        MediaValidationError("The media file cannot be read from device storage: $path")

    data class TemporaryOrIncompleteFile(val path: String) :
        MediaValidationError("Cannot play incomplete or temporary download file: $path")

    data class EmptyFile(val path: String) :
        MediaValidationError("The video file is empty (0 bytes): $path")

    data class UnsupportedFormat(val extension: String) :
        MediaValidationError("The format '.$extension' is not a supported playable video format.")

    data class InsecureUrl(val url: String) :
        MediaValidationError("Insecure playback URL rejected. Only verified HTTPS URLs are permitted.")

    data class MalformedUrl(val reason: String) :
        MediaValidationError("Malformed media URL: $reason")

    data class NoDirectStreamAvailable(val fileName: String) :
        MediaValidationError("Direct playback is not available for '$fileName'. Please download the file or open it in your browser.")
}
