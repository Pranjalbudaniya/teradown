package com.tera.down.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType

/**
 * Room entity representing a persistent download task in the download queue.
 */
@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["surl"]),
        Index(value = ["fsId"]),
        Index(value = ["status"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey
    val id: String,
    val originalUrl: String,
    val surl: String,
    val fsId: String,
    val name: String,
    val relPath: String,
    val size: Long,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val mediaType: MediaType = MediaType.VIDEO,
    val category: Int? = null,
    val destinationPath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val md5: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
