package com.tera.down.feature.downloader

/**
 * Encapsulates retry limits, exponential backoff, and error permanence classification
 * for background download executions.
 *
 * Prevents unbounded retry loops on permanent errors (e.g. 401, 403, 404, invalid URLs)
 * while allowing transient errors (e.g. network disconnects, server 5xx, timeouts)
 * to recover gracefully.
 */
object DownloadRetryPolicy {

    const val DEFAULT_MAX_RETRIES = 3
    const val INITIAL_BACKOFF_MS = 2000L
    const val MAX_BACKOFF_MS = 30000L
    const val RATE_LIMIT_BACKOFF_MS = 15000L

    private val PERMANENT_HTTP_STATUSES = setOf(
        400, // Bad Request
        401, // Unauthorized
        403, // Forbidden
        404, // Not Found
        405, // Method Not Allowed
        410, // Gone
        414, // URI Too Long
        451  // Unavailable For Legal Reasons
    )

    private val TRANSIENT_HTTP_STATUSES = setOf(
        408, // Request Timeout
        429, // Too Many Requests (Rate limit)
        500, // Internal Server Error
        502, // Bad Gateway
        503, // Service Unavailable
        504  // Gateway Timeout
    )

    /**
     * Determines whether an error is permanently fatal and must NOT be automatically retried.
     */
    fun isPermanentError(errorType: DownloadErrorType, httpStatusCode: Int?): Boolean {
        if (errorType == DownloadErrorType.INVALID_URL || errorType == DownloadErrorType.UNSUPPORTED_SCHEME) {
            return true
        }
        if (httpStatusCode != null && PERMANENT_HTTP_STATUSES.contains(httpStatusCode)) {
            return true
        }
        return false
    }

    /**
     * Determines whether a failed transfer is eligible for an automatic retry attempt.
     */
    fun shouldRetry(
        attemptCount: Int,
        errorType: DownloadErrorType,
        httpStatusCode: Int?,
        maxRetries: Int = DEFAULT_MAX_RETRIES
    ): Boolean {
        if (isPermanentError(errorType, httpStatusCode)) {
            return false
        }
        if (errorType == DownloadErrorType.INSUFFICIENT_SPACE) {
            return false // Storage exhaustion cannot be fixed by immediate retries
        }
        return attemptCount < maxRetries
    }

    /**
     * Calculates exponential backoff delay in milliseconds based on attempt number and status code.
     */
    fun calculateBackoffMs(attempt: Int, httpStatusCode: Int? = null): Long {
        if (httpStatusCode == 429) {
            // Server rate limiting: back off more aggressively
            return RATE_LIMIT_BACKOFF_MS
        }
        if (attempt <= 1) return INITIAL_BACKOFF_MS
        val factor = 1L shl (attempt - 1).coerceAtMost(6)
        return (INITIAL_BACKOFF_MS * factor).coerceAtMost(MAX_BACKOFF_MS)
    }
}
