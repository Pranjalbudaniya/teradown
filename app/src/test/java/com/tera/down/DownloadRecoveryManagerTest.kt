package com.tera.down

import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import com.tera.down.feature.downloader.DownloadQueueCoordinator
import com.tera.down.feature.downloader.DownloadRecoveryManagerImpl
import com.tera.down.feature.downloader.RecoveryAction
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.manifest.ManifestRepositoryImpl
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRecoveryManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var fakeManifestDao: FakeDownloadManifestDao
    private lateinit var queueRepo: DownloadQueueRepositoryImpl
    private lateinit var manifestRepo: ManifestRepositoryImpl
    private lateinit var storageManager: StorageManager
    private lateinit var tempBaseDir: File

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
        tempBaseDir = File(root, "recovery_test_${System.currentTimeMillis()}")
        tempBaseDir.mkdirs()
        storageManager = StorageManager(baseDir = tempBaseDir)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    private fun insertTask(
        id: String,
        name: String,
        status: DownloadStatus,
        size: Long = 10000L,
        downloadedBytes: Long = 0L,
        destinationPath: String? = null
    ) {
        val task = DownloadTaskEntity(
            id = id,
            originalUrl = "https://example.com/$name",
            surl = "surl-$id",
            fsId = "fs-$id",
            name = name,
            relPath = name,
            size = size,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = 0L,
            mediaType = MediaType.VIDEO,
            destinationPath = destinationPath,
            status = status,
            errorMessage = null,
            md5 = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        kotlinx.coroutines.runBlocking {
            fakeTaskDao.insertTask(task)
            manifestRepo.recordEnqueued(id, task.originalUrl, name, size, MediaType.VIDEO)
        }
    }

    @Test
    fun reconcile_recoversInterruptedTaskWithPartialData() = runTest(testDispatcher) {
        insertTask(id = "t-partial", name = "video_partial.mp4", status = DownloadStatus.DOWNLOADING, size = 10000L)

        // Simulate partial 4000-byte temporary staging file
        val tempFile = storageManager.getTempFile("t-partial")
        tempFile.writeBytes(ByteArray(4000))

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepo,
            storageManager = storageManager,
            coordinator = null,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        val reports = recoveryManager.reconcileAndRecover()
        assertEquals(1, reports.size)
        val report = reports[0]
        assertEquals("t-partial", report.taskId)
        assertEquals(RecoveryAction.RECOVERED_TO_PAUSED_WITH_PARTIAL_DATA, report.actionTaken)
        assertEquals(DownloadStatus.PAUSED, report.newStatus)
        assertEquals(4000L, report.partialBytesRecovered)

        // Queue entity updated to PAUSED with 4000 downloadedBytes
        val updated = fakeTaskDao.getTaskById("t-partial")
        assertNotNull(updated)
        assertEquals(DownloadStatus.PAUSED, updated?.status)
        assertEquals(4000L, updated?.downloadedBytes)

        // Manifest record reconciled to INTERRUPTED
        val manifest = manifestRepo.getManifestByTaskId("t-partial")
        assertEquals(ManifestStatus.INTERRUPTED, manifest?.status)
    }

    @Test
    fun reconcile_recoversInterruptedTaskWithoutPartialData() = runTest(testDispatcher) {
        insertTask(id = "t-nopartial", name = "video_none.mp4", status = DownloadStatus.DOWNLOADING, size = 10000L)

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepo,
            storageManager = storageManager,
            coordinator = null,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        val reports = recoveryManager.reconcileAndRecover()
        assertEquals(1, reports.size)
        assertEquals(RecoveryAction.RECOVERED_TO_PAUSED_NO_PARTIAL_DATA, reports[0].actionTaken)

        val updated = fakeTaskDao.getTaskById("t-nopartial")
        assertEquals(DownloadStatus.PAUSED, updated?.status)
        assertEquals(0L, updated?.downloadedBytes)

        val manifest = manifestRepo.getManifestByTaskId("t-nopartial")
        assertEquals(ManifestStatus.INTERRUPTED, manifest?.status)
    }

    @Test
    fun reconcile_verifiesCompleteWhenDestinationFileMatchesSize() = runTest(testDispatcher) {
        val destFile = File(storageManager.downloadDirectory, "finished.mp4")
        destFile.writeBytes(ByteArray(5000))

        insertTask(
            id = "t-done",
            name = "finished.mp4",
            status = DownloadStatus.DOWNLOADING,
            size = 5000L,
            destinationPath = destFile.absolutePath
        )

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepo,
            storageManager = storageManager,
            coordinator = null,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        val reports = recoveryManager.reconcileAndRecover()
        assertEquals(1, reports.size)
        assertEquals(RecoveryAction.VERIFIED_COMPLETED_FROM_FINAL_FILE, reports[0].actionTaken)

        val updated = fakeTaskDao.getTaskById("t-done")
        assertEquals(DownloadStatus.COMPLETED, updated?.status)

        val manifest = manifestRepo.getManifestByTaskId("t-done")
        assertEquals(ManifestStatus.COMPLETED, manifest?.status)

        // Indexed in library
        val libMedia = fakeLibDao.getAllMedia()
        assertEquals(1, libMedia.size)
        assertEquals("finished.mp4", libMedia[0].name)
    }

    @Test
    fun reconcile_skipsActivelyDownloadingTasks() = runTest(testDispatcher) {
        insertTask(id = "t-active", name = "active.mp4", status = DownloadStatus.DOWNLOADING, size = 10000L)

        val mockCoordinator = object : DownloadQueueCoordinator {
            override fun isDownloading(taskId: String): Boolean = taskId == "t-active"
            override fun startDownload(taskId: String): Boolean = true
            override fun pauseDownload(taskId: String): Boolean = true
            override fun cancelDownload(taskId: String): Boolean = true
            override fun retryDownload(taskId: String): Boolean = true
            override fun getActiveTaskCount(): Int = 1
            override fun addProgressListener(listener: com.tera.down.feature.downloader.DownloadProgressListener) {}
            override fun removeProgressListener(listener: com.tera.down.feature.downloader.DownloadProgressListener) {}
        }

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepo,
            storageManager = storageManager,
            coordinator = mockCoordinator,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        val reports = recoveryManager.reconcileAndRecover()
        assertEquals(1, reports.size)
        assertEquals(RecoveryAction.SKIPPED_ACTIVE_TRANSFER, reports[0].actionTaken)

        val task = fakeTaskDao.getTaskById("t-active")
        assertEquals(DownloadStatus.DOWNLOADING, task?.status) // Untouched
    }

    @Test
    fun reconcile_doesNotDeleteUnknownFilesInStorage() = runTest(testDispatcher) {
        val unknownFile = File(storageManager.downloadDirectory, "external_user_file.mp4")
        unknownFile.writeText("Pre-existing user content")

        val unknownTemp = File(storageManager.tempDirectory, "unrelated_temp.tmp")
        unknownTemp.writeText("Other temp data")

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepo,
            storageManager = storageManager,
            coordinator = null,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        recoveryManager.reconcileAndRecover()

        // Conservative policy: files must remain untouched
        assertTrue(unknownFile.exists())
        assertTrue(unknownTemp.exists())
    }

    @Test
    fun reconcile_isIdempotentWhenRunMultipleTimes() = runTest(testDispatcher) {
        insertTask(id = "t-idem", name = "idem.mp4", status = DownloadStatus.DOWNLOADING, size = 10000L)
        val tempFile = storageManager.getTempFile("t-idem")
        tempFile.writeBytes(ByteArray(2500))

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepo,
            storageManager = storageManager,
            coordinator = null,
            manifestReporter = manifestRepo,
            ioDispatcher = testDispatcher
        )

        // First execution recovers task to PAUSED
        val firstReports = recoveryManager.reconcileAndRecover()
        assertEquals(RecoveryAction.RECOVERED_TO_PAUSED_WITH_PARTIAL_DATA, firstReports[0].actionTaken)

        // Second execution finds task already in stable PAUSED state
        val secondReports = recoveryManager.reconcileAndRecover()
        assertEquals(RecoveryAction.NO_ACTION_NEEDED, secondReports[0].actionTaken)

        val task = fakeTaskDao.getTaskById("t-idem")
        assertEquals(DownloadStatus.PAUSED, task?.status)
        assertEquals(2500L, task?.downloadedBytes)
    }
}
