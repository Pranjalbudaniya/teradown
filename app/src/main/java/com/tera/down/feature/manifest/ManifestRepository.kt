package com.tera.down.feature.manifest

import com.tera.down.core.database.dao.DownloadManifestDao
import com.tera.down.core.database.entity.DownloadManifestEntity
import com.tera.down.core.manifest.DownloadManifestItem
import com.tera.down.core.manifest.ManifestErrorCategory
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.player.MediaUrlSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

interface ManifestRepository : ManifestReporter {
    fun observeAllManifest(): Flow<List<DownloadManifestItem>>
    fun observeManifestByStatus(status: ManifestStatus): Flow<List<DownloadManifestItem>>
    suspend fun getAllManifest(): List<DownloadManifestItem>
    suspend fun getManifestById(id: String): DownloadManifestItem?
    suspend fun getManifestByTaskId(taskId: String): DownloadManifestItem?
    suspend fun deleteManifestRecord(id: String): Boolean
    suspend fun clearManifestHistory(): Int
    suspend fun countManifest(): Int
}

class ManifestRepositoryImpl(
    private val downloadManifestDao: DownloadManifestDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ManifestRepository {

    override fun observeAllManifest(): Flow<List<DownloadManifestItem>> {
        return downloadManifestDao.observeAllManifest().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeManifestByStatus(status: ManifestStatus): Flow<List<DownloadManifestItem>> {
        return downloadManifestDao.observeManifestByStatus(status).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAllManifest(): List<DownloadManifestItem> = withContext(ioDispatcher) {
        downloadManifestDao.getAllManifest().map { it.toDomain() }
    }

    override suspend fun getManifestById(id: String): DownloadManifestItem? = withContext(ioDispatcher) {
        downloadManifestDao.getManifestById(id)?.toDomain()
    }

    override suspend fun getManifestByTaskId(taskId: String): DownloadManifestItem? = withContext(ioDispatcher) {
        downloadManifestDao.getManifestByTaskId(taskId)?.toDomain()
    }

    override suspend fun deleteManifestRecord(id: String): Boolean = withContext(ioDispatcher) {
        downloadManifestDao.deleteManifestById(id) > 0
    }

    override suspend fun clearManifestHistory(): Int = withContext(ioDispatcher) {
        downloadManifestDao.deleteAllManifest()
    }

    override suspend fun countManifest(): Int = withContext(ioDispatcher) {
        downloadManifestDao.countManifest()
    }

    override suspend fun recordEnqueued(
        taskId: String,
        originalUrl: String,
        fileName: String,
        fileSize: Long,
        mediaType: MediaType
    ) = withContext(ioDispatcher) {
        // Idempotency: avoid duplicate rows if already recorded
        val existing = downloadManifestDao.getManifestByTaskId(taskId)
        if (existing != null) return@withContext

        val redactedUrl = MediaUrlSanitizer.sanitize(originalUrl)
        val entity = DownloadManifestEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            sourceUrlRedacted = redactedUrl,
            fileName = fileName,
            fileSize = fileSize,
            mediaType = mediaType,
            destinationPath = null,
            status = ManifestStatus.QUEUED,
            bytesReceived = 0L,
            resumed = false,
            errorCategory = null,
            errorMessage = null,
            checksumSha256 = null,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            attemptCount = 1,
            schemaVersion = 1
        )
        downloadManifestDao.insertManifest(entity)
    }

    override suspend fun recordStarted(taskId: String) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        val updated = existing.copy(
            status = ManifestStatus.DOWNLOADING
        )
        downloadManifestDao.updateManifest(updated)
    }

    override suspend fun recordProgress(taskId: String, bytesReceived: Long) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        // Reject progress events on completed or cancelled manifest records
        if (existing.status == ManifestStatus.COMPLETED || existing.status == ManifestStatus.CANCELLED) {
            return@withContext
        }
        if (bytesReceived > existing.bytesReceived) {
            val updated = existing.copy(
                bytesReceived = bytesReceived
            )
            downloadManifestDao.updateManifest(updated)
        }
    }

    override suspend fun recordCompleted(
        taskId: String,
        destinationPath: String,
        bytesReceived: Long,
        resumed: Boolean,
        checksumSha256: String?
    ) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        // Idempotency: if already completed, do not re-update
        if (existing.status == ManifestStatus.COMPLETED) {
            return@withContext
        }
        // Do not resurrect cancelled tasks
        if (existing.status == ManifestStatus.CANCELLED) {
            return@withContext
        }
        val updated = existing.copy(
            destinationPath = destinationPath,
            bytesReceived = bytesReceived,
            status = ManifestStatus.COMPLETED,
            resumed = resumed,
            checksumSha256 = checksumSha256,
            completedAt = System.currentTimeMillis(),
            errorCategory = null,
            errorMessage = null
        )
        downloadManifestDao.updateManifest(updated)
    }

    override suspend fun recordFailed(
        taskId: String,
        errorCategory: ManifestErrorCategory,
        errorMessage: String,
        bytesReceived: Long
    ) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        // A completed task must never be marked failed by a delayed or stale worker event
        if (existing.status == ManifestStatus.COMPLETED) {
            return@withContext
        }
        val sanitizedError = com.tera.down.core.model.AppError.redactSensitive(errorMessage)
        val updated = existing.copy(
            status = ManifestStatus.FAILED,
            errorCategory = errorCategory.name,
            errorMessage = sanitizedError,
            bytesReceived = if (bytesReceived > 0L) bytesReceived else existing.bytesReceived,
            completedAt = System.currentTimeMillis()
        )
        downloadManifestDao.updateManifest(updated)
    }

    override suspend fun recordCancelled(taskId: String, bytesReceived: Long) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        if (existing.status == ManifestStatus.COMPLETED) {
            return@withContext
        }
        val updated = existing.copy(
            status = ManifestStatus.CANCELLED,
            bytesReceived = if (bytesReceived > 0L) bytesReceived else existing.bytesReceived,
            completedAt = System.currentTimeMillis()
        )
        downloadManifestDao.updateManifest(updated)
    }

    override suspend fun recordInterrupted(taskId: String, bytesReceived: Long) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        val updated = existing.copy(
            status = ManifestStatus.INTERRUPTED,
            errorMessage = "Download interrupted",
            errorCategory = ManifestErrorCategory.UNKNOWN.name,
            bytesReceived = if (bytesReceived > 0L) bytesReceived else existing.bytesReceived,
            completedAt = System.currentTimeMillis()
        )
        downloadManifestDao.updateManifest(updated)
    }

    override suspend fun recordRetrying(taskId: String) = withContext(ioDispatcher) {
        val existing = downloadManifestDao.getManifestByTaskId(taskId) ?: return@withContext
        val updated = existing.copy(
            status = ManifestStatus.QUEUED,
            attemptCount = existing.attemptCount + 1,
            errorCategory = null,
            errorMessage = null,
            completedAt = null
        )
        downloadManifestDao.updateManifest(updated)
    }

    override suspend fun reconcileInterruptedTasks() = withContext(ioDispatcher) {
        val all = downloadManifestDao.getAllManifest()
        val now = System.currentTimeMillis()
        for (item in all) {
            if (item.status == ManifestStatus.DOWNLOADING) {
                downloadManifestDao.updateManifest(
                    item.copy(
                        status = ManifestStatus.INTERRUPTED,
                        errorMessage = "Interrupted due to app process termination",
                        errorCategory = ManifestErrorCategory.UNKNOWN.name,
                        completedAt = now
                    )
                )
            }
        }
    }

    private fun DownloadManifestEntity.toDomain(): DownloadManifestItem = DownloadManifestItem(
        id = id,
        taskId = taskId,
        sourceUrlRedacted = sourceUrlRedacted,
        fileName = fileName,
        fileSize = fileSize,
        mediaType = mediaType,
        destinationPath = destinationPath,
        status = status,
        bytesReceived = bytesReceived,
        resumed = resumed,
        errorCategory = errorCategory,
        errorMessage = errorMessage,
        checksumSha256 = checksumSha256,
        createdAt = createdAt,
        completedAt = completedAt,
        attemptCount = attemptCount,
        schemaVersion = schemaVersion
    )
}
