package com.tera.down

import com.tera.down.feature.auth.AuthCoordinatorImpl
import com.tera.down.feature.auth.InteractiveAuthResult
import com.tera.down.feature.auth.TeraboxSession
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthCoordinatorTest {

    private lateinit var coordinator: AuthCoordinatorImpl

    @Before
    fun setup() {
        coordinator = AuthCoordinatorImpl(maxAttempts = 2)
    }

    @Test
    fun requestInteractiveAuth_successfulSubmission_returnsSuccess() {
        runBlocking {
            val deferred = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Login required")
            }

            delay(50)
            assertTrue(coordinator.isAuthInProgress)
            val request = coordinator.authRequestFlow.value
            assertNotNull(request)
            assertEquals("https://terabox.com/s/1test", request?.shareUrl)

            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "test_cookie=abc",
                userAgent = "TestBrowser/1.0"
            )
            coordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            val result = deferred.await()
            assertTrue(result is InteractiveAuthResult.Success)
            assertEquals("terabox.com", (result as InteractiveAuthResult.Success).session.domain)
            assertFalse(coordinator.isAuthInProgress)
            assertNull(coordinator.authRequestFlow.value)
        }
    }

    @Test
    fun requestInteractiveAuth_cancelledByUser_returnsCancelled() {
        runBlocking {
            val deferred = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Captcha")
            }

            delay(50)
            coordinator.cancelAuth()

            val result = deferred.await()
            assertTrue(result is InteractiveAuthResult.Cancelled)
            assertFalse(coordinator.isAuthInProgress)
            assertNull(coordinator.authRequestFlow.value)
        }
    }

    @Test
    fun requestInteractiveAuth_concurrentCalls_joinsSingleFlightDeferred() {
        runBlocking {
            val worker1 = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Worker 1 hit auth")
            }
            val worker2 = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Worker 2 hit auth")
            }
            val worker3 = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Worker 3 hit auth")
            }

            delay(50)
            // Exactly 1 attempt registered for this single-flight episode
            assertEquals(1, coordinator.currentAttemptCount)

            val session = TeraboxSession(
                domain = "terabox.com",
                cookieString = "cookie=shared_success",
                userAgent = "DesktopUA"
            )
            coordinator.submitAuthResult(InteractiveAuthResult.Success(session))

            val res1 = worker1.await()
            val res2 = worker2.await()
            val res3 = worker3.await()

            assertTrue(res1 is InteractiveAuthResult.Success)
            assertTrue(res2 is InteractiveAuthResult.Success)
            assertTrue(res3 is InteractiveAuthResult.Success)

            assertEquals("cookie=shared_success", (res1 as InteractiveAuthResult.Success).session.cookieString)
            assertEquals("cookie=shared_success", (res2 as InteractiveAuthResult.Success).session.cookieString)
            assertEquals("cookie=shared_success", (res3 as InteractiveAuthResult.Success).session.cookieString)
        }
    }

    @Test
    fun requestInteractiveAuth_twoAttemptCap_enforcesLimit() {
        runBlocking {
            // Episode Attempt 1 (e.g. user submitted error or failed session)
            val attempt1 = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Attempt 1")
            }
            delay(50)
            assertEquals(1, coordinator.currentAttemptCount)
            coordinator.submitAuthResult(InteractiveAuthResult.Error("Verification failed"))
            val res1 = attempt1.await()
            assertTrue(res1 is InteractiveAuthResult.Error)

            // Episode Attempt 2
            val attempt2 = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Attempt 2")
            }
            delay(50)
            assertEquals(2, coordinator.currentAttemptCount)
            coordinator.submitAuthResult(InteractiveAuthResult.Error("Verification failed again"))
            val res2 = attempt2.await()
            assertTrue(res2 is InteractiveAuthResult.Error)

            // Attempt 3: Cap is reached, immediate rejection without opening request
            val res3 = coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Attempt 3")
            assertTrue("Expected MaxAttemptsExceeded, got: $res3", res3 is InteractiveAuthResult.MaxAttemptsExceeded)
            assertNull(coordinator.authRequestFlow.value)

            // Reset episode allows new attempts
            coordinator.resetAttempts()
            assertEquals(0, coordinator.currentAttemptCount)

            val attemptAfterReset = async {
                coordinator.requestInteractiveAuth("https://terabox.com/s/1test", "Fresh attempt")
            }
            delay(50)
            assertEquals(1, coordinator.currentAttemptCount)
            coordinator.cancelAuth()
            attemptAfterReset.await()
        }
    }
}
