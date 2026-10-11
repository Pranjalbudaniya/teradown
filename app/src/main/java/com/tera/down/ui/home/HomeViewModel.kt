package com.tera.down.ui.home

import androidx.lifecycle.ViewModel
import com.tera.down.feature.resolver.TeraboxUrlParseResult
import com.tera.down.feature.resolver.TeraboxUrlParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeUiState(
    val urlInput: String = "",
    val passwordInput: String = "",
    val showPasswordInput: Boolean = false,
    val errorMessage: String? = null,
    val isInspecting: Boolean = false
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onUrlChanged(newUrl: String) {
        _uiState.update {
            it.copy(
                urlInput = newUrl,
                errorMessage = null
            )
        }

        // Check if the pasted URL contains an embedded extraction password (?pwd=...)
        val parseResult = TeraboxUrlParser.parse(newUrl)
        if (parseResult is TeraboxUrlParseResult.Success && parseResult.shareLink.pwd != null) {
            _uiState.update {
                it.copy(
                    passwordInput = parseResult.shareLink.pwd,
                    showPasswordInput = true
                )
            }
        }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(passwordInput = password) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(showPasswordInput = !it.showPasswordInput) }
    }

    fun clearUrl() {
        _uiState.update { it.copy(urlInput = "", errorMessage = null) }
    }

    fun inspectLink(onNavigateToBrowse: (url: String, pwd: String?) -> Unit) {
        val trimmed = _uiState.value.urlInput.trim()
        val parseResult = TeraboxUrlParser.parse(trimmed)

        when (parseResult) {
            is TeraboxUrlParseResult.InvalidUrl -> {
                _uiState.update { it.copy(errorMessage = parseResult.reason) }
            }

            is TeraboxUrlParseResult.Success -> {
                val shareLink = parseResult.shareLink

                // Explicit password input overrides URL-embedded password if supplied
                val explicitPwd = _uiState.value.passwordInput.trim()
                val finalPwd = if (explicitPwd.isNotBlank()) explicitPwd else shareLink.pwd

                _uiState.update { it.copy(errorMessage = null) }
                onNavigateToBrowse(shareLink.originalUrl, finalPwd)
            }
        }
    }
}
