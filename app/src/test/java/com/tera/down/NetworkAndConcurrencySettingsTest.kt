package com.tera.down

import com.tera.down.core.model.DownloadStatus
import com.tera.down.feature.downloader.DownloadEngine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkAndConcurrencySettingsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var fakeManifestDao: FakeDownloadManifestDao
    private lateinit var queueRepo: DownloadQueueRepositoryImpl
    private lateinit var manifestRepo: ManifestRepositoryImpl
    private lateinit var storageManager: StorageManager
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var connectivityMonitor: FakeNetworkConnectivityMonitor
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
        tempDir = File(root, "net_concurrency_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        storageManager = StorageManager(baseDir = tempDir)

        settingsRepo = FakeSettingsRepository()
        connectivityMonitor = FakeNetworkConnectivityMonitor(initialConnected = true, initialWifi = true)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun concurrencyLimit_enforcedAccordingToSettings() = runBlocking {
        // Configure concurrency limit to 1
        settingsRepo.setMaxConcurrentDownloads(1)

        val task1 = queueRepo.enqueueDirectDownload("https://example.com/file1.mp4", "file1.mp4", 1000L)
        val task2 = queueRepo.enqueueDirectDownload("https://example.com/file2.mp4", "file2.mp4", 1000L)

        val slowEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                delay(1000)
                return DownloadResult.Success(request.destinationFile, 1000L)
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = slowEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo,
            settingsRepository = settingsRepo,
            networkConnectivityMonitor = connectivityMonitor
        )

        // Start first download -> Succeeds
        val start1 = coordinator.startDownload(task1)
        assertTrue(start1)
        assertEquals(1, coordinator.getActiveTaskCount())

        // Start second download while limit of 1 is reached -> Rejected
        val start2 = coordinator.startDownload(task2)
        assertFalse(start2)
        assertEquals(1, coordinator.getActiveTaskCount())
    }

    @Test
    fun wifiOnly_blocksStartingOnCellularData() = runBlocking {
        // Enable Wi-Fi only mode
        settingsRepo.setWifiOnly(true)
        // Simulate mobile data (connected, but not Wi-Fi)
        connectivityMonitor.setWifi(false)
        connectivityMonitor.setConnected(true)

        val taskId = queueRepo.enqueueDirectDownload("https://example.com/mobile.mp4", "mobile.mp4", 5000L)

        val fastEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult = DownloadResult.Success(request.destinationFile, 5000L)
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = fastEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo,
            settingsRepository = settingsRepo,
            networkConnectivityMonitor = connectivityMonitor
        )

        // Attempting to download on cellular must be blocked
        val started = coordinator.startDownload(taskId)
        assertFalse(started)
        assertEquals(0, coordinator.getActiveTaskCount())

        // Task should be updated with truthful waiting state
        val task = queueRepo.getTaskById(taskId)
        assertEquals(DownloadStatus.PAUSED, task?.status)
        assertTrue(task?.errorMessage?.contains("Waiting for Wi-Fi") == true)
    }

    @Test
    fun wifiOnly_allowsStartingWhenWifiIsConnected() = runBlocking {
        settingsRepo.setWifiOnly(true)
        connectivityMonitor.setWifi(true)

        val taskId = queueRepo.enqueueDirectDownload("https://example.com/wifi_ok.mp4", "wifi_ok.mp4", 2000L)

        val fastEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult = DownloadResult.Success(request.destinationFile, 2000L)
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = fastEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo,
            settingsRepository = settingsRepo,
            networkConnectivityMonitor = connectivityMonitor
        )

        val started = coordinator.startDownload(taskId)
        assertTrue(started)

        val task = queueRepo.getTaskById(taskId)
        assertEquals(DownloadStatus.COMPLETED, task?.status)
    }

    @Test
    fun wifiLoss_pausesActiveDownloadsCooperatively() = runBlocking {
        settingsRepo.setWifiOnly(true)
        connectivityMonitor.setWifi(true)

        val taskId = queueRepo.enqueueDirectDownload("https://example.com/stream.mp4", "stream.mp4", 10000L)

        val slowEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                delay(2000)
                return DownloadResult.Success(request.destinationFile, 10000L)
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepo,
            downloadEngine = slowEngine,
            storageManager = storageManager,
            scope = testScope,
            ioDispatcher = testDispatcher,
            manifestReporter = manifestRepo,
            settingsRepository = settingsRepo,
            networkConnectivityMonitor = connectivityMonitor
        )

        coordinator.startDownload(taskId)
        assertTrue(coordinator.isDownloading(taskId))

        // Wi-Fi disconnects mid-transfer
        connectivityMonitor.setWifi(false)

        // Coordinator cooperatively pauses active transfers
        assertFalse(coordinator.isDownloading(taskId))
        val task = queueRepo.getTaskById(taskId)
        assertEquals(DownloadStatus.PAUSED, task?.status)
        assertTrue(task?.errorMessage?.contains("Wi-Fi disconnected") == true)
    }
}
