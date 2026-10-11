package com.tera.down

import com.tera.down.core.model.ShareLink
import com.tera.down.feature.auth.AuthCoordinatorImpl
import com.tera.down.feature.auth.DefaultSessionRepository
import com.tera.down.feature.auth.InteractiveAuthResult
import com.tera.down.feature.auth.TeraboxSession
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.TeraboxShareLinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ResolverPromoRecoveryTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionRepository: DefaultSessionRepository
    private lateinit var authCoordinator: AuthCoordinatorImpl
    private lateinit var resolver: TeraboxShareLinkResolver

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
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolve_appPromoScreenEncountered_interactiveAuthDismissesAndRetries() {
        runBlocking {
            val promoHtml = """
                <!DOCTYPE html>
                <html>
                <body>
                    <div class="banner">Download app to view this folder!</div>
                    <a href="intent://details">Open in app</a>
                </body>
                </html>
            """.trimIndent()

            val successJson = """
                {
                    "errno": 0,
                    "shareid": "8877",
                    "title": "Promo Dismissed Share",
                    "list": [
                        {
                            "fs_id": 999,
                            "server_filename": "video.mp4",
                            "size": 2048,
                            "isdir": 0,
                            "category": 1,
                            "dlink": "https://d.terabox.com/file/video.mp4"
                        }
                    ]
                }
            """.trimIndent()

            // 1st call returns promo HTML; 2nd call after user dismisses returns JSON
            server.enqueue(MockResponse().setResponseCode(200).setBody(promoHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(successJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1promoFlow",
                surl = "1promoFlow",
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
                cookieString = "ndus=VALID_DISMISSED_COOKIE",
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            val result = deferredResolve.await()
            assertTrue("Expected Success after dismissing promo, got: $result", result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success
            assertEquals("Promo Dismissed Share", success.shareInfo.title)
        }
    }

    @Test
    fun resolve_appPromoScreenEncountered_userCancelsAuth_returnsAppOnly() {
        runBlocking {
            val promoHtml = "<html><body><div>Open in app to continue</div></body></html>"
            server.enqueue(MockResponse().setResponseCode(200).setBody(promoHtml))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1promoCancel",
                surl = "1promoCancel",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)
            authCoordinator.cancelAuth()

            val result = deferredResolve.await()
            assertTrue("Expected AppOnly when user cancels promo, got: $result", result is ShareResolveResult.AppOnly)
            val appOnly = result as ShareResolveResult.AppOnly
            assertTrue(appOnly.message.contains("cancelled", ignoreCase = true))
        }
    }

    @Test
    fun resolve_appOnlyContentEncountered_returnsAppOnlyWithoutLooping() {
        runBlocking {
            val appOnlyHtml = """
                <!DOCTYPE html>
                <html>
                <body>
                    <div class="restriction">Download only in the app. This item cannot be accessed via web.</div>
                </body>
                </html>
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(appOnlyHtml))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1appOnlyFile",
                surl = "1appOnlyFile",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue("Expected AppOnly directly, got: $result", result is ShareResolveResult.AppOnly)
            val appOnly = result as ShareResolveResult.AppOnly
            assertTrue(appOnly.message.contains("app only", ignoreCase = true))
        }
    }
}
