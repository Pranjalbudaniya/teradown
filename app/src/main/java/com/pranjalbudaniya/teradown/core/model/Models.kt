package com.pranjalbudaniya.teradown.core.model

/**
 * Media classifications as defined in the Terabox specification (Section 7).
 */
enum class MediaType {
    VIDEO,
    PHOTO,
    OTHER
}

/**
 * Share-level states representing the reachability, validity, and authorization of a share link.
 * Specification Section 4.
 */
enum class ShareStatus {
    OK,
    NEEDS_PASSWORD,
    WRONG_PASSWORD,
    NOT_LOGGED_IN,
    EXPIRED,
    CANCELLED,
    BLOCKED,
    CAPTCHA_OR_RATELIMIT
}

/**
 * Backwards compatibility alias for ShareStatus.
 */
typealias ShareState = ShareStatus

/**
 * Parsed Terabox share link representation.
 * Contains the original URL, extracted identifier, optional extraction code, and host.
 * Passwords are deliberately masked in string representations to prevent accidental log leakage.
 */
data class ShareLink(
    val originalUrl: String,
    val surl: String,
    val pwd: String? = null,
    val host: String? = null
) {
    /**
     * Surl variant without the leading '1' (if present and non-empty).
     * Used by the resolver when querying endpoints that require stripping the leading '1'.
     */
    val surlWithoutLeadingOne: String
        get() = if (surl.startsWith("1") && surl.length > 1) surl.substring(1) else surl

    /**
     * Surl variant guaranteed to have a leading '1'.
     * Used by the resolver when querying endpoints that expect a leading '1'.
     */
    val surlWithLeadingOne: String
        get() = if (surl.startsWith("1")) surl else "1$surl"

    /**
     * Diagnostic string with masked extraction password to protect user credentials in logs.
     */
    fun toSafeString(): String {
        val pwdLabel = if (pwd != null) "[PROTECTED]" else "none"
        return "ShareLink(surl='$surl', host='$host', pwd=$pwdLabel)"
    }

    override fun toString(): String = toSafeString()
}

/**
 * Domain entity representing an individual file entry inside a Terabox share.
 * Section 6 of specification.
 */
data class MediaEntry(
    val fsId: String,
    val name: String,
    val relPath: String,
    val size: Long,
    val md5: String? = null,
    val category: Int? = null,
    val mediaType: MediaType
)

/**
 * File-level statuses stored in the manifest with retryability and queue-pause semantics.
 * Specification Section 4.
 */
enum class FileDownloadStatus(
    val isRetryable: Boolean,
    val isQueueWidePause: Boolean = false
) {
    PENDING(isRetryable = false),
    DOWNLOADING(isRetryable = false),
    DONE(isRetryable = false),
    SKIPPED_EXISTS(isRetryable = false),
    SKIPPED_EMPTY(isRetryable = false),
    SKIPPED_TYPE(isRetryable = false),
    NO_DIRECT_LINK(isRetryable = true),
    MISSING(isRetryable = false),
    BLOCKED(isRetryable = false),
    FAILED_LINK_EXPIRED(isRetryable = true),
    FAILED_SIZE(isRetryable = true),
    FAILED_NETWORK(isRetryable = true),
    QUOTA_PAUSED(isRetryable = true, isQueueWidePause = true),
    NEEDS_AUTH(isRetryable = true, isQueueWidePause = true),
    DISK_FULL(isRetryable = true, isQueueWidePause = true),
    LIST_FAILED(isRetryable = true)
}

/**
 * Metadata for an inspected Terabox share link.
 */
data class ShareInfo(
    val surl: String,
    val title: String,
    val shareId: String? = null,
    val uk: String? = null,
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val state: ShareStatus = ShareStatus.OK,
    val requiresPassword: Boolean = false,
    val password: String? = null
)

/**
 * An individual file entry displayed in the share browser UI.
 */
data class ShareFileItem(
    val fsId: String,
    val name: String,
    val size: Long,
    val relPath: String,
    val mediaType: MediaType,
    val category: Int = 0,
    val directUrl: String? = null,
    val isSelected: Boolean = false
)

/**
 * High-level lifecycle status for download task items displayed in the UI.
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Represents a download task displayed in the download queue UI.
 */
data class DownloadTaskItem(
    val id: String,
    val fsId: String,
    val name: String,
    val size: Long,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val mediaType: MediaType = MediaType.VIDEO,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (size > 0L) (downloadedBytes.toFloat() / size.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Represents a locally indexed video or photo stored on the device.
 */
data class LocalMediaItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val dateAdded: Long,
    val mediaType: MediaType,
    val durationMs: Long? = null
)
