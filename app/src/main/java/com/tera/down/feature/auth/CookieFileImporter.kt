package com.tera.down.feature.auth

import android.webkit.CookieManager
import com.tera.down.feature.resolver.TeraboxShareLinkParser
import com.tera.down.ui.auth.DesktopWebViewConfigurator
import java.io.InputStream

/**
 * Outcome of importing and validating a Terabox cookie file per Specification Section 17.3 & 17.5.
 */
sealed interface CookieImportResult {
    data class Success(
        val domain: String,
        val cookieCount: Int,
        val expiryEstimate: Long?
    ) : CookieImportResult

    data class InvalidFormat(val reason: String) : CookieImportResult
    data class IrrelevantDomains(val reason: String) : CookieImportResult
    data class ExpiredCookies(val reason: String) : CookieImportResult
    data class MissingAuthTokens(val reason: String) : CookieImportResult
    data class RejectedSession(val reason: String) : CookieImportResult
    data class NetworkFailure(val reason: String) : CookieImportResult
}

/**
 * Handles end-to-end import, security filtering, session synchronization, and server validation
 * for imported Terabox cookie files.
 */
class CookieFileImporter(
    private val sessionRepository: SessionRepository,
    private val sessionValidator: SessionValidator
) {

    companion object {
        /**
         * Validates that [rawDomain] belongs to legitimate Terabox and Baidu infrastructure.
         * Drops any third-party or arbitrary domains supplied by the file.
         */
        fun isTeraboxDomain(rawDomain: String): Boolean {
            val clean = rawDomain.trim().trimStart('.').lowercase()
            if (clean.isBlank()) return false

            val isSupportedRoot = TeraboxShareLinkParser.supportedDomains.any { sup ->
                clean == sup || clean.endsWith(".$sup")
            }
            val isBaidu = clean == "baidu.com" || clean.endsWith(".baidu.com")
            return isSupportedRoot || isBaidu
        }
    }

    /**
     * Reads and imports cookies from an [InputStream].
     * Releases stream immediately and does not retain the file on disk.
     */
    suspend fun importFromStream(inputStream: InputStream): CookieImportResult {
        val rawContent = try {
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return CookieImportResult.InvalidFormat("Unable to read selected cookie file: ${e.message}")
        }
        return importFromText(rawContent)
    }

    /**
     * Imports, filters, synchronizes, and validates cookies from raw text content.
     */
    suspend fun importFromText(rawContent: String): CookieImportResult {
        // 1. Format validation and parsing
        val parseResult = CookieFileParser.parse(rawContent)
        if (parseResult is CookieFileParseResult.Failure) {
            return CookieImportResult.InvalidFormat(parseResult.reason)
        }

        val allCookies = (parseResult as CookieFileParseResult.Success).cookies

        // 2. Domain Security Filtering: Keep only legitimate Terabox domains (Requirement 4)
        val teraboxCookies = allCookies.filter { isTeraboxDomain(it.domain) }
        if (teraboxCookies.isEmpty()) {
            return CookieImportResult.IrrelevantDomains(
                "No cookies found for supported Terabox domains. The selected file contains cookies for unrelated sites."
            )
        }

        // 3. Expiry Validation (Requirement 3)
        val activeCookies = teraboxCookies.filter { !it.isExpired }
        if (activeCookies.isEmpty()) {
            return CookieImportResult.ExpiredCookies(
                "All Terabox cookies in the imported file have expired. Please export a fresh session from your browser."
            )
        }

        // 4. Authentication Token Verification (Requirement 3 & Section 17.5)
        val lowerNames = activeCookies.map { it.name.lowercase() }
        val hasAuthToken = DefaultSessionValidator.AUTH_COOKIE_MARKERS.any { marker ->
            val cleanMarker = marker.trimEnd('=').lowercase()
            lowerNames.contains(cleanMarker)
        }

        if (!hasAuthToken) {
            return CookieImportResult.MissingAuthTokens(
                "Cookies do not contain an authenticated Terabox user token (e.g. ndus). Please ensure you are logged into your Terabox account when exporting cookies."
            )
        }

        // 5. Construct candidate session
        val primaryDomain = activeCookies
            .firstOrNull { it.domain.contains("terabox.com") }
            ?.domain?.trimStart('.')
            ?: activeCookies.first().domain.trimStart('.')

        val cookieHeader = activeCookies.joinToString("; ") { "${it.name}=${it.value}" }
        val earliestExpirySeconds = activeCookies
            .mapNotNull { it.expiresAtEpochSeconds }
            .filter { it > 0L }
            .minOrNull()
        val expiryEstimate = earliestExpirySeconds?.let { it * 1000L }

        val candidateSession = TeraboxSession(
            domain = primaryDomain.ifBlank { "terabox.com" },
            cookieString = cookieHeader,
            userAgent = DesktopWebViewConfigurator.desktopUserAgent(null),
            savedAt = System.currentTimeMillis(),
            lastOkAt = System.currentTimeMillis(),
            expiryEstimate = expiryEstimate
        )

        // 6. Online Server Validation (Requirement 6: Never assume validity without verification)
        val validationResult = sessionValidator.validateSession(candidateSession)
        val verifiedDomain = when (validationResult) {
            is SessionValidationResult.Valid -> validationResult.verifiedDomain ?: candidateSession.domain
            is SessionValidationResult.NetworkError -> {
                return CookieImportResult.NetworkFailure(
                    "Network error verifying session with Terabox servers: ${validationResult.reason}"
                )
            }
            is SessionValidationResult.ServerError -> {
                return CookieImportResult.NetworkFailure(
                    "Server error (${validationResult.code}) verifying session: ${validationResult.message}"
                )
            }
            is SessionValidationResult.NotLoggedIn -> {
                return CookieImportResult.RejectedSession(
                    "Terabox server rejected the imported session: ${validationResult.reason}"
                )
            }
            is SessionValidationResult.InvalidTokens -> {
                return CookieImportResult.MissingAuthTokens(
                    validationResult.reason
                )
            }
            is SessionValidationResult.UnexpectedResponse -> {
                return CookieImportResult.RejectedSession(
                    "Unexpected response from server: ${validationResult.reason}"
                )
            }
        }

        val verifiedSession = candidateSession.copy(domain = verifiedDomain)

        // 7. Synchronize with Android WebView CookieManager (Requirement 5)
        synchronizeCookieManager(activeCookies, verifiedSession.domain)

        // 8. Persist authenticated session securely (Requirement 7)
        sessionRepository.saveSession(verifiedSession)

        return CookieImportResult.Success(
            domain = verifiedSession.domain,
            cookieCount = activeCookies.size,
            expiryEstimate = expiryEstimate
        )
    }

    private fun synchronizeCookieManager(cookies: List<ParsedCookie>, primaryDomain: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val targetHosts = setOf(primaryDomain, "www.terabox.com", "terabox.com")
            for (host in targetHosts) {
                val hostUrl = "https://$host"
                for (c in cookies) {
                    val cookieStr = buildString {
                        append("${c.name}=${c.value}; Path=${c.path}")
                        if (c.domain.isNotBlank()) append("; Domain=${c.domain}")
                        if (c.isSecure) append("; Secure")
                        if (c.isHttpOnly) append("; HttpOnly")
                    }
                    cookieManager.setCookie(hostUrl, cookieStr)
                }
            }
            cookieManager.flush()
        } catch (_: Throwable) {
            // Gracefully ignore in non-Android or unit test environments
        }
    }
}
