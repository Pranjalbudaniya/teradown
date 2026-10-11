package com.tera.down.core.player

import java.net.URI

/**
 * Utility for sanitizing media URLs to prevent sensitive authentication tokens,
 * signatures, session cookies, or user credentials from leaking into logs and analytics.
 */
object MediaUrlSanitizer {

    private val EXACT_SENSITIVE_PARAMS = setOf(
        "token", "auth", "signature", "sig", "key", "access_token",
        "session", "pwd", "password", "sign", "expire", "timestamp",
        "st", "e", "ipbits", "ip", "sparams"
    )

    private val SENSITIVE_SUBSTRINGS = setOf(
        "token", "auth", "sign", "pwd", "pass", "secret", "credential"
    )

    /**
     * Masks sensitive query parameter values while preserving the URL structure and host for debugging.
     */
    fun sanitize(url: String?): String {
        if (url.isNullOrBlank()) return "[EMPTY_URL]"

        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: "unknown_host"
            val path = uri.path ?: ""
            val query = uri.query

            if (query.isNullOrBlank()) {
                "$scheme://$host$path"
            } else {
                val sanitizedQuery = query.split("&").joinToString("&") { param ->
                    val parts = param.split("=", limit = 2)
                    val key = parts[0]
                    if (parts.size == 2) {
                        val lowerKey = key.lowercase()
                        val isSensitive = EXACT_SENSITIVE_PARAMS.contains(lowerKey) ||
                            SENSITIVE_SUBSTRINGS.any { lowerKey.contains(it) }

                        if (isSensitive) {
                            "$key=***"
                        } else {
                            param
                        }
                    } else {
                        key
                    }
                }
                "$scheme://$host$path?$sanitizedQuery"
            }
        } catch (_: Exception) {
            // If URI parsing fails, mask any query string completely
            val queryIdx = url.indexOf('?')
            if (queryIdx >= 0) {
                "${url.substring(0, queryIdx)}?[MASKED_QUERY]"
            } else {
                url.take(30) + "..."
            }
        }
    }
}
