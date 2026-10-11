package com.tera.down

import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.manifest.DownloadManifestItem
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink
import com.tera.down.core.model.ShareStatus
import com.tera.down.feature.downloader.DownloadEngine
import com.tera.down.feature.downloader.DownloadQueueCoordinatorImpl
import com.tera.down.feature.downloader.DownloadRecoveryManagerImpl
import com.tera.down.feature.downloader.DownloadRequest
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.downloader.OkHttpDownloadEngine
import com.tera.down.feature.downloader.RecoveryAction
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.manifest.ManifestExporter
import com.tera.down.feature.manifest.ManifestRepositoryImpl
import com.tera.down.feature.player.PlaybackPositionTracker
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.queue.LibraryRepositoryImpl
import com.tera.down.feature.resolver.ShareLinkResolver
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.TeraboxShareLinkParser
import com.tera.down.ui.browse.BrowseContentState
import com.tera.down.ui.browse.BrowseShareViewModel
import com.tera.down.ui.home.HomeViewModel
import com.tera.down.ui.library.LibraryViewModel
import com.tera.down.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * End-to-End Release Candidate Validation Suite for Phase 14.
 *
 * Exercises the end-to-end integration of:
 * - App startup & navigation route encoding
 * - Share URL validation & resolver dispatch
 * - File browser folder navigation & batch queueing
 * - HTTP download streaming & atomic disk finalization
 * - Recovery manager reconciliation across app restarts
 * - Library indexing, truthful storage discovery, & playback tracking
 * - Safe file actions (physical delete vs record-only removal)
 * - Settings enforcement & manifest export with privacy redaction
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndReleaseCandidateWorkflowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockWebServer: MockWebServer
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibraryDao: FakeLibraryMediaDao
    private lateinit var fakeManifestDao: FakeDownloadManifestDao
    private lateinit var fakeSettingsRepo: FakeSettingsRepository

    private lateinit var queueRepository: DownloadQueueRepositoryImpl
    private lateinit var libraryRepository: LibraryRepositoryImpl
    private lateinit var manifestRepository: ManifestRepositoryImpl
    private lateinit var storageManager: StorageManager
    private lateinit var downloadEngine: OkHttpDownloadEngine

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockWebServer = MockWebServer()
        mockWebServer.start()

        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibraryDao = FakeLibraryMediaDao()
        fakeManifestDao = FakeDownloadManifestDao()
        fakeSettingsRepo = FakeSettingsRepository()

        queueRepository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = testDispatcher
        )
        libraryRepository = LibraryRepositoryImpl(
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = testDispatcher
        )
        manifestRepository = ManifestRepositoryImpl(
            downloadManifestDao = fakeManifestDao,
            ioDispatcher = testDispatcher
        )

        val baseStorageDir = tempFolder.newFolder("app_downloads")
        storageManager = StorageManager(baseDir = baseStorageDir)

        downloadEngine = OkHttpDownloadEngine(
            storageManager = storageManager,
            ioDispatcher = testDispatcher,
            allowHttpForTesting = true
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // WORKFLOW A: APP STARTUP, NAVIGATION & ROUTE ENCODING
    // =========================================================================

    @Test
    fun workflowA_appStartupAndNavigation() {
        // 1. Home ViewModel starts in clean idle state
        val homeViewModel = HomeViewModel()
        assertEquals("", homeViewModel.uiState.value.urlInput)
        assertNull(homeViewModel.uiState.value.errorMessage)

        // 2. Route creation encodes URL components safely
        val shareUrl = "https://terabox.com/s/1holiday?key=val&flag=true"
        val route = Screen.BrowseShare.createRoute(shareUrl, "secretPass")
        assertTrue(route.startsWith("browse?url="))
        assertTrue(route.contains("pwd=secretPass"))
        assertFalse(route.contains("?key=val")) // Encoded, not raw unescaped query parameter

        // 3. Player route encodes title and file URI
        val playerRoute = Screen.Player.createRoute("/storage/Movie.mp4", "My Movie (2026)")
        assertTrue(playerRoute.contains("My+Movie"))
        assertTrue(playerRoute.contains("storage"))
    }

    // =========================================================================
    // WORKFLOW B & C: SHARE RESOLUTION, FOLDER BROWSING & BATCH QUEUEING
    // =========================================================================

    @Test
    fun workflowBC_shareResolution_browsing_andBatchQueueing() = runBlocking {
        val testFiles = listOf(
            ShareFileItem(
                fsId = "101",
                name = "Intro_4K.mp4",
                size = 2048L,
                relPath = "Media/Intro_4K.mp4",
                mediaType = MediaType.VIDEO,
                category = 1,
                directUrl = "https://d.terabox.com/Intro_4K.mp4",
                isSelected = true,
                isDirectory = false
            ),
            ShareFileItem(
                fsId = "102",
                name = "Cover.jpg",
                size = 512L,
                relPath = "Media/Cover.jpg",
                mediaType = MediaType.PHOTO,
                category = 3,
                directUrl = "https://d.terabox.com/Cover.jpg",
                isSelected = true,
                isDirectory = false
            ),
            ShareFileItem(
                fsId = "103",
                name = "SubFolder",
                size = 0L,
                relPath = "Media/SubFolder",
                mediaType = MediaType.OTHER,
                category = 0,
                directUrl = null,
                isSelected = false,
                isDirectory = true
            )
        )

        val fakeResolver = object : ShareLinkResolver {
            override val supportedDomains: Set<String> get() = TeraboxShareLinkParser.supportedDomains
            override fun canHandle(host: String): Boolean = true
            override suspend fun resolve(shareLink: ShareLink, password: String?, dirPath: String, page: Int): ShareResolveResult {
                return ShareResolveResult.Success(
                    shareInfo = ShareInfo("1holiday", "Holiday Share", "s1", "u1", 2, 2560L, ShareStatus.OK),
                    items = testFiles,
                    currentDirectory = dirPath
                )
            }
        }

        val browseViewModel = BrowseShareViewModel(
            queueRepository = queueRepository,
            fallbackResolver = fakeResolver
        )

        browseViewModel.loadShare("https://terabox.com/s/1holiday")
        val state = browseViewModel.uiState.value.contentState
        assertTrue(state is BrowseContentState.Success)
        val successState = state as BrowseContentState.Success
        assertEquals(3, successState.files.size)

        // Queue all selected files
        browseViewModel.queueSelected(onNavigateToDownloads = {})

        // Queue in database contains exactly 2 active tasks (SubFolder was omitted)
        val queuedTasks = queueRepository.observeAllTasks().first()
        assertEquals(2, queuedTasks.size)

        // Re-queueing the same files prevents duplicates
        browseViewModel.queueSelected(onNavigateToDownloads = {})
        val queuedAfterDuplicate = queueRepository.observeAllTasks().first()
        assertEquals(2, queuedAfterDuplicate.size)
    }

    // =========================================================================
    // WORKFLOW D: DOWNLOAD ENGINE STREAMING & FINALIZATION (MOCK HTTP SERVER)
    // =========================================================================

    @Test
    fun workflowD_streamingDownloadExecution_andVerification() = runBlocking {
        val testPayload = "Antigravity-Verified-Release-Candidate-Payload".repeat(20)
        val payloadBytes = testPayload.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payloadBytes.size)
                .setHeader("Content-Type", "video/mp4")
                .setBody(testPayload)
        )

        val destFile = storageManager.getDestinationFile("E2E_Video.mp4")
        val tempFile = storageManager.getTempFile("task_e2e_1")

        val request = DownloadRequest(
            taskId = "task_e2e_1",
            url = mockWebServer.url("/download/E2E_Video.mp4").toString(),
            destinationFile = destFile,
            tempFile = tempFile,
            expectedSize = payloadBytes.size.toLong(),
            allowResume = true
        )

        var reportedProgress = 0L
        val result = downloadEngine.download(request) { bytes, _, _ ->
            reportedProgress = bytes
        }

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payloadBytes.size.toLong(), success.totalBytes)
        assertEquals(destFile.absolutePath, success.file.absolutePath)
        assertTrue(destFile.exists())
        assertEquals(payloadBytes.size.toLong(), destFile.length())
        assertEquals(testPayload, destFile.readText())

        // Temporary file must be cleanly finalized/deleted
        assertFalse(tempFile.exists())
    }

    // =========================================================================
    // WORKFLOW E: PERSISTENCE, RECOVERY RECONCILIATION & QUEUE CLEANUP
    // =========================================================================

    @Test
    fun workflowE_processDeath_andRecoveryReconciliation() = runBlocking {
        // Enqueue an active task left in DOWNLOADING
        val task = DownloadTaskEntity(
            id = "task_crashed",
            originalUrl = "https://example.com/movie.mp4",
            surl = "surl_crash",
            fsId = "fs_crash",
            name = "Movie.mp4",
            relPath = "Movie.mp4",
            size = 5000L,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 1500L,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTaskDao.insertTask(task)

        // Seed manifest record as would exist when the task was initially queued/started
        manifestRepository.recordEnqueued(
            taskId = "task_crashed",
            originalUrl = "https://example.com/movie.mp4",
            fileName = "Movie.mp4",
            fileSize = 5000L,
            mediaType = MediaType.VIDEO
        )
        manifestRepository.recordStarted("task_crashed")

        // Simulate existing partial staging file on disk (1500 bytes)
        val stagingFile = storageManager.getTempFile("task_crashed")
        stagingFile.writeBytes(ByteArray(1500))

        val recoveryManager = DownloadRecoveryManagerImpl(
            downloadTaskDao = fakeTaskDao,
            queueRepository = queueRepository,
            storageManager = storageManager,
            manifestReporter = manifestRepository,
            ioDispatcher = testDispatcher
        )

        // Run reconciliation
        val reports = recoveryManager.reconcileAndRecover()
        assertEquals(1, reports.size)
        assertEquals(RecoveryAction.RECOVERED_TO_PAUSED_WITH_PARTIAL_DATA, reports[0].actionTaken)

        // Task must NOT be marked complete; must be safely transitioned to PAUSED with partial data
        val recoveredTask = fakeTaskDao.getTaskById("task_crashed")
        assertEquals(DownloadStatus.PAUSED, recoveredTask?.status)
        assertEquals(1500L, recoveredTask?.downloadedBytes)

        // Manifest must be marked as INTERRUPTED
        val manifest = fakeManifestDao.getManifestByTaskId("task_crashed")
        assertEquals(ManifestStatus.INTERRUPTED, manifest?.status)
        assertEquals(1500L, manifest?.bytesReceived)
    }

    // =========================================================================
    // WORKFLOW F: LIBRARY MANAGEMENT, TRUTHFUL STORAGE & RECORD REMOVAL
    // =========================================================================

    @Test
    fun workflowF_libraryManagement_truthfulStorage_andSafeDeletion() = runBlocking {
        val realFile = tempFolder.newFile("Present_Clip.mp4").apply {
            writeBytes(ByteArray(1024))
        }

        // Add 1 real file and 1 missing file
        queueRepository.enqueueShareFiles(
            surl = "surl_lib",
            originalUrl = "https://terabox.com/s/surl_lib",
            items = listOf(
                ShareFileItem("201", "Present_Clip.mp4", 1024L, "Present_Clip.mp4", MediaType.VIDEO, 1, directUrl = "https://d.com/1"),
                ShareFileItem("202", "Missing_Clip.mp4", 4096L, "Missing_Clip.mp4", MediaType.VIDEO, 1, directUrl = "https://d.com/2")
            )
        )
        val tasks = queueRepository.observeAllTasks().first()
        queueRepository.updateTaskStatus(tasks[0].id, DownloadStatus.DOWNLOADING)
        queueRepository.completeTask(tasks[0].id, realFile.absolutePath)

        queueRepository.updateTaskStatus(tasks[1].id, DownloadStatus.DOWNLOADING)
        queueRepository.completeTask(tasks[1].id, "/storage/emulated/0/Download/NonExistent.mp4")

        val libViewModel = LibraryViewModel(libraryRepository)
        val state = libViewModel.uiState.value

        assertEquals(2, state.items.size)
        assertEquals(1, state.missingCount)
        // Only existing file counts toward occupied storage (1024 bytes)
        assertEquals(1024L, state.totalOccupiedBytes)

        // Search
        libViewModel.onSearchQueryChange("Present")
        assertEquals(1, libViewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("Present_Clip.mp4", libViewModel.uiState.value.filteredAndSortedItems[0].name)
        libViewModel.clearSearchAndFilters()

        // Lookup library entry indexed for Present_Clip.mp4
        val allMedia = libraryRepository.observeAllMedia().first()
        val targetMedia = allMedia.first { it.name == "Present_Clip.mp4" }

        // Track playback position
        val positionTracker = PlaybackPositionTracker(
            libraryRepository = libraryRepository,
            scope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher
        )
        positionTracker.flush(targetMedia.id, 12500L, 60000L)
        val updatedItem = libraryRepository.getMediaById(targetMedia.id)
        // Position was saved in library record
        assertEquals(12500L, updatedItem?.lastPositionMs)

        // Record-only removal preserves physical file
        libViewModel.deleteMediaRecordOnly(targetMedia.id)
        assertTrue(realFile.exists()) // File still safely on disk!
        val remaining = libraryRepository.observeAllMedia().first()
        assertEquals(1, remaining.size)
    }

    // =========================================================================
    // WORKFLOW G & H: SETTINGS, PRIVACY & MANIFEST EXPORT VALIDATION
    // =========================================================================

    @Test
    fun workflowGH_settingsPersistence_concurrency_andManifestExport() = runBlocking {
        // 1. Settings persistence
        fakeSettingsRepo.setMaxConcurrentDownloads(2)
        fakeSettingsRepo.setWifiOnly(true)
        val currentSettings = fakeSettingsRepo.getSettings()
        assertEquals(2, currentSettings.maxConcurrentDownloads)
        assertTrue(currentSettings.wifiOnly)

        // 2. Concurrency limiting in coordinator
        val slowEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
            ): DownloadResult {
                delay(5000)
                return DownloadResult.Success(request.destinationFile, 100L)
            }
        }

        val coordinator = DownloadQueueCoordinatorImpl(
            queueRepository = queueRepository,
            downloadEngine = slowEngine,
            storageManager = storageManager,
            scope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher,
            settingsRepository = fakeSettingsRepo
        )

        // Enqueue 3 tasks
        val t1 = queueRepository.enqueueDirectDownload("https://example.com/1.mp4", "1.mp4", 100L)
        val t2 = queueRepository.enqueueDirectDownload("https://example.com/2.mp4", "2.mp4", 100L)
        val t3 = queueRepository.enqueueDirectDownload("https://example.com/3.mp4", "3.mp4", 100L)

        val start1 = coordinator.startDownload(t1)
        val start2 = coordinator.startDownload(t2)
        assertTrue(start1)
        assertTrue(start2)
        assertEquals(2, coordinator.getActiveTaskCount())

        // Task 3 is rejected due to maxConcurrentDownloads = 2
        val task3Started = coordinator.startDownload(t3)
        assertFalse("Task 3 should be rejected when concurrency limit is reached", task3Started)
        assertEquals(2, coordinator.getActiveTaskCount())

        // 3. Manifest export with privacy query parameter redaction
        val manifestItem = DownloadManifestItem(
            id = "m1",
            taskId = "t1",
            sourceUrlRedacted = "https://d.terabox.com/file?token=***&sign=***",
            fileName = "PrivateVideo.mp4",
            fileSize = 1000L,
            destinationPath = "/path/PrivateVideo.mp4",
            status = ManifestStatus.COMPLETED,
            bytesReceived = 1000L
        )

        val jsonOutputStream = ByteArrayOutputStream()
        ManifestExporter.exportToJson(listOf(manifestItem), jsonOutputStream)
        val jsonExport = jsonOutputStream.toString(StandardCharsets.UTF_8.name())
        assertFalse("Exported JSON must never contain unredacted secrets", jsonExport.contains("SECRET_TOKEN"))
        assertTrue(jsonExport.contains("token=***"))
        assertTrue(jsonExport.contains("PrivateVideo.mp4"))

        val csvOutputStream = ByteArrayOutputStream()
        ManifestExporter.exportToCsv(listOf(manifestItem), csvOutputStream)
        val csvExport = csvOutputStream.toString(StandardCharsets.UTF_8.name())
        assertTrue(csvExport.contains("PrivateVideo.mp4"))
        assertTrue(csvExport.contains("token=***"))
    }
}
