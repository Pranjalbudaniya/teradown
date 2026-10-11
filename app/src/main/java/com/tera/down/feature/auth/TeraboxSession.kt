package com.tera.down.feature.auth

/**
 * Encapsulates an active, verified browser session with a Terabox domain.
 *
 * Stores the domain, matching browser User-Agent, language preference, and cookie header.
 * Sensitive cookies are never exposed in plain text logs or exported files.
 */
data class TeraboxSession(
    val domain: String,
    val cookieString: String,
    val userAgent: String,
    val acceptLanguage: String = "en-US,en;q=0.9",
    val savedAt: Long = System.currentTimeMillis(),
    val lastOkAt: Long = System.currentTimeMillis(),
    val expiryEstimate: Long? = null
) {
    /**
     * Checks if this session has passed its estimated expiry timestamp.
     */
    val isExpired: Boolean
        get() = expiryEstimate != null && System.currentTimeMillis() >= expiryEstimate

    /**
     * Checks if the session was verified recently (within 24 hours).
     */
    val isRecentlyVerified: Boolean
        get() = System.currentTimeMillis() - lastOkAt < 24 * 60 * 60 * 1000L

    /**
     * Redacted representation for safe logging and debugging.
     */
    override fun toString(): String {
        return "TeraboxSession(domain='$domain', userAgent='$userAgent', savedAt=$savedAt, lastOkAt=$lastOkAt, isExpired=$isExpired)"
    }
}
