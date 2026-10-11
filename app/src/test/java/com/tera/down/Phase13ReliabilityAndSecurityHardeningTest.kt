package com.tera.down

import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.manifest.ManifestErrorCategory
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.AppError
import com.tera.down.core.model.AppErrorCode
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.network.SafeRedirectInterceptor
import com.tera.down.feature.downloader.DownloadErrorType
import com.tera.down.feature.downloader.DownloadRequest
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.downloader.DownloadRetryPolicy
import com.tera.down.feature.downloader.DownloadUrlValidator
import com.tera.down.feature.downloader.FilenameSanitizer
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.manifest.ManifestRepositoryImpl
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.queue.DownloadQueueStateValidator
import com.tera.down.feature.resolver.ShareResolveErrorType
import com.tera.down.feature.resolver.ShareResolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class Phase13ReliabilityAndSecurityHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibraryDao: FakeLibraryMediaDao
    private lateinit var fakeManifestDao: FakeDownloadManifestDao
    private lateinit var queueRepository: DownloadQueueRepositoryImpl
    private lateinit var manifestRepository: ManifestRepositoryImpl

    @Before
    fun setup() {
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibraryDao = FakeLibraryMediaDao()
        fakeManifestDao = FakeDownloadManifestDao()

        queueRepository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = testDispatcher
        )
        manifestRepository = ManifestRepositoryImpl(
            downloadManifestDao = fakeManifestDao,
            ioDispatcher = testDispatcher
        )
    }

    // =========================================================================
    // 1. STATE TRANSITION & STALE WORKER HARDENING
    // =========================================================================

    @Test
    fun invalidStateTransitions_areSafelyRejected() {
        // Direct jump from QUEUED to COMPLETED is rejected
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.QUEUED, DownloadStatus.COMPLETED))

        // CANCELLED task cannot jump to DOWNLOADING or COMPLETED
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.CANCELLED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.CANCELLED, DownloadStatus.COMPLETED))

        // FAILED task cannot jump to COMPLETED
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.FAILED, DownloadStatus.COMPLETED))

        // COMPLETED is terminal: cannot transition to FAILED, PAUSED, CANCELLED, DOWNLOADING
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.FAILED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.PAUSED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED))
    }

    @Test
    fun staleProgressEvents_areRejected_whenTaskNotInDownloadingState() = runBlocking {
        val task = DownloadTaskEntity(
            id = "task_stale_prog",
            originalUrl = "https://example.com/video.mp4",
            surl = "surl1",
            fsId = "fs1",
            name = "video.mp4",
            relPath = "video.mp4",
            size = 1000L,
            status = DownloadStatus.PAUSED, // Paused by user!
            downloadedBytes = 200L,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTaskDao.insertTask(task)

        // Delayed worker in background attempts to emit progress update
        val updated = queueRepository.updateProgress("task_stale_prog", 500L, 100000L)
        assertFalse("Progress update must be rejected when task is PAUSED", updated)

        // Verify task values remain untouched
        val retrieved = fakeTaskDao.getTaskById("task_stale_prog")
        assertEquals(200L, retrieved?.downloadedBytes)
        assertEquals(0L, retrieved?.speedBytesPerSec)
        assertEquals(DownloadStatus.PAUSED, retrieved?.status)
    }

    @Test
    fun staleCompletionEvent_rejectedIfTaskWasCancelled() = runBlocking {
        val task = DownloadTaskEntity(
            id = "task_cancelled_race",
            originalUrl = "https://example.com/video.mp4",
            surl = "surl2",
            fsId = "fs2",
            name = "video.mp4",
            relPath = "video.mp4",
            size = 1000L,
            status = DownloadStatus.CANCELLED, // Cancelled by user while downloading
            downloadedBytes = 0L,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTaskDao.insertTask(task)

        // Delayed completion invocation from outdated worker
        val completed = queueRepository.completeTask("task_cancelled_race", "/path/video.mp4")
        assertFalse("Outdated worker must not mark a CANCELLED task as COMPLETED", completed)

        val retrieved = fakeTaskDao.getTaskById("task_cancelled_race")
        assertEquals(DownloadStatus.CANCELLED, retrieved?.status)
    }

    @Test
    fun completeTask_isIdempotent_whenAlreadyCompleted() = runBlocking {
        val task = DownloadTaskEntity(
            id = "task_idemp",
            originalUrl = "https://example.com/video.mp4",
            surl = "surl3",
            fsId = "fs3",
            name = "video.mp4",
            relPath = "video.mp4",
            size = 1000L,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 1000L,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTaskDao.insertTask(task)

        // First completion
        val first = queueRepository.completeTask("task_idemp", "/path/video.mp4")
        assertTrue(first)

        // Second completion (worker retry or duplicate event)
        val second = queueRepository.completeTask("task_idemp", "/path/video.mp4")
        assertTrue("Subsequent completion should succeed idempotently", second)

        val retrieved = fakeTaskDao.getTaskById("task_idemp")
        assertEquals(DownloadStatus.COMPLETED, retrieved?.status)
    }

    // =========================================================================
    // 2. MANIFEST IDEMPOTENCY & STALE OVERWRITE PROTECTION
    // =========================================================================

    @Test
    fun manifest_staleEventsDoNotOverwriteCompletedRecord() = runBlocking {
        manifestRepository.recordEnqueued("task_m1", "https://example.com/v.mp4?token=SECRET", "v.mp4", 1000L, MediaType.VIDEO)
        manifestRepository.recordStarted("task_m1")
        manifestRepository.recordCompleted("task_m1", "/path/v.mp4", 1000L, resumed = false)

        // Stale progress after completion
        manifestRepository.recordProgress("task_m1", 500L)
        // Stale failure after completion
        manifestRepository.recordFailed("task_m1", ManifestErrorCategory.NETWORK_ERROR, "Socket closed")

        val manifest = fakeManifestDao.getManifestByTaskId("task_m1")
        assertNotNull(manifest)
        assertEquals(ManifestStatus.COMPLETED, manifest?.status)
        assertEquals(1000L, manifest?.bytesReceived)
        assertNull(manifest?.errorMessage)
    }

    @Test
    fun manifest_cannotResurrectCancelledTaskAsCompleted() = runBlocking {
        manifestRepository.recordEnqueued("task_m2", "https://example.com/v.mp4", "v.mp4", 1000L, MediaType.VIDEO)
        manifestRepository.recordCancelled("task_m2", 150L)

        // Stale worker tries to mark cancelled task as completed
        manifestRepository.recordCompleted("task_m2", "/path/v.mp4", 1000L, resumed = false)

        val manifest = fakeManifestDao.getManifestByTaskId("task_m2")
        assertEquals(ManifestStatus.CANCELLED, manifest?.status)
        assertEquals(150L, manifest?.bytesReceived)
    }

    // =========================================================================
    // 3. URL AND NETWORK SECURITY
    // =========================================================================

    @Test
    fun urlValidator_rejectsNonHttpsAndMalformedSchemes() {
        assertFalse(DownloadUrlValidator.isValidUrl("http://insecure.example.com/file.mp4"))
        assertFalse(DownloadUrlValidator.isValidUrl("file:///etc/passwd"))
        assertFalse(DownloadUrlValidator.isValidUrl("content://media/external/images/1"))
        assertFalse(DownloadUrlValidator.isValidUrl("javascript:alert(1)"))
        assertFalse(DownloadUrlValidator.isValidUrl(""))
        assertFalse(DownloadUrlValidator.isValidUrl(null))

        assertTrue(DownloadUrlValidator.isValidUrl("https://d.terabox.com/file/video.mp4"))
    }

    @Test
    fun safeRedirectInterceptor_enforcesHttpsAndAllowedHosts() {
        val interceptor = SafeRedirectInterceptor(
            allowedHostsPredicate = { it.endsWith("terabox.com") },
            requireHttps = true
        )

        // 1. Insecure HTTP request is rejected
        val fakeHttpChain = FakeChain(Request.Builder().url("http://terabox.com/api").build())
        try {
            interceptor.intercept(fakeHttpChain)
            fail("Expected IOException on cleartext HTTP")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Cleartext HTTP"))
        }

        // 2. Untrusted host is rejected
        val fakeUntrustedChain = FakeChain(Request.Builder().url("https://malicious-redirect.com/api").build())
        try {
            interceptor.intercept(fakeUntrustedChain)
            fail("Expected IOException on untrusted host")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("untrusted or unsupported host"))
        }
    }

    // =========================================================================
    // 4. FILENAME SANITIZATION & PATH TRAVERSAL
    // =========================================================================

    @Test
    fun filenameSanitizer_protectsAgainstUrlEncodedTraversalAndReservedNames() {
        // Path traversal attempts
        assertEquals("passwd", FilenameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("passwd", FilenameSanitizer.sanitize("..%2f..%2fetc%2fpasswd"))
        assertEquals("passwd", FilenameSanitizer.sanitize("%2e%2e%2fpasswd"))

        // FAT32 / Windows reserved device names
        assertEquals("file_CON.txt", FilenameSanitizer.sanitize("CON.txt"))
        assertEquals("file_nul.mp4", FilenameSanitizer.sanitize("nul.mp4"))
        assertEquals("file_PRN", FilenameSanitizer.sanitize("PRN"))
        assertEquals("file_com1.dat", FilenameSanitizer.sanitize("com1.dat"))

        // Normal files remain intact
        assertEquals("My Holiday Video.mp4", FilenameSanitizer.sanitize("My Holiday Video.mp4"))
    }

    // =========================================================================
    // 5. PRIVACY & SENSITIVE DATA REDACTION
    // =========================================================================

    @Test
    fun appError_redactsSensitiveParameters() {
        val rawUrl = "https://d.terabox.com/file?sign=SECRET_SIGNATURE_KEY&timestamp=12345&auth=TOKEN123"
        val redacted = AppError.redactSensitive(rawUrl)
        assertFalse(redacted.contains("SECRET_SIGNATURE_KEY"))
        assertFalse(redacted.contains("TOKEN123"))
        assertTrue(redacted.contains("sign=***"))
        assertTrue(redacted.contains("auth=***"))

        val bearerHeader = "Failed to authenticate: Bearer eyJhbGciOiJIUzI1NiJ9.secret"
        val redactedBearer = AppError.redactSensitive(bearerHeader)
        assertFalse(redactedBearer.contains("secret"))
        assertTrue(redactedBearer.contains("Bearer ***"))
    }

    // =========================================================================
    // 6. FILE SAFETY & ATOMIC FINALIZATION
    // =========================================================================

    @Test
    fun storageManager_finalizeDownload_protectsExistingFilesWithUniqueNames() {
        val baseDir = tempFolder.newFolder("downloads")
        val storageManager = StorageManager(baseDir = baseDir)

        val destFile = File(baseDir, "clip.mp4").apply { writeBytes(ByteArray(10)) }
        val tempFile = File(storageManager.tempDirectory, "task_t1.tmp").apply { writeBytes(ByteArray(20)) }

        val finalized = storageManager.finalizeDownload(tempFile, destFile)

        // Existing file was preserved
        assertTrue(destFile.exists())
        assertEquals(10L, destFile.length())

        // Finalized file was placed at a non-conflicting unique name
        assertTrue(finalized.exists())
        assertEquals("clip_(1).mp4", finalized.name)
        assertEquals(20L, finalized.length())
        assertFalse(tempFile.exists())
    }

    // =========================================================================
    // 7. ERROR-TO-USER-MESSAGE MAPPING AND RETRY CLASSIFICATION
    // =========================================================================

    @Test
    fun errorMapping_andRetryClassification() {
        // 1. Permanent errors
        val invalidUrlError = AppError.fromDownloadError(
            DownloadResult.Error(DownloadErrorType.INVALID_URL, "bad url")
        )
        assertEquals(AppErrorCode.INVALID_SHARE_LINK, invalidUrlError.code)
        assertFalse(invalidUrlError.isRetryable)

        val expiredResolve = AppError.fromShareResolveResult(
            ShareResolveResult.Expired("Link has expired")
        )
        assertNotNull(expiredResolve)
        assertEquals(AppErrorCode.SHARE_EXPIRED, expiredResolve!!.code)
        assertFalse(expiredResolve.isRetryable)

        val storageError = AppError.fromDownloadError(
            DownloadResult.Error(DownloadErrorType.INSUFFICIENT_SPACE, "No disk space")
        )
        assertEquals(AppErrorCode.INSUFFICIENT_STORAGE, storageError.code)
        assertFalse(storageError.isRetryable)

        // 2. Transient retryable errors
        val rateLimitError = AppError.fromHttpStatus(429, "Too Many Requests")
        assertEquals(AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT, rateLimitError.code)
        assertTrue(rateLimitError.isRetryable)

        val timeoutError = AppError.fromThrowable(java.net.SocketTimeoutException("Read timed out"))
        assertEquals(AppErrorCode.NETWORK_TIMEOUT, timeoutError.code)
        assertTrue(timeoutError.isRetryable)

        val retryPolicy = DownloadRetryPolicy.shouldRetry(
            attemptCount = 1,
            errorType = DownloadErrorType.NETWORK_FAILURE,
            httpStatusCode = 503
        )
        assertTrue(retryPolicy)

        val permanentPolicy = DownloadRetryPolicy.shouldRetry(
            attemptCount = 1,
            errorType = DownloadErrorType.INVALID_URL,
            httpStatusCode = null
        )
        assertFalse(permanentPolicy)
    }

    // Helper fake chain for interceptor testing
    private class FakeChain(private val req: Request) : Interceptor.Chain {
        override fun request(): Request = req
        override fun proceed(request: Request): Response {
            return Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okhttp3.ResponseBody.create(null, "OK"))
                .build()
        }
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun connection(): okhttp3.Connection? = null
        override fun readTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
    }
}
