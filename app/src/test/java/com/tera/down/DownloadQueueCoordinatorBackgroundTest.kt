package com.tera.down

import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.DownloadStatus
import com.tera.down.feature.downloader.DownloadEngine
import com.tera.down.feature.downloader.DownloadErrorType
import com.tera.down.feature.downloader.DownloadProgressListener
import com.tera.down.feature.downloader.DownloadQueueCoordinatorImpl
import com.tera.down.feature.downloader.DownloadRequest
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.manifest.ManifestRepositoryImpl
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
class DownloadQueueCoordinatorBackgroundTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var fakeManifestDao: FakeDownloadManifestDao
    private lateinit var queueRepo: DownloadQueueRepositoryImpl
    private lateinit var manifestRepo: ManifestRepositoryImpl
    private lateinit var storageManager: StorageManager
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()
        fakeManifestDao = FakeDownloadManifestDao()

        manifestRepo = ManifestRepositoryImpl(
            downloadManifestDao = fakeManifestDao,
            ioDispatcher = testDispatcher
        )
        queueRepo = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibDao,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo
        )

        val root = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        tempDir = File(root, "coord_bg_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        storageManager = StorageManager(baseDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun duplicateScheduling_preventedWhenAlreadyActive() = runBlocking {
        val taskId = queueRepo.enqueueDirectDownload(
            url = "https://example.com/stream.mp4",
            filename = "stream.mp4",
            size = 5000L
        )

        val slowEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                delay(500)
                return DownloadResult.Success(request.destinationFile, 5000L)
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = slowEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo
        )

        val firstStart = coordinator.startDownload(taskId)
        assertTrue(firstStart)
        assertTrue(coordinator.isDownloading(taskId))

        // Second duplicate start while still active must be rejected
        val duplicateStart = coordinator.startDownload(taskId)
        assertFalse(duplicateStart)
    }

    @Test
    fun cancellation_abortsActiveTransferAndCleansStaging() = runBlocking {
        val taskId = queueRepo.enqueueDirectDownload(
            url = "https://example.com/big_file.mp4",
            filename = "big_file.mp4",
            size = 10000L
        )

        // Write a partial temp staging file
        val temp = storageManager.getTempFile(taskId)
        temp.writeBytes(ByteArray(2000))
        assertTrue(temp.exists())

        var cancelledReported = false
        val listener = object : DownloadProgressListener {
            override fun onDownloadStarted(taskId: String, taskName: String) {}
            override fun onDownloadProgress(taskId: String, taskName: String, downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {}
            override fun onDownloadCompleted(taskId: String, taskName: String, file: File) {}
            override fun onDownloadFailed(taskId: String, taskName: String, errorMessage: String, isPermanent: Boolean) {}
            override fun onDownloadCancelled(taskId: String, taskName: String) {
                cancelledReported = true
            }
        }

        val slowEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                delay(1000)
                return DownloadResult.Cancelled
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = slowEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo
        )
        coordinator.addProgressListener(listener)

        coordinator.startDownload(taskId)
        assertTrue(coordinator.isDownloading(taskId))

        // Cancel
        val cancelled = coordinator.cancelDownload(taskId)
        assertTrue(cancelled)
        assertFalse(coordinator.isDownloading(taskId))

        // Temp file cleaned up
        assertFalse(temp.exists())

        // Listener notified
        assertTrue(cancelledReported)

        // Manifest marked CANCELLED
        val manifest = manifestRepo.getManifestByTaskId(taskId)
        assertEquals(ManifestStatus.CANCELLED, manifest?.status)
    }

    @Test
    fun permanentError_notifiesListenerAndBlocksLoop() = runBlocking {
        val taskId = queueRepo.enqueueDirectDownload(
            url = "https://example.com/notfound.mp4",
            filename = "notfound.mp4",
            size = 1000L
        )

        var listenerPermanentFlag: Boolean? = null
        val listener = object : DownloadProgressListener {
            override fun onDownloadStarted(taskId: String, taskName: String) {}
            override fun onDownloadProgress(taskId: String, taskName: String, downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {}
            override fun onDownloadCompleted(taskId: String, taskName: String, file: File) {}
            override fun onDownloadFailed(taskId: String, taskName: String, errorMessage: String, isPermanent: Boolean) {
                listenerPermanentFlag = isPermanent
            }
            override fun onDownloadCancelled(taskId: String, taskName: String) {}
        }

        val failingEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                return DownloadResult.Error(
                    type = DownloadErrorType.HTTP_ERROR,
                    message = "HTTP 404 Not Found",
                    httpStatusCode = 404,
                    isResumable = false
                )
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = failingEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo
        )
        coordinator.addProgressListener(listener)

        coordinator.startDownload(taskId)

        assertEquals(true, listenerPermanentFlag)
        val task = queueRepo.getTaskById(taskId)
        assertEquals(DownloadStatus.FAILED, task?.status)
    }
}
