package com.tera.down.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tera.down.TeradownApplication
import com.tera.down.feature.auth.AuthCoordinator
import com.tera.down.feature.auth.InteractiveAuthResult
import com.tera.down.feature.auth.SessionRepository
import com.tera.down.feature.auth.TeraboxSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

data class InteractiveAuthUiState(
    val initialUrl: String = "",
    val currentUrl: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = true,
    val infoMessage: String? = "Log in or complete the verification challenge below, then tap Done.",
    val errorMessage: String? = null,
    val isComplete: Boolean = false,
    val promoOverlayDetected: Boolean = false,
    val maxPromoHitsReached: Boolean = false
)

class InteractiveAuthViewModel(
    private val authCoordinator: AuthCoordinator,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(InteractiveAuthUiState())
    val uiState: StateFlow<InteractiveAuthUiState> = _uiState.asStateFlow()

    fun initialize(url: String) {
        if (_uiState.value.initialUrl.isBlank()) {
            _uiState.update {
                it.copy(
                    initialUrl = url,
                    currentUrl = url
                )
            }
        }
    }

    fun onUrlChanged(newUrl: String) {
        _uiState.update { it.copy(currentUrl = newUrl) }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update {
            it.copy(
                progress = progress,
                isLoading = progress in 1..99
            )
        }
    }

    fun onPromoDetected() {
        _uiState.update {
            it.copy(
                promoOverlayDetected = true,
                infoMessage = "App promo screen detected. Dismiss the overlay or tap Done if you've finished verification."
            )
        }
    }

    fun onMaxPromoHits() {
        _uiState.update {
            it.copy(
                maxPromoHitsReached = true,
                errorMessage = "Multiple app-install redirects were intercepted. Tap Done if you have already logged in, or Cancel."
            )
        }
    }

    fun onDismissPromoNotice() {
        _uiState.update { it.copy(promoOverlayDetected = false) }
    }

    fun onDone(currentUrl: String, cookieString: String?, userAgent: String) {
        viewModelScope.launch {
            if (cookieString.isNullOrBlank()) {
                _uiState.update {
                    it.copy(errorMessage = "No session cookies detected yet. Please ensure you are logged in or completed the check.")
                }
                return@launch
            }

            val domain = try {
                URI(currentUrl).host ?: "terabox.com"
            } catch (_: Exception) {
                "terabox.com"
            }

            val session = TeraboxSession(
                domain = domain,
                cookieString = cookieString,
                userAgent = userAgent,
                savedAt = System.currentTimeMillis(),
                lastOkAt = System.currentTimeMillis()
            )

            sessionRepository.saveSession(session)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            _uiState.update {
                it.copy(
                    isComplete = true,
                    infoMessage = "Authentication verified successfully!"
                )
            }
        }
    }

    fun onCancel() {
        authCoordinator.cancelAuth()
        _uiState.update { it.copy(isComplete = true) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TeradownApplication
                InteractiveAuthViewModel(
                    authCoordinator = app.container.authCoordinator,
                    sessionRepository = app.container.sessionRepository
                )
            }
        }
    }
}
