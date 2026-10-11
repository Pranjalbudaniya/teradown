package com.tera.down

import com.tera.down.core.database.dao.DownloadTaskDao
import com.tera.down.core.database.dao.LibraryMediaDao
import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDownloadTaskDao(
    initialTasks: List<DownloadTaskEntity> = emptyList(),
    private val sharedStore: MutableMap<String, DownloadTaskEntity>? = null
) : DownloadTaskDao {

    private val tasks = MutableStateFlow<Map<String, DownloadTaskEntity>>(
        sharedStore ?: initialTasks.associateBy { it.id }
    )

    override fun observeAllTasks(): Flow<List<DownloadTaskEntity>> {
        return tasks.map { map ->
            map.values.sortedByDescending { it.createdAt }
        }
    }

    override fun observeTasksByStatus(status: DownloadStatus): Flow<List<DownloadTaskEntity>> {
        return tasks.map { map ->
            map.values.filter { it.status == status }.sortedByDescending { it.createdAt }
        }
    }

    override fun observeActiveTasks(): Flow<List<DownloadTaskEntity>> {
        return tasks.map { map ->
            map.values.filter {
                it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.PAUSED
            }.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun getTaskById(id: String): DownloadTaskEntity? {
        return tasks.value[id]
    }

    override suspend fun getActiveTask(surl: String, fsId: String): DownloadTaskEntity? {
        return tasks.value.values.firstOrNull {
            it.surl == surl && it.fsId == fsId && (
                it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.PAUSED
            )
        }
    }

    override suspend fun getAllTasks(): List<DownloadTaskEntity> {
        return tasks.value.values.sortedByDescending { it.createdAt }
    }

    override suspend fun insertTask(task: DownloadTaskEntity) {
        val current = tasks.value.toMutableMap()
        current[task.id] = task
        sharedStore?.put(task.id, task)
        tasks.value = current
    }

    override suspend fun insertTasks(tasks: List<DownloadTaskEntity>) {
        val current = this.tasks.value.toMutableMap()
        for (task in tasks) {
            current[task.id] = task
            sharedStore?.put(task.id, task)
        }
        this.tasks.value = current
    }

    override suspend fun updateTask(task: DownloadTaskEntity) {
        val current = tasks.value.toMutableMap()
        current[task.id] = task
        sharedStore?.put(task.id, task)
        tasks.value = current
    }

    override suspend fun deleteTaskById(id: String): Int {
        val current = tasks.value.toMutableMap()
        val removed = current.remove(id)
        sharedStore?.remove(id)
        tasks.value = current
        return if (removed != null) 1 else 0
    }

    override suspend fun deleteCompletedTasks(): Int {
        val current = tasks.value.toMutableMap()
        val completedKeys = current.filterValues { it.status == DownloadStatus.COMPLETED }.keys
        completedKeys.forEach {
            current.remove(it)
            sharedStore?.remove(it)
        }
        tasks.value = current
        return completedKeys.size
    }

    override suspend fun resetFailedTasks(timestamp: Long): Int {
        val current = tasks.value.toMutableMap()
        var count = 0
        current.forEach { (id, task) ->
            if (task.status == DownloadStatus.FAILED) {
                val updated = task.copy(
                    status = DownloadStatus.QUEUED,
                    errorMessage = null,
                    updatedAt = timestamp
                )
                current[id] = updated
                sharedStore?.put(id, updated)
                count++
            }
        }
        tasks.value = current
        return count
    }

    override suspend fun countActiveTasks(): Int {
        return tasks.value.values.count {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
    }

    override suspend fun getQueuedTasks(): List<DownloadTaskEntity> {
        return tasks.value.values.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.createdAt }
    }
}

class FakeLibraryMediaDao(
    initialMedia: List<LibraryMediaEntity> = emptyList(),
    private val sharedStore: MutableMap<String, LibraryMediaEntity>? = null
) : LibraryMediaDao {

    private val media = MutableStateFlow<Map<String, LibraryMediaEntity>>(
        sharedStore ?: initialMedia.associateBy { it.id }
    )

    override fun observeAllMedia(): Flow<List<LibraryMediaEntity>> {
        return media.map { map ->
            map.values.sortedByDescending { it.dateAdded }
        }
    }

    override fun observeMediaByType(mediaType: MediaType): Flow<List<LibraryMediaEntity>> {
        return media.map { map ->
            map.values.filter { it.mediaType == mediaType }.sortedByDescending { it.dateAdded }
        }
    }

    override suspend fun getAllMedia(): List<LibraryMediaEntity> {
        return media.value.values.sortedByDescending { it.dateAdded }
    }

    override suspend fun getMediaById(id: String): LibraryMediaEntity? {
        return media.value[id]
    }

    override suspend fun getMediaByPath(path: String): LibraryMediaEntity? {
        return media.value.values.firstOrNull { it.path == path }
    }

    override suspend fun insertMedia(media: LibraryMediaEntity) {
        val current = this.media.value.toMutableMap()
        current[media.id] = media
        sharedStore?.put(media.id, media)
        this.media.value = current
    }

    override suspend fun insertMediaList(mediaList: List<LibraryMediaEntity>) {
        val current = this.media.value.toMutableMap()
        for (item in mediaList) {
            current[item.id] = item
            sharedStore?.put(item.id, item)
        }
        this.media.value = current
    }

    override suspend fun deleteMedia(media: LibraryMediaEntity) {
        deleteMediaById(media.id)
    }

    override suspend fun deleteMediaById(id: String): Int {
        val current = media.value.toMutableMap()
        val removed = current.remove(id)
        sharedStore?.remove(id)
        media.value = current
        return if (removed != null) 1 else 0
    }

    override suspend fun updatePlaybackPosition(id: String, positionMs: Long): Int {
        val current = media.value.toMutableMap()
        val item = current[id] ?: return 0
        val updated = item.copy(lastPositionMs = positionMs)
        current[id] = updated
        sharedStore?.put(id, updated)
        media.value = current
        return 1
    }

    override suspend fun getMediaByTaskId(taskId: String): LibraryMediaEntity? {
        return media.value.values.firstOrNull { it.taskId == taskId }
    }

    override suspend fun updateDuration(id: String, durationMs: Long): Int {
        val current = media.value.toMutableMap()
        val item = current[id] ?: return 0
        val updated = item.copy(durationMs = durationMs)
        current[id] = updated
        sharedStore?.put(id, updated)
        media.value = current
        return 1
    }

    override suspend fun updateNameAndPath(id: String, newName: String, newPath: String): Int {
        val current = media.value.toMutableMap()
        val item = current[id] ?: return 0
        val updated = item.copy(name = newName, path = newPath)
        current[id] = updated
        sharedStore?.put(id, updated)
        media.value = current
        return 1
    }
}

class FakeDownloadManifestDao(
    initialManifest: List<com.tera.down.core.database.entity.DownloadManifestEntity> = emptyList(),
    private val sharedStore: MutableMap<String, com.tera.down.core.database.entity.DownloadManifestEntity>? = null
) : com.tera.down.core.database.dao.DownloadManifestDao {

    private val manifest = MutableStateFlow<Map<String, com.tera.down.core.database.entity.DownloadManifestEntity>>(
        sharedStore ?: initialManifest.associateBy { it.id }
    )

    override fun observeAllManifest(): Flow<List<com.tera.down.core.database.entity.DownloadManifestEntity>> {
        return manifest.map { map ->
            map.values.sortedByDescending { it.createdAt }
        }
    }

    override fun observeManifestByStatus(status: com.tera.down.core.manifest.ManifestStatus): Flow<List<com.tera.down.core.database.entity.DownloadManifestEntity>> {
        return manifest.map { map ->
            map.values.filter { it.status == status }.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun getAllManifest(): List<com.tera.down.core.database.entity.DownloadManifestEntity> {
        return manifest.value.values.sortedByDescending { it.createdAt }
    }

    override suspend fun getManifestById(id: String): com.tera.down.core.database.entity.DownloadManifestEntity? {
        return manifest.value[id]
    }

    override suspend fun getManifestByTaskId(taskId: String): com.tera.down.core.database.entity.DownloadManifestEntity? {
        return manifest.value.values.firstOrNull { it.taskId == taskId }
    }

    override suspend fun insertManifest(entity: com.tera.down.core.database.entity.DownloadManifestEntity) {
        val current = manifest.value.toMutableMap()
        current[entity.id] = entity
        sharedStore?.put(entity.id, entity)
        manifest.value = current
    }

    override suspend fun updateManifest(entity: com.tera.down.core.database.entity.DownloadManifestEntity) {
        val current = manifest.value.toMutableMap()
        current[entity.id] = entity
        sharedStore?.put(entity.id, entity)
        manifest.value = current
    }

    override suspend fun deleteManifestById(id: String): Int {
        val current = manifest.value.toMutableMap()
        val removed = current.remove(id)
        sharedStore?.remove(id)
        manifest.value = current
        return if (removed != null) 1 else 0
    }

    override suspend fun deleteAllManifest(): Int {
        val current = manifest.value.toMutableMap()
        val count = current.size
        current.clear()
        sharedStore?.clear()
        manifest.value = current
        return count
    }

    override suspend fun countManifest(): Int {
        return manifest.value.size
    }
}
