package com.tera.down

import com.tera.down.core.model.DownloadStatus
import com.tera.down.feature.downloader.DownloadEngine
import com.tera.down.feature.downloader.DownloadErrorType
import com.tera.down.feature.downloader.DownloadQueueCoordinator
import com.tera.down.feature.downloader.DownloadQueueCoordinatorImpl
import com.tera.down.feature.downloader.DownloadRequest
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.queue.LibraryRepository
import com.tera.down.feature.queue.LibraryRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueCoordinatorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepository
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var storageManager: StorageManager
    private lateinit var tempDir: File

    @Before
    fun setup() {
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()

        queueRepository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibDao,
            ioDispatcher = testDispatcher
        )
        libraryRepository = LibraryRepositoryImpl(
            libraryMediaDao = fakeLibDao,
            ioDispatcher = testDispatcher
        )

        val root = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        tempDir = File(root, "coordinator_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        storageManager = StorageManager(
            baseDir = tempDir
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun startDownload_executesAndCompletesTask() {
        runBlocking {
            val taskId = queueRepository.enqueueDirectDownload(
                url = "https://example.com/movie.mp4",
                filename = "movie.mp4",
                size = 5000L
            )

            val mockEngine = object : DownloadEngine {
                override suspend fun download(
                    request: DownloadRequest,
                    onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
                ): DownloadResult {
                    onProgress(2500L, 5000L, 1000L)
                    val dest = request.destinationFile.apply { writeText("Sample Content") }
                    return DownloadResult.Success(dest, 5000L)
                }
            }

            val coordinator = DownloadQueueCoordinatorImpl(
                queueRepository = queueRepository,
                downloadEngine = mockEngine,
                storageManager = storageManager,
                scope = testScope,
                ioDispatcher = testDispatcher
            )

            val started = coordinator.startDownload(taskId)
            assertTrue(started)

            // Verify task completed in repository
            val task = queueRepository.getTaskById(taskId)
            assertEquals(DownloadStatus.COMPLETED, task?.status)

            // Verify library was indexed
            val libMedia = libraryRepository.observeAllMedia().first()
            assertEquals(1, libMedia.size)
            assertEquals("movie.mp4", libMedia[0].name)
        }
    }

    @Test
    fun duplicateStart_isPrevented() {
        runBlocking {
            val taskId = queueRepository.enqueueDirectDownload(
                url = "https://example.com/clip.mp4",
                filename = "clip.mp4",
                size = 1000L
            )

            val mockEngine = object : DownloadEngine {
                override suspend fun download(
                    request: DownloadRequest,
                    onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
                ): DownloadResult {
                    delay(1000)
                    return DownloadResult.Success(request.destinationFile, 1000L)
                }
            }

            val coordinator = DownloadQueueCoordinatorImpl(
                queueRepository = queueRepository,
                downloadEngine = mockEngine,
                storageManager = storageManager,
                scope = CoroutineScope(Dispatchers.Default),
                ioDispatcher = Dispatchers.Default
            )

            val firstStart = coordinator.startDownload(taskId)
            assertTrue(firstStart)

            // Attempting to start the exact same running task again is rejected
            val duplicateStart = coordinator.startDownload(taskId)
            assertFalse(duplicateStart)

            coordinator.cancelDownload(taskId)
        }
    }

    @Test
    fun pauseDownload_pausesActiveJob() {
        runBlocking {
            val taskId = queueRepository.enqueueDirectDownload(
                url = "https://example.com/long.mp4",
                filename = "long.mp4",
                size = 50000L
            )

            val mockEngine = object : DownloadEngine {
                override suspend fun download(
                    request: DownloadRequest,
                    onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
                ): DownloadResult {
                    delay(2000)
                    return DownloadResult.Success(request.destinationFile, 50000L)
                }
            }

            val coordinator = DownloadQueueCoordinatorImpl(
                queueRepository = queueRepository,
                downloadEngine = mockEngine,
                storageManager = storageManager,
                scope = CoroutineScope(Dispatchers.Default),
                ioDispatcher = Dispatchers.Default
            )

            coordinator.startDownload(taskId)
            delay(50)

            coordinator.pauseDownload(taskId)
            delay(50)

            assertFalse(coordinator.isDownloading(taskId))
            val task = queueRepository.getTaskById(taskId)
            assertEquals(DownloadStatus.PAUSED, task?.status)
        }
    }

    @Test
    fun errorOutcome_updatesTaskToFailed() {
        runBlocking {
            val taskId = queueRepository.enqueueDirectDownload(
                url = "https://example.com/broken.mp4",
                filename = "broken.mp4",
                size = 1000L
            )

            val mockEngine = object : DownloadEngine {
                override suspend fun download(
                    request: DownloadRequest,
                    onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
                ): DownloadResult {
                    return DownloadResult.Error(
                        type = DownloadErrorType.NETWORK_FAILURE,
                        message = "Connection reset by peer"
                    )
                }
            }

            val coordinator = DownloadQueueCoordinatorImpl(
                queueRepository = queueRepository,
                downloadEngine = mockEngine,
                storageManager = storageManager,
                scope = testScope,
                ioDispatcher = testDispatcher
            )

            coordinator.startDownload(taskId)

            val task = queueRepository.getTaskById(taskId)
            assertEquals(DownloadStatus.FAILED, task?.status)
            assertEquals("Connection reset by peer", task?.errorMessage)
        }
    }

    @Test
    fun coordinatorWithManifestReporter_recordsLifecycleAccurately() = runBlocking {
        val fakeManifestDao = FakeDownloadManifestDao()
        val manifestRepo = com.tera.down.feature.manifest.ManifestRepositoryImpl(
            downloadManifestDao = fakeManifestDao,
            ioDispatcher = testDispatcher
        )

        val queueRepoWithManifest = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibDao,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        val taskId = queueRepoWithManifest.enqueueDirectDownload(
            url = "https://example.com/tracked.mp4?token=secret99",
            filename = "tracked.mp4",
            size = 4000L
        )

        // Manifest should have QUEUED record with redacted URL
        val enqueuedRecord = manifestRepo.getManifestByTaskId(taskId)
        assertEquals(com.tera.down.core.manifest.ManifestStatus.QUEUED, enqueuedRecord?.status)
        assertTrue(enqueuedRecord?.sourceUrlRedacted?.contains("token=***") == true)

        val mockEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                val dest = request.destinationFile.apply { writeText("Done") }
                return DownloadResult.Success(dest, 4000L, resumed = true)
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepoWithManifest,
            downloadEngine = mockEngine,
            storageManager = storageManager,
            scope = testScope,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        coordinator.startDownload(taskId)

        val completedRecord = manifestRepo.getManifestByTaskId(taskId)
        assertEquals(com.tera.down.core.manifest.ManifestStatus.COMPLETED, completedRecord?.status)
        assertEquals(4000L, completedRecord?.bytesReceived)
        assertTrue(completedRecord?.resumed == true)
        assertNotNull(completedRecord?.completedAt)
    }
}
