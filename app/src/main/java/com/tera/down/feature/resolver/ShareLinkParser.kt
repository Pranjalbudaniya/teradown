package com.tera.down.feature.resolver

import com.tera.down.core.model.ShareLink
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Typed result of parsing a raw share link.
 */
sealed interface ShareLinkParseResult {
    data class Success(val shareLink: ShareLink) : ShareLinkParseResult
    data class InvalidUrl(val reason: String) : ShareLinkParseResult
    data class UnsupportedHost(val host: String, val reason: String) : ShareLinkParseResult
}

/**
 * Contract for parsing supported share URLs into [ShareLink] instances.
 */
interface ShareLinkParser {
    val supportedDomains: Set<String>
    fun canParse(rawInput: String?): Boolean
    fun parse(rawInput: String?): ShareLinkParseResult
}

/**
 * Parser for Terabox and verified mirror URLs.
 * Extracts the share identifier (surl), optional extraction code (pwd), and validates host domains.
 */
object TeraboxShareLinkParser : ShareLinkParser {

    override val supportedDomains: Set<String> = setOf(
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

    override fun canParse(rawInput: String?): Boolean {
        if (rawInput.isNullOrBlank()) return false
        val trimmed = rawInput.trim()
        val normalized = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
        val host = try {
            URI(normalized).host
        } catch (_: Exception) {
            null
        } ?: return false
        return isRecognizedHost(host)
    }

    override fun parse(rawInput: String?): ShareLinkParseResult {
        if (rawInput.isNullOrBlank()) {
            return ShareLinkParseResult.InvalidUrl("URL cannot be empty.")
        }

        val trimmed = rawInput.trim()

        val schemeRegex = "^([a-zA-Z][a-zA-Z0-9+.-]*):\\/\\/".toRegex()
        val schemeMatch = schemeRegex.find(trimmed)

        val normalizedUrl = if (schemeMatch == null) {
            "https://$trimmed"
        } else {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") {
                return ShareLinkParseResult.InvalidUrl(
                    "Unsupported scheme '$scheme'. Only HTTP and HTTPS URLs are supported."
                )
            }
            trimmed
        }

        val uri: URI = try {
            URI(normalizedUrl)
        } catch (e: Exception) {
            return ShareLinkParseResult.InvalidUrl("Malformed URL: ${e.message ?: "Invalid syntax"}")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return ShareLinkParseResult.InvalidUrl("URL host is missing or invalid.")
        }

        if (!isRecognizedHost(host)) {
            return ShareLinkParseResult.UnsupportedHost(
                host = host,
                reason = "Host '$host' is not a recognized Terabox or mirror domain."
            )
        }

        val queryParams = parseQueryParams(uri.rawQuery)
        val pwd = queryParams["pwd"]?.trim()?.ifBlank { null }

        val path = uri.path ?: ""
        var extractedSurl: String? = null

        if (path.startsWith("/s/") || path == "/s") {
            val afterS = path.removePrefix("/s/").removePrefix("/s").trim()
            val segment = afterS.substringBefore('/').trim()
            if (segment.isNotEmpty()) {
                extractedSurl = segment
            } else {
                return ShareLinkParseResult.InvalidUrl("Missing share identifier in URL path.")
            }
        }

        if (extractedSurl == null) {
            val surlQuery = queryParams["surl"]
            if (surlQuery != null) {
                if (surlQuery.isNotBlank()) {
                    extractedSurl = surlQuery.trim()
                } else {
                    return ShareLinkParseResult.InvalidUrl("Missing share identifier in 'surl' query parameter.")
                }
            }
        }

        if (extractedSurl.isNullOrBlank()) {
            return ShareLinkParseResult.InvalidUrl(
                "URL does not contain a recognized Terabox share path ('/s/...') or 'surl' parameter."
            )
        }

        val shareLink = ShareLink(
            originalUrl = trimmed,
            surl = extractedSurl,
            pwd = pwd,
            host = host.lowercase()
        )

        return ShareLinkParseResult.Success(shareLink)
    }

    fun isRecognizedHost(host: String): Boolean {
        val lowerHost = host.lowercase()
        return supportedDomains.any { domain ->
            lowerHost == domain || lowerHost.endsWith(".$domain")
        }
    }

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
        } catch (_: Exception) {
            value
        }
    }
}
