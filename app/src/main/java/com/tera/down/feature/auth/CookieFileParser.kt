package com.tera.down.feature.auth

import com.tera.down.feature.resolver.SimpleJsonParser

/**
 * Represents a parsed cookie with security attributes and expiry information.
 */
data class ParsedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochSeconds: Long? = null,
    val isSecure: Boolean = false,
    val isHttpOnly: Boolean = false
) {
    /**
     * Determines whether the cookie has expired based on current epoch time.
     * Non-positive expiry timestamps represent session cookies, which are considered active.
     */
    val isExpired: Boolean
        get() {
            val exp = expiresAtEpochSeconds ?: return false
            if (exp <= 0L) return false
            return exp < (System.currentTimeMillis() / 1000L)
        }
}

enum class CookieFileFormat {
    NETSCAPE,
    JSON,
    RAW_HEADER
}

sealed interface CookieFileParseResult {
    data class Success(
        val cookies: List<ParsedCookie>,
        val format: CookieFileFormat
    ) : CookieFileParseResult

    data class Failure(val reason: String) : CookieFileParseResult
}

/**
 * Parser supporting documented cookie file formats:
 * 1. Netscape / Mozilla HTTP Cookie File (standard cookies.txt format, including #HttpOnly_ prefixes)
 * 2. JSON cookie exports (EditThisCookie, Cookie-Editor, or TeraboxSession export format)
 * 3. Raw HTTP Cookie header strings (e.g. from browser DevTools / cURL)
 */
object CookieFileParser {

    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"

    fun parse(rawContent: String): CookieFileParseResult {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) {
            return CookieFileParseResult.Failure("Cookie file is empty.")
        }

