package com.tera.down

import com.tera.down.core.database.dao.DownloadTaskDao
import com.tera.down.core.database.dao.LibraryMediaDao
import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DaoUnitTest {

    private lateinit var downloadTaskDao: DownloadTaskDao
    private lateinit var libraryMediaDao: LibraryMediaDao

    @Before
    fun setup() {
        downloadTaskDao = FakeDownloadTaskDao()
        libraryMediaDao = FakeLibraryMediaDao()
    }

    @Test
    fun insertAndObserveDownloadTasks() = runBlocking {
        val task = DownloadTaskEntity(
            id = "task_test_1",
            originalUrl = "https://terabox.com/s/1abc",
            surl = "1abc",
            fsId = "9001",
            name = "TestVideo.mp4",
            relPath = "TestVideo.mp4",
            size = 50000000L,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            mediaType = MediaType.VIDEO,
            category = 1,
            destinationPath = null,
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            md5 = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        downloadTaskDao.insertTask(task)

        val tasks = downloadTaskDao.observeAllTasks().first()
        assertEquals(1, tasks.size)
        assertEquals("task_test_1", tasks[0].id)
        assertEquals("TestVideo.mp4", tasks[0].name)
        assertEquals(DownloadStatus.QUEUED, tasks[0].status)
    }

    @Test
    fun updateDownloadTaskStatusAndProgress() = runBlocking {
        val task = DownloadTaskEntity(
            id = "task_test_2",
            originalUrl = "https://terabox.com/s/1abc",
            surl = "1abc",
            fsId = "9002",
            name = "TestClip.mp4",
            relPath = "TestClip.mp4",
            size = 1000000L,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            mediaType = MediaType.VIDEO,
            category = 1,
            destinationPath = null,
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            md5 = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        downloadTaskDao.insertTask(task)

        // Update to downloading with progress
        val updated = task.copy(
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 500000L,
            speedBytesPerSec = 120000L,
            updatedAt = 2000L
        )
        downloadTaskDao.updateTask(updated)

        val fetched = downloadTaskDao.getTaskById("task_test_2")
        assertNotNull(fetched)
        assertEquals(DownloadStatus.DOWNLOADING, fetched?.status)
        assertEquals(500000L, fetched?.downloadedBytes)
        assertEquals(120000L, fetched?.speedBytesPerSec)
    }

    @Test
    fun duplicateActiveTaskPrevention() = runBlocking {
        val task1 = DownloadTaskEntity(
            id = "task_active_1",
            originalUrl = "https://terabox.com/s/1xyz",
            surl = "1xyz",
            fsId = "5555",
            name = "ActiveFile.mp4",
            relPath = "ActiveFile.mp4",
            size = 1000L,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            mediaType = MediaType.VIDEO,
            category = 1,
            destinationPath = null,
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            md5 = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        downloadTaskDao.insertTask(task1)

        // Check active task for the same surl and fsId
        val active = downloadTaskDao.getActiveTask("1xyz", "5555")
        assertNotNull(active)
        assertEquals("task_active_1", active?.id)

        // For completed or cancelled task, active query returns null
        downloadTaskDao.updateTask(task1.copy(status = DownloadStatus.COMPLETED))
        val activeAfterCompleted = downloadTaskDao.getActiveTask("1xyz", "5555")
        assertNull(activeAfterCompleted)
    }

    @Test
    fun resetFailedTasks_onlyAffectsFailedTasks() = runBlocking {
        val failedTask = DownloadTaskEntity(
            id = "task_failed",
            originalUrl = "https://terabox.com/s/1abc",
            surl = "1abc",
            fsId = "1",
            name = "Failed.mp4",
            relPath = "Failed.mp4",
            size = 1000L,
            downloadedBytes = 100L,
            speedBytesPerSec = 0L,
            mediaType = MediaType.VIDEO,
            category = 1,
            destinationPath = null,
            status = DownloadStatus.FAILED,
            errorMessage = "Connection timeout",
            md5 = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val activeTask = DownloadTaskEntity(
            id = "task_active",
            originalUrl = "https://terabox.com/s/1abc",
            surl = "1abc",
            fsId = "2",
            name = "Active.mp4",
            relPath = "Active.mp4",
            size = 2000L,
            downloadedBytes = 500L,
            speedBytesPerSec = 100L,
            mediaType = MediaType.VIDEO,
            category = 1,
            destinationPath = null,
            status = DownloadStatus.DOWNLOADING,
            errorMessage = null,
            md5 = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        downloadTaskDao.insertTask(failedTask)
        downloadTaskDao.insertTask(activeTask)

        val resetCount = downloadTaskDao.resetFailedTasks()
        assertEquals(1, resetCount)

        val updatedFailed = downloadTaskDao.getTaskById("task_failed")
        assertEquals(DownloadStatus.QUEUED, updatedFailed?.status)
        assertNull(updatedFailed?.errorMessage)

        val untouchedActive = downloadTaskDao.getTaskById("task_active")
        assertEquals(DownloadStatus.DOWNLOADING, untouchedActive?.status)
    }

    @Test
    fun clearCompletedTasks_doesNotDeleteLibraryRecords() = runBlocking {
        val completedTask = DownloadTaskEntity(
            id = "task_done",
            originalUrl = "https://terabox.com/s/1abc",
            surl = "1abc",
            fsId = "300",
            name = "SavedVideo.mp4",
            relPath = "SavedVideo.mp4",
            size = 5000L,
            downloadedBytes = 5000L,
            speedBytesPerSec = 0L,
            mediaType = MediaType.VIDEO,
            category = 1,
            destinationPath = "/storage/emulated/0/Download/Teradown/SavedVideo.mp4",
            status = DownloadStatus.COMPLETED,
            errorMessage = null,
            md5 = null,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val libraryEntry = LibraryMediaEntity(
            id = "lib_entry_1",
            taskId = "task_done",
            name = "SavedVideo.mp4",
            path = "/storage/emulated/0/Download/Teradown/SavedVideo.mp4",
            size = 5000L,
            mediaType = MediaType.VIDEO,
            durationMs = null,
            dateAdded = 2000L,
            fsId = "300",
            surl = "1abc"
        )

        downloadTaskDao.insertTask(completedTask)
        libraryMediaDao.insertMedia(libraryEntry)

        // When completed tasks are cleared
        val deletedCount = downloadTaskDao.deleteCompletedTasks()
        assertEquals(1, deletedCount)

        // Then task is removed from queue
        assertNull(downloadTaskDao.getTaskById("task_done"))

        // But library entry is 100% preserved
        val preservedLibraryItem = libraryMediaDao.getMediaById("lib_entry_1")
        assertNotNull(preservedLibraryItem)
        assertEquals("SavedVideo.mp4", preservedLibraryItem?.name)
    }

    @Test
    fun libraryMedia_filterByType() = runBlocking {
        val video = LibraryMediaEntity(
            id = "vid_1",
            taskId = null,
            name = "Vid.mp4",
            path = "/path/Vid.mp4",
            size = 100L,
            mediaType = MediaType.VIDEO,
            durationMs = 5000L,
            dateAdded = 1000L
        )
        val photo = LibraryMediaEntity(
            id = "photo_1",
            taskId = null,
            name = "Pic.jpg",
            path = "/path/Pic.jpg",
            size = 50L,
            mediaType = MediaType.PHOTO,
            durationMs = null,
            dateAdded = 1100L
        )

        libraryMediaDao.insertMedia(video)
        libraryMediaDao.insertMedia(photo)

        val all = libraryMediaDao.observeAllMedia().first()
        assertEquals(2, all.size)

        val videosOnly = libraryMediaDao.observeMediaByType(MediaType.VIDEO).first()
        assertEquals(1, videosOnly.size)
        assertEquals("Vid.mp4", videosOnly[0].name)

        val photosOnly = libraryMediaDao.observeMediaByType(MediaType.PHOTO).first()
        assertEquals(1, photosOnly.size)
        assertEquals("Pic.jpg", photosOnly[0].name)
    }

    @Test
    fun manifestDao_insertObserveUpdateAndDelete() = runBlocking {
        val manifestDao = FakeDownloadManifestDao()
        val entry = com.tera.down.core.database.entity.DownloadManifestEntity(
            id = "m-1",
            taskId = "t-1",
            sourceUrlRedacted = "https://example.com/share",
            fileName = "vid.mp4",
            fileSize = 1000L,
            mediaType = MediaType.VIDEO,
            destinationPath = null,
            status = com.tera.down.core.manifest.ManifestStatus.QUEUED,
            bytesReceived = 0L,
            resumed = false,
            createdAt = 1000L
        )

        manifestDao.insertManifest(entry)
        val all = manifestDao.observeAllManifest().first()
        assertEquals(1, all.size)
        assertEquals("m-1", all[0].id)

        val updated = entry.copy(
            status = com.tera.down.core.manifest.ManifestStatus.COMPLETED,
            bytesReceived = 1000L,
            completedAt = 2000L
        )
        manifestDao.updateManifest(updated)
        val fetched = manifestDao.getManifestByTaskId("t-1")
        assertEquals(com.tera.down.core.manifest.ManifestStatus.COMPLETED, fetched?.status)
        assertEquals(1000L, fetched?.bytesReceived)

        val deleted = manifestDao.deleteManifestById("m-1")
        assertEquals(1, deleted)
        assertEquals(0, manifestDao.countManifest())
    }
}
