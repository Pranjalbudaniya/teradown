package com.tera.down.feature.resolver.classification

import java.util.concurrent.atomic.AtomicReference

/**
 * Manages bounded, redacted diagnostic HTML samples for diagnosing unknown responses
 * without leaking sensitive cookies, tokens, or credentials.
 */
object DiagnosticsManager {

    private const val MAX_SAMPLE_BYTES = 2048

    private val SENSITIVE_PARAM_REGEX = Regex(
        """(?i)([?&](?:token|auth|sign|signature|key|access_token|session|pwd|password|cookie|secret|credential|bdstoken|csrf)=)[^&#\s"'>]+"""
    )

    private val SENSITIVE_COOKIE_REGEX = Regex(
        """(?i)(cookie\s*[:=]\s*)[^;\r\n"'>]+"""
    )

    private val SENSITIVE_PASSWORD_INPUT_REGEX_1 = Regex(
        """(?i)(type\s*=\s*["']?password["']?[^>]*value\s*=\s*["']?)[^"'\s>]+"""
    )

    private val SENSITIVE_PASSWORD_INPUT_REGEX_2 = Regex(
        """(?i)(value\s*=\s*["']?)[^"'\s>]+(["']?[^>]*type\s*=\s*["']?password)"""
    )

    private val SENSITIVE_BEARER_REGEX = Regex(
        """(?i)bearer\s+[a-zA-Z0-9_\-\.]+"""
    )

    data class DiagnosticEntry(
        val statusCode: Int,
        val sanitizedHost: String,
        val verdict: String,
        val redactedHtml: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val lastSample = AtomicReference<String?>(null)
    private val lastEntry = AtomicReference<DiagnosticEntry?>(null)

    /**
     * Redacts credentials, tokens, and authorization query parameters from raw HTML.
     */
    fun redactHtml(rawHtml: String?): String {
        if (rawHtml.isNullOrBlank()) return ""

        val truncated = if (rawHtml.length > MAX_SAMPLE_BYTES) {
            rawHtml.substring(0, MAX_SAMPLE_BYTES)
        } else {
            rawHtml
        }

        var sanitized = SENSITIVE_PARAM_REGEX.replace(truncated, "$1***")
        sanitized = SENSITIVE_COOKIE_REGEX.replace(sanitized, "$1***")
        sanitized = SENSITIVE_PASSWORD_INPUT_REGEX_1.replace(sanitized, "$1***")
        sanitized = SENSITIVE_PASSWORD_INPUT_REGEX_2.replace(sanitized, "$1***$2")
        sanitized = SENSITIVE_BEARER_REGEX.replace(sanitized, "Bearer ***")

        return sanitized
    }

    /**
     * Stores the latest redacted sample and diagnostic metadata for unknown HTML diagnostics.
     */
    fun recordSample(
        rawHtml: String?,
        statusCode: Int = 200,
        finalUrl: String = "",
        verdict: String = "UNKNOWN_HTML"
    ): String {
        val redacted = redactHtml(rawHtml)
        lastSample.set(redacted)

        val sanitizedHost = try {
            val uri = java.net.URI(finalUrl)
            uri.host ?: "unknown_host"
        } catch (_: Exception) {
            "unknown_host"
        }

        lastEntry.set(
            DiagnosticEntry(
                statusCode = statusCode,
                sanitizedHost = sanitizedHost,
                verdict = verdict,
                redactedHtml = redacted
            )
        )
        return redacted
    }

    /**
     * Retrieves the current stored diagnostic sample.
     */
    fun getLastSample(): String? = lastSample.get()

    /**
     * Retrieves the current stored diagnostic entry.
     */
    fun getLastEntry(): DiagnosticEntry? = lastEntry.get()

    /**
     * Clears stored diagnostic samples.
     */
    fun clear() {
        lastSample.set(null)
        lastEntry.set(null)
    }
}
