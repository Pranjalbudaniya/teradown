package com.tera.down

import com.tera.down.core.player.MediaUrlSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlSanitizerTest {

    @Test
    fun sanitize_masksSensitiveQueryParameters() {
        val rawUrl = "https://d.terabox.app/file/stream.mp4?token=secret123&auth_key=abc987&expire=1800000000&sign=xyz789&id=file123"
        val sanitized = MediaUrlSanitizer.sanitize(rawUrl)

        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("abc987"))
        assertFalse(sanitized.contains("xyz789"))
        assertTrue(sanitized.contains("token=***"))
        assertTrue(sanitized.contains("auth_key=***"))
        assertTrue(sanitized.contains("sign=***"))
        assertTrue(sanitized.contains("expire=***"))
        assertTrue(sanitized.contains("https://d.terabox.app/file/stream.mp4"))
    }

    @Test
    fun sanitize_preservesNonSensitiveParams() {
        val rawUrl = "https://cdn.example.com/video.mp4?format=mp4&resolution=1080p"
        val sanitized = MediaUrlSanitizer.sanitize(rawUrl)

        assertEquals("https://cdn.example.com/video.mp4?format=mp4&resolution=1080p", sanitized)
    }

    @Test
    fun sanitize_handlesUrlWithoutQueryParams() {
        val rawUrl = "https://cdn.example.com/video.mp4"
        val sanitized = MediaUrlSanitizer.sanitize(rawUrl)

        assertEquals("https://cdn.example.com/video.mp4", sanitized)
    }

    @Test
    fun sanitize_handlesNullAndBlankSafely() {
        assertEquals("[EMPTY_URL]", MediaUrlSanitizer.sanitize(null))
        assertEquals("[EMPTY_URL]", MediaUrlSanitizer.sanitize(""))
        assertEquals("[EMPTY_URL]", MediaUrlSanitizer.sanitize("   "))
    }
}
