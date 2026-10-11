package com.pranjalbudaniya.teradown.feature.resolver

import com.pranjalbudaniya.teradown.core.model.ShareLink
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Result of parsing a Terabox share link.
 */
sealed interface TeraboxUrlParseResult {
    data class Success(val shareLink: ShareLink) : TeraboxUrlParseResult
    data class InvalidUrl(val reason: String) : TeraboxUrlParseResult
}

/**
 * Pure Kotlin parser for Terabox share URLs according to Section 1 & 5 of the specification.
 *
 * Validates structural URL patterns, extracts the share identifier (surl) and optional extraction
 * password (pwd), and ensures domain safety without performing any network requests.
 */
object TeraboxUrlParser {

    /**
     * Recognized official Terabox and verified mirror root domains.
     * Host checks verify exact match or subdomain suffix (.domain).
     */
    val RECOGNIZED_DOMAINS: Set<String> = setOf(
        "terabox.com",
        "teraboxapp.com",
        "1024tera.com",
        "1024terabox.com",
        "teraboxshare.com",
        "teraboxlink.com",
        "dubox.com",
        "mirrobox.com",
        "nephobox.com",
        "4funbox.com",
        "momerybox.com"
    )

    /**
     * Parses and validates a Terabox share link string.
     *
     * Never throws exceptions on malformed user input.
     * Never logs sensitive query parameters or passwords.
     */
    fun parse(rawInput: String?): TeraboxUrlParseResult {
        if (rawInput.isNullOrBlank()) {
            return TeraboxUrlParseResult.InvalidUrl("URL cannot be empty.")
        }

        val trimmed = rawInput.trim()

        // Scheme validation
        val schemeRegex = "^([a-zA-Z][a-zA-Z0-9+.-]*):\\/\\/".toRegex()
        val schemeMatch = schemeRegex.find(trimmed)

        val normalizedUrl = if (schemeMatch == null) {
            // User provided a link without scheme (e.g. terabox.com/s/1abc)
            "https://$trimmed"
        } else {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") {
                return TeraboxUrlParseResult.InvalidUrl(
                    "Unsupported scheme '$scheme'. Only HTTP and HTTPS URLs are supported."
                )
            }
            trimmed
        }

        val uri: URI = try {
            URI(normalizedUrl)
        } catch (e: Exception) {
            return TeraboxUrlParseResult.InvalidUrl("Malformed URL: ${e.message ?: "Invalid syntax"}")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return TeraboxUrlParseResult.InvalidUrl("URL host is missing or invalid.")
        }

        // Domain verification against recognized domains
        if (!isRecognizedHost(host)) {
            return TeraboxUrlParseResult.InvalidUrl(
                "Host '$host' is not a recognized Terabox or mirror domain."
            )
        }

        // Extract query parameters with percent-decoding
        val queryParams = parseQueryParams(uri.rawQuery)

        // Extract extraction password (pwd)
        val pwd = queryParams["pwd"]?.trim()?.ifBlank { null }

        // Extract share identifier (surl)
        // Check Path pattern: /s/{surl}
        val path = uri.path ?: ""
        var extractedSurl: String? = null

        if (path.startsWith("/s/") || path == "/s") {
            val afterS = path.removePrefix("/s/").removePrefix("/s").trim()
            val segment = afterS.substringBefore('/').trim()
            if (segment.isNotEmpty()) {
                extractedSurl = segment
            } else {
                return TeraboxUrlParseResult.InvalidUrl("Missing share identifier in URL path.")
            }
        }

        // Check Query pattern: ?surl={surl} (used in /wap/share/filelist?surl=... or /share/init?surl=...)
        if (extractedSurl == null) {
            val surlQuery = queryParams["surl"]
            if (surlQuery != null) {
                if (surlQuery.isNotBlank()) {
                    extractedSurl = surlQuery.trim()
                } else {
                    return TeraboxUrlParseResult.InvalidUrl("Missing share identifier in 'surl' query parameter.")
                }
            }
        }

        if (extractedSurl.isNullOrBlank()) {
            return TeraboxUrlParseResult.InvalidUrl(
                "URL does not contain a recognized Terabox share path ('/s/...') or 'surl' parameter."
            )
        }

        val shareLink = ShareLink(
            originalUrl = trimmed,
            surl = extractedSurl,
            pwd = pwd,
            host = host.lowercase()
        )

        return TeraboxUrlParseResult.Success(shareLink)
    }

    /**
     * Checks if the host strictly matches a recognized domain or is a subdomain of a recognized domain.
     * Prevents phishing attacks (e.g., fake-terabox.com or terabox.attacker.org).
     */
    fun isRecognizedHost(host: String): Boolean {
        val lowerHost = host.lowercase()
        return RECOGNIZED_DOMAINS.any { domain ->
            lowerHost == domain || lowerHost.endsWith(".$domain")
        }
    }

    /**
     * Parses raw query string into a key-value map with safe percent-decoding.
     */
    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()

        val result = mutableMapOf<String, String>()
        val pairs = rawQuery.split("&")
        for (pair in pairs) {
            if (pair.isBlank()) continue
            val parts = pair.split("=", limit = 2)
            val key = safeUrlDecode(parts[0].trim())
            val value = if (parts.size > 1) safeUrlDecode(parts[1].trim()) else ""
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun safeUrlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            value
        }
    }
}
