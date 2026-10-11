package com.tera.down

import com.tera.down.feature.auth.AuthCoordinatorImpl
import com.tera.down.feature.auth.DefaultSessionRepository
import com.tera.down.feature.auth.DefaultSessionValidator
import com.tera.down.feature.auth.InteractiveAuthResult
import com.tera.down.feature.auth.SessionValidationResult
import com.tera.down.feature.auth.SessionValidator
import com.tera.down.feature.auth.TeraboxSession
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.ui.auth.InteractiveAuthViewModel
import com.tera.down.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class InteractiveAuthValidationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionRepository: DefaultSessionRepository
    private lateinit var authCoordinator: AuthCoordinatorImpl
    private lateinit var testHttpClient: OkHttpClient

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        testHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        sessionRepository = DefaultSessionRepository(context = null)
        authCoordinator = AuthCoordinatorImpl(maxAttempts = 2)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun onDone_missingCookies_rejectsAuthenticationWithActionableError() = runTest(testDispatcher) {
        val fakeValidator = object : SessionValidator {
            override suspend fun validateSession(session: TeraboxSession): SessionValidationResult =
                SessionValidationResult.Valid
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = fakeValidator
        )
        viewModel.initialize("https://www.terabox.com/main")

        // 1. Null cookies
        viewModel.onDone(
            currentUrl = "https://www.terabox.com/main",
            cookieString = null,
            userAgent = "DesktopUA"
        )

        var state = viewModel.uiState.value
        assertFalse(state.isComplete)
        assertFalse(state.isValidating)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("No session cookies detected", ignoreCase = true))
        assertNull(sessionRepository.getSession())

        // 2. Blank cookies
        viewModel.onDone(
            currentUrl = "https://www.terabox.com/main",
            cookieString = "   ",
            userAgent = "DesktopUA"
        )

        state = viewModel.uiState.value
        assertFalse(state.isComplete)
        assertNotNull(state.errorMessage)
        assertNull(sessionRepository.getSession())
    }

    @Test
    fun sessionValidator_guestTrackingCookiesWithoutAuthMarker_failsValidation() = runTest(testDispatcher) {
        val validator = DefaultSessionValidator(httpClient = testHttpClient)

        // Cookies that only have guest tracking, no auth token (ndus, bduss, etc.)
        val guestSession = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "browserid=123456789; lang=en; csrfToken=abcd9876",
            userAgent = "DesktopUA"
        )

        val result = validator.validateSession(guestSession)
        assertTrue(result is SessionValidationResult.Invalid)
        val invalid = result as SessionValidationResult.Invalid
        assertTrue(invalid.reason.contains("ndus", ignoreCase = true))
    }

    @Test
    fun sessionValidator_expiredSession_failsValidation() = runTest(testDispatcher) {
        val validator = DefaultSessionValidator(httpClient = testHttpClient)

        val expiredSession = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "ndus=VALID_TOKEN_BUT_EXPIRED",
            userAgent = "DesktopUA",
            expiryEstimate = System.currentTimeMillis() - 10000L // 10s ago
        )

        val result = validator.validateSession(expiredSession)
        assertTrue(result is SessionValidationResult.Invalid)
        val invalid = result as SessionValidationResult.Invalid
        assertTrue(invalid.reason.contains("expired", ignoreCase = true))
    }

    @Test
    fun sessionValidator_serverRedirectsToLogin_failsValidation() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://passport.terabox.com/v2/api/login")
        )

        val validator = DefaultSessionValidator(
            httpClient = testHttpClient,
            baseUrlOverride = mockWebServer.url("/").toString()
        )
        val session = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "ndus=COOKIE_REJECTED_BY_SERVER",
            userAgent = "DesktopUA"
        )

        val result = validator.validateSession(session)
        assertTrue(result is SessionValidationResult.Invalid)
        val invalid = result as SessionValidationResult.Invalid
        assertTrue(invalid.reason.contains("login endpoint", ignoreCase = true))
    }

    @Test
    fun sessionValidator_serverReturnsHtmlInsteadOfJson_failsValidation() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<!DOCTYPE html><html><body><h1>Please Log In</h1></body></html>")
        )

        val validator = DefaultSessionValidator(
            httpClient = testHttpClient,
            baseUrlOverride = mockWebServer.url("/").toString()
        )
        val session = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "ndus=AUTHENTICATED_COOKIE",
            userAgent = "DesktopUA"
        )

        val result = validator.validateSession(session)
        assertTrue(result is SessionValidationResult.Invalid)
        val invalid = result as SessionValidationResult.Invalid
        assertTrue(invalid.reason.contains("web page", ignoreCase = true))
    }

    @Test
    fun sessionValidator_serverReturnsUnauthenticatedErrno_failsValidation() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"errno": -6, "errmsg": "User not logged in"}""")
        )

        val validator = DefaultSessionValidator(
            httpClient = testHttpClient,
            baseUrlOverride = mockWebServer.url("/").toString()
        )
        val session = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "ndus=REVOKED_COOKIE",
            userAgent = "DesktopUA"
        )

        val result = validator.validateSession(session)
        assertTrue(result is SessionValidationResult.Invalid)
        val invalid = result as SessionValidationResult.Invalid
        assertTrue(invalid.reason.contains("unauthenticated", ignoreCase = true))
    }

    @Test
    fun onDone_failedSessionValidation_rejectsAuthAndDoesNotPersist() = runTest(testDispatcher) {
        val failingValidator = object : SessionValidator {
            override suspend fun validateSession(session: TeraboxSession): SessionValidationResult =
                SessionValidationResult.Invalid("Remote server rejected authentication token.")
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = failingValidator
        )
        viewModel.initialize("https://www.terabox.com/main")

        viewModel.onDone(
            currentUrl = "https://www.terabox.com/main",
            cookieString = "ndus=SOME_COOKIE",
            userAgent = "DesktopUA"
        )

        val state = viewModel.uiState.value
        assertFalse(state.isComplete)
        assertFalse(state.isValidating)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Remote server rejected authentication token"))
        assertNull(sessionRepository.getSession())
    }

    @Test
    fun onDone_successfulSessionValidation_persistsSessionAndSubmitsSuccess() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"errno": 0, "data": {"uk": 123456, "uname": "terabox_tester"}}""")
        )

        val validator = DefaultSessionValidator(
            httpClient = testHttpClient,
            baseUrlOverride = mockWebServer.url("/").toString()
        )
        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = validator
        )
        viewModel.initialize("https://${mockWebServer.hostName}:${mockWebServer.port}/main")

        val deferredAuth = async {
            authCoordinator.requestInteractiveAuth("https://${mockWebServer.hostName}:${mockWebServer.port}/s/test", "Login check")
        }

        viewModel.onDone(
            currentUrl = "https://${mockWebServer.hostName}:${mockWebServer.port}/main",
            cookieString = "ndus=VALID_ACTIVE_SESSION_TOKEN; panweb=1",
            userAgent = "DesktopUA"
        )

        val state = viewModel.uiState.value
        assertTrue(state.isComplete)
        assertFalse(state.isValidating)
        assertNull(state.errorMessage)

        // Session was persisted in repository
        val savedSession = sessionRepository.getSession()
        assertNotNull(savedSession)
        assertEquals("ndus=VALID_ACTIVE_SESSION_TOKEN; panweb=1", savedSession?.cookieString)
        assertEquals("DesktopUA", savedSession?.userAgent)

        // Coordinator unblocked with Success
        val authResult = deferredAuth.await()
        assertTrue(authResult is InteractiveAuthResult.Success)
        val success = authResult as InteractiveAuthResult.Success
        assertEquals("ndus=VALID_ACTIVE_SESSION_TOKEN; panweb=1", success.session.cookieString)
    }

    @Test
    fun googleSignIn_detected_triggersBlockedNoticeWithoutSpoofing() = runTest(testDispatcher) {
        val fakeValidator = object : SessionValidator {
            override suspend fun validateSession(session: TeraboxSession): SessionValidationResult =
                SessionValidationResult.Valid
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = fakeValidator
        )
        viewModel.initialize("https://www.terabox.com/main")

        assertFalse(viewModel.uiState.value.isGoogleSignInBlocked)

        // 1. Navigation to Google OAuth endpoint triggers blocked flag
        viewModel.onUrlChanged("https://accounts.google.com/o/oauth2/v2/auth?client_id=123")
        assertTrue(viewModel.uiState.value.isGoogleSignInBlocked)

        // 2. Explicit callback for in-page blocked message sets actionable advice
        viewModel.onGoogleSignInBlocked()
        assertTrue(viewModel.uiState.value.isGoogleSignInBlocked)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Google restricts sign-in inside embedded app WebViews"))

        // 3. User dismisses notice
        viewModel.onDismissGoogleSignInNotice()
        assertFalse(viewModel.uiState.value.isGoogleSignInBlocked)
    }

    @Test
    fun onCancel_cancelsAuthCoordinatorAndCompletes() = runTest(testDispatcher) {
        val fakeValidator = object : SessionValidator {
            override suspend fun validateSession(session: TeraboxSession): SessionValidationResult =
                SessionValidationResult.Valid
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = fakeValidator
        )
        viewModel.initialize("https://www.terabox.com/main")

        val deferredAuth = async {
            authCoordinator.requestInteractiveAuth("https://www.terabox.com/s/123", "Login required")
        }

        viewModel.onCancel()

        assertTrue(viewModel.uiState.value.isComplete)
        val result = deferredAuth.await()
        assertTrue(result is InteractiveAuthResult.Cancelled)
    }

    @Test
    fun settings_logout_clearsPersistedSession() = runTest(testDispatcher) {
        val savedSession = TeraboxSession(
            domain = "terabox.com",
            cookieString = "ndus=SAVED_LOGIN_TOKEN",
            userAgent = "DesktopUA"
        )
        sessionRepository.saveSession(savedSession)
        assertNotNull(sessionRepository.getSession())

        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "test_storage_${System.currentTimeMillis()}").apply { mkdirs() }
        val storageManager = StorageManager(baseDir = tempDir)
        val settingsRepo = FakeSettingsRepository()
        val connectivityMonitor = FakeNetworkConnectivityMonitor(initialConnected = true, initialWifi = true)

        val settingsViewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            networkConnectivityMonitor = connectivityMonitor,
            sessionRepository = sessionRepository,
            sessionValidator = DefaultSessionValidator(httpClient = testHttpClient)
        )

        // Verify session status initially reflects logged in state
        assertTrue(settingsViewModel.uiState.value.isSessionActive)
        assertEquals("terabox.com", settingsViewModel.uiState.value.activeSessionDomain)

        // Perform explicit logout
        settingsViewModel.logout()

        // Verify session is cleared from repository and status is updated
        assertNull(sessionRepository.getSession())
        assertFalse(settingsViewModel.uiState.value.isSessionActive)
        assertNull(settingsViewModel.uiState.value.activeSessionDomain)

        tempDir.deleteRecursively()
    }
}
