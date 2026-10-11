package com.tera.down.feature.queue

import com.tera.down.core.database.dao.DownloadTaskDao
import com.tera.down.core.database.dao.LibraryMediaDao
import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.DownloadTaskItem
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

data class BatchEnqueueResult(
    val enqueuedTaskIds: List<String>,
    val addedCount: Int,
    val duplicateCount: Int,
    val unavailableCount: Int,
    val unsupportedCount: Int
)

interface DownloadQueueRepository {
    fun observeAllTasks(): Flow<List<DownloadTaskItem>>
    suspend fun getTaskById(id: String): DownloadTaskItem?
    suspend fun enqueueShareFiles(surl: String, originalUrl: String, items: List<ShareFileItem>): List<String>
    suspend fun enqueueShareFilesWithResult(surl: String, originalUrl: String, items: List<ShareFileItem>): BatchEnqueueResult
    suspend fun pauseTask(id: String): Boolean
    suspend fun resumeTask(id: String): Boolean
    suspend fun cancelTask(id: String): Boolean
    suspend fun retryTask(id: String): Boolean
    suspend fun retryAllFailedTasks(): Int
    suspend fun clearCompletedTasks(): Int
    suspend fun updateTaskStatus(id: String, newStatus: DownloadStatus, errorMessage: String? = null): Boolean
    suspend fun updateProgress(id: String, downloadedBytes: Long, speedBytesPerSec: Long): Boolean
    suspend fun completeTask(id: String, destinationPath: String): Boolean
    suspend fun getTaskEntityById(id: String): DownloadTaskEntity?
    suspend fun getQueuedTaskEntities(): List<DownloadTaskEntity>
    suspend fun enqueueDirectDownload(
        url: String,
        filename: String,
        size: Long = 0L,
        mediaType: MediaType = MediaType.VIDEO
    ): String
}

