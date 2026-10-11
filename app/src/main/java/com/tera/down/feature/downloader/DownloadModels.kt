package com.tera.down.feature.downloader

import java.io.File

/**
 * Parameters representing a download request submitted to [DownloadEngine].
 */
data class DownloadRequest(
    val taskId: String,
    val url: String,
    val destinationFile: File,
    val tempFile: File,
    val headers: Map<String, String> = emptyMap(),
    val expectedSize: Long = 0L,
    val allowResume: Boolean = true
)

/**
 * Classification of errors encountered during download operations.
 */
enum class DownloadErrorType {
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    NETWORK_FAILURE,
    HTTP_ERROR,
    INSUFFICIENT_SPACE,
    FILE_IO_ERROR,
    SERVER_ERROR,
    UNKNOWN
}

/**
 * Result outcome returned by [DownloadEngine.download].
 */
sealed interface DownloadResult {

    data class Success(
        val file: File,
        val totalBytes: Long,
        val mimeType: String? = null,
        val resumed: Boolean = false
    ) : DownloadResult

    data class Error(
        val type: DownloadErrorType,
        val message: String,
        val httpStatusCode: Int? = null,
        val isResumable: Boolean = false,
        val cause: Throwable? = null
    ) : DownloadResult

    object Cancelled : DownloadResult
}
