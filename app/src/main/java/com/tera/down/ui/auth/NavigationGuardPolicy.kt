package com.tera.down.ui.auth

import com.tera.down.feature.resolver.TeraboxShareLinkParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Action determined by [NavigationGuardPolicy] for an attempted navigation URL.
 */
sealed interface NavigationAction {
    /**
     * Navigation is permitted and should proceed normally within the WebView.
     */
    data object Allow : NavigationAction

    /**
     * Navigation is blocked (e.g. app store, app deep link, tracker) and consumed in-app.
     */
    data class BlockAndStay(val reason: String, val promoHits: Int) : NavigationAction

    /**
     * An intent deep link was intercepted and contained a safe browser fallback URL
     * pointing to an approved Terabox host.
     */
    data class LoadFallback(val fallbackUrl: String, val promoHits: Int) : NavigationAction

    /**
     * Intercepted promo/launch attempts reached the limit without progress.
     * The app should stop automatic attempts and present manual recovery options.
     */
    data class MaxPromoHitsExceeded(val hits: Int, val lastUrl: String) : NavigationAction
}

/**
 * Pure, unit-testable navigation guard implementing Layer B (Section 18.4).
 *
 * Enforces:
 * - Scheme restrictions: blocks `intent://`, `market://`, `terabox://`.
 * - Defensive intent fallback extraction for verified Terabox domains.
 * - Host filtering: blocks Google Play, App Store, and adjust/install redirect hosts.
 * - Caps promo hits at [maxPromoHits] (default 3) to prevent endless redirect loops.
 */
class NavigationGuardPolicy(
    private val maxPromoHits: Int = DEFAULT_MAX_PROMO_HITS
) {
    companion object {
        const val DEFAULT_MAX_PROMO_HITS = 3

        val APP_STORE_HOSTS = setOf(
            "play.google.com",
            "market.android.com",
            "apps.apple.com"
        )

        val APP_INSTALL_TRACKER_HOSTS = setOf(
            "app.adjust.com",
            "adjust.com",
            "branch.io",
            "app.link",
            "kochava.com",
            "appsflyer.com"
        )
    }

    private var promoHitsCount = 0

    val promoHits: Int
        get() = promoHitsCount

    /**
     * Evaluates an attempted navigation [urlStr].
     */
    fun evaluateUrl(urlStr: String?): NavigationAction {
        if (urlStr.isNullOrBlank()) {
            return NavigationAction.BlockAndStay("Empty or null URL", promoHitsCount)
        }

        val trimmedUrl = urlStr.trim()

        // 1. Check for intent:// scheme
        if (trimmedUrl.startsWith("intent:", ignoreCase = true)) {
            promoHitsCount++
            if (promoHitsCount >= maxPromoHits) {
                return NavigationAction.MaxPromoHitsExceeded(promoHitsCount, trimmedUrl)
            }

            val fallbackUrl = extractBrowserFallbackUrl(trimmedUrl)
            if (fallbackUrl != null && isSafeApprovedTeraboxUrl(fallbackUrl)) {
                return NavigationAction.LoadFallback(fallbackUrl, promoHitsCount)
            }
            return NavigationAction.BlockAndStay("Blocked intent scheme without valid fallback", promoHitsCount)
        }

        // 2. Check for other non-http(s) schemes (market://, terabox://, etc.)
        val scheme = try {
            val colonIdx = trimmedUrl.indexOf(':')
            if (colonIdx > 0) trimmedUrl.substring(0, colonIdx).lowercase() else ""
        } catch (_: Exception) {
            ""
        }

        if (scheme != "http" && scheme != "https") {
            promoHitsCount++
            if (promoHitsCount >= maxPromoHits) {
                return NavigationAction.MaxPromoHitsExceeded(promoHitsCount, trimmedUrl)
            }
            return NavigationAction.BlockAndStay("Blocked non-http scheme: $scheme", promoHitsCount)
        }

        // 3. Evaluate HTTPS hosts
        val host = try {
            URI(trimmedUrl).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }

        if (APP_STORE_HOSTS.any { host.contains(it) } || APP_INSTALL_TRACKER_HOSTS.any { host.contains(it) }) {
            promoHitsCount++
            if (promoHitsCount >= maxPromoHits) {
                return NavigationAction.MaxPromoHitsExceeded(promoHitsCount, trimmedUrl)
            }
            return NavigationAction.BlockAndStay("Blocked app store / tracker navigation to host: $host", promoHitsCount)
        }

        // Normal legitimate navigation allowed (share page, login portal, cloudflare challenge)
        return NavigationAction.Allow
    }

    /**
     * Safely extracts browser_fallback_url from an intent string.
     */
    fun extractBrowserFallbackUrl(intentUriString: String): String? {
        // e.g. "intent://...#Intent;...;S.browser_fallback_url=https%3A%2F%2Fterabox.com%2Fs%2F1abc;end"
        val regexS = Regex("""S\.browser_fallback_url=([^;]+)""")
        val matchS = regexS.find(intentUriString)
        if (matchS != null) {
            val encoded = matchS.groupValues[1]
            return try {
                URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
            } catch (_: Exception) {
                encoded
            }
        }

        // Query parameter fallback e.g. "?browser_fallback_url=https://..."
        val regexParam = Regex("""[?&]browser_fallback_url=([^&#]+)""")
        val matchParam = regexParam.find(intentUriString)
        if (matchParam != null) {
            val encoded = matchParam.groupValues[1]
            return try {
                URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
            } catch (_: Exception) {
                encoded
            }
        }

        return null
    }

    /**
     * Validates that the fallback URL is HTTPS and belongs to a recognized Terabox host.
     */
    fun isSafeApprovedTeraboxUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return false
        }
        val host = try {
            URI(url).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        return TeraboxShareLinkParser.isRecognizedHost(host)
    }

    /**
     * Resets promo hits counter.
     */
    fun resetPromoHits() {
        promoHitsCount = 0
    }
}
