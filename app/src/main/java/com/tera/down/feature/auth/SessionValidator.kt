package com.tera.down.feature.auth

import com.tera.down.feature.resolver.SimpleJsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Result of validating an authenticated Terabox session per Section 17.5.
 *
 * Distinctly classifies:
 * - [Valid]: Session verified by server and authenticated.
 * - [ProvisionallyValid]: Could not reach any server to verify, but cookies contain
 *   recognized auth markers. Session is accepted provisionally and will be re-verified
 *   when the app next contacts Terabox for a download.
 * - [NotLoggedIn]: Session explicitly rejected by server (expired tokens, login redirect, unauthenticated errno).
 * - [InvalidTokens]: Structural token issue (missing cookies, missing auth markers, expired timestamp).
 * - [NetworkError]: Transient network/connectivity failure (timeout, DNS resolution, TLS failure, connection refused).
 * - [ServerError]: Remote server error (HTTP 5xx).
 * - [UnexpectedResponse]: Unexpected response format (HTML captcha gate, bot challenge).
 */
sealed interface SessionValidationResult {
    data class Valid(
        val uk: Long? = null,
        val uname: String? = null,
        val verifiedDomain: String? = null
    ) : SessionValidationResult

    /**
     * Accepted provisionally: auth markers present but no server could be reached to confirm.
     * The session is saved and will be re-verified on next actual server interaction.
     */
    data class ProvisionallyValid(
        val reason: String,
        val verifiedDomain: String? = null
    ) : SessionValidationResult

