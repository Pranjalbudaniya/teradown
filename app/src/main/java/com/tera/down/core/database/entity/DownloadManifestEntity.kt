package com.tera.down.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.MediaType

/**
 * Room entity representing an immutable audit trail of a download request.
 * Persists independently of the active download queue so clearing completed tasks
 * never clears user manifest history.
 */
@Entity(
    tableName = "download_manifest",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class DownloadManifestEntity(
    @PrimaryKey
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
