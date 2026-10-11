package com.tera.down

import com.tera.down.feature.downloader.DownloadErrorType
import com.tera.down.feature.downloader.DownloadRequest
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.downloader.OkHttpDownloadEngine
import com.tera.down.feature.downloader.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpDownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var storageManager: StorageManager
    private lateinit var engine: OkHttpDownloadEngine

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        val root = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        tempDir = File(root, "okhttp_engine_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        storageManager = StorageManager(
            baseDir = tempDir
        )

        engine = OkHttpDownloadEngine(
            storageManager = storageManager,
            ioDispatcher = Dispatchers.Unconfined,
            allowHttpForTesting = true
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun successfulStreamingDownload() = runBlocking {
        val payload = "Hello, Teradown streaming download engine!".repeat(50)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.toByteArray().size)
                .setHeader("Content-Type", "video/mp4")
                .setBody(payload)
        )

        val destFile = storageManager.getDestinationFile("test_video.mp4")
        val tempFile = storageManager.getTempFile("task_1")

        val request = DownloadRequest(
            taskId = "task_1",
            url = server.url("/stream/test_video.mp4").toString(),
            destinationFile = destFile,
            tempFile = tempFile
        )

        var progressCalls = 0
        val result = engine.download(request) { bytes, total, _ ->
            progressCalls++
            assertTrue(bytes > 0)
        }

        assertTrue("Expected Success result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payload.toByteArray().size.toLong(), success.totalBytes)
        assertTrue(success.file.exists())
        assertEquals(payload, success.file.readText())
        assertFalse("Temp file should be moved/finalized", tempFile.exists())
        assertTrue("Progress should have been invoked", progressCalls > 0)
    }

    @Test
    fun unknownContentLength_downloadsSuccessfully() = runBlocking {
        val payload = "Chunked transfer streaming data payload"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setChunkedBody(payload, 8)
        )

        val destFile = storageManager.getDestinationFile("chunked.bin")
        val tempFile = storageManager.getTempFile("task_chunked")

        val request = DownloadRequest(
            taskId = "task_chunked",
            url = server.url("/chunked.bin").toString(),
            destinationFile = destFile,
            tempFile = tempFile
        )

        val result = engine.download(request)
        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payload, success.file.readText())
    }

    @Test
    fun http404_returnsHttpError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("File Not Found")
        )

        val destFile = storageManager.getDestinationFile("notfound.bin")
        val tempFile = storageManager.getTempFile("task_404")

        val request = DownloadRequest(
            taskId = "task_404",
            url = server.url("/notfound.bin").toString(),
            destinationFile = destFile,
            tempFile = tempFile
        )

        val result = engine.download(request)
        assertTrue(result is DownloadResult.Error)
        val error = result as DownloadResult.Error
        assertEquals(DownloadErrorType.HTTP_ERROR, error.type)
        assertEquals(404, error.httpStatusCode)
    }

    @Test
    fun http500_returnsServerError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val destFile = storageManager.getDestinationFile("error.bin")
        val tempFile = storageManager.getTempFile("task_500")

        val request = DownloadRequest(
            taskId = "task_500",
            url = server.url("/error.bin").toString(),
            destinationFile = destFile,
            tempFile = tempFile
        )

        val result = engine.download(request)
        assertTrue(result is DownloadResult.Error)
        val error = result as DownloadResult.Error
        assertEquals(DownloadErrorType.SERVER_ERROR, error.type)
        assertEquals(500, error.httpStatusCode)
    }

    @Test
    fun safeResume_whenServerReturns206PartialContent() = runBlocking {
        // Pre-stage existing 10 bytes in temp file
        val tempFile = storageManager.getTempFile("task_resume")
        tempFile.writeText("0123456789")

        val remainingPayload = "ABCDEFGHIJ"
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setHeader("Content-Length", remainingPayload.toByteArray().size)
                .setBody(remainingPayload)
        )

        val destFile = storageManager.getDestinationFile("resumed.txt")
        val request = DownloadRequest(
            taskId = "task_resume",
            url = server.url("/resumed.txt").toString(),
            destinationFile = destFile,
            tempFile = tempFile,
            allowResume = true
        )

        val result = engine.download(request)
        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success

        // Verify request sent Range header
        val recordedRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("bytes=10-", recordedRequest?.getHeader("Range"))

        // Final file has full 20 bytes
        assertEquals(20L, success.totalBytes)
        assertEquals("0123456789ABCDEFGHIJ", success.file.readText())
    }

    @Test
    fun safeResumeFallback_whenServerIgnoresRangeAndReturns200() = runBlocking {
        // Pre-stage existing 10 bytes in temp file
        val tempFile = storageManager.getTempFile("task_fallback")
        tempFile.writeText("0123456789")

        val fullPayload = "FULL_CONTENT"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", fullPayload.toByteArray().size)
                .setBody(fullPayload)
        )

        val destFile = storageManager.getDestinationFile("fallback.txt")
        val request = DownloadRequest(
            taskId = "task_fallback",
            url = server.url("/fallback.txt").toString(),
            destinationFile = destFile,
            tempFile = tempFile,
            allowResume = true
        )

        val result = engine.download(request)
        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success

        // Critical safety check: partial file must have been discarded to prevent corruption!
        // Destination file has EXACTLY fullPayload, NOT pre-staged + fullPayload
        assertEquals(fullPayload.toByteArray().size.toLong(), success.totalBytes)
        assertEquals(fullPayload, success.file.readText())
    }

    @Test
    fun rangeNotSatisfiable416_resetsAndRestartsFromScratch() = runBlocking {
        val tempFile = storageManager.getTempFile("task_416")
        tempFile.writeText("OldStaleDataThatExceedsServerSize")

        // First response 416 (Range Not Satisfiable)
        server.enqueue(MockResponse().setResponseCode(416))

        // Second response 200 with complete file
        val cleanPayload = "CLEAN_RESTART_CONTENT"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", cleanPayload.toByteArray().size)
                .setBody(cleanPayload)
        )

        val destFile = storageManager.getDestinationFile("recovered.txt")
        val request = DownloadRequest(
            taskId = "task_416",
            url = server.url("/recovered.txt").toString(),
            destinationFile = destFile,
            tempFile = tempFile,
            allowResume = true
        )

        val result = engine.download(request)
        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(cleanPayload, success.file.readText())
    }

    @Test
    fun coroutineCancellation_abortsTransfer() {
        runBlocking {
            // Slow response to allow mid-transfer cancellation
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("Large content payload that will be cancelled midway")
                    .throttleBody(5, 100, TimeUnit.MILLISECONDS)
            )

            val destFile = storageManager.getDestinationFile("cancel.bin")
            val tempFile = storageManager.getTempFile("task_cancel")

            val request = DownloadRequest(
                taskId = "task_cancel",
                url = server.url("/cancel.bin").toString(),
                destinationFile = destFile,
                tempFile = tempFile
            )

            val job = launch(Dispatchers.IO) {
                engine.download(request)
            }

            delay(60)
            job.cancel()
            job.join()

            assertTrue("Job should be cancelled", job.isCancelled)
            assertFalse("Destination file should not be finalized upon cancellation", destFile.exists())
        }
    }
}
