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
import com.tera.down.feature.auth.SessionValidationResult
import com.tera.down.feature.auth.SessionValidator
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
    val isValidating: Boolean = false,
    val infoMessage: String? = "Log in or complete the verification challenge below, then tap Done.",
    val errorMessage: String? = null,
    val isComplete: Boolean = false,
    val promoOverlayDetected: Boolean = false,
    val maxPromoHitsReached: Boolean = false,
    val isGoogleSignInBlocked: Boolean = false
)

class InteractiveAuthViewModel(
    private val authCoordinator: AuthCoordinator,
    private val sessionRepository: SessionRepository,
    private val sessionValidator: SessionValidator
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
        val isGoogle = newUrl.contains("accounts.google.com", ignoreCase = true)
        _uiState.update {
            it.copy(
                currentUrl = newUrl,
                isGoogleSignInBlocked = isGoogle || it.isGoogleSignInBlocked
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update {
            it.copy(
                progress = progress,
                isLoading = progress in 1..99
            )
        }
    }

    fun onGoogleSignInBlocked() {
        _uiState.update {
            it.copy(
                isGoogleSignInBlocked = true,
                errorMessage = "Google restricts sign-in inside embedded app WebViews. Please sign in using Terabox Email/Password, Phone number, or QR code instead."
            )
        }
    }

    fun onDismissGoogleSignInNotice() {
        _uiState.update { it.copy(isGoogleSignInBlocked = false) }
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
            _uiState.update { it.copy(isValidating = true, errorMessage = null) }

            // 1. Presence check (Requirement 2 & 10)
            if (cookieString.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isValidating = false,
                        errorMessage = "No session cookies detected. Please ensure you have signed in or solved the check before tapping Done."
                    )
                }
                return@launch
            }

            val domain = try {
                URI(currentUrl).host ?: "terabox.com"
            } catch (_: Exception) {
                "terabox.com"
            }

            val candidateSession = TeraboxSession(
                domain = domain,
                cookieString = cookieString,
                userAgent = userAgent,
                savedAt = System.currentTimeMillis(),
                lastOkAt = System.currentTimeMillis()
            )

            // 2. Strict session validation check (Requirement 3: Never assume authentication succeeded merely because cookies exist)
            val validationVerdict = sessionValidator.validateSession(candidateSession)
            if (validationVerdict is SessionValidationResult.Invalid) {
                _uiState.update {
                    it.copy(
                        isValidating = false,
                        errorMessage = "Session validation check failed: ${validationVerdict.reason}. Please make sure you are logged in to your Terabox account."
                    )
                }
                return@launch
            }

            // 3. Persist verified session
            sessionRepository.saveSession(candidateSession)
            authCoordinator.submitAuthResult(InteractiveAuthResult.Success(candidateSession))

            _uiState.update {
                it.copy(
                    isValidating = false,
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
                    sessionRepository = app.container.sessionRepository,
                    sessionValidator = app.container.sessionValidator
                )
            }
        }
    }
}

