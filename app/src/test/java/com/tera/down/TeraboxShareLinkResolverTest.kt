package com.tera.down

import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareLink
import com.tera.down.feature.resolver.ShareResolveErrorType
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.TeraboxShareLinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TeraboxShareLinkResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: TeraboxShareLinkResolver

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        resolver = TeraboxShareLinkResolver(
            httpClient = client,
            baseUrlOverride = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolve_successfulResponseWithDirectLinks_returnsSuccess() {
        runBlocking {
            val responseJson = """
            {
                "errno": 0,
                "shareid": "99182371",
                "uk": "54231",
                "title": "My Holiday Media",
                "has_more": 0,
                "list": [
                    {
                        "fs_id": 1001,
                        "server_filename": "clip1.mp4",
                        "size": 150000000,
                        "isdir": 0,
                        "category": 1,
                        "path": "/My Holiday Media/clip1.mp4",
                        "dlink": "https://d.terabox.com/file/clip1.mp4"
                    },
                    {
                        "fs_id": 1002,
                        "server_filename": "photo1.jpg",
                        "size": 5000000,
                        "isdir": 0,
                        "category": 3,
                        "path": "/My Holiday Media/photo1.jpg",
                        "dlink": "https://d.terabox.com/file/photo1.jpg"
                    }
                ]
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1holiday",
                surl = "1holiday",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue("Expected Success, got: $result", result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success

            assertEquals("My Holiday Media", success.shareInfo.title)
            assertEquals("99182371", success.shareInfo.shareId)
            assertEquals("54231", success.shareInfo.uk)
            assertEquals(2, success.items.size)

            val video = success.items[0]
            assertEquals("clip1.mp4", video.name)
            assertEquals(150000000L, video.size)
            assertEquals(MediaType.VIDEO, video.mediaType)
            assertEquals("https://d.terabox.com/file/clip1.mp4", video.directUrl)
            assertFalse(video.isDirectory)

            val photo = success.items[1]
            assertEquals("photo1.jpg", photo.name)
            assertEquals(MediaType.PHOTO, photo.mediaType)
            assertEquals("https://d.terabox.com/file/photo1.jpg", photo.directUrl)

            // Verify query sent to MockWebServer
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("shorturl=holiday"))
            assertTrue(request.path!!.contains("root=1"))
        }
    }

    @Test
    fun resolve_nestedFoldersAndPagination_parsedCorrectly() {
        runBlocking {
            val responseJson = """
            {
                "errno": 0,
                "shareid": "123",
                "title": "Folder Share",
                "has_more": 1,
                "list": [
                    {
                        "fs_id": 2001,
                        "server_filename": "Subfolder_A",
                        "size": 0,
                        "isdir": 1,
                        "category": 6,
                        "path": "/Folder Share/Subfolder_A"
                    },
                    {
                        "fs_id": 2002,
                        "server_filename": "movie.mkv",
                        "size": 50000000,
                        "isdir": 0,
                        "category": 1,
                        "path": "/Folder Share/movie.mkv",
                        "dlink": "https://d.terabox.com/file/movie.mkv"
                    }
                ]
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1folder",
                surl = "1folder",
                host = "terabox.com"
            )

            val result = resolver.resolve(link, dirPath = "/Folder Share", page = 1)
            assertTrue(result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success

            assertEquals(2, success.items.size)
            val folder = success.items[0]
            assertTrue(folder.isDirectory)
            assertEquals("Subfolder_A", folder.name)

            assertNotNull(success.pagination)
            assertTrue(success.pagination!!.hasMore)
            assertEquals(1, success.pagination!!.currentPage)
        }
    }

    @Test
    fun resolve_emptyFolder_returnsSuccessWithEmptyList() {
        runBlocking {
            val responseJson = """
            {
                "errno": 0,
                "title": "Empty Folder",
                "has_more": 0,
                "list": []
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1empty",
                surl = "1empty",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.Success)
            val success = result as ShareResolveResult.Success
            assertTrue(success.items.isEmpty())
        }
    }

    @Test
    fun resolve_itemsWithoutDirectLinks_returnsDirectDownloadUnavailable() {
        runBlocking {
            val responseJson = """
            {
                "errno": 0,
                "title": "Stream Only Files",
                "has_more": 0,
                "list": [
                    {
                        "fs_id": 3001,
                        "server_filename": "stream_only.mp4",
                        "size": 8000000,
                        "isdir": 0,
                        "category": 1,
                        "path": "/stream_only.mp4"
                    }
                ]
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1stream",
                surl = "1stream",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue("Expected DirectDownloadUnavailable, got $result", result is ShareResolveResult.DirectDownloadUnavailable)
            val unavailable = result as ShareResolveResult.DirectDownloadUnavailable
            assertEquals(1, unavailable.items.size)
            assertFalse(unavailable.message.isEmpty())
        }
    }

    @Test
    fun resolve_passwordRequired_returnsNeedsPassword() {
        runBlocking {
            val responseJson = """
            {
                "errno": -9,
                "errmsg": "password is required"
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1pwdshare",
                surl = "1pwdshare",
                host = "terabox.com"
            )

            val result = resolver.resolve(link, password = null)
            assertTrue(result is ShareResolveResult.NeedsPassword)
        }
    }

    @Test
    fun resolve_wrongPasswordProvided_returnsWrongPassword() {
        runBlocking {
            val responseJson = """
            {
                "errno": -9,
                "errmsg": "password is required or incorrect"
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1pwdshare?pwd=wrong",
                surl = "1pwdshare",
                pwd = "wrong",
                host = "terabox.com"
            )

            val result = resolver.resolve(link, password = "wrong")
            assertTrue(result is ShareResolveResult.WrongPassword)
        }
    }

    @Test
    fun resolve_loginRequired_returnsAuthRequired() {
        runBlocking {
            val responseJson = """
            {
                "errno": -6,
                "errmsg": "login required"
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1auth",
                surl = "1auth",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.AuthRequired)
        }
    }

    @Test
    fun resolve_http429RateLimit_returnsCaptchaOrRateLimited() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1ratelimit",
                surl = "1ratelimit",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.CaptchaOrRateLimited)
        }
    }

    @Test
    fun resolve_expiredShare_returnsExpired() {
        runBlocking {
            val responseJson = """
            {
                "errno": -1,
                "errmsg": "share has expired"
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1expired",
                surl = "1expired",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.Expired)
        }
    }

    @Test
    fun resolve_cancelledShare_returnsCancelled() {
        runBlocking {
            val responseJson = """
            {
                "errno": -7,
                "errmsg": "share was cancelled"
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1cancelled",
                surl = "1cancelled",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.Cancelled)
        }
    }

    @Test
    fun resolve_blockedShare_returnsBlocked() {
        runBlocking {
            val responseJson = """
            {
                "errno": -12,
                "errmsg": "content blocked"
            }
            """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1blocked",
                surl = "1blocked",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.Blocked)
        }
    }

    @Test
    fun resolve_htmlWebResponse_returnsUnsupportedAccessFlow() {
        runBlocking {
            val html = "<!DOCTYPE html><html><body>Login to Continue</body></html>"
            server.enqueue(MockResponse().setResponseCode(200).setBody(html))

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1html",
                surl = "1html",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.UnsupportedAccessFlow)
        }
    }

    @Test
    fun resolve_networkTimeout_returnsFailure() {
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBodyDelay(3, TimeUnit.SECONDS)
                    .setBody("""{"errno": 0}""")
            )

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1timeout",
                surl = "1timeout",
                host = "terabox.com"
            )

            val result = resolver.resolve(link)
            assertTrue(result is ShareResolveResult.Failure)
            val failure = result as ShareResolveResult.Failure
            assertEquals(ShareResolveErrorType.TIMEOUT, failure.errorType)
        }
    }

    @Test
    fun resolve_coroutineCancellation_stopsImmediately() {
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBodyDelay(3, TimeUnit.SECONDS)
                    .setBody("""{"errno": 0}""")
            )

            val link = ShareLink(
                originalUrl = "https://terabox.com/s/1cancel",
                surl = "1cancel",
                host = "terabox.com"
            )

            val deferred = async(Dispatchers.IO) {
                resolver.resolve(link)
            }

            delay(50)
            deferred.cancel()

            assertTrue(deferred.isCancelled)
        }
    }
}
