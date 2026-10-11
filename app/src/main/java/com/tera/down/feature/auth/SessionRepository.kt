package com.tera.down.feature.auth

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets

/**
 * Repository interface for managing authenticated Terabox sessions.
 */
interface SessionRepository {
    val sessionFlow: StateFlow<TeraboxSession?>
    suspend fun getSession(): TeraboxSession?
    suspend fun saveSession(session: TeraboxSession)
    suspend fun recordSuccess(domain: String)
    suspend fun clearSession()
    suspend fun switchUserAgent(newUserAgent: String)
    fun getCookieHeader(domain: String): String?
    fun getUserAgent(): String?
}

/**
 * Default implementation persisting session data with private-mode storage and in-memory caching.
 * Falls back to pure in-memory mode when running in isolated test environments without Android Context.
 */
class DefaultSessionRepository(
    private val context: Context? = null
) : SessionRepository {

    private val _sessionFlow = MutableStateFlow<TeraboxSession?>(null)
    override val sessionFlow: StateFlow<TeraboxSession?> = _sessionFlow.asStateFlow()

    init {
        loadSessionFromStorage()
    }

    private fun loadSessionFromStorage() {
        if (context == null) return

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val domain = prefs.getString(KEY_DOMAIN, null) ?: return
            val encodedCookies = prefs.getString(KEY_COOKIES, null) ?: return
            val userAgent = prefs.getString(KEY_USER_AGENT, null) ?: ""
            val acceptLanguage = prefs.getString(KEY_ACCEPT_LANG, "en-US,en;q=0.9") ?: "en-US,en;q=0.9"
            val savedAt = prefs.getLong(KEY_SAVED_AT, System.currentTimeMillis())
            val lastOkAt = prefs.getLong(KEY_LAST_OK_AT, System.currentTimeMillis())
            val expiry = if (prefs.contains(KEY_EXPIRY)) prefs.getLong(KEY_EXPIRY, 0L) else null

            val cookieString = try {
                String(Base64.decode(encodedCookies, Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                try {
                    String(java.util.Base64.getDecoder().decode(encodedCookies), StandardCharsets.UTF_8)
                } catch (_: Throwable) {
                    encodedCookies
                }
            }

            val loadedSession = TeraboxSession(
                domain = domain,
                cookieString = cookieString,
                userAgent = userAgent,
                acceptLanguage = acceptLanguage,
                savedAt = savedAt,
                lastOkAt = lastOkAt,
                expiryEstimate = expiry
            )
            _sessionFlow.value = loadedSession
        } catch (_: Throwable) {
            // Storage load failure: keep empty session safely
        }
    }

    override suspend fun getSession(): TeraboxSession? {
        return _sessionFlow.value
    }

    override suspend fun saveSession(session: TeraboxSession) {
        _sessionFlow.value = session

        if (context == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encodedCookies = try {
                Base64.encodeToString(session.cookieString.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(session.cookieString.toByteArray(StandardCharsets.UTF_8))
            }

            prefs.edit()
                .putString(KEY_DOMAIN, session.domain)
                .putString(KEY_COOKIES, encodedCookies)
                .putString(KEY_USER_AGENT, session.userAgent)
                .putString(KEY_ACCEPT_LANG, session.acceptLanguage)
                .putLong(KEY_SAVED_AT, session.savedAt)
                .putLong(KEY_LAST_OK_AT, session.lastOkAt)
                .apply {
                    if (session.expiryEstimate != null) {
                        putLong(KEY_EXPIRY, session.expiryEstimate)
                    } else {
                        remove(KEY_EXPIRY)
                    }
                }
                .apply()
        } catch (_: Exception) {
            // Persistence failure; in-memory cache remains active
        }
    }

    override suspend fun recordSuccess(domain: String) {
        val current = _sessionFlow.value ?: return
        val updated = current.copy(lastOkAt = System.currentTimeMillis())
        saveSession(updated)
    }

    override suspend fun clearSession() {
        _sessionFlow.value = null
        if (context == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (_: Exception) {}
    }

    override suspend fun switchUserAgent(newUserAgent: String) {
        val current = _sessionFlow.value
        val isCurrentDesktop = current?.userAgent?.contains("Linux x86_64") == true
        val isNewDesktop = newUserAgent.contains("Linux x86_64")

        // Section 18.10: Switching UA mode (desktop to mobile or back) clears cookies first
        if (current != null && isCurrentDesktop != isNewDesktop) {
            clearSession()
        }

        val updated = _sessionFlow.value?.copy(userAgent = newUserAgent)
            ?: TeraboxSession(
                domain = current?.domain ?: "terabox.com",
                cookieString = "",
                userAgent = newUserAgent
            )
        saveSession(updated)
    }

    override fun getCookieHeader(domain: String): String? {
        val session = _sessionFlow.value ?: return null
        if (session.isExpired) return null

        // Domain matching: host must match, end with stored domain, or both be recognized Terabox family hosts
        val normalizedReq = domain.lowercase().trimStart('.')
        val normalizedSession = session.domain.lowercase().trimStart('.')
        if (normalizedReq == normalizedSession ||
            normalizedReq.endsWith(".$normalizedSession") ||
            normalizedSession.endsWith(".$normalizedReq") ||
            (com.tera.down.feature.resolver.TeraboxShareLinkParser.isRecognizedHost(normalizedReq) &&
             (com.tera.down.feature.resolver.TeraboxShareLinkParser.isRecognizedHost(normalizedSession) ||
              normalizedSession.contains("terabox") ||
              normalizedSession.contains("1024tera") ||
              normalizedSession.contains("dubox")))
        ) {
            return session.cookieString.ifBlank { null }
        }
        return null
    }

    override fun getUserAgent(): String? {
        return _sessionFlow.value?.userAgent?.ifBlank { null }
    }

    companion object {
        private const val PREFS_NAME = "teradown_secure_session"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_ACCEPT_LANG = "accept_lang"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_LAST_OK_AT = "last_ok_at"
        private const val KEY_EXPIRY = "expiry"
    }
}
