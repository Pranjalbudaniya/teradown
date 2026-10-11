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

class InteractiveAuthResolverIntegrationTest {

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
    fun resolve_loginHtmlEncountered_triggersAuthAndRetriesSuccessfully() {
        runBlocking {
            val loginHtml = """
                <!DOCTYPE html>
                <html>
                <body>
                    <form action="/login" method="POST">
                        <input type="password" name="password" />
                    </form>
                </body>
                </html>
            """.trimIndent()

            val successJson = """
                {
                    "errno": 0,
                    "shareid": "9988",
                    "title": "Recovered Share",
                    "list": [
                        {
                            "fs_id": 501,
                            "server_filename": "verified_doc.pdf",
                            "size": 1024,
                            "isdir": 0,
                            "category": 4,
                            "dlink": "https://d.terabox.com/file/doc.pdf"
                        }
                    ]
                }
            """.trimIndent()

            // 1st request returns login HTML; 2nd request (after auth) returns JSON
            server.enqueue(MockResponse().setResponseCode(200).setBody(loginHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(successJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1authFlow",
                surl = "1authFlow",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            // Wait for coordinator to receive auth request
            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)
            val authReq = authCoordinator.authRequestFlow.value
            assertEquals("https://terabox.com/s/1authFlow", authReq?.shareUrl)

            // User logs in and submits fresh session
            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "ndus=VALID_SESSION_COOKIE",
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.0.0"
            )
            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            // Resolver unblocks, retries, and succeeds
            val result = deferredResolve.await()
            assertTrue("Expected Success after interactive auth, got: $result", result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success
            assertEquals("Recovered Share", success.shareInfo.title)
            assertEquals(1, success.items.size)
            assertEquals("verified_doc.pdf", success.items[0].name)

            // Verify retried request included the new session cookie
            server.takeRequest(2, TimeUnit.SECONDS) // 1st request
            val retryRequest = server.takeRequest(2, TimeUnit.SECONDS) // 2nd request
            assertTrue(retryRequest?.getHeader("Cookie")?.contains("ndus=VALID_SESSION_COOKIE") == true)
        }
    }

    @Test
    fun resolve_loginHtmlEncountered_userCancelsAuth_returnsAuthRequired() {
        runBlocking {
            val loginHtml = "<html><body><form><input type='password'/></form></body></html>"
            server.enqueue(MockResponse().setResponseCode(200).setBody(loginHtml))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1cancelFlow",
                surl = "1cancelFlow",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)
            authCoordinator.cancelAuth()

            val result = deferredResolve.await()
            assertTrue("Expected AuthRequired upon cancellation, got: $result", result is ShareResolveResult.AuthRequired)
            val authRequired = result as ShareResolveResult.AuthRequired
            assertTrue(authRequired.message.contains("cancelled", ignoreCase = true))
        }
    }

    @Test
    fun resolve_captchaChallenge_triggersAuthAndRetriesSuccessfully() {
        runBlocking {
            val captchaHtml = "<html><body><h1>Security Check</h1><p>Please complete the captcha</p></body></html>"
            val successJson = """{"errno": 0, "title": "Captcha Passed", "list": []}"""

            server.enqueue(MockResponse().setResponseCode(200).setBody(captchaHtml))
            server.enqueue(MockResponse().setResponseCode(200).setBody(successJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1captchaFlow",
                surl = "1captchaFlow",
                host = "terabox.com"
            )

            val deferredResolve = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            delay(100)
            assertTrue(authCoordinator.isAuthInProgress)

            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "cf_clearance=VALID",
                userAgent = "DesktopUA"
            )
            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            val result = deferredResolve.await()
            assertTrue(result is ShareResolveResult.Success)
        }
    }
}