    data class NotLoggedIn(val reason: String) : SessionValidationResult
    data class InvalidTokens(val reason: String) : SessionValidationResult
    data class NetworkError(val reason: String, val cause: Throwable? = null) : SessionValidationResult
    data class ServerError(val code: Int, val message: String) : SessionValidationResult
    data class UnexpectedResponse(val reason: String) : SessionValidationResult
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
 * 3. Server verification check using the standard `/api/user/getinfo` endpoint (Section 17.5).
 * 4. Multi-host mirror failover to avoid false network errors when specific domains (e.g. terabox.com)
 *    are blocked or unreachable in user regions.
 */
class DefaultSessionValidator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val baseUrlOverride: String? = null
) : SessionValidator {

    companion object {
        /**
         * Key authentication and session cookie markers issued by Terabox and Baidu authentication servers.
         */
        val AUTH_COOKIE_MARKERS = listOf(
            "ndus=",
            "bduss=",
            "stoken=",
            "sessionid=",
            "auth_token=",
            "panweb="
        )

        /**
         * Verified Terabox web mirror domains that share the same user session backend.
         * Used for fallback verification when primary domain is blocked by DNS or ISP firewall.
         */
        val VERIFIED_MIRROR_DOMAINS = listOf(
            "www.terabox.app",
            "1024tera.com",
            "terabox.fun",
            "teraboxapp.com",
            "www.terabox.com"
        )

        /**
         * Redacts credentials, tokens, and query parameters from URLs for safe logging.
         */
        fun redactUrl(url: String): String {
            val qIndex = url.indexOf('?')
            return if (qIndex >= 0) url.substring(0, qIndex) + "?[REDACTED]" else url
        }
    }

    override suspend fun validateSession(session: TeraboxSession): SessionValidationResult {
        val cookies = session.cookieString.trim()
        if (cookies.isBlank()) {
            return SessionValidationResult.InvalidTokens("No cookies provided in session.")
        }

        // 1. Structural check: Must contain at least one recognized authentication token
        val lowerCookies = cookies.lowercase()
        val hasAuthToken = AUTH_COOKIE_MARKERS.any { lowerCookies.contains(it) }
        if (!hasAuthToken) {
            return SessionValidationResult.InvalidTokens("Cookies do not contain an authenticated Terabox user token (e.g. ndus).")
        }

        // 2. Expiry check
        if (session.isExpired) {
            return SessionValidationResult.InvalidTokens("Session has expired.")
        }

        // 3. Online validation check against legitimate /api/user/getinfo endpoint (Section 17.5)
        if (baseUrlOverride != null) {
            val testUrl = "${baseUrlOverride.trimEnd('/')}/api/user/getinfo"
            return executeValidationRequest(testUrl, session, baseUrlOverride)
        }

        // Build ordered list of candidate hosts: session domain first, then verified mirrors
        val rawDomain = session.domain.trim().trimStart('.').lowercase()
        val primaryHost = if (rawDomain.isNotBlank()) rawDomain else "www.terabox.app"
        val candidateHosts = (listOf(primaryHost) + VERIFIED_MIRROR_DOMAINS).distinct()

        var lastNetworkError: SessionValidationResult.NetworkError? = null

        for (host in candidateHosts) {
            val testUrl = "https://$host/api/user/getinfo"
            val result = executeValidationRequest(testUrl, session, host)

            when (result) {
                is SessionValidationResult.Valid -> return result
                is SessionValidationResult.ProvisionallyValid -> return result
                is SessionValidationResult.NotLoggedIn -> return result
                is SessionValidationResult.InvalidTokens -> return result
                is SessionValidationResult.ServerError -> return result
                is SessionValidationResult.UnexpectedResponse -> return result
                is SessionValidationResult.NetworkError -> {
                    // Save network error and try next mirror host if available
                    lastNetworkError = result
                }
            }
        }

        // If all candidate hosts were unreachable via network, fall back to provisional acceptance.
        // The WebView could reach Terabox (user logged in there), but OkHttp cannot due to regional ISP blocks.
        if (hasAuthToken) {
            return SessionValidationResult.ProvisionallyValid(
                reason = "Could not reach Terabox servers to verify, but session cookies contain authentication tokens. Session accepted provisionally.",
                verifiedDomain = primaryHost
            )
        }

        return lastNetworkError ?: SessionValidationResult.NetworkError("Unable to reach Terabox servers on any mirror domain.")
    }

    private fun executeValidationRequest(
        url: String,
        session: TeraboxSession,
        hostHeader: String
    ): SessionValidationResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", session.cookieString)
                .header("User-Agent", session.userAgent)
                .header("Referer", "https://$hostHeader/")
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                val code = resp.code

                // Redirects to login or passport indicate unauthorized session
                if (code in 300..399) {
                    val location = resp.header("Location").orEmpty()
                    return if (location.contains("login") || location.contains("passport")) {
                        SessionValidationResult.NotLoggedIn("Server redirected to login endpoint.")
                    } else {
                        SessionValidationResult.NotLoggedIn("Server returned HTTP $code redirect.")
                    }
                }

                if (code == 401 || code == 403) {
                    return SessionValidationResult.NotLoggedIn("HTTP $code Unauthorized.")
                }

                if (code in 500..599) {
                    return SessionValidationResult.ServerError(code, "Server returned HTTP $code.")
                }

                val body = resp.body?.string().orEmpty()
                val contentType = resp.header("Content-Type").orEmpty().lowercase()

                // Response is HTML (login gate, smart app banner, or captcha challenge)
                if (contentType.contains("text/html") || body.contains("<!doctype html", ignoreCase = true)) {
                    return SessionValidationResult.UnexpectedResponse("Server returned web page instead of user metadata.")
                }

                // Parse response body
                val json = SimpleJsonParser.parse(body)
                if (json != null) {
                    val errno = json.optInt("errno", -999)
                    if (errno == 0) {
                        val uk = (json.map["data"] as? Map<*, *>)?.get("uk") as? Number
                            ?: json.optLong("uk", 0L)
                        val uname = (json.map["data"] as? Map<*, *>)?.get("uname")?.toString()
                            ?: json.optString("uname")
                        return SessionValidationResult.Valid(
                            uk = uk.toLong(),
                            uname = uname.ifBlank { null },
                            verifiedDomain = if (baseUrlOverride != null) session.domain else hostHeader
                        )
                    }

                    // Known unauthenticated error codes
                    if (errno == -6 || errno == 105 || errno == -1) {
                        return SessionValidationResult.NotLoggedIn("Account session is not authenticated (errno: $errno).")
                    }

                    return SessionValidationResult.NotLoggedIn("Server returned error code (errno: $errno).")
                }

                // If body could not be parsed as JSON
                if (resp.isSuccessful) {
                    SessionValidationResult.UnexpectedResponse("Unrecognized response body format.")
                } else {
                    SessionValidationResult.ServerError(code, "Server returned HTTP $code.")
                }
            }
        } catch (e: SocketTimeoutException) {
            SessionValidationResult.NetworkError("Connection timed out reaching $hostHeader.", e)
        } catch (e: UnknownHostException) {
            SessionValidationResult.NetworkError("DNS resolution failed for $hostHeader.", e)
        } catch (e: SSLException) {
            SessionValidationResult.NetworkError("TLS/SSL verification error connecting to $hostHeader.", e)
        } catch (e: ConnectException) {
            SessionValidationResult.NetworkError("Failed to connect to $hostHeader: connection refused or unreachable.", e)
        } catch (e: IOException) {
            SessionValidationResult.NetworkError("Network I/O error reaching $hostHeader: ${e.message ?: "connection failure"}", e)
        } catch (e: Throwable) {
            SessionValidationResult.NetworkError("Network error: ${e.message ?: "unable to verify session"}", e)
        }
    }
}
