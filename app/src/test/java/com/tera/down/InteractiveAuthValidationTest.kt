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
import okhttp3.mockwebserver.SocketPolicy
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
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
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
                SessionValidationResult.Valid()
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = fakeValidator
        )
        viewModel.initialize("https://www.terabox.app/main")

        // 1. Null cookies
        viewModel.onDone(
            currentUrl = "https://www.terabox.app/main",
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
            currentUrl = "https://www.terabox.app/main",
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
        assertTrue(result is SessionValidationResult.InvalidTokens)
        val invalid = result as SessionValidationResult.InvalidTokens
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
        assertTrue(result is SessionValidationResult.InvalidTokens)
        val invalid = result as SessionValidationResult.InvalidTokens
        assertTrue(invalid.reason.contains("expired", ignoreCase = true))
    }

    @Test
    fun sessionValidator_serverRedirectsToLogin_failsValidation() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://passport.terabox.app/v2/api/login")
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
        assertTrue(result is SessionValidationResult.NotLoggedIn)
        val notLoggedIn = result as SessionValidationResult.NotLoggedIn
        assertTrue(notLoggedIn.reason.contains("login endpoint", ignoreCase = true))
    }

    @Test
    fun sessionValidator_serverReturnsHtmlInsteadOfJson_returnsUnexpectedResponse() = runTest(testDispatcher) {
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
        assertTrue(result is SessionValidationResult.UnexpectedResponse)
        val unexpected = result as SessionValidationResult.UnexpectedResponse
        assertTrue(unexpected.reason.contains("web page", ignoreCase = true))
    }

    @Test
    fun sessionValidator_serverReturnsUnauthenticatedErrno_returnsNotLoggedIn() = runTest(testDispatcher) {
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
        assertTrue(result is SessionValidationResult.NotLoggedIn)
        val notLoggedIn = result as SessionValidationResult.NotLoggedIn
        assertTrue(notLoggedIn.reason.contains("errno: -6", ignoreCase = true))
    }

    @Test
    fun sessionValidator_timeout_returnsNetworkError() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        val validator = DefaultSessionValidator(
            httpClient = testHttpClient,
            baseUrlOverride = mockWebServer.url("/").toString()
        )
        val session = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "ndus=COOKIE_FOR_TIMEOUT_TEST",
            userAgent = "DesktopUA"
        )

        val result = validator.validateSession(session)
        assertTrue(result is SessionValidationResult.NetworkError)
        val netErr = result as SessionValidationResult.NetworkError
        assertTrue(netErr.reason.contains("timed out", ignoreCase = true))
    }

    @Test
    fun sessionValidator_server500_returnsServerError() = runTest(testDispatcher) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val validator = DefaultSessionValidator(
            httpClient = testHttpClient,
            baseUrlOverride = mockWebServer.url("/").toString()
        )
        val session = TeraboxSession(
            domain = mockWebServer.hostName + ":" + mockWebServer.port,
            cookieString = "ndus=COOKIE_FOR_500_TEST",
            userAgent = "DesktopUA"
        )

        val result = validator.validateSession(session)
        assertTrue(result is SessionValidationResult.ServerError)
        val serverErr = result as SessionValidationResult.ServerError
        assertEquals(500, serverErr.code)
    }

    @Test
    fun onDone_failedSessionValidation_rejectsAuthAndDoesNotPersist() = runTest(testDispatcher) {
        val failingValidator = object : SessionValidator {
            override suspend fun validateSession(session: TeraboxSession): SessionValidationResult =
                SessionValidationResult.NotLoggedIn("Remote server rejected authentication token.")
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = failingValidator
        )
        viewModel.initialize("https://www.terabox.app/main")

        viewModel.onDone(
            currentUrl = "https://www.terabox.app/main",
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
    fun onDone_networkError_preservesCandidateSessionAndAllowsRetry() = runTest(testDispatcher) {
        val networkFailingValidator = object : SessionValidator {
            var calls = 0
            override suspend fun validateSession(session: TeraboxSession): SessionValidationResult {
                calls++
                return if (calls == 1) {
                    SessionValidationResult.NetworkError("Connection timed out reaching server")
                } else {
                    SessionValidationResult.Valid(uk = 99999L, uname = "recovered_user", verifiedDomain = "www.terabox.app")
                }
            }
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = networkFailingValidator
        )
        viewModel.initialize("https://www.terabox.app/main")

        // 1. Initial attempt fails with network error
        viewModel.onDone(
            currentUrl = "https://www.terabox.app/main",
            cookieString = "ndus=VALID_RECOVERABLE_TOKEN",
            userAgent = "DesktopUA"
        )

        var state = viewModel.uiState.value
        assertFalse(state.isComplete)
        assertTrue(state.canRetry)
        assertTrue(state.isNetworkError)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Network error", ignoreCase = true))
        assertTrue(state.errorMessage!!.contains("preserved", ignoreCase = true))

        // Session is NOT saved yet, but preserved for retry
        assertNull(sessionRepository.getSession())

        // 2. User taps Retry
        viewModel.retryValidation()

        state = viewModel.uiState.value
        assertTrue(state.isComplete)
        assertFalse(state.canRetry)
        assertNull(state.errorMessage)

        // Session was now persisted upon successful retry
        val saved = sessionRepository.getSession()
        assertNotNull(saved)
        assertEquals("ndus=VALID_RECOVERABLE_TOKEN", saved?.cookieString)
        assertEquals("www.terabox.app", saved?.domain)
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

        // Verify request URL was lowercase /api/user/getinfo
        val recordedReq = mockWebServer.takeRequest()
        assertEquals("/api/user/getinfo", recordedReq.path)

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
                SessionValidationResult.Valid()
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = fakeValidator
        )
        viewModel.initialize("https://www.terabox.app/main")

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
                SessionValidationResult.Valid()
        }

        val viewModel = InteractiveAuthViewModel(
            authCoordinator = authCoordinator,
            sessionRepository = sessionRepository,
            sessionValidator = fakeValidator
        )
        viewModel.initialize("https://www.terabox.app/main")

        val deferredAuth = async {
            authCoordinator.requestInteractiveAuth("https://www.terabox.app/s/123", "Login required")
        }

        viewModel.onCancel()

        assertTrue(viewModel.uiState.value.isComplete)
        val result = deferredAuth.await()
        assertTrue(result is InteractiveAuthResult.Cancelled)
    }

    @Test
    fun settings_logout_clearsPersistedSession() = runTest(testDispatcher) {
        val savedSession = TeraboxSession(
            domain = "www.terabox.app",
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
        assertEquals("www.terabox.app", settingsViewModel.uiState.value.activeSessionDomain)

        // Perform explicit logout
        settingsViewModel.logout()

        // Verify session is cleared from repository and status is updated
        assertNull(sessionRepository.getSession())
        assertFalse(settingsViewModel.uiState.value.isSessionActive)
        assertNull(settingsViewModel.uiState.value.activeSessionDomain)

        tempDir.deleteRecursively()
    }
}
