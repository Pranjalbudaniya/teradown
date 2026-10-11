package com.tera.down.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tera.down.TeradownApplication
import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.ThemeMode
import com.tera.down.core.network.NetworkConnectivityMonitor
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.downloader.StorageValidationResult
import com.tera.down.feature.settings.SettingsRepository
import com.tera.down.ui.components.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val effectiveDownloadPath: String = "",
    val isStorageValid: Boolean = true,
    val storageErrorMessage: String? = null,
    val availableStorageFormatted: String = "",
    val isWifiConnected: Boolean = false,
    val isNetworkConnected: Boolean = false,
    val diagnosticSummary: String? = null
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository? = null,
    private val storageManager: StorageManager? = null,
    private val networkConnectivityMonitor: NetworkConnectivityMonitor? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshStorageInfo()
        refreshNetworkInfo()

        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.settingsFlow.collect { settings ->
                    _uiState.update { it.copy(settings = settings) }
                    refreshStorageInfo(settings.downloadDirectory)
                }
            }
        }

        networkConnectivityMonitor?.let { monitor ->
            viewModelScope.launch {
                monitor.isConnectedFlow.collect { connected ->
                    _uiState.update {
                        it.copy(
                            isNetworkConnected = connected,
                            isWifiConnected = monitor.isWifi()
                        )
                    }
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository?.setThemeMode(mode)
                ?: _uiState.update { it.copy(settings = it.settings.copy(themeMode = mode)) }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository?.setWifiOnly(enabled)
                ?: _uiState.update { it.copy(settings = it.settings.copy(wifiOnly = enabled)) }
        }
    }

    fun setMaxConcurrentDownloads(workers: Int) {
        val clamped = workers.coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)
        viewModelScope.launch {
            settingsRepository?.setMaxConcurrentDownloads(clamped)
                ?: _uiState.update { it.copy(settings = it.settings.copy(maxConcurrentDownloads = clamped)) }
        }
    }

    fun setAutoRetryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository?.setAutoRetryEnabled(enabled)
                ?: _uiState.update { it.copy(settings = it.settings.copy(autoRetryEnabled = enabled)) }
        }
    }

    fun setMaxRetryAttempts(attempts: Int) {
        val clamped = attempts.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)
        viewModelScope.launch {
            settingsRepository?.setMaxRetryAttempts(clamped)
                ?: _uiState.update { it.copy(settings = it.settings.copy(maxRetryAttempts = clamped)) }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository?.setNotificationsEnabled(enabled)
                ?: _uiState.update { it.copy(settings = it.settings.copy(notificationsEnabled = enabled)) }
        }
    }

    fun setConfirmLargeBatches(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository?.setConfirmLargeBatches(enabled)
                ?: _uiState.update { it.copy(settings = it.settings.copy(confirmLargeBatches = enabled)) }
        }
    }

    fun setDownloadDirectory(path: String) {
        viewModelScope.launch {
            settingsRepository?.setDownloadDirectory(path)
            refreshStorageInfo(path)
        }
    }

    fun resetDownloadDirectoryToDefault() {
        viewModelScope.launch {
            settingsRepository?.setDownloadDirectory("")
            refreshStorageInfo("")
        }
    }

    fun runDiagnostic() {
        val storage = _uiState.value
        val settings = storage.settings
        val netStatus = if (storage.isWifiConnected) "Wi-Fi (Unmetered)" else if (storage.isNetworkConnected) "Cellular / Metered" else "Offline"
        val storageStatus = if (storage.isStorageValid) "Accessible (${storage.availableStorageFormatted} free)" else "Inaccessible: ${storage.storageErrorMessage}"

        val report = buildString {
            appendLine("=== Teradown Diagnostics ===")
            appendLine("Network: $netStatus")
            appendLine("Wi-Fi Only Mode: ${if (settings.wifiOnly) "Enabled" else "Disabled"}")
            appendLine("Concurrent Worker Limit: ${settings.maxConcurrentDownloads}")
            appendLine("Auto-Retry: ${if (settings.autoRetryEnabled) "Enabled (Max ${settings.maxRetryAttempts})" else "Disabled"}")
            appendLine("Storage Path: ${storage.effectiveDownloadPath}")
            appendLine("Storage Health: $storageStatus")
            appendLine("Theme: ${settings.themeMode.name}")
        }

        _uiState.update { it.copy(diagnosticSummary = report) }
    }

    private fun refreshStorageInfo(customPath: String? = _uiState.value.settings.downloadDirectory) {
        val sm = storageManager
        if (sm == null) {
            _uiState.update {
                it.copy(
                    effectiveDownloadPath = customPath ?: "Default Storage",
                    isStorageValid = true,
                    availableStorageFormatted = "Unknown"
                )
            }
            return
        }

        val effectiveDir = sm.getEffectiveDownloadDirectory(customPath)
        val validation = sm.validateDirectory(effectiveDir)

        when (validation) {
            is StorageValidationResult.Valid -> {
                _uiState.update {
                    it.copy(
                        effectiveDownloadPath = effectiveDir.absolutePath,
                        isStorageValid = true,
                        storageErrorMessage = null,
                        availableStorageFormatted = Formatters.formatBytes(validation.availableBytes)
                    )
                }
            }
            is StorageValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        effectiveDownloadPath = effectiveDir.absolutePath,
                        isStorageValid = false,
                        storageErrorMessage = validation.reason,
                        availableStorageFormatted = "0 B"
                    )
                }
            }
        }
    }

    private fun refreshNetworkInfo() {
        val monitor = networkConnectivityMonitor
        if (monitor != null) {
            _uiState.update {
                it.copy(
                    isNetworkConnected = monitor.isConnected(),
                    isWifiConnected = monitor.isWifi()
                )
            }
        }
    }

    companion object {
        fun provideFactory(
            settingsRepository: SettingsRepository,
            storageManager: StorageManager,
            networkConnectivityMonitor: NetworkConnectivityMonitor? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    settingsRepository = settingsRepository,
                    storageManager = storageManager,
                    networkConnectivityMonitor = networkConnectivityMonitor
                ) as T
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? TeradownApplication
                SettingsViewModel(
                    settingsRepository = app?.container?.settingsRepository,
                    storageManager = app?.container?.storageManager,
                    networkConnectivityMonitor = app?.container?.networkConnectivityMonitor
                )
            }
        }
    }
}
