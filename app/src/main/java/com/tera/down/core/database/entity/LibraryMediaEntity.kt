package com.tera.down.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tera.down.core.model.MediaType

/**
 * Room entity representing an indexed video or photo stored in the local library.
 * Persists independently of the active download queue so clearing completed tasks
 * never removes library entries.
 */
@Entity(
    tableName = "library_media",
    indices = [
        Index(value = ["mediaType"]),
        Index(value = ["dateAdded"])
    ]
)
data class LibraryMediaEntity(
    @PrimaryKey
    val id: String,
    val taskId: String? = null,
    val name: String,
    val path: String,
    val size: Long,
    val mediaType: MediaType = MediaType.VIDEO,
    val durationMs: Long? = null,
    val lastPositionMs: Long = 0L,
    val dateAdded: Long = System.currentTimeMillis(),
    val fsId: String? = null,
    val surl: String? = null
)