class DownloadQueueRepositoryImpl(
    private val downloadTaskDao: DownloadTaskDao,
    private val libraryMediaDao: LibraryMediaDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val manifestReporter: com.tera.down.feature.manifest.ManifestReporter? = null
) : DownloadQueueRepository {

    override fun observeAllTasks(): Flow<List<DownloadTaskItem>> {
        return downloadTaskDao.observeAllTasks().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getTaskById(id: String): DownloadTaskItem? = withContext(ioDispatcher) {
        downloadTaskDao.getTaskById(id)?.toDomainModel()
    }

    override suspend fun getTaskEntityById(id: String): DownloadTaskEntity? = withContext(ioDispatcher) {
        downloadTaskDao.getTaskById(id)
    }

    override suspend fun getQueuedTaskEntities(): List<DownloadTaskEntity> = withContext(ioDispatcher) {
        downloadTaskDao.getQueuedTasks()
    }

    override suspend fun enqueueDirectDownload(
        url: String,
        filename: String,
        size: Long,
        mediaType: MediaType
    ): String = withContext(ioDispatcher) {
        val existingActive = downloadTaskDao.getActiveTask("direct", url)
        if (existingActive != null) {
            return@withContext existingActive.id
        }

        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = DownloadTaskEntity(
            id = newId,
            originalUrl = url,
            surl = "direct",
            fsId = url.hashCode().toString(),
            name = filename,
            relPath = filename,
            size = size,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            mediaType = mediaType,
            destinationPath = null,
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            md5 = null,
            createdAt = now,
            updatedAt = now
        )
        downloadTaskDao.insertTask(entity)
        manifestReporter?.recordEnqueued(
            taskId = newId,
            originalUrl = url,
            fileName = filename,
            fileSize = size,
            mediaType = mediaType
        )
        newId
    }

    override suspend fun enqueueShareFilesWithResult(
        surl: String,
        originalUrl: String,
        items: List<ShareFileItem>
    ): BatchEnqueueResult = withContext(ioDispatcher) {
        val createdTaskIds = mutableListOf<String>()
        var duplicates = 0
        var unavailable = 0
        var unsupported = 0

        for (item in items) {
            // Ignore folders and unsupported media types
            if (item.mediaType == MediaType.OTHER || item.isDirectory) {
                unsupported++
                continue
            }

            val downloadUrl = item.directUrl?.takeIf { it.isNotBlank() } ?: originalUrl
            if (downloadUrl.isBlank()) {
                unavailable++
                continue
            }

            // Deduplication: prevent adding duplicate active tasks for the same share + file id
            val existingActive = downloadTaskDao.getActiveTask(surl, item.fsId)
            if (existingActive != null) {
                duplicates++
                continue
            }

            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = DownloadTaskEntity(
                id = newId,
                originalUrl = downloadUrl,
                surl = surl,
                fsId = item.fsId,
                name = item.name,
                relPath = item.relPath,
                size = item.size,
                downloadedBytes = 0L,
                speedBytesPerSec = 0L,
                mediaType = item.mediaType,
                category = item.category,
                destinationPath = null,
                status = DownloadStatus.QUEUED,
                errorMessage = null,
                md5 = null,
                createdAt = now,
                updatedAt = now
            )
            downloadTaskDao.insertTask(entity)
            manifestReporter?.recordEnqueued(
                taskId = newId,
                originalUrl = downloadUrl,
                fileName = item.name,
                fileSize = item.size,
                mediaType = item.mediaType
            )
            createdTaskIds.add(newId)
        }

        BatchEnqueueResult(
            enqueuedTaskIds = createdTaskIds,
            addedCount = createdTaskIds.size,
            duplicateCount = duplicates,
            unavailableCount = unavailable,
            unsupportedCount = unsupported
        )
    }

    override suspend fun enqueueShareFiles(
        surl: String,
        originalUrl: String,
        items: List<ShareFileItem>
    ): List<String> = enqueueShareFilesWithResult(surl, originalUrl, items).enqueuedTaskIds

    override suspend fun pauseTask(id: String): Boolean = withContext(ioDispatcher) {
        val entity = downloadTaskDao.getTaskById(id) ?: return@withContext false
        if (!DownloadQueueStateValidator.canTransition(entity.status, DownloadStatus.PAUSED)) {
            return@withContext false
        }
        val updated = entity.copy(
            status = DownloadStatus.PAUSED,
            speedBytesPerSec = 0L,
            updatedAt = System.currentTimeMillis()
        )
        downloadTaskDao.updateTask(updated)
        true
    }

    override suspend fun resumeTask(id: String): Boolean = withContext(ioDispatcher) {
        val entity = downloadTaskDao.getTaskById(id) ?: return@withContext false
        if (!DownloadQueueStateValidator.canTransition(entity.status, DownloadStatus.QUEUED)) {
            return@withContext false
        }
        val updated = entity.copy(
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        downloadTaskDao.updateTask(updated)
        true
    }

    override suspend fun cancelTask(id: String): Boolean = withContext(ioDispatcher) {
        val deletedCount = downloadTaskDao.deleteTaskById(id)
        if (deletedCount > 0) {
            manifestReporter?.recordCancelled(id)
            true
        } else {
            false
        }
    }

    override suspend fun retryTask(id: String): Boolean = withContext(ioDispatcher) {
        val entity = downloadTaskDao.getTaskById(id) ?: return@withContext false
        if (!DownloadQueueStateValidator.canTransition(entity.status, DownloadStatus.QUEUED)) {
            return@withContext false
        }
        val updated = entity.copy(
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        downloadTaskDao.updateTask(updated)
        manifestReporter?.recordRetrying(id)
        true
    }

    override suspend fun retryAllFailedTasks(): Int = withContext(ioDispatcher) {
        downloadTaskDao.resetFailedTasks()
    }

    override suspend fun clearCompletedTasks(): Int = withContext(ioDispatcher) {
        downloadTaskDao.deleteCompletedTasks()
    }

    override suspend fun updateTaskStatus(
        id: String,
        newStatus: DownloadStatus,
        errorMessage: String?
    ): Boolean = withContext(ioDispatcher) {
        val entity = downloadTaskDao.getTaskById(id) ?: return@withContext false
        if (!DownloadQueueStateValidator.canTransition(entity.status, newStatus)) {
            return@withContext false
        }
        val updated = entity.copy(
            status = newStatus,
            errorMessage = errorMessage,
            speedBytesPerSec = if (newStatus != DownloadStatus.DOWNLOADING) 0L else entity.speedBytesPerSec,
            updatedAt = System.currentTimeMillis()
        )
        downloadTaskDao.updateTask(updated)
        true
    }

    override suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        speedBytesPerSec: Long
    ): Boolean = withContext(ioDispatcher) {
        val entity = downloadTaskDao.getTaskById(id) ?: return@withContext false
        // Reject stale progress events if task is paused, cancelled, failed, or completed
        if (entity.status in setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.CANCELLED, DownloadStatus.FAILED)) {
            return@withContext false
        }
        val coercedBytes = DownloadQueueStateValidator.coerceDownloadedBytes(downloadedBytes, entity.size)
        val updated = entity.copy(
            downloadedBytes = coercedBytes,
            speedBytesPerSec = speedBytesPerSec.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis()
        )
        downloadTaskDao.updateTask(updated)
        true
    }

    override suspend fun completeTask(id: String, destinationPath: String): Boolean = withContext(ioDispatcher) {
        val entity = downloadTaskDao.getTaskById(id) ?: return@withContext false
        // Idempotent: if already marked complete, acknowledge success safely
        if (entity.status == DownloadStatus.COMPLETED) {
            return@withContext true
        }
        if (!DownloadQueueStateValidator.canTransition(entity.status, DownloadStatus.COMPLETED)) {
            return@withContext false
        }

        val now = System.currentTimeMillis()
        val updatedTask = entity.copy(
            status = DownloadStatus.COMPLETED,
            downloadedBytes = entity.size,
            speedBytesPerSec = 0L,
            destinationPath = destinationPath,
            updatedAt = now
        )
        downloadTaskDao.updateTask(updatedTask)

        // Automatically index completed download into the persistent library table with deduplication
        val existingMedia = libraryMediaDao.getMediaByTaskId(entity.id)
            ?: libraryMediaDao.getMediaByPath(destinationPath)
        val entryId = existingMedia?.id ?: UUID.randomUUID().toString()

        val libraryEntry = LibraryMediaEntity(
            id = entryId,
            taskId = entity.id,
            name = entity.name,
            path = destinationPath,
            size = entity.size,
            mediaType = entity.mediaType,
            durationMs = existingMedia?.durationMs,
            lastPositionMs = existingMedia?.lastPositionMs ?: 0L,
            dateAdded = existingMedia?.dateAdded ?: now,
            fsId = entity.fsId,
            surl = entity.surl
        )
        libraryMediaDao.insertMedia(libraryEntry)
        true
    }

    private fun DownloadTaskEntity.toDomainModel(): DownloadTaskItem {
        return DownloadTaskItem(
            id = id,
            fsId = fsId,
            name = name,
            size = size,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = speedBytesPerSec,
            status = status,
            mediaType = mediaType,
            errorMessage = errorMessage
        )
    }
}
