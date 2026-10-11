package com.tera.down.core.player

import com.tera.down.core.classifier.MediaClassifier
import java.io.File
import java.net.URI

/**
 * Validates local media files and authorized online media streams before attempting playback.
 * Enforces strict security constraints: rejects temporary/incomplete files, non-HTTPS URLs,
 * inaccessible files, corrupt empty files, and unsupported media formats.
 */
object PlayerMediaValidator {

    private val REJECTED_ONLINE_EXTENSIONS = setOf(
        "html", "htm", "php", "jsp", "asp", "aspx", "js", "css"
    )

    /**
     * Validates a local video file on device storage.
     */
    fun validateLocalFile(
        path: String?,
        title: String? = null,
        libraryId: String? = null,
        initialPositionMs: Long = 0L,
        durationMs: Long? = null
    ): Result<PlayableMedia> {
        if (path.isNullOrBlank()) {
            return Result.failure(MediaValidationError.MissingFile("Empty file path"))
        }

        val trimmedPath = path.trim()

        // Reject temporary / partial download files
        if (isTemporaryOrIncompletePath(trimmedPath)) {
            return Result.failure(MediaValidationError.TemporaryOrIncompleteFile(trimmedPath))
        }

        val file = File(trimmedPath)

        if (!file.exists()) {
            return Result.failure(MediaValidationError.MissingFile(trimmedPath))
        }

        if (!file.canRead()) {
            return Result.failure(MediaValidationError.InaccessibleFile(trimmedPath))
        }

        if (file.length() <= 0L) {
            return Result.failure(MediaValidationError.EmptyFile(trimmedPath))
        }

        val ext = MediaClassifier.extractExtension(file.name)
        if (!MediaClassifier.isVideoExtension(ext)) {
            return Result.failure(MediaValidationError.UnsupportedFormat(ext.ifEmpty { "unknown" }))
        }

        val cleanTitle = if (!title.isNullOrBlank()) title else file.name

        return Result.success(
            PlayableMedia(
                uri = file.toURI().toString(),
                title = cleanTitle,
                sourceType = MediaSourceType.LOCAL_FILE,
                initialPositionMs = initialPositionMs.coerceAtLeast(0L),
                durationMs = durationMs,
                libraryId = libraryId,
                fileSize = file.length()
            )
        )
    }

    /**
     * Validates an authorized direct online media stream URL.
     * Enforces strict HTTPS and ensures the stream does not derive from unsupported web endpoints.
     */
    fun validateOnlineUrl(
        url: String?,
        title: String? = null,
        libraryId: String? = null
    ): Result<PlayableMedia> {
        if (url.isNullOrBlank()) {
            return Result.failure(
                MediaValidationError.NoDirectStreamAvailable(title ?: "video")
            )
        }

        val trimmedUrl = url.trim()

        // Reject insecure plain HTTP or unsupported schemes
        if (trimmedUrl.startsWith("http://", ignoreCase = true)) {
            return Result.failure(MediaValidationError.InsecureUrl(trimmedUrl))
        }

        if (!trimmedUrl.startsWith("https://", ignoreCase = true)) {
            return Result.failure(MediaValidationError.InsecureUrl(trimmedUrl))
        }

        return try {
            val uri = URI(trimmedUrl)
            val host = uri.host
            if (host.isNullOrBlank()) {
                return Result.failure(MediaValidationError.MalformedUrl("Missing host in URL"))
            }

            // Check if path indicates an HTML page rather than media
            val path = uri.path ?: ""
            val ext = MediaClassifier.extractExtension(path)
            if (REJECTED_ONLINE_EXTENSIONS.contains(ext)) {
                return Result.failure(
                    MediaValidationError.MalformedUrl("URL targets web document (.$ext) rather than playable media stream")
                )
            }

            val fallbackTitle = if (!title.isNullOrBlank()) {
                title
            } else {
                path.substringAfterLast('/').ifEmpty { "Online Stream" }
            }

            Result.success(
                PlayableMedia(
                    uri = trimmedUrl,
                    title = fallbackTitle,
                    sourceType = MediaSourceType.ONLINE_DIRECT,
                    initialPositionMs = 0L,
                    libraryId = libraryId
                )
            )
        } catch (e: Exception) {
            Result.failure(MediaValidationError.MalformedUrl(e.message ?: "Invalid URL format"))
        }
    }

    /**
     * Checks if a file path belongs to an incomplete download or temporary working file.
     */
    fun isTemporaryOrIncompletePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".tmp") ||
            lower.endsWith(".part") ||
            lower.endsWith(".download") ||
            lower.endsWith(".crdownload") ||
            lower.contains(".tmp.")
    }
}