        // 1. Attempt JSON parsing if content begins with '{' or '['
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonResult = parseJson(trimmed)
            if (jsonResult is CookieFileParseResult.Success) {
                return jsonResult
            }
        }

        // 2. Attempt Netscape cookies.txt parsing
        val netscapeResult = parseNetscape(trimmed)
        if (netscapeResult is CookieFileParseResult.Success) {
            return netscapeResult
        }

        // 3. Attempt Raw Cookie Header parsing (e.g. "ndus=...; panweb=1" or "Cookie: ...")
        val rawHeaderResult = parseRawHeader(trimmed)
        if (rawHeaderResult is CookieFileParseResult.Success) {
            return rawHeaderResult
        }

        return CookieFileParseResult.Failure(
            "Malformed or unrecognized cookie file format. Supported formats: Netscape cookies.txt and JSON cookie exports."
        )
    }

    private fun parseNetscape(raw: String): CookieFileParseResult {
        val lines = raw.lines()
        val cookies = mutableListOf<ParsedCookie>()
        var recognizedLines = 0

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            // Standard comments that do NOT encode HttpOnly cookies
            if (trimmedLine.startsWith("#") && !trimmedLine.startsWith(HTTP_ONLY_PREFIX, ignoreCase = true)) {
                continue
            }

            val isHttpOnly = trimmedLine.startsWith(HTTP_ONLY_PREFIX, ignoreCase = true)
            val effectiveLine = if (isHttpOnly) {
                trimmedLine.substring(HTTP_ONLY_PREFIX.length).trim()
            } else {
                trimmedLine
            }

            // Split by tab first (standard Netscape format); fall back to whitespace if tab absent
            val tokens = if (effectiveLine.contains("\t")) {
                effectiveLine.split("\t").map { it.trim() }
            } else {
                effectiveLine.split(Regex("""\s+""")).map { it.trim() }
            }

            // Netscape format has 7 columns:
            // 0: domain, 1: includeSubdomains, 2: path, 3: secure, 4: expires, 5: name, 6: value
            if (tokens.size >= 6) {
                val domain = tokens[0]
                val path = tokens[2].ifBlank { "/" }
                val secure = tokens[3].equals("TRUE", ignoreCase = true)
                val expires = tokens[4].toLongOrNull()
                val name = tokens[5]
                val value = if (tokens.size >= 7) tokens.subList(6, tokens.size).joinToString(" ") else ""

                if (name.isNotBlank() && domain.isNotBlank()) {
                    cookies.add(
                        ParsedCookie(
                            name = name,
                            value = value,
                            domain = domain,
                            path = path,
                            expiresAtEpochSeconds = expires,
                            isSecure = secure,
                            isHttpOnly = isHttpOnly
                        )
                    )
                    recognizedLines++
                }
            }
        }

        return if (cookies.isNotEmpty()) {
            CookieFileParseResult.Success(cookies, CookieFileFormat.NETSCAPE)
        } else {
            CookieFileParseResult.Failure("No valid Netscape cookie entries found.")
        }
    }

    private fun parseJson(raw: String): CookieFileParseResult {
        return try {
            val parsed = SimpleJsonParser.parseAny(raw) ?: return CookieFileParseResult.Failure("Invalid JSON structure.")
            val cookies = mutableListOf<ParsedCookie>()

            when (parsed) {
                is List<*> -> {
                    // Array of cookie objects (standard browser extension export format)
                    for (item in parsed) {
                        if (item is Map<*, *>) {
                            extractCookieFromMap(item)?.let { cookies.add(it) }
                        }
                    }
                }
                is Map<*, *> -> {
                    // Object with "cookies" array
                    val cookiesList = parsed["cookies"] as? List<*>
                    if (cookiesList != null) {
                        for (item in cookiesList) {
                            if (item is Map<*, *>) {
                                extractCookieFromMap(item)?.let { cookies.add(it) }
                            }
                        }
                    } else if (parsed.containsKey("cookieString")) {
                        // TeraboxSession format with raw cookieString
                        val cStr = parsed["cookieString"]?.toString().orEmpty()
                        val dom = parsed["domain"]?.toString() ?: "terabox.com"
                        return parseRawHeader(cStr, dom)
                    } else {
                        // Key-value dictionary where keys are cookie names
                        for ((k, v) in parsed) {
                            val key = k?.toString()?.trim().orEmpty()
                            val value = v?.toString()?.trim().orEmpty()
                            if (key.isNotBlank()) {
                                cookies.add(
                                    ParsedCookie(
                                        name = key,
                                        value = value,
                                        domain = ".terabox.com"
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (cookies.isNotEmpty()) {
                CookieFileParseResult.Success(cookies, CookieFileFormat.JSON)
            } else {
                CookieFileParseResult.Failure("No valid cookies found in JSON content.")
            }
        } catch (e: Exception) {
            CookieFileParseResult.Failure("Failed to parse JSON cookie export: ${e.message}")
        }
    }

    private fun extractCookieFromMap(map: Map<*, *>): ParsedCookie? {
        val name = (map["name"] ?: map["key"])?.toString()?.trim().orEmpty()
        val value = (map["value"])?.toString()?.trim().orEmpty()
        val domain = (map["domain"] ?: map["host"])?.toString()?.trim().orEmpty().ifBlank { ".terabox.com" }
        val path = (map["path"])?.toString()?.trim().orEmpty().ifBlank { "/" }

        if (name.isBlank()) return null

        val expiresRaw = map["expirationDate"] ?: map["expires"] ?: map["expiry"]
        val expiresSeconds = when (expiresRaw) {
            is Number -> {
                val num = expiresRaw.toLong()
                if (num > 10_000_000_000L) num / 1000L else num
            }
            is String -> {
                val num = expiresRaw.toDoubleOrNull()?.toLong()
                if (num != null && num > 10_000_000_000L) num / 1000L else num
            }
            else -> null
        }

        val secure = map["secure"] == true || map["secure"]?.toString()?.equals("true", ignoreCase = true) == true
        val httpOnly = map["httpOnly"] == true || map["httpOnly"]?.toString()?.equals("true", ignoreCase = true) == true

        return ParsedCookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            expiresAtEpochSeconds = expiresSeconds,
            isSecure = secure,
            isHttpOnly = httpOnly
        )
    }

    private fun parseRawHeader(raw: String, defaultDomain: String = ".terabox.com"): CookieFileParseResult {
        var clean = raw.trim()
        if (clean.startsWith("Cookie:", ignoreCase = true)) {
            clean = clean.substring("Cookie:".length).trim()
        }

        val pairs = clean.split(";").map { it.trim() }.filter { it.isNotBlank() }
        val cookies = mutableListOf<ParsedCookie>()

        for (pair in pairs) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val name = pair.substring(0, eqIdx).trim()
                val value = pair.substring(eqIdx + 1).trim()
                if (name.isNotBlank()) {
                    cookies.add(
                        ParsedCookie(
                            name = name,
                            value = value,
                            domain = defaultDomain
                        )
                    )
                }
            }
        }

        return if (cookies.isNotEmpty()) {
            CookieFileParseResult.Success(cookies, CookieFileFormat.RAW_HEADER)
        } else {
            CookieFileParseResult.Failure("Unable to parse as cookie header.")
        }
    }
}
