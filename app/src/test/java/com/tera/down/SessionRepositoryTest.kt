package com.tera.down

import com.tera.down.feature.auth.DefaultSessionRepository
import com.tera.down.feature.auth.TeraboxSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

    private lateinit var repository: DefaultSessionRepository

    @Before
    fun setup() {
        // Runs in in-memory mode without Android Context
        repository = DefaultSessionRepository(context = null)
    }

    @Test
    fun sessionLifecycle_saveAndRetrieve_preservesSession() = runBlocking {
        val now = System.currentTimeMillis()
        val session = TeraboxSession(
            domain = "terabox.com",
            cookieString = "ndus=Y123456; pan_token=abc",
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.0.0",
            acceptLanguage = "en-US,en;q=0.9",
            savedAt = now,
            lastOkAt = now,
            expiryEstimate = now + 86400000L
        )

        repository.saveSession(session)

        val retrieved = repository.getSession()
        assertNotNull(retrieved)
        assertEquals("terabox.com", retrieved?.domain)
        assertEquals("ndus=Y123456; pan_token=abc", retrieved?.cookieString)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.0.0", repository.getUserAgent())
        assertTrue(retrieved!!.isRecentlyVerified)
        assertFalse(retrieved.isExpired)
    }

    @Test
    fun getCookieHeader_domainScopingRules() = runBlocking {
        val session = TeraboxSession(
            domain = "terabox.com",
            cookieString = "session=active",
            userAgent = "DesktopUA"
        )
        repository.saveSession(session)

        // Exact match
        assertEquals("session=active", repository.getCookieHeader("terabox.com"))

        // Subdomain matches
        assertEquals("session=active", repository.getCookieHeader("www.terabox.com"))
        assertEquals("session=active", repository.getCookieHeader("d.terabox.com"))
        assertEquals("session=active", repository.getCookieHeader("pan.terabox.com"))

        // Unrelated domains must NOT receive cookies
        assertNull(repository.getCookieHeader("google.com"))
        assertNull(repository.getCookieHeader("example.com"))
        assertNull(repository.getCookieHeader("not-terabox.com"))
    }

    @Test
    fun getCookieHeader_expiredSession_returnsNull() = runBlocking {
        val pastTime = System.currentTimeMillis() - 10000L
        val expiredSession = TeraboxSession(
            domain = "terabox.com",
            cookieString = "session=expired",
            userAgent = "DesktopUA",
            expiryEstimate = pastTime
        )
        repository.saveSession(expiredSession)

        assertTrue(expiredSession.isExpired)
        assertNull("Expired session must not supply cookies", repository.getCookieHeader("terabox.com"))
    }

    @Test
    fun teraboxSession_toString_redactsCookies() {
        val session = TeraboxSession(
            domain = "terabox.com",
            cookieString = "ndus=SUPER_SECRET_COOKIE_VALUE; token=SUPER_SECRET_TOKEN",
            userAgent = "DesktopUA"
        )

        val str = session.toString()
        assertFalse("Cookie value must not appear in toString()", str.contains("SUPER_SECRET_COOKIE_VALUE"))
        assertFalse("Token must not appear in toString()", str.contains("SUPER_SECRET_TOKEN"))
        assertTrue(str.contains("domain='terabox.com'"))
    }

    @Test
    fun clearSession_clearsStoredSession() = runBlocking {
        val session = TeraboxSession(
            domain = "terabox.com",
            cookieString = "cookie=val",
            userAgent = "UA"
        )
        repository.saveSession(session)
        assertNotNull(repository.getSession())

        repository.clearSession()
        assertNull(repository.getSession())
        assertNull(repository.getCookieHeader("terabox.com"))
        assertNull(repository.getUserAgent())
    }

    @Test
    fun switchUserAgent_switchingBetweenDesktopAndMobile_clearsCookies() {
        runBlocking {
            val desktopSession = TeraboxSession(
                domain = "terabox.com",
                cookieString = "ndus=DESKTOP_COOKIE",
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0"
            )
            repository.saveSession(desktopSession)
            assertEquals("ndus=DESKTOP_COOKIE", repository.getCookieHeader("terabox.com"))

            // Switching to Mobile UA clears cookies to prevent UA/cookie mismatch
            val mobileUa = "Mozilla/5.0 (Linux; Android 14) Mobile Safari/537.36"
            repository.switchUserAgent(mobileUa)

            assertNull("Cookies must be cleared when switching UA mode", repository.getCookieHeader("terabox.com"))
            assertEquals(mobileUa, repository.getUserAgent())
        }
    }
}
