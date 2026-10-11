package com.tera.down

import com.tera.down.feature.downloader.DownloadUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUrlValidatorTest {

    @Test
    fun validHttpsUrls_passValidation() {
        assertTrue(DownloadUrlValidator.isValidUrl("https://example.com/file.mp4"))
        assertTrue(DownloadUrlValidator.isValidUrl("https://d.terabox.com/file/video.mp4?sign=123"))
        assertTrue(DownloadUrlValidator.isValidUrl("https://127.0.0.1:8443/test.bin"))

        // Should not throw
        DownloadUrlValidator.validateUrl("https://example.com/file.mp4")
    }

    @Test
    fun httpUrls_rejectedByDefault_allowedForTesting() {
        assertFalse(DownloadUrlValidator.isValidUrl("http://example.com/file.mp4", allowHttpForTesting = false))
        assertTrue(DownloadUrlValidator.isValidUrl("http://localhost:8080/file.mp4", allowHttpForTesting = true))

        var threw = false
        try {
            DownloadUrlValidator.validateUrl("http://example.com/file.mp4", allowHttpForTesting = false)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("Only secure HTTPS"))
        }
        assertTrue(threw)
    }

    @Test
    fun unsupportedSchemes_rejected() {
        val unsupported = listOf(
            "ftp://example.com/file.mp4",
            "file:///sdcard/download.mp4",
            "javascript:alert(1)",
            "data:text/plain;base64,SGVsbG8=",
            "content://media/external/images/1",
            "ws://example.com/socket"
        )

        for (url in unsupported) {
            assertFalse("Expected '$url' to be rejected", DownloadUrlValidator.isValidUrl(url))
        }
    }

    @Test
    fun malformedOrBlankUrls_rejected() {
        assertFalse(DownloadUrlValidator.isValidUrl(""))
        assertFalse(DownloadUrlValidator.isValidUrl("   "))
        assertFalse(DownloadUrlValidator.isValidUrl(null))
        assertFalse(DownloadUrlValidator.isValidUrl("not-a-url"))
        assertFalse(DownloadUrlValidator.isValidUrl("https://"))
    }

    @Test
    fun toSafeLogUrl_masksQueryParamsAndTokens() {
        // Query parameters masked
        val masked = DownloadUrlValidator.toSafeLogUrl("https://d.terabox.com/file/102.mp4?sign=ab2934&token=secret123&user=99")
        assertEquals("https://d.terabox.com/file/102.mp4?***", masked)

        // URL without query unchanged
        val clean = DownloadUrlValidator.toSafeLogUrl("https://d.terabox.com/file/102.mp4")
        assertEquals("https://d.terabox.com/file/102.mp4", clean)

        // Port preserved without query leakage
        val withPort = DownloadUrlValidator.toSafeLogUrl("https://localhost:8443/download?auth=sec")
        assertEquals("https://localhost:8443/download?***", withPort)

        // Blank or null handled safely
        assertEquals("<empty_url>", DownloadUrlValidator.toSafeLogUrl(null))
        assertEquals("<empty_url>", DownloadUrlValidator.toSafeLogUrl(""))
    }
}
