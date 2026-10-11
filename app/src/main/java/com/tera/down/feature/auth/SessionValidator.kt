package com.tera.down.feature.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Result of validating an authenticated Terabox session per Section 17.5.
 */
sealed interface SessionValidationResult {
    data object Valid : SessionValidationResult
    data class Invalid(val reason: String) : SessionValidationResult
}

/**
 * Contract for validating a [TeraboxSession] against structural criteria and server endpoints.
 */
interface SessionValidator {
    /**
     * Verifies that [session] contains necessary authentication tokens and
     * passes a session verification check against the Terabox server.
     */
    suspend fun validateSession(session: TeraboxSession): SessionValidationResult
}

/**
 * Default implementation of [SessionValidator].
 *
 * Verifies:
 * 1. Non-empty cookies and presence of authenticating tokens (e.g. ndus, BDUSS, STOKEN).
 * 2. Non-expired session lifetime.
 * 3. Server verification check using a lightweight authenticated call (Section 17.5).
 */
class DefaultSessionValidator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val baseUrlOverride: String? = null
) : SessionValidator {

    companion object {
        /**
         * Key authentication cookie markers issued by Terabox and Baidu authentication servers.
         * An unauthenticated guest session will only receive tracking/csrf cookies (browserid, lang, etc.).
         */
        val AUTH_COOKIE_MARKERS = listOf(
            "ndus=",
            "bduss=",
            "stoken=",
            "sessionid=",
            "auth_token=",
            "panweb="
        )
    }

    override suspend fun validateSession(session: TeraboxSession): SessionValidationResult {
        val cookies = session.cookieString.trim()
        if (cookies.isBlank()) {
            return SessionValidationResult.Invalid("No cookies provided in session.")
        }

        // 1. Structural check: Must contain at least one recognized authentication token
        val lowerCookies = cookies.lowercase()
        val hasAuthToken = AUTH_COOKIE_MARKERS.any { lowerCookies.contains(it) }
        if (!hasAuthToken) {
            return SessionValidationResult.Invalid("Cookies do not contain an authenticated Terabox user token (e.g. ndus).")
        }

        // 2. Expiry check
        if (session.isExpired) {
            return SessionValidationResult.Invalid("Session has expired.")
        }

        // 3. Online validation check (Section 17.5: cheap authenticated call)
        val domain = session.domain.ifBlank { "terabox.com" }
        val testUrl = if (baseUrlOverride != null) {
            "${baseUrlOverride.trimEnd('/')}/api/user/getInfo"
        } else {
            "https://$domain/api/user/getInfo"
        }

        return try {
            val request = Request.Builder()
                .url(testUrl)
                .header("Cookie", session.cookieString)
                .header("User-Agent", session.userAgent)
                .header("Referer", if (baseUrlOverride != null) baseUrlOverride.trimEnd('/') + "/" else "https://$domain/")
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                val code = resp.code

                // Redirects to login or passport indicate unauthorized session
                if (code in 300..399) {
                    val location = resp.header("Location").orEmpty()
                    if (location.contains("login") || location.contains("passport")) {
                        return SessionValidationResult.Invalid("Server redirected to login endpoint.")
                    }
                }

                if (code == 401 || code == 403) {
                    return SessionValidationResult.Invalid("HTTP $code Unauthorized.")
                }

                val body = resp.body?.string().orEmpty()
                val contentType = resp.header("Content-Type").orEmpty().lowercase()

                // Response is HTML (login gate or captcha page)
                if (contentType.contains("text/html") || body.contains("<!doctype html", ignoreCase = true)) {
                    return SessionValidationResult.Invalid("Server returned web page instead of user metadata.")
                }

                // Known unauthenticated error codes in JSON (e.g. "errno": -6 or "errno": 105)
                val noSpacesBody = body.replace(" ", "")
                if (noSpacesBody.contains("\"errno\":-6") || noSpacesBody.contains("\"errno\":105") || noSpacesBody.contains("\"errno\":-1")) {
                    return SessionValidationResult.Invalid("Server returned unauthenticated status code.")
                }

                if (resp.isSuccessful) {
                    SessionValidationResult.Valid
                } else {
                    SessionValidationResult.Invalid("Server returned HTTP $code.")
                }
            }
        } catch (e: Throwable) {
            // Strictly enforce validation: Never assume authentication succeeded if server check cannot verify it
            SessionValidationResult.Invalid("Unable to verify session with server: ${e.message ?: "network error"}")
        }
    }
}
