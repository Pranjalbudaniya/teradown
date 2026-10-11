package com.tera.down

import com.tera.down.feature.resolver.ShareResolveErrorType
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.classification.DiagnosticsManager
import com.tera.down.feature.resolver.classification.ResponseVerdict
import com.tera.down.feature.resolver.classification.TeraboxResponseClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeraboxResponseClassifierTest {

    private lateinit var classifier: TeraboxResponseClassifier

    @Before
    fun setup() {
        classifier = TeraboxResponseClassifier()
        DiagnosticsManager.clear()
    }

    @Test
    fun classify_validJsonSuccess_returnsOk() {
        val jsonBody = """
            {
                "errno": 0,
                "shareid": "12345",
                "list": []
            }
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "application/json",
            rawBody = jsonBody
        )

        assertTrue(verdict is ResponseVerdict.Ok)
        val ok = verdict as ResponseVerdict.Ok
        assertEquals(0, ok.json.optInt("errno", -1))
        assertEquals("12345", ok.json.optString("shareid"))
    }

    @Test
    fun classify_validJsonServedWithHtmlContentType_returnsOk() {
        // Terabox server mistakenly responds with Content-Type: text/html
        val jsonBody = """{"errno": 0, "title": "Test Files", "list": []}"""

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "text/html; charset=utf-8",
            rawBody = jsonBody
        )

        assertTrue("Expected Ok despite text/html header, got: $verdict", verdict is ResponseVerdict.Ok)
    }

    @Test
    fun classify_loginHtmlWithFormAndPassword_returnsNotLoggedIn() {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <head><title>Terabox - Sign In</title></head>
            <body>
                <div class="login-box">
                    <form action="/login" method="POST">
                        <input type="text" name="username" />
                        <input type="password" name="password" />
                        <button type="submit">Sign in</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/main",
            contentType = "text/html",
            rawBody = htmlBody
        )

        assertTrue(verdict is ResponseVerdict.NotLoggedIn)
        val notLoggedIn = verdict as ResponseVerdict.NotLoggedIn
        assertTrue(notLoggedIn.reason.contains("Login form", ignoreCase = true) || notLoggedIn.reason.contains("password", ignoreCase = true))
    }

    @Test
    fun classify_redirectedToPassportLogin_returnsNotLoggedIn() {
        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://passport.terabox.com/v2/api?login",
            contentType = "text/html",
            rawBody = "<html><body>Redirecting to login portal...</body></html>"
        )

        assertTrue(verdict is ResponseVerdict.NotLoggedIn)
        val notLoggedIn = verdict as ResponseVerdict.NotLoggedIn
        assertEquals("https://passport.terabox.com/v2/api?login", notLoggedIn.redirectUrl)
    }

    @Test
    fun classify_humanVerificationCaptcha_returnsCaptchaOrRateLimit() {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <body>
                <h1>Security Check</h1>
                <p>Please complete the captcha below to verify you are human.</p>
                <div id="cf-turnstile"></div>
            </body>
            </html>
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/share/list",
            contentType = "text/html",
            rawBody = htmlBody
        )

        assertTrue(verdict is ResponseVerdict.CaptchaOrRateLimit)
        val captcha = verdict as ResponseVerdict.CaptchaOrRateLimit
        assertNotNull(captcha.markerMatched)
    }

    @Test
    fun classify_http429StatusCode_returnsCaptchaOrRateLimit() {
        val verdict = classifier.classify(
            statusCode = 429,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "text/plain",
            rawBody = "Too many requests. Please wait."
        )

        assertTrue(verdict is ResponseVerdict.CaptchaOrRateLimit)
    }

    @Test
    fun classify_http401StatusCode_returnsNotLoggedIn() {
        val verdict = classifier.classify(
            statusCode = 401,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "text/plain",
            rawBody = "Unauthorized"
        )

        assertTrue(verdict is ResponseVerdict.NotLoggedIn)
    }

    @Test
    fun classify_unknownHtml_returnsUnknownHtmlAndRecordsSample() {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <head><title>Some Generic Internal Status</title></head>
            <body>
                <div class="custom-portal">Random unclassified vendor error page</div>
            </body>
            </html>
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/api/custom",
            contentType = "text/html",
            rawBody = htmlBody
        )

        assertTrue(verdict is ResponseVerdict.UnknownHtml)
        val unknown = verdict as ResponseVerdict.UnknownHtml
        assertTrue(unknown.sample.contains("Random unclassified vendor error page"))
        assertNotNull(DiagnosticsManager.getLastSample())
        assertEquals(200, DiagnosticsManager.getLastEntry()?.statusCode)
        assertEquals("www.terabox.com", DiagnosticsManager.getLastEntry()?.sanitizedHost)
    }

    @Test
    fun classify_emptyBodyWithHttp200_returnsMalformedResponse() {
        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "application/json",
            rawBody = ""
        )

        assertTrue(verdict is ResponseVerdict.ResolverError)
        val error = (verdict as ResponseVerdict.ResolverError).result as ShareResolveResult.Failure
        assertEquals(ShareResolveErrorType.MALFORMED_RESPONSE, error.errorType)
    }

    @Test
    fun classify_emptyBodyWithHttp500_returnsHttpError() {
        val verdict = classifier.classify(
            statusCode = 502,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "text/html",
            rawBody = ""
        )

        assertTrue(verdict is ResponseVerdict.HttpError)
        assertEquals(502, (verdict as ResponseVerdict.HttpError).statusCode)
    }

    @Test
    fun classify_malformedJsonPayload_returnsResolverError() {
        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/api/share/list",
            contentType = "application/json",
            rawBody = "{ not valid json ... "
        )

        assertTrue(verdict is ResponseVerdict.ResolverError)
        val error = (verdict as ResponseVerdict.ResolverError).result as ShareResolveResult.Failure
        assertEquals(ShareResolveErrorType.MALFORMED_RESPONSE, error.errorType)
    }

    @Test
    fun classify_appPromoDetection_evaluatedBeforeLogin() {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="banner">Open in app to download files faster!</div>
                <div class="subtext">Or login to continue on web</div>
            </body>
            </html>
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/s/1abc",
            contentType = "text/html",
            rawBody = htmlBody
        )

        assertTrue("Expected AppPromo, got: $verdict", verdict is ResponseVerdict.AppPromo)
    }

    @Test
    fun classify_appOnlyMarkers_returnsAppOnly() {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="notice">Download only in the app. This file is restricted to mobile app users.</div>
            </body>
            </html>
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/s/1abc",
            contentType = "text/html",
            rawBody = htmlBody
        )

        assertTrue("Expected AppOnly, got: $verdict", verdict is ResponseVerdict.AppOnly)
    }

    @Test
    fun classify_pageWithFileListMarkers_doesNotTriggerPromoOrAppOnly() {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <body>
                <div>server_filename: Vacation.mp4</div>
                <div>dlink: https://d.terabox.com/file</div>
                <div>get the app for faster downloads</div>
            </body>
            </html>
        """.trimIndent()

        val verdict = classifier.classify(
            statusCode = 200,
            finalUrl = "https://www.terabox.com/s/1abc",
            contentType = "text/html",
            rawBody = htmlBody
        )

        // Presence of file list markers (server_filename, dlink) prevents promo misclassification
        assertFalse("Real file list must not be classified as AppPromo", verdict is ResponseVerdict.AppPromo)
        assertFalse("Real file list must not be classified as AppOnly", verdict is ResponseVerdict.AppOnly)
    }

    @Test
    fun classify_diagnosticsManager_redactsSecrets() {
        val sensitiveHtml = """
            <html>
            <body>
                <a href="https://terabox.com/share?token=SECRET_TOKEN_XYZ&pwd=SECRET_PASSWORD_123">Link</a>
                <div>Cookie: session_id=SUPER_SECRET_COOKIE_VALUE; path=/</div>
                <input type="password" value="MY_PASSWORD" />
                <p>Authorization: Bearer EYJHBGCIOIJS.SECRET.TOKEN</p>
            </body>
            </html>
        """.trimIndent()

        val redacted = DiagnosticsManager.redactHtml(sensitiveHtml)

        assertFalse(redacted.contains("SECRET_TOKEN_XYZ"))
        assertFalse(redacted.contains("SECRET_PASSWORD_123"))
        assertFalse(redacted.contains("SUPER_SECRET_COOKIE_VALUE"))
        assertFalse(redacted.contains("MY_PASSWORD"))
        assertFalse(redacted.contains("EYJHBGCIOIJS.SECRET.TOKEN"))

        assertTrue(redacted.contains("token=***"))
        assertTrue(redacted.contains("pwd=***"))
        assertTrue(redacted.contains("Cookie: ***"))
        assertTrue(redacted.contains("Bearer ***"))
    }
}
