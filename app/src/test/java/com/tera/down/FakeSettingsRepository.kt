package com.tera.down

import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ThemeMode
import com.tera.down.core.network.NetworkConnectivityMonitor
import com.tera.down.feature.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeSettingsRepository(
    initialSettings: AppSettings = AppSettings()
) : SettingsRepository {

    private val _settings = MutableStateFlow(initialSettings)
    override val settingsFlow: Flow<AppSettings> = _settings.asStateFlow()

    override suspend fun getSettings(): AppSettings = _settings.value

    override suspend fun setThemeMode(mode: ThemeMode) {
        _settings.update { it.copy(themeMode = mode) }
    }

    override suspend fun setDownloadDirectory(path: String) {
        _settings.update { it.copy(downloadDirectory = path.trim()) }
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        _settings.update { it.copy(wifiOnly = enabled) }
    }

    override suspend fun setMaxConcurrentDownloads(limit: Int) {
        val clamped = limit.coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)
        _settings.update { it.copy(maxConcurrentDownloads = clamped) }
    }

    override suspend fun setAutoRetryEnabled(enabled: Boolean) {
        _settings.update { it.copy(autoRetryEnabled = enabled) }
    }

    override suspend fun setMaxRetryAttempts(attempts: Int) {
        val clamped = attempts.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)
        _settings.update { it.copy(maxRetryAttempts = clamped) }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        _settings.update { it.copy(notificationsEnabled = enabled) }
    }

    override suspend fun setConfirmLargeBatches(enabled: Boolean) {
        _settings.update { it.copy(confirmLargeBatches = enabled) }
    }

    override suspend fun setDefaultMediaFilter(filter: MediaType?) {
        _settings.update { it.copy(defaultMediaFilter = filter) }
    }

    override suspend fun resetToDefaults() {
        _settings.value = AppSettings()
    }
}

class FakeNetworkConnectivityMonitor(
    initialConnected: Boolean = true,
    initialWifi: Boolean = true
) : NetworkConnectivityMonitor {

    private val _connected = MutableStateFlow(initialConnected)
    private val _wifi = MutableStateFlow(initialWifi)

    override fun isConnected(): Boolean = _connected.value
    override fun isWifi(): Boolean = _wifi.value
    override val isConnectedFlow: Flow<Boolean> = _connected.asStateFlow()
    override val isWifiFlow: Flow<Boolean> = _wifi.asStateFlow()

    fun setConnected(connected: Boolean) {
        _connected.value = connected
        if (!connected) _wifi.value = false
    }

    fun setWifi(wifi: Boolean) {
        _wifi.value = wifi
        if (wifi) _connected.value = true
    }
}
