package com.tera.down.feature.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates interactive authentication requests across concurrent tasks.
 *
 * Guarantees single-flight execution (only one visible window opened even if multiple workers fail concurrently),
 * enforces a strict two-attempt cap per recovery episode, and preserves queue integrity upon cancellation.
 */
interface AuthCoordinator {
    val authRequestFlow: StateFlow<AuthRequest?>
    val isAuthInProgress: Boolean
    val currentAttemptCount: Int

    /**
     * Requests interactive authentication for [shareUrl]. If another request is already in progress,
     * joins the in-flight request rather than opening multiple screens.
     */
    suspend fun requestInteractiveAuth(shareUrl: String, reason: String): InteractiveAuthResult

    /**
     * Submits the outcome of the user's interactive authentication attempt.
     */
    fun submitAuthResult(result: InteractiveAuthResult)

    /**
     * Cancels the active authentication request.
     */
    fun cancelAuth()

    /**
     * Resets the attempt counter for a new episode.
     */
    fun resetAttempts()
}

class AuthCoordinatorImpl(
    private val maxAttempts: Int = 2
) : AuthCoordinator {

    private val mutex = Mutex()
    private val _authRequestFlow = MutableStateFlow<AuthRequest?>(null)
    override val authRequestFlow: StateFlow<AuthRequest?> = _authRequestFlow.asStateFlow()

    private var activeDeferred: CompletableDeferred<InteractiveAuthResult>? = null
    private var episodeAttemptCount = 0

    override val isAuthInProgress: Boolean
        get() = activeDeferred?.isActive == true

    override val currentAttemptCount: Int
        get() = episodeAttemptCount

    override suspend fun requestInteractiveAuth(shareUrl: String, reason: String): InteractiveAuthResult {
        val isInitiator: Boolean
        val deferredToAwait: CompletableDeferred<InteractiveAuthResult>

        mutex.withLock {
            val current = activeDeferred
            if (current != null && current.isActive) {
                // Another worker already initiated auth; join it (Single-flight)
                isInitiator = false
                deferredToAwait = current
            } else {
                if (episodeAttemptCount >= maxAttempts) {
                    return InteractiveAuthResult.MaxAttemptsExceeded(
                        "Maximum authentication attempts ($maxAttempts) reached. Service continues to request verification. Please try again later."
                    )
                }

                episodeAttemptCount++
                val newDeferred = CompletableDeferred<InteractiveAuthResult>()
                activeDeferred = newDeferred
                deferredToAwait = newDeferred
                isInitiator = true
                _authRequestFlow.value = AuthRequest(shareUrl = shareUrl, reason = reason)
            }
        }

        val result = try {
            deferredToAwait.await()
        } finally {
            if (isInitiator) {
                mutex.withLock {
                    if (activeDeferred === deferredToAwait) {
                        activeDeferred = null
                        _authRequestFlow.value = null
                    }
                }
            }
        }

        if (result is InteractiveAuthResult.Success) {
            mutex.withLock {
                episodeAttemptCount = 0
            }
        }

        return result
    }

    override fun submitAuthResult(result: InteractiveAuthResult) {
        activeDeferred?.complete(result)
    }

    override fun cancelAuth() {
        activeDeferred?.complete(InteractiveAuthResult.Cancelled("User cancelled authentication."))
    }

    override fun resetAttempts() {
        episodeAttemptCount = 0
    }
}
