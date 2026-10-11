package com.tera.down.feature.downloader

import java.net.URI

/**
 * URL validation and sanitization utility for the download engine.
 *
 * Enforces security requirements:
 * - Restricts requests to HTTPS by default.
 * - Rejects unsupported, malformed, or blank schemes.
 * - Masks sensitive query parameters (e.g. auth tokens, signatures) to prevent accidental
 *   credential leakage in application logs.
 */
object DownloadUrlValidator {

    private val SUPPORTED_SCHEMES = setOf("https")

    /**
     * Returns true if the provided [url] is a valid, well-formed URL conforming to allowed schemes.
     */
    fun isValidUrl(url: String?, allowHttpForTesting: Boolean = false): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" && allowHttpForTesting) {
            return !uri.host.isNullOrBlank()
        }

        if (scheme !in SUPPORTED_SCHEMES) return false
        if (uri.host.isNullOrBlank()) return false

        return true
    }

    /**
     * Validates [url] and throws an [IllegalArgumentException] with descriptive rationale if invalid.
     */
    fun validateUrl(url: String?, allowHttpForTesting: Boolean = false) {
        if (url.isNullOrBlank()) {
            throw IllegalArgumentException("Download URL cannot be blank")
        }
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed URL syntax: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Missing URL scheme in '$url'")
        if (scheme == "http" && allowHttpForTesting) {
            if (uri.host.isNullOrBlank()) {
                throw IllegalArgumentException("URL host is missing or invalid in '$url'")
            }
            return
        }

        if (scheme !in SUPPORTED_SCHEMES) {
            throw IllegalArgumentException("Unsupported scheme '$scheme'. Only secure HTTPS connections are permitted.")
        }

        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("URL host is missing or invalid in '$url'")
        }
    }

    /**
     * Sanitizes a URL for logging by stripping query parameters and extraction keys.
     * Prevents access tokens, sign keys, and session parameters from appearing in logs.
     */
    fun toSafeLogUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<empty_url>"
        return try {
            val uri = URI(url.trim())
            val portPart = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            val base = "${uri.scheme}://${uri.host.orEmpty()}$portPart${uri.path.orEmpty()}"
            if (!uri.rawQuery.isNullOrEmpty()) {
                "$base?***"
            } else {
                base
            }
        } catch (e: Exception) {
            "https://***"
        }
    }
}
