package com.tera.down.feature.queue

import com.tera.down.core.database.dao.LibraryMediaDao
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.LocalMediaItem
import com.tera.down.core.model.MediaType
import com.tera.down.feature.downloader.FilenameSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

interface LibraryRepository {
    fun observeAllMedia(): Flow<List<LocalMediaItem>>
    fun observeMediaByType(mediaType: MediaType): Flow<List<LocalMediaItem>>
    suspend fun addMedia(item: LocalMediaItem)
    suspend fun deleteMedia(id: String): Boolean
    suspend fun deleteMediaFileAndRecord(id: String): Boolean
    suspend fun renameMedia(id: String, newName: String): Boolean
    suspend fun getMediaById(id: String): LocalMediaItem?
    suspend fun getMediaByPath(path: String): LocalMediaItem?
    suspend fun updatePlaybackPosition(id: String, positionMs: Long): Boolean
    suspend fun updateDuration(id: String, durationMs: Long): Boolean
    suspend fun refreshMediaItem(id: String): LocalMediaItem?
}

class LibraryRepositoryImpl(
    private val libraryMediaDao: LibraryMediaDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LibraryRepository {

    override fun observeAllMedia(): Flow<List<LocalMediaItem>> {
        return libraryMediaDao.observeAllMedia()
            .map { entities ->
                entities.map { it.toDomainModel() }
            }
            .flowOn(ioDispatcher)
    }

    override fun observeMediaByType(mediaType: MediaType): Flow<List<LocalMediaItem>> {
        return libraryMediaDao.observeMediaByType(mediaType)
            .map { entities ->
                entities.map { it.toDomainModel() }
            }
            .flowOn(ioDispatcher)
    }

    override suspend fun addMedia(item: LocalMediaItem): Unit = withContext(ioDispatcher) {
        val existing = libraryMediaDao.getMediaByPath(item.path)
        val entity = LibraryMediaEntity(
            id = existing?.id ?: item.id,
            name = item.name,
            path = item.path,
            size = item.size,
            mediaType = item.mediaType,
            durationMs = item.durationMs ?: existing?.durationMs,
            lastPositionMs = item.lastPositionMs,
            dateAdded = existing?.dateAdded ?: item.dateAdded
        )
        libraryMediaDao.insertMedia(entity)
    }

    override suspend fun deleteMedia(id: String): Boolean = withContext(ioDispatcher) {
        // Record-only removal without deleting underlying physical file
        val deletedCount = libraryMediaDao.deleteMediaById(id)
        deletedCount > 0
    }

    override suspend fun deleteMediaFileAndRecord(id: String): Boolean = withContext(ioDispatcher) {
        val entity = libraryMediaDao.getMediaById(id) ?: return@withContext false
        val file = File(entity.path)
        if (file.exists()) {
            try {
                file.delete()
            } catch (_: SecurityException) {
                // If physical delete fails, continue to delete database record
            }
        }
        val deletedCount = libraryMediaDao.deleteMediaById(id)
        deletedCount > 0
    }

    override suspend fun renameMedia(id: String, newName: String): Boolean = withContext(ioDispatcher) {
        val entity = libraryMediaDao.getMediaById(id) ?: return@withContext false
        val sanitized = FilenameSanitizer.sanitize(newName.trim())
        if (sanitized.isBlank()) return@withContext false

        val oldFile = File(entity.path)
        val finalName = if (!sanitized.contains(".") && oldFile.extension.isNotEmpty()) {
            "$sanitized.${oldFile.extension}"
        } else {
            sanitized
        }

        if (oldFile.exists()) {
            val parent = oldFile.parentFile ?: return@withContext false
            val newFile = File(parent, finalName)

            if (newFile.exists() && newFile.canonicalPath != oldFile.canonicalPath) {
                return@withContext false // Do not overwrite another file
            }

            val renamed = oldFile.renameTo(newFile)
            if (!renamed) {
                return@withContext false
            }

            val updated = libraryMediaDao.updateNameAndPath(id, finalName, newFile.absolutePath)
            updated > 0
        } else {
            // Physical file not found on disk, update library database record name
            val updated = libraryMediaDao.updateNameAndPath(id, finalName, entity.path)
            updated > 0
        }
    }

    override suspend fun getMediaById(id: String): LocalMediaItem? = withContext(ioDispatcher) {
        libraryMediaDao.getMediaById(id)?.toDomainModel()
    }

    override suspend fun getMediaByPath(path: String): LocalMediaItem? = withContext(ioDispatcher) {
        libraryMediaDao.getMediaByPath(path)?.toDomainModel()
    }

    override suspend fun updatePlaybackPosition(id: String, positionMs: Long): Boolean = withContext(ioDispatcher) {
        val updated = libraryMediaDao.updatePlaybackPosition(id, positionMs)
        updated > 0
    }

    override suspend fun updateDuration(id: String, durationMs: Long): Boolean = withContext(ioDispatcher) {
        val updated = libraryMediaDao.updateDuration(id, durationMs)
        updated > 0
    }

    override suspend fun refreshMediaItem(id: String): LocalMediaItem? = withContext(ioDispatcher) {
        libraryMediaDao.getMediaById(id)?.toDomainModel()
    }

    private fun LibraryMediaEntity.toDomainModel(): LocalMediaItem {
        val file = File(path)
        val exists = try {
            file.exists() && file.canRead()
        } catch (_: SecurityException) {
            false
        }
        val actualSize = if (exists && file.length() > 0L) file.length() else size

        return LocalMediaItem(
            id = id,
            name = name,
            path = path,
            size = actualSize,
            dateAdded = dateAdded,
            mediaType = mediaType,
            durationMs = durationMs,
            lastPositionMs = lastPositionMs,
            existsOnDisk = exists
        )
    }
}
