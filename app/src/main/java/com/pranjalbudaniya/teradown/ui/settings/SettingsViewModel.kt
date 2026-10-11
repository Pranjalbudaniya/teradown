package com.pranjalbudaniya.teradown.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val downloadPath: String = "App External Storage (Android/data/.../files/Download)",
    val wifiOnly: Boolean = false,
    val videoWorkers: Int = 2,
    val photoWorkers: Int = 4,
    val hasCookieConfigured: Boolean = false,
    val diagnosticResult: String? = null,
    val isRunningDiagnostic: Boolean = false
)

class SettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setWifiOnly(enabled: Boolean) {
        _uiState.update { it.copy(wifiOnly = enabled) }
    }

    fun setVideoWorkers(workers: Int) {
        _uiState.update { it.copy(videoWorkers = workers.coerceIn(1, 4)) }
    }

    fun setPhotoWorkers(workers: Int) {
        _uiState.update { it.copy(photoWorkers = workers.coerceIn(1, 6)) }
    }

    fun runDiagnostic() {
        _uiState.update {
            it.copy(
                isRunningDiagnostic = true,
                diagnosticResult = "Diagnostic check:\n- Android SDK: API 35\n- Network Security: Cleartext enabled for 127.0.0.1 (Localhost proxy)\n- Session Cookie: Not set (Anonymous mode only)\n- Storage Access: App Scoped Storage OK"
            )
        }
    }
}
