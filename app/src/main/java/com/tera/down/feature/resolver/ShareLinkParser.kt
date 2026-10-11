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
        "terabox.app",
        "1024tera.com",
        "1024terabox.com",
        "teraboxshare.com",
        "teraboxlink.com",
        "terasharelink.com",
        "terafileshare.com",
        "tibibox.com",
        "freeterabox.com",
        "dubox.com",
        "mirrobox.com",
        "nephobox.com",
        "4funbox.com",
        "momerybox.com",
        "terabox.fun",
        "terafast.me",
        "terashare.in",
        "box.sharing.in"
    )

    private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val TEXT_PWD_REGEX = Regex("""(?:pwd|code|password|pass|提取码|提取碼)[:\s=]+([a-zA-Z0-9]{4})""", RegexOption.IGNORE_CASE)

    override fun canParse(rawInput: String?): Boolean {
        if (rawInput.isNullOrBlank()) return false
        val trimmed = rawInput.trim()
        val candidate = URL_IN_TEXT_REGEX.find(trimmed)?.value ?: trimmed
        val normalized = if (!candidate.contains("://")) "https://$candidate" else candidate
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
        val extractedUrl = URL_IN_TEXT_REGEX.find(trimmed)?.value ?: trimmed

        val schemeRegex = "^([a-zA-Z][a-zA-Z0-9+.-]*):\\/\\/".toRegex()
        val schemeMatch = schemeRegex.find(extractedUrl)

        val normalizedUrl = if (schemeMatch == null) {
            "https://$extractedUrl"
        } else {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") {
                return ShareLinkParseResult.InvalidUrl(
                    "Unsupported scheme '$scheme'. Only HTTP and HTTPS URLs are supported."
                )
            }
            extractedUrl
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
        val fragmentParams = parseQueryParams(uri.rawFragment)
        var pwd = queryParams["pwd"]?.trim()?.ifBlank { null }
            ?: fragmentParams["pwd"]?.trim()?.ifBlank { null }

        // If no password in URL query, extract from text if user pasted message
        if (pwd == null) {
            pwd = TEXT_PWD_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        }

        val path = uri.path ?: ""
        val fragment = uri.fragment ?: ""
        var extractedSurl: String? = null

        // 1. Direct /s/ path extraction
        if (path.startsWith("/s/") || path == "/s") {
            val afterS = path.removePrefix("/s/").removePrefix("/s").trim()
            val segment = afterS.substringBefore('/').trim()
            if (segment.isNotEmpty()) {
                extractedSurl = segment
            } else {
                return ShareLinkParseResult.InvalidUrl("Missing share identifier in URL path.")
            }
        }

        // 2. Explicit 'surl' query parameter
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

        // 3. Known endpoint paths that require surl query parameter
        val endpointPaths = listOf("/wap/share/filelist", "/share/init", "/share/list", "/sharing/link", "/disk/share")
        if (extractedSurl == null && endpointPaths.any { path.equals(it, ignoreCase = true) }) {
            return ShareLinkParseResult.InvalidUrl("Missing share identifier parameter in endpoint URL.")
        }

        // 4. Alternative share paths (e.g. /share/<surl>, /sharing/<surl>, /wap/link/<surl>)
        if (extractedSurl == null) {
            val knownEndpointSegments = setOf("filelist", "init", "list", "link", "share", "main")
            val altPrefixes = listOf("/share/", "/sharing/", "/wap/link/")
            for (prefix in altPrefixes) {
                if (path.startsWith(prefix, ignoreCase = true)) {
                    val after = path.substring(prefix.length).trim()
                    val segment = after.substringBefore('/').trim()
                    if (segment.isNotEmpty() && !knownEndpointSegments.contains(segment.lowercase())) {
                        extractedSurl = segment
                        break
                    }
                }
            }
        }

        // 5. Fragment-based extraction for SPA hash routing (e.g. #/s/1xxxx or #/share/1xxxx)
        if (extractedSurl == null && fragment.isNotBlank()) {
            for (prefix in listOf("/s/", "s/", "/share/", "share/")) {
                if (fragment.contains(prefix, ignoreCase = true)) {
                    val after = fragment.substring(fragment.indexOf(prefix, ignoreCase = true) + prefix.length).trim()
                    val segment = after.substringBefore('/').substringBefore('&').substringBefore('?').trim()
                    if (segment.isNotEmpty()) {
                        extractedSurl = segment
                        break
                    }
                }
            }
        }

        // 6. Secondary query parameter extraction (?shorturl=..., ?shareid=..., ?share_id=..., ?id=...)
        if (extractedSurl == null) {
            val queryCandidates = listOf("shorturl", "shareid", "share_id", "id")
            for (candidate in queryCandidates) {
                val value = queryParams[candidate]?.trim()
                if (!value.isNullOrBlank()) {
                    extractedSurl = value
                    break
                }
            }
        }

        // 7. Query param from fragment (e.g. #...?surl=...)
        if (extractedSurl == null) {
            val queryCandidates = listOf("surl", "shorturl", "shareid", "share_id", "id")
            for (candidate in queryCandidates) {
                val value = fragmentParams[candidate]?.trim()
                if (!value.isNullOrBlank()) {
                    extractedSurl = value
                    break
                }
            }
        }

        if (extractedSurl.isNullOrBlank()) {
            return ShareLinkParseResult.InvalidUrl(
                "URL does not contain a recognized Terabox share path ('/s/...') or share identifier parameter."
            )
        }

        val shareLink = ShareLink(
            originalUrl = extractedUrl,
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
