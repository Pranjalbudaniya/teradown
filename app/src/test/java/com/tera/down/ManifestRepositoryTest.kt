package com.tera.down

import com.tera.down.core.manifest.ManifestErrorCategory
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.MediaType
import com.tera.down.feature.manifest.ManifestRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManifestRepositoryTest {

    private lateinit var fakeManifestDao: FakeDownloadManifestDao
    private lateinit var repository: ManifestRepositoryImpl
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        fakeManifestDao = FakeDownloadManifestDao()
        repository = ManifestRepositoryImpl(
            downloadManifestDao = fakeManifestDao,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun recordEnqueued_createsSanitizedRecordWithStatusQueued() = runTest(testDispatcher) {
        val originalUrl = "https://example.com/share?token=super_secret&pwd=mypassword"
        repository.recordEnqueued(
            taskId = "task-100",
            originalUrl = originalUrl,
            fileName = "movie.mp4",
            fileSize = 50000000L,
            mediaType = MediaType.VIDEO
        )

        val record = repository.getManifestByTaskId("task-100")
        assertNotNull(record)
        assertEquals("task-100", record!!.taskId)
        assertEquals("movie.mp4", record.fileName)
        assertEquals(50000000L, record.fileSize)
        assertEquals(ManifestStatus.QUEUED, record.status)
        assertEquals(1, record.attemptCount)
        assertEquals(0L, record.bytesReceived)
        assertFalse(record.resumed)

        // Sensitive credentials must be masked
        assertFalse(record.sourceUrlRedacted.contains("super_secret"))
        assertFalse(record.sourceUrlRedacted.contains("mypassword"))
        assertTrue(record.sourceUrlRedacted.contains("token=***"))
        assertTrue(record.sourceUrlRedacted.contains("pwd=***"))
    }

    @Test
    fun recordEnqueued_isIdempotentForSameTaskId() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-200",
            originalUrl = "https://example.com/test",
            fileName = "file1.mp4",
            fileSize = 1000L,
            mediaType = MediaType.VIDEO
        )

        // Attempt second enqueue with different name for same task ID
        repository.recordEnqueued(
            taskId = "task-200",
            originalUrl = "https://example.com/test",
            fileName = "file1_duplicate.mp4",
            fileSize = 2000L,
            mediaType = MediaType.VIDEO
        )

        val all = repository.getAllManifest()
        assertEquals(1, all.size)
        assertEquals("file1.mp4", all[0].fileName)
    }

    @Test
    fun lifecycle_started_progress_completed() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-300",
            originalUrl = "https://example.com/media",
            fileName = "clip.mp4",
            fileSize = 10000L,
            mediaType = MediaType.VIDEO
        )

        // 1. Started
        repository.recordStarted("task-300")
        var record = repository.getManifestByTaskId("task-300")
        assertEquals(ManifestStatus.DOWNLOADING, record!!.status)

        // 2. Progress
        repository.recordProgress("task-300", 5000L)
        record = repository.getManifestByTaskId("task-300")
        assertEquals(5000L, record!!.bytesReceived)

        // 3. Completed
        repository.recordCompleted(
            taskId = "task-300",
            destinationPath = "/storage/emulated/0/Download/clip.mp4",
            bytesReceived = 10000L,
            resumed = true,
            checksumSha256 = "abc123sha"
        )
        record = repository.getManifestByTaskId("task-300")
        assertEquals(ManifestStatus.COMPLETED, record!!.status)
        assertEquals("/storage/emulated/0/Download/clip.mp4", record.destinationPath)
        assertEquals(10000L, record.bytesReceived)
        assertTrue(record.resumed)
        assertEquals("abc123sha", record.checksumSha256)
        assertNotNull(record.completedAt)
        assertNull(record.errorMessage)
    }

    @Test
    fun lifecycle_failed_recordsErrorCategoryAndMessage() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-400",
            originalUrl = "https://example.com/bad",
            fileName = "corrupt.mp4",
            fileSize = 20000L,
            mediaType = MediaType.VIDEO
        )
        repository.recordStarted("task-400")
        repository.recordProgress("task-400", 3000L)

        repository.recordFailed(
            taskId = "task-400",
            errorCategory = ManifestErrorCategory.NETWORK_ERROR,
            errorMessage = "Connection timeout after 30000ms",
            bytesReceived = 3000L
        )

        val record = repository.getManifestByTaskId("task-400")
        assertNotNull(record)
        assertEquals(ManifestStatus.FAILED, record!!.status)
        assertEquals(ManifestErrorCategory.NETWORK_ERROR.name, record.errorCategory)
        assertEquals("Connection timeout after 30000ms", record.errorMessage)
        assertEquals(3000L, record.bytesReceived)
        assertNotNull(record.completedAt)
    }

    @Test
    fun lifecycle_cancelled_and_interrupted() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-cancel",
            originalUrl = "https://example.com/cancel",
            fileName = "cancel.mp4",
            fileSize = 10000L,
            mediaType = MediaType.VIDEO
        )
        repository.recordCancelled("task-cancel", 1000L)
        val cancelRecord = repository.getManifestByTaskId("task-cancel")
        assertEquals(ManifestStatus.CANCELLED, cancelRecord!!.status)
        assertEquals(1000L, cancelRecord.bytesReceived)

        repository.recordEnqueued(
            taskId = "task-interrupt",
            originalUrl = "https://example.com/interrupt",
            fileName = "interrupt.mp4",
            fileSize = 10000L,
            mediaType = MediaType.VIDEO
        )
        repository.recordInterrupted("task-interrupt", 2000L)
        val interruptRecord = repository.getManifestByTaskId("task-interrupt")
        assertEquals(ManifestStatus.INTERRUPTED, interruptRecord!!.status)
        assertEquals(2000L, interruptRecord.bytesReceived)
    }

    @Test
    fun retry_incrementsAttemptCountAndClearsErrorState() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-retry",
            originalUrl = "https://example.com/retry",
            fileName = "retry.mp4",
            fileSize = 10000L,
            mediaType = MediaType.VIDEO
        )
        repository.recordFailed(
            taskId = "task-retry",
            errorCategory = ManifestErrorCategory.HTTP_ERROR,
            errorMessage = "HTTP 503 Service Unavailable"
        )

        var record = repository.getManifestByTaskId("task-retry")
        assertEquals(ManifestStatus.FAILED, record!!.status)
        assertEquals(1, record.attemptCount)

        // Retry action
        repository.recordRetrying("task-retry")
        record = repository.getManifestByTaskId("task-retry")
        assertEquals(ManifestStatus.QUEUED, record!!.status)
        assertEquals(2, record.attemptCount)
        assertNull(record.errorMessage)
        assertNull(record.errorCategory)
        assertNull(record.completedAt)
    }

    @Test
    fun reconcileInterruptedTasks_reconcilesDownloadingTasksOnStartup() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-crash",
            originalUrl = "https://example.com/crash",
            fileName = "crash.mp4",
            fileSize = 10000L,
            mediaType = MediaType.VIDEO
        )
        repository.recordStarted("task-crash")
        assertEquals(ManifestStatus.DOWNLOADING, repository.getManifestByTaskId("task-crash")!!.status)

        // Simulate app restart / process termination reconciliation
        repository.reconcileInterruptedTasks()

        val record = repository.getManifestByTaskId("task-crash")
        assertEquals(ManifestStatus.INTERRUPTED, record!!.status)
        assertTrue(record.errorMessage!!.contains("process termination"))
    }

    @Test
    fun clearManifestHistory_clearsRecordsExplicitly() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-1",
            originalUrl = "https://example.com/1",
            fileName = "1.mp4",
            fileSize = 1000L,
            mediaType = MediaType.VIDEO
        )
        repository.recordEnqueued(
            taskId = "task-2",
            originalUrl = "https://example.com/2",
            fileName = "2.mp4",
            fileSize = 2000L,
            mediaType = MediaType.VIDEO
        )

        assertEquals(2, repository.countManifest())
        val deletedCount = repository.clearManifestHistory()
        assertEquals(2, deletedCount)
        assertEquals(0, repository.countManifest())
    }

    @Test
    fun deleteManifestRecord_removesSingleRecord() = runTest(testDispatcher) {
        repository.recordEnqueued(
            taskId = "task-del",
            originalUrl = "https://example.com/del",
            fileName = "del.mp4",
            fileSize = 1000L,
            mediaType = MediaType.VIDEO
        )
        val record = repository.getManifestByTaskId("task-del")!!
        val deleted = repository.deleteManifestRecord(record.id)
        assertTrue(deleted)
        assertNull(repository.getManifestByTaskId("task-del"))
    }

    @Test
    fun observeManifestByStatus_filtersProperly() = runTest(testDispatcher) {
        repository.recordEnqueued("t-1", "https://example.com/1", "1.mp4", 1000L, MediaType.VIDEO)
        repository.recordEnqueued("t-2", "https://example.com/2", "2.mp4", 2000L, MediaType.VIDEO)
        repository.recordCompleted("t-2", "/path/2.mp4", 2000L, false)

        val completedFlow = repository.observeManifestByStatus(ManifestStatus.COMPLETED)
        val completedList = completedFlow.first()
        assertEquals(1, completedList.size)
        assertEquals("t-2", completedList[0].taskId)

        val queuedFlow = repository.observeManifestByStatus(ManifestStatus.QUEUED)
        val queuedList = queuedFlow.first()
        assertEquals(1, queuedList.size)
        assertEquals("t-1", queuedList[0].taskId)
    }
}
