package com.tera.down.feature.resolver.classification

import com.tera.down.core.model.ShareLink
import com.tera.down.feature.resolver.ShareResolveErrorType
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.SimpleJsonObject
import com.tera.down.feature.resolver.SimpleJsonParser

/**
 * Central response classifier implementing Section 17.1 of the Terabox Downloader specification.
 *
 * Inspects HTTP response status, final redirect URL, Content-Type, and a bounded body sample
 * to accurately identify valid JSON, login barriers, CAPTCHA/human challenges, promo screens,
 * and unknown HTML.
 */
class TeraboxResponseClassifier(
    private val humanCheckMarkers: List<String> = DEFAULT_HUMAN_CHECK_MARKERS,
    private val loginPathMarkers: List<String> = DEFAULT_LOGIN_PATH_MARKERS,
    private val appPromoMarkers: List<String> = DEFAULT_APP_PROMO_MARKERS,
    private val appOnlyMarkers: List<String> = DEFAULT_APP_ONLY_MARKERS
) {

    companion object {
        const val MAX_BODY_SAMPLE_CHARS = 20000

        val DEFAULT_HUMAN_CHECK_MARKERS = listOf(
            "captcha",
            "verify you are human",
            "security check",
            "checking your browser",
            "challenge",
            "robot",
            "recaptcha",
            "hcaptcha",
            "geetest",
            "cf-browser-verification",
            "cf-turnstile",
            "access denied",
            "rate limit",
            "too many requests",
            "waf",
            "please verify"
        )

        val DEFAULT_LOGIN_PATH_MARKERS = listOf(
            "passport.",
            "login.",
            "/login",
            "/signin",
            "/passport"
        )

        val DEFAULT_APP_PROMO_MARKERS = listOf(
            "download app",
            "open in app",
            "get the app",
            "install the app",
            "scan to download",
            "download in the app",
            "smart-app-banner"
        )

        val DEFAULT_APP_ONLY_MARKERS = listOf(
            "download only in the app",
            "only available in the app",
            "view in the app only",
            "offered in the app only",
            "can only be downloaded in the app",
            "large files can only be downloaded via app",
            "download in app only"
        )

        private val FILE_LIST_MARKERS = listOf(
            "server_filename",
            "dlink",
            "shareid",
            "share_id",
            "fs_id",
            "category"
        )
    }

    /**
     * Classifies an HTTP response received from a Terabox endpoint.
     *
     * @param statusCode HTTP response code (e.g. 200, 401, 429)
     * @param finalUrl The final URL after redirect resolution
     * @param contentType The Content-Type response header, if present
     * @param rawBody The response body string
     * @param shareLink Optional context about the share link being resolved
     * @param providedPassword Optional password provided by the user
     */
    fun classify(
        statusCode: Int,
        finalUrl: String,
        contentType: String?,
        rawBody: String?,
        shareLink: ShareLink? = null,
        providedPassword: String? = null
    ): ResponseVerdict {
        val bodyTrimmed = rawBody.orEmpty().trim()

        // 1. Handle HTTP 429 / 401 directly if status indicates unambiguous barrier
        if (statusCode == 429) {
            return ResponseVerdict.CaptchaOrRateLimit(
                reason = "Service returned HTTP 429 rate limit or verification requirement."
            )
        }
        if (statusCode == 401) {
            return ResponseVerdict.NotLoggedIn(
                reason = "Service returned HTTP 401 authentication required."
            )
        }

        // 2. Handle empty body
        if (bodyTrimmed.isEmpty()) {
            return if (statusCode in 200..299) {
                ResponseVerdict.ResolverError(
                    ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.MALFORMED_RESPONSE,
                        message = "Server returned an empty response body."
                    )
                )
            } else {
                ResponseVerdict.HttpError(
                    statusCode = statusCode,
                    message = "HTTP error $statusCode with empty response body."
                )
            }
        }

        // 3. Robust JSON vs HTML sniffing (ignoring incorrect Content-Type headers)
        // If content starts with JSON delimiters, attempt JSON parse first
        val firstChar = bodyTrimmed.firstOrNull()
        val looksLikeJson = firstChar == '{' || firstChar == '['
        val looksLikeHtml = firstChar == '<' || contentType?.contains("html", ignoreCase = true) == true

        if (looksLikeJson || !looksLikeHtml) {
            val json = SimpleJsonParser.parse(bodyTrimmed)
            if (json != null) {
                return mapJsonPayload(json, shareLink, providedPassword)
            }
            // If it looked like JSON but parse failed and doesn't look like HTML, report malformed JSON
            if (!looksLikeHtml) {
                return ResponseVerdict.ResolverError(
                    ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.MALFORMED_RESPONSE,
                        message = "Failed to parse service response as JSON."
                    )
                )
            }
        }

        // 4. At this point, the response is HTML or HTML-like
        val sampleLen = minOf(bodyTrimmed.length, MAX_BODY_SAMPLE_CHARS)
        val bodySample = bodyTrimmed.substring(0, sampleLen).lowercase()
        val finalUrlLower = finalUrl.lowercase()

        // Step A: Inspect final redirect URL: App Promo / App Store evaluated before login per Section 2
        if (finalUrlLower.startsWith("market://") ||
            finalUrlLower.startsWith("intent://") ||
            finalUrlLower.startsWith("terabox://") ||
            finalUrlLower.contains("play.google.com") ||
            finalUrlLower.contains("apps.apple.com")
        ) {
            return ResponseVerdict.AppPromo("Redirected to app installation destination: $finalUrl")
        }

        for (marker in loginPathMarkers) {
            if (finalUrlLower.contains(marker)) {
                return ResponseVerdict.NotLoggedIn(
                    reason = "Redirected to login endpoint: $finalUrl",
                    redirectUrl = finalUrl
                )
            }
        }

        // Step B: Check App-Only and App-Promo screens (evaluated before login markers per section 18.5/18.6)
        val hasFileListMarkers = FILE_LIST_MARKERS.any { bodySample.contains(it) }
        if (!hasFileListMarkers) {
            for (marker in appOnlyMarkers) {
                if (bodySample.contains(marker)) {
                    return ResponseVerdict.AppOnly("Offered in the official app only ('$marker')")
                }
            }
            for (marker in appPromoMarkers) {
                if (bodySample.contains(marker)) {
                    return ResponseVerdict.AppPromo("Detected app promotion screen ('$marker')")
                }
            }
        }

        // Step C: Check Login / Authentication barriers
        val hasPasswordInput = bodySample.contains("type=\"password\"") || bodySample.contains("type='password'")
        val hasLoginForm = bodySample.contains("<form") && (
            bodySample.contains("login") ||
            bodySample.contains("passport") ||
            bodySample.contains("signin")
        )
        val hasLoginMarkers = bodySample.contains("login-box") ||
            bodySample.contains("login-form") ||
            bodySample.contains("passport-login") ||
            bodySample.contains("sign in to terabox") ||
            bodySample.contains("log in to terabox") ||
            bodySample.contains("user login")

        if (hasPasswordInput && (hasLoginForm || hasLoginMarkers)) {
            return ResponseVerdict.NotLoggedIn(
                reason = "Login form with password input detected in web response."
            )
        }
        if (hasLoginMarkers) {
            return ResponseVerdict.NotLoggedIn(
                reason = "Authentication portal detected in web response."
            )
        }

        // Step D: Check Human-Verification & Browser-Check markers
        for (marker in humanCheckMarkers) {
            if (bodySample.contains(marker)) {
                return ResponseVerdict.CaptchaOrRateLimit(
                    reason = "Human verification or browser check encountered ($marker).",
                    markerMatched = marker
                )
            }
        }

        // Step E: Unrecognized HTML — record redacted sample and return UnknownHtml
        val redactedSample = DiagnosticsManager.recordSample(
            rawHtml = bodyTrimmed,
            statusCode = statusCode,
            finalUrl = finalUrl,
            verdict = "UNKNOWN_HTML"
        )
        return ResponseVerdict.UnknownHtml(
            sample = redactedSample,
            reason = "Received unrecognized HTML document instead of metadata."
        )
    }

    /**
     * Maps parsed Terabox JSON payload and errno codes per Section 4 specification.
     */
    private fun mapJsonPayload(
        json: SimpleJsonObject,
        shareLink: ShareLink?,
        providedPassword: String?
    ): ResponseVerdict {
        val errno = json.optInt("errno", 0)
        val errmsg = json.optString("errmsg", "")

        if (errno == 0) {
            return ResponseVerdict.Ok(json)
        }

        // Auth requirement
        if (errno == -6 || errno == 401 || errmsg.contains("login", ignoreCase = true)) {
            return ResponseVerdict.NotLoggedIn(
                reason = "Session expired or authentication required (errno $errno: $errmsg)"
            )
        }

        // CAPTCHA / rate limit
        if (errno == 9000 || errno == -2 || errmsg.contains("captcha", ignoreCase = true) || errmsg.contains("risk", ignoreCase = true)) {
            return ResponseVerdict.CaptchaOrRateLimit(
                reason = "Service requested verification or rate limited (errno $errno: $errmsg)"
            )
        }

        // Password required or incorrect
        if (errno == -9 || errno == 2153 || errmsg.contains("pwd", ignoreCase = true) || errmsg.contains("password", ignoreCase = true) || errmsg.contains("extraction", ignoreCase = true)) {
            val link = shareLink ?: ShareLink(originalUrl = "", surl = "", host = "terabox.com")
            val result = if (!providedPassword.isNullOrBlank()) {
                ShareResolveResult.WrongPassword(link, "Incorrect extraction password.")
            } else {
                ShareResolveResult.NeedsPassword(link, "Password required to access this share.")
            }
            return ResponseVerdict.ResolverError(result)
        }

        // Expired
        if (errno == -1 || errno == 71000 || errmsg.contains("expired", ignoreCase = true)) {
            return ResponseVerdict.ResolverError(ShareResolveResult.Expired("This share link has expired."))
        }

        // Cancelled / deleted
        if (errno == -7 || errno == 110 || errmsg.contains("cancelled", ignoreCase = true) || errmsg.contains("deleted", ignoreCase = true)) {
            return ResponseVerdict.ResolverError(ShareResolveResult.Cancelled("This share has been cancelled or removed."))
        }

        // Blocked / policy violation
        if (errno == -12 || errno == -10 || errmsg.contains("block", ignoreCase = true) || errmsg.contains("violation", ignoreCase = true)) {
            return ResponseVerdict.ResolverError(ShareResolveResult.Blocked("This share has been blocked or violates platform terms."))
        }

        // General business error
        return ResponseVerdict.ResolverError(
            ShareResolveResult.Failure(
                errorType = ShareResolveErrorType.HTTP_ERROR,
                message = "Terabox error (errno $errno): ${errmsg.ifBlank { "Unmapped error code" }}"
            )
        )
    }
}
