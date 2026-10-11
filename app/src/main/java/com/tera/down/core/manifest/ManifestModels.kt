package com.tera.down.core.manifest

import com.tera.down.core.model.MediaType

/**
 * Task outcome status recorded in the download manifest.
 */
enum class ManifestStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

/**
 * High-level error categorization for manifest reporting.
 */
enum class ManifestErrorCategory {
    NETWORK_ERROR,
    SERVER_ERROR,
    HTTP_ERROR,
    INSUFFICIENT_STORAGE,
    FILE_IO_ERROR,
    INVALID_URL,
    CANCELLED,
    TIMEOUT,
    UNKNOWN
}

/**
 * Domain item representing a manifest record of app download activity.
 */
data class DownloadManifestItem(
    val id: String,
    val taskId: String,
    val sourceUrlRedacted: String,
    val fileName: String,
    val fileSize: Long,
    val mediaType: MediaType = MediaType.VIDEO,
    val destinationPath: String? = null,
    val status: ManifestStatus = ManifestStatus.QUEUED,
    val bytesReceived: Long = 0L,
    val resumed: Boolean = false,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val checksumSha256: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val attemptCount: Int = 1,
    val schemaVersion: Int = 1
)

/**
 * Export envelope metadata for JSON export format.
 */
data class ManifestExportDocument(
    val schemaVersion: Int = 1,
    val appVersion: String = "1.0.0",
    val exportedAt: Long = System.currentTimeMillis(),
    val totalRecords: Int,
    val records: List<DownloadManifestItem>
)
