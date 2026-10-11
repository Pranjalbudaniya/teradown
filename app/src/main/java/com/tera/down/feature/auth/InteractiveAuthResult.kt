package com.tera.down.feature.auth

import java.util.UUID

/**
 * Result of an interactive authentication or verification attempt.
 */
sealed interface InteractiveAuthResult {
    /**
     * User completed login or verification and session was captured.
     */
    data class Success(val session: TeraboxSession) : InteractiveAuthResult

    /**
     * User cancelled or closed the authentication window without completing it.
     */
    data class Cancelled(val reason: String = "Authentication was cancelled by the user.") : InteractiveAuthResult

    /**
     * The strict 2-attempt recovery episode cap was reached.
     */
    data class MaxAttemptsExceeded(val message: String) : InteractiveAuthResult

    /**
     * An error occurred during authentication handling.
     */
    data class Error(val message: String, val cause: Throwable? = null) : InteractiveAuthResult
}

/**
 * Represents a pending interactive authentication request to be presented in the UI.
 */
data class AuthRequest(
    val shareUrl: String,
    val reason: String,
    val id: String = UUID.randomUUID().toString()
)
