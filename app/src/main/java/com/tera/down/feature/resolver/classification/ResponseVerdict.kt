package com.tera.down.feature.resolver.classification

import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.SimpleJsonObject

/**
 * Typed classification outcomes for HTTP responses received from Terabox or mirror hosts.
 *
 * Distinguishes successful JSON metadata from login barriers, human-verification challenges,
 * unrecognized HTML pages, promo gates, and server-side error codes.
 */
sealed interface ResponseVerdict {

    /**
     * Valid JSON payload returned by the service with errno 0.
     */
    data class Ok(val json: SimpleJsonObject) : ResponseVerdict

    /**
     * The response indicates the user is not authenticated (login page, passport redirect, or errno -6/401).
     */
    data class NotLoggedIn(
        val reason: String,
        val redirectUrl: String? = null
    ) : ResponseVerdict

    /**
     * Human verification, CAPTCHA, or request rate limit detected (e.g. Cloudflare, Geetest, errno 9000).
     */
    data class CaptchaOrRateLimit(
        val reason: String,
        val markerMatched: String? = null
    ) : ResponseVerdict

    /**
     * Unrecognized HTML response returned where structured JSON metadata was expected.
     * Contains a bounded, redacted sample for diagnostics.
     */
    data class UnknownHtml(
        val sample: String,
        val reason: String
    ) : ResponseVerdict

    /**
     * "Download the app" promo gate detected (prepared for Phase 2 alternate URL and promo navigation guard).
     */
    data class AppPromo(
        val reason: String
    ) : ResponseVerdict

    /**
     * The file or content is offered strictly in the official Terabox mobile application only.
     */
    data class AppOnly(
        val reason: String = "Offered in the app only."
    ) : ResponseVerdict

    /**
     * Structured business error mapped from JSON errno (e.g. password required, expired, cancelled, blocked).
     */
    data class ResolverError(
        val result: ShareResolveResult
    ) : ResponseVerdict

    /**
     * General HTTP network or status error (e.g. 500, 502, 503).
     */
    data class HttpError(
        val statusCode: Int,
        val message: String
    ) : ResponseVerdict
}
