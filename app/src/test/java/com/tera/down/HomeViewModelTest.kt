package com.tera.down

import com.tera.down.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeViewModelTest {

    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        viewModel = HomeViewModel()
    }

    @Test
    fun urlChanged_updatesState() {
        viewModel.onUrlChanged("https://terabox.com/s/1abc")
        assertEquals("https://terabox.com/s/1abc", viewModel.uiState.value.urlInput)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun urlWithPassword_extractsPasswordAutomatically() {
        viewModel.onUrlChanged("https://terabox.com/s/1abc?pwd=x9z1")
        assertEquals("x9z1", viewModel.uiState.value.passwordInput)
        assertTrue(viewModel.uiState.value.showPasswordInput)
    }

    @Test
    fun clearUrl_resetsInputAndError() {
        viewModel.onUrlChanged("https://terabox.com/s/1abc")
        viewModel.clearUrl()
        assertEquals("", viewModel.uiState.value.urlInput)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun emptyInput_showsErrorOnInspect() {
        var navigated = false
        viewModel.inspectLink { _, _ -> navigated = true }
        assertTrue(!navigated)
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun invalidUrl_setsErrorMessage_andDoesNotNavigate() {
        viewModel.onUrlChanged("https://google.com/s/1abc")
        var navigated = false
        viewModel.inspectLink { _, _ -> navigated = true }
        assertTrue(!navigated)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("not a recognized Terabox"))
    }

    @Test
    fun unsupportedScheme_setsErrorMessage_andDoesNotNavigate() {
        viewModel.onUrlChanged("ftp://terabox.com/s/1abc")
        var navigated = false
        viewModel.inspectLink { _, _ -> navigated = true }
        assertTrue(!navigated)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Unsupported scheme"))
    }

    @Test
    fun validUrl_triggersNavigationWithExtractedPassword() {
        viewModel.onUrlChanged("https://terabox.com/s/1abc?pwd=autoPass")
        var targetUrl: String? = null
        var targetPwd: String? = null
        viewModel.inspectLink { url, pwd ->
            targetUrl = url
            targetPwd = pwd
        }
        assertEquals("https://terabox.com/s/1abc?pwd=autoPass", targetUrl)
        assertEquals("autoPass", targetPwd)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun explicitPassword_overridesUrlPassword() {
        viewModel.onUrlChanged("https://terabox.com/s/1abc?pwd=urlPass")
        // User manually changes password input
        viewModel.onPasswordChanged("explicitOverridePass")

        var finalPwd: String? = null
        viewModel.inspectLink { _, pwd -> finalPwd = pwd }

        assertEquals("explicitOverridePass", finalPwd)
    }
}
