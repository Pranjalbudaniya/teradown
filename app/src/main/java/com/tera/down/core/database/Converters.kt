package com.tera.down.core.database

import androidx.room.TypeConverter
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType

/**
 * Room type converters for domain enums.
 */
class Converters {

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType {
        return try {
            MediaType.valueOf(value)
        } catch (e: Exception) {
            MediaType.OTHER
        }
    }

    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus {
        return try {
            DownloadStatus.valueOf(value)
        } catch (e: Exception) {
            DownloadStatus.QUEUED
        }
    }

    @TypeConverter
    fun fromManifestStatus(value: com.tera.down.core.manifest.ManifestStatus): String = value.name

    @TypeConverter
    fun toManifestStatus(value: String): com.tera.down.core.manifest.ManifestStatus {
        return try {
            com.tera.down.core.manifest.ManifestStatus.valueOf(value)
        } catch (e: Exception) {
            com.tera.down.core.manifest.ManifestStatus.QUEUED
        }
    }
}
