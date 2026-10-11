package com.tera.down.core.model

/**
 * Supported UI themes for Teradown.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Immutable data model representing persistent user preferences.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val downloadDirectory: String = "",
    val wifiOnly: Boolean = false,
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT,
    val autoRetryEnabled: Boolean = true,
    val maxRetryAttempts: Int = DEFAULT_MAX_RETRIES,
    val notificationsEnabled: Boolean = true,
    val confirmLargeBatches: Boolean = true,
    val defaultMediaFilter: MediaType? = null
) {
    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 4
        const val DEFAULT_MAX_CONCURRENT = 2

        const val MIN_RETRIES = 1
        const val MAX_RETRIES = 5
        const val DEFAULT_MAX_RETRIES = 3

        const val LARGE_BATCH_THRESHOLD = 5
    }
}
