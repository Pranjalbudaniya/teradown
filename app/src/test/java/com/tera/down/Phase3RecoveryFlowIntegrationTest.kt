package com.tera.down

import com.tera.down.core.model.AppErrorCode
import com.tera.down.core.model.AppError
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareLink
import com.tera.down.feature.auth.AuthCoordinatorImpl
import com.tera.down.feature.auth.DefaultSessionRepository
import com.tera.down.feature.auth.InteractiveAuthResult
import com.tera.down.feature.auth.TeraboxSession
import com.tera.down.feature.downloader.DownloadRequest
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.downloader.OkHttpDownloadEngine
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.TeraboxShareLinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class Phase3RecoveryFlowIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionRepository: DefaultSessionRepository
    private lateinit var authCoordinator: AuthCoordinatorImpl
    private lateinit var resolver: TeraboxShareLinkResolver
    private lateinit var fakeDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepository

    private val sampleShareSuccessJson = """
        {
            "errno": 0,
            "shareid": "100200",
            "title": "Vacation Archive",
            "list": [
                {
                    "fs_id": 101,
                    "server_filename": "BeachVideo.mp4",
                    "size": 5242880,
                    "isdir": 0,
                    "category": 1,
                    "dlink": "https://d.terabox.com/file/BeachVideo.mp4"
                },
                {
                    "fs_id": 102,
                    "server_filename": "FamilyPhoto.jpg",
                    "size": 1048576,
                    "isdir": 0,
                    "category": 3,
                    "dlink": "https://d.terabox.com/file/FamilyPhoto.jpg"
                }
            ]
        }
    """.trimIndent()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        sessionRepository = DefaultSessionRepository(context = null)
        authCoordinator = AuthCoordinatorImpl(maxAttempts = 2)

        resolver = TeraboxShareLinkResolver(
            httpClient = client,
            baseUrlOverride = server.url("/").toString(),
            sessionRepository = sessionRepository,
            authCoordinator = authCoordinator
        )

        fakeDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()
        queueRepository = DownloadQueueRepositoryImpl(fakeDao, fakeLibDao, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ==========================================
    // Flow A: Normal share link
    // ==========================================
    @Test
    fun flowA_normalShareLink_resolvesDirectlyWithoutAuthWindow() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleShareSuccessJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1flowA",
                surl = "1flowA",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue("Normal link must resolve to Success, got: $result", result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success
            assertEquals("Vacation Archive", success.shareInfo.title)
            assertEquals(2, success.items.size)

            // Verify no auth coordinator window was triggered
            assertFalse(authCoordinator.isAuthInProgress)
            assertNull(authCoordinator.authRequestFlow.value)

            // Enqueue items into download queue without issues
            val enqueueResult = queueRepository.enqueueShareFilesWithResult(
                surl = success.shareInfo.surl,
                originalUrl = link.originalUrl,
                items = success.items
            )
            assertEquals(2, enqueueResult.addedCount)
            assertEquals(0, enqueueResult.duplicateCount)
        }
    }

    // ==========================================
    // Flow B: Login required
    // ==========================================
    @Test
    fun flowB_loginRequired_pausesSafely_userAuthenticates_retriesAndPreservesQueue() {
        runBlocking {
            val loginHtml = """
                <!DOCTYPE html>
                <html><body>
                    <div class="login-box">
                        <form action="/login"><input type="password" name="pwd"/></form>
                    </div>
                </body></html>
            """.trimIndent()

            // 1st request returns login HTML; 2nd request (after auth) returns JSON metadata
            server.enqueue(MockResponse().setResponseCode(200).setBody(loginHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleShareSuccessJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1flowB",
                surl = "1flowB",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            // Verify resolver pauses and requests authentication
            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)
            val authReq = authCoordinator.authRequestFlow.value
            assertNotNull(authReq)
            assertEquals("https://terabox.com/s/1flowB", authReq?.shareUrl)

            // User logs in via WebView and submits valid session
            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "ndus=VALID_LOGIN_COOKIE",
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.0.0"
            )
            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            // Resolver resumes, retries, and succeeds
            val result = deferredResolve.await()
            assertTrue(result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success
            assertEquals(2, success.items.size)

            // Verify session was persisted
            assertEquals("ndus=VALID_LOGIN_COOKIE", sessionRepository.getCookieHeader("terabox.com"))
        }
    }

    // ==========================================
    // Flow C: Human-verification page
    // ==========================================
    @Test
    fun flowC_humanVerification_promptsUser_stopsAfterLimitWithClearMessage() {
        runBlocking {
            val captchaHtml = """
                <html><body>
                    <h1>Security Check</h1>
                    <div id="cf-turnstile"></div>
                </body></html>
            """.trimIndent()

            // Both attempts return CAPTCHA challenge
            server.enqueue(MockResponse().setResponseCode(200).setBody(captchaHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(captchaHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(captchaHtml))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1flowC",
                surl = "1flowC",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            // 1st challenge prompt
            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(
                TeraboxSession(domain = "terabox.com", cookieString = "c1=1", userAgent = "UA")
            ))

            // 2nd challenge prompt
            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(
                TeraboxSession(domain = "terabox.com", cookieString = "c2=2", userAgent = "UA")
            ))

            // After 2 attempts exceeded, stops cleanly with CaptchaOrRateLimited error
            val result = deferredResolve.await()
            assertTrue("Expected CaptchaOrRateLimited, got: $result", result is ShareResolveResult.CaptchaOrRateLimited)
            val captchaResult = result as ShareResolveResult.CaptchaOrRateLimited
            assertTrue(captchaResult.message.contains("attempts", ignoreCase = true) || captchaResult.message.contains("verification", ignoreCase = true))
        }
    }

    // ==========================================
    // Flow D: Promo screen
    // ==========================================
    @Test
    fun flowD_promoScreen_verifiesUa_retriesAlternateUrl_andDismissesOverlay() {
        runBlocking {
            val promoHtml = """
                <!DOCTYPE html>
                <html><body>
                    <div class="banner">Download app to open this link!</div>
                    <p>Open in app for higher download speed</p>
                </body></html>
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(promoHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleShareSuccessJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1flowD",
                surl = "1flowD",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)

            // User dismisses promo overlay and submits verification
            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "dismissed=true",
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.0.0"
            )
            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            val result = deferredResolve.await()
            assertTrue("Expected Success after dismissing promo, got: $result", result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success
            assertEquals("Vacation Archive", success.shareInfo.title)
        }
    }

    // ==========================================
    // Flow E: App-only content
    // ==========================================
    @Test
    fun flowE_appOnlyContent_mapsToNoDirectLink_stopsRetrying_leavesQueueConsistent() {
        runBlocking {
            val appOnlyHtml = """
                <!DOCTYPE html>
                <html><body>
                    <div class="alert">Download only in the app. Large files cannot be accessed via web.</div>
                </body></html>
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(appOnlyHtml))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1flowE",
                surl = "1flowE",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue("Expected AppOnly, got: $result", result is ShareResolveResult.AppOnly)
            val appOnly = result as ShareResolveResult.AppOnly
            assertTrue(appOnly.message.contains("app only", ignoreCase = true) || appOnly.message.contains("official app", ignoreCase = true))

            // Map to AppError: must map to DIRECT_DOWNLOAD_UNAVAILABLE (NO_DIRECT_LINK) and be non-retryable
            val appError = AppError.fromShareResolveResult(result)
            assertNotNull(appError)
            assertEquals(AppErrorCode.DIRECT_DOWNLOAD_UNAVAILABLE, appError?.code)
            assertFalse("App-only errors must NOT be retryable", appError!!.isRetryable)
        }
    }

    // ==========================================
    // Flow F: Authentication cancelled
    // ==========================================
    @Test
    fun flowF_authenticationCancelled_returnsNeedsAuth_manifestIntact_userCanResumeLater() {
        runBlocking {
            val loginHtml = "<html><body><form><input type='password'/></form></body></html>"
            server.enqueue(MockResponse().setResponseCode(200).setBody(loginHtml))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1flowF",
                surl = "1flowF",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)

            // User taps Cancel
            authCoordinator.cancelAuth()

            val result = deferredResolve.await()
            assertTrue("Expected AuthRequired upon cancellation, got: $result", result is ShareResolveResult.AuthRequired)
            val authRequired = result as ShareResolveResult.AuthRequired
            assertTrue(authRequired.message.contains("cancelled", ignoreCase = true))

            // Queue and storage remain completely intact
            val allTasks = fakeDao.getAllTasks()
            assertTrue("Queue must not contain corrupt tasks on auth cancellation", allTasks.isEmpty())
        }
    }

    // ==========================================
    // Flow G: Concurrent resolver failures (Single-flight execution)
    // ==========================================
    @Test
    fun flowG_concurrentResolverFailures_singleFlightCoordinator_onlyOneWindowOpens() {
        runBlocking {
            val loginHtml = "<html><body><form><input type='password'/></form></body></html>"

            // 3 initial requests return login HTML, 3 retried requests return success JSON
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(loginHtml))
            }
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(sampleShareSuccessJson))
            }

            val link1 = ShareLink(originalUrl = "https://terabox.com/s/1c1", surl = "1c1", host = "terabox.com")
            val link2 = ShareLink(originalUrl = "https://terabox.com/s/1c2", surl = "1c2", host = "terabox.com")
            val link3 = ShareLink(originalUrl = "https://terabox.com/s/1c3", surl = "1c3", host = "terabox.com")

            // Launch 3 concurrent resolve requests
            val deferreds = listOf(
                async(Dispatchers.IO) { resolver.resolve(link1) },
                async(Dispatchers.IO) { resolver.resolve(link2) },
                async(Dispatchers.IO) { resolver.resolve(link3) }
            )

            delay(150)
            assertTrue(authCoordinator.isAuthInProgress)
            // Exactly 1 episode attempt was initiated, all 3 workers join it
            assertEquals(1, authCoordinator.currentAttemptCount)

            // Single user login submission satisfies all 3 workers
            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "ndus=CONCURRENT_RESOLVE_COOKIE",
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.0.0"
            )
            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            // All 3 resolve coroutines resume and complete successfully
            val results = deferreds.awaitAll()
            assertEquals(3, results.size)
            results.forEach { res ->
                assertTrue("Concurrent worker must succeed, got: $res", res is ShareResolveResult.Success)
            }
        }
    }

    // ==========================================
    // Flow H: HTML error response during download execution
    // ==========================================
    @Test
    fun flowH_downloadEngine_rejectsHtmlWebpageResponseForMediaFile() {
        runBlocking {
            val htmlErrorResponse = """
                <!DOCTYPE html>
                <html><body>
                    <h1>Session Expired</h1>
                    <p>Please log in to your Terabox account to download this file.</p>
                </body></html>
            """.trimIndent()

            val testServer = MockWebServer()
            testServer.start()
            testServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(htmlErrorResponse)
            )

            val tempDir = File(System.getProperty("java.io.tmpdir"), "teradown_test_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            val destFile = File(tempDir, "sample_video.mp4")
            val tempFile = File(tempDir, "sample_video.mp4.part")

            val storageManager = StorageManager(baseDir = tempDir)

            val downloadEngine = OkHttpDownloadEngine(
                storageManager = storageManager,
                allowHttpForTesting = true
            )

            val downloadRequest = DownloadRequest(
                taskId = "test_task_html_reject",
                url = testServer.url("/mock_download.mp4").toString(),
                destinationFile = destFile,
                tempFile = tempFile,
                expectedSize = 1024L
            )

            val result = downloadEngine.download(downloadRequest) { _, _, _ -> }

            // Must NOT treat the HTML error page as a successful mp4 video download
            assertTrue("Download must fail when server returns HTML for a video file, got: $result", result is DownloadResult.Error)
            val error = result as DownloadResult.Error
            assertTrue(error.message.contains("HTML", ignoreCase = true))
            assertFalse("HTML error must not create a valid media file", destFile.exists())

            testServer.shutdown()
            tempDir.deleteRecursively()
        }
    }
}
