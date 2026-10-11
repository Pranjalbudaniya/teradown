package com.tera.down

import com.tera.down.feature.downloader.DownloadErrorType
import com.tera.down.feature.downloader.DownloadRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {

    @Test
    fun isPermanentError_identifiesNonRetryableErrors() {
        // URL errors
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.INVALID_URL, null))
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.UNSUPPORTED_SCHEME, null))

        // Fatal client HTTP status codes
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 400))
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 401))
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 403))
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 404))
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 410))
        assertTrue(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 451))

        // Transient errors must NOT be classified as permanent
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.NETWORK_FAILURE, null))
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 408))
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.HTTP_ERROR, 429))
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.SERVER_ERROR, 500))
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.SERVER_ERROR, 502))
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.SERVER_ERROR, 503))
        assertFalse(DownloadRetryPolicy.isPermanentError(DownloadErrorType.SERVER_ERROR, 504))
    }

    @Test
    fun shouldRetry_enforcesRetryLimitsAndBlocksPermanentErrors() {
        // Permanent error is rejected even on first attempt
        assertFalse(
            DownloadRetryPolicy.shouldRetry(
                attemptCount = 1,
                errorType = DownloadErrorType.INVALID_URL,
                httpStatusCode = null
            )
        )
        assertFalse(
            DownloadRetryPolicy.shouldRetry(
                attemptCount = 1,
                errorType = DownloadErrorType.HTTP_ERROR,
                httpStatusCode = 404
            )
        )

        // Storage exhaustion cannot be fixed by immediate retries
        assertFalse(
            DownloadRetryPolicy.shouldRetry(
                attemptCount = 1,
                errorType = DownloadErrorType.INSUFFICIENT_SPACE,
                httpStatusCode = null
            )
        )

        // Transient network failure allows retry up to max limit (default 3)
        assertTrue(
            DownloadRetryPolicy.shouldRetry(
                attemptCount = 1,
                errorType = DownloadErrorType.NETWORK_FAILURE,
                httpStatusCode = null
            )
        )
        assertTrue(
            DownloadRetryPolicy.shouldRetry(
                attemptCount = 2,
                errorType = DownloadErrorType.NETWORK_FAILURE,
                httpStatusCode = null
            )
        )
        // Attempt count 3 reached max -> no further automatic retry
        assertFalse(
            DownloadRetryPolicy.shouldRetry(
                attemptCount = 3,
                errorType = DownloadErrorType.NETWORK_FAILURE,
                httpStatusCode = null
            )
        )
    }

    @Test
    fun calculateBackoffMs_exponentialBackoffWithCap() {
        // Attempt 1: Initial delay 2000ms
        assertEquals(2000L, DownloadRetryPolicy.calculateBackoffMs(1))

        // Attempt 2: 4000ms
        assertEquals(4000L, DownloadRetryPolicy.calculateBackoffMs(2))

        // Attempt 3: 8000ms
        assertEquals(8000L, DownloadRetryPolicy.calculateBackoffMs(3))

        // Attempt 4: 16000ms
        assertEquals(16000L, DownloadRetryPolicy.calculateBackoffMs(4))

        // High attempt capped at max backoff (30000ms)
        assertEquals(30000L, DownloadRetryPolicy.calculateBackoffMs(10))
    }

    @Test
    fun calculateBackoffMs_rateLimitBackoff() {
        // HTTP 429 Too Many Requests triggers dedicated rate limit backoff (15000ms)
        assertEquals(15000L, DownloadRetryPolicy.calculateBackoffMs(attempt = 1, httpStatusCode = 429))
        assertEquals(15000L, DownloadRetryPolicy.calculateBackoffMs(attempt = 3, httpStatusCode = 429))
    }
}
