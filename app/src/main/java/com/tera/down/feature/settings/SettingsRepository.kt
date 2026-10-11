package com.tera.down.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

interface SettingsRepository {
    val settingsFlow: Flow<AppSettings>
    suspend fun getSettings(): AppSettings
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDownloadDirectory(path: String)
    suspend fun setWifiOnly(enabled: Boolean)
    suspend fun setMaxConcurrentDownloads(limit: Int)
    suspend fun setAutoRetryEnabled(enabled: Boolean)
    suspend fun setMaxRetryAttempts(attempts: Int)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setConfirmLargeBatches(enabled: Boolean)
    suspend fun setDefaultMediaFilter(filter: MediaType?)
    suspend fun resetToDefaults()
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DOWNLOAD_DIR = stringPreferencesKey("download_directory")
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEY_MAX_CONCURRENT = intPreferencesKey("max_concurrent_downloads")
        val KEY_AUTO_RETRY = booleanPreferencesKey("auto_retry_enabled")
        val KEY_MAX_RETRIES = intPreferencesKey("max_retry_attempts")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val KEY_CONFIRM_BATCHES = booleanPreferencesKey("confirm_large_batches")
        val KEY_DEFAULT_FILTER = stringPreferencesKey("default_media_filter")
    }

    override val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapPreferencesToSettings(preferences)
        }

    override suspend fun getSettings(): AppSettings {
        return settingsFlow.first()
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = mode.name
        }
    }

    override suspend fun setDownloadDirectory(path: String) {
        dataStore.edit { preferences ->
            preferences[KEY_DOWNLOAD_DIR] = path.trim()
        }
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_WIFI_ONLY] = enabled
        }
    }

    override suspend fun setMaxConcurrentDownloads(limit: Int) {
        val clamped = limit.coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)
        dataStore.edit { preferences ->
            preferences[KEY_MAX_CONCURRENT] = clamped
        }
    }

    override suspend fun setAutoRetryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_AUTO_RETRY] = enabled
        }
    }

    override suspend fun setMaxRetryAttempts(attempts: Int) {
        val clamped = attempts.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)
        dataStore.edit { preferences ->
            preferences[KEY_MAX_RETRIES] = clamped
        }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATIONS] = enabled
        }
    }

    override suspend fun setConfirmLargeBatches(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_CONFIRM_BATCHES] = enabled
        }
    }

    override suspend fun setDefaultMediaFilter(filter: MediaType?) {
        dataStore.edit { preferences ->
            if (filter != null) {
                preferences[KEY_DEFAULT_FILTER] = filter.name
            } else {
                preferences.remove(KEY_DEFAULT_FILTER)
            }
        }
    }

    override suspend fun resetToDefaults() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private fun mapPreferencesToSettings(preferences: Preferences): AppSettings {
        val themeModeStr = preferences[KEY_THEME_MODE]
        val themeMode = try {
            if (themeModeStr != null) ThemeMode.valueOf(themeModeStr) else ThemeMode.SYSTEM
        } catch (_: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }

        val maxConcurrent = (preferences[KEY_MAX_CONCURRENT] ?: AppSettings.DEFAULT_MAX_CONCURRENT)
            .coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)

        val maxRetries = (preferences[KEY_MAX_RETRIES] ?: AppSettings.DEFAULT_MAX_RETRIES)
            .coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)

        val defaultFilterStr = preferences[KEY_DEFAULT_FILTER]
        val defaultFilter = try {
            if (defaultFilterStr != null) MediaType.valueOf(defaultFilterStr) else null
        } catch (_: IllegalArgumentException) {
            null
        }

        return AppSettings(
            themeMode = themeMode,
            downloadDirectory = preferences[KEY_DOWNLOAD_DIR] ?: "",
            wifiOnly = preferences[KEY_WIFI_ONLY] ?: false,
            maxConcurrentDownloads = maxConcurrent,
            autoRetryEnabled = preferences[KEY_AUTO_RETRY] ?: true,
            maxRetryAttempts = maxRetries,
            notificationsEnabled = preferences[KEY_NOTIFICATIONS] ?: true,
            confirmLargeBatches = preferences[KEY_CONFIRM_BATCHES] ?: true,
            defaultMediaFilter = defaultFilter
        )
    }
}
