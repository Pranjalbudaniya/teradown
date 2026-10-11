package com.tera.down

import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.queue.LibraryRepository
import com.tera.down.feature.queue.LibraryRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadQueueRepositoryTest {

    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibraryDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepository
    private lateinit var libraryRepository: LibraryRepository

    @Before
    fun setup() {
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibraryDao = FakeLibraryMediaDao()

        queueRepository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = Dispatchers.Unconfined
        )
        libraryRepository = LibraryRepositoryImpl(
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun enqueueShareFiles_createsQueuedTasks_andPreventsDuplicateActive() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "101",
                name = "Video1.mp4",
                size = 1000000L,
                relPath = "Video1.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            ),
            ShareFileItem(
                fsId = "102",
                name = "Unsupported.zip",
                size = 500000L,
                relPath = "Unsupported.zip",
                mediaType = MediaType.OTHER,
                category = 6
            )
        )

        val taskIds = queueRepository.enqueueShareFiles(
            surl = "surl123",
            originalUrl = "https://terabox.com/s/surl123",
            items = files
        )

        // Only supported media type is enqueued
        assertEquals(1, taskIds.size)

        val tasks = queueRepository.observeAllTasks().first()
        assertEquals(1, tasks.size)
        assertEquals("Video1.mp4", tasks[0].name)
        assertEquals(DownloadStatus.QUEUED, tasks[0].status)

        // Duplicate active task prevention: enqueuing the exact same file returns empty list
        val duplicateTaskIds = queueRepository.enqueueShareFiles(
            surl = "surl123",
            originalUrl = "https://terabox.com/s/surl123",
            items = files
        )
        assertTrue(duplicateTaskIds.isEmpty())

        // Queue count remains 1
        assertEquals(1, queueRepository.observeAllTasks().first().size)
    }

    @Test
    fun pauseAndResumeLifecycle() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "201",
                name = "Clip.mp4",
                size = 5000000L,
                relPath = "Clip.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = queueRepository.enqueueShareFiles("surl_pause", "https://terabox.com/s/surl_pause", files)
        val taskId = taskIds.first()

        // Transition to DOWNLOADING first
        queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)
        val downloadingTask = queueRepository.getTaskById(taskId)
        assertEquals(DownloadStatus.DOWNLOADING, downloadingTask?.status)

        // Pause
        val pauseResult = queueRepository.pauseTask(taskId)
        assertTrue(pauseResult)
        val pausedTask = queueRepository.getTaskById(taskId)
        assertEquals(DownloadStatus.PAUSED, pausedTask?.status)
        assertEquals(0L, pausedTask?.speedBytesPerSec)

        // Resume (transitions to QUEUED)
        val resumeResult = queueRepository.resumeTask(taskId)
        assertTrue(resumeResult)
        val resumedTask = queueRepository.getTaskById(taskId)
        assertEquals(DownloadStatus.QUEUED, resumedTask?.status)
    }

    @Test
    fun completeTask_updatesQueueAndIndexesIntoLibrary() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "301",
                name = "FinishedPhoto.jpg",
                size = 250000L,
                relPath = "Photos/FinishedPhoto.jpg",
                mediaType = MediaType.PHOTO,
                category = 3
            )
        )
        val taskIds = queueRepository.enqueueShareFiles("surl_done", "https://terabox.com/s/surl_done", files)
        val taskId = taskIds.first()

        // Move to DOWNLOADING
        queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)

        val destination = "/storage/emulated/0/Download/Teradown/FinishedPhoto.jpg"
        val completeResult = queueRepository.completeTask(taskId, destination)
        assertTrue(completeResult)

        // Check task is completed in queue
        val completedTask = queueRepository.getTaskById(taskId)
        assertEquals(DownloadStatus.COMPLETED, completedTask?.status)
        assertEquals(completedTask?.size, completedTask?.downloadedBytes)

        // Check entry exists in Library
        val libraryItems = libraryRepository.observeAllMedia().first()
        assertEquals(1, libraryItems.size)
        assertEquals("FinishedPhoto.jpg", libraryItems[0].name)
        assertEquals(destination, libraryItems[0].path)
        assertEquals(MediaType.PHOTO, libraryItems[0].mediaType)
    }

    @Test
    fun clearCompletedTasks_removesFromQueue_keepsLibraryMediaUntouched() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "401",
                name = "VideoToClear.mp4",
                size = 1000L,
                relPath = "VideoToClear.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = queueRepository.enqueueShareFiles("surl_clear", "https://terabox.com/s/surl_clear", files)
        val taskId = taskIds.first()

        queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)
        queueRepository.completeTask(taskId, "/path/VideoToClear.mp4")

        // 1 completed task in queue, 1 in library
        assertEquals(1, queueRepository.observeAllTasks().first().size)
        assertEquals(1, libraryRepository.observeAllMedia().first().size)

        // Clear completed tasks
        val cleared = queueRepository.clearCompletedTasks()
        assertEquals(1, cleared)

        // Queue is now empty
        assertTrue(queueRepository.observeAllTasks().first().isEmpty())

        // Library media is PRESERVED!
        val libraryItems = libraryRepository.observeAllMedia().first()
        assertEquals(1, libraryItems.size)
        assertEquals("VideoToClear.mp4", libraryItems[0].name)
    }

    @Test
    fun retryFailedTasks_resetsState() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "501",
                name = "FailingVideo.mp4",
                size = 2000L,
                relPath = "FailingVideo.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = queueRepository.enqueueShareFiles("surl_fail", "https://terabox.com/s/surl_fail", files)
        val taskId = taskIds.first()

        queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)
        queueRepository.updateTaskStatus(taskId, DownloadStatus.FAILED, "HTTP 403 Expired")

        val failedTask = queueRepository.getTaskById(taskId)
        assertEquals(DownloadStatus.FAILED, failedTask?.status)
        assertEquals("HTTP 403 Expired", failedTask?.errorMessage)

        // Retry single
        val retryResult = queueRepository.retryTask(taskId)
        assertTrue(retryResult)

        val retriedTask = queueRepository.getTaskById(taskId)
        assertEquals(DownloadStatus.QUEUED, retriedTask?.status)
        assertNull(retriedTask?.errorMessage)
    }

    @Test
    fun progressUpdates_areConstrainedCorrectly() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "601",
                name = "SizedVideo.mp4",
                size = 1000L,
                relPath = "SizedVideo.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = queueRepository.enqueueShareFiles("surl_prog", "https://terabox.com/s/surl_prog", files)
        val taskId = taskIds.first()

        // Update progress beyond total size
        queueRepository.updateProgress(taskId, 1500L, 50000L)

        val task = queueRepository.getTaskById(taskId)
        assertNotNull(task)
        // Must be clamped to 1000L
        assertEquals(1000L, task?.downloadedBytes)
        assertEquals(1.0f, task?.progress ?: 0f, 0.001f)
    }

    @Test
    fun persistenceAcrossRepositoryRecreation() = runBlocking {
        // Shared backing store simulating persistent storage engine
        val sharedTaskStore = mutableMapOf<String, DownloadTaskEntity>()
        val sharedLibStore = mutableMapOf<String, LibraryMediaEntity>()

        // 1. First repository instance adds task
        val repo1 = DownloadQueueRepositoryImpl(
            downloadTaskDao = FakeDownloadTaskDao(sharedStore = sharedTaskStore),
            libraryMediaDao = FakeLibraryMediaDao(sharedStore = sharedLibStore),
            ioDispatcher = Dispatchers.Unconfined
        )

        val files = listOf(
            ShareFileItem(
                fsId = "701",
                name = "PersistentTask.mp4",
                size = 8000L,
                relPath = "PersistentTask.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = repo1.enqueueShareFiles("surl_persist", "https://terabox.com/s/surl_persist", files)
        assertEquals(1, taskIds.size)

        // 2. Second repository instance recreated against the persistent store
        val repo2 = DownloadQueueRepositoryImpl(
            downloadTaskDao = FakeDownloadTaskDao(sharedStore = sharedTaskStore),
            libraryMediaDao = FakeLibraryMediaDao(sharedStore = sharedLibStore),
            ioDispatcher = Dispatchers.Unconfined
        )

        val retrievedTasks = repo2.observeAllTasks().first()
        assertEquals(1, retrievedTasks.size)
        assertEquals("PersistentTask.mp4", retrievedTasks[0].name)
        assertEquals(DownloadStatus.QUEUED, retrievedTasks[0].status)
    }
}
