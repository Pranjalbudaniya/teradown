package com.tera.down.feature.resolver

import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink

/**
 * Pagination information for directory contents.
 */
data class FolderPagination(
    val currentPage: Int,
    val totalPages: Int,
    val hasMore: Boolean,
    val pageSize: Int
)

/**
 * Typed error categories for share resolution failures.
 */
enum class ShareResolveErrorType {
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    UNSUPPORTED_HOST,
    NETWORK_ERROR,
    HTTP_ERROR,
    RATE_LIMITED,
    TIMEOUT,
    MALFORMED_RESPONSE,
    CANCELLED
}

/**
 * Typed result of resolving a share link.
 */
sealed interface ShareResolveResult {

    /**
     * Share was resolved with metadata and file listing.
     */
    data class Success(
        val shareInfo: ShareInfo,
        val items: List<ShareFileItem>,
        val pagination: FolderPagination? = null,
        val currentDirectory: String = "/"
    ) : ShareResolveResult

    /**
     * The share is protected by an extraction code / password.
     */
    data class NeedsPassword(
        val shareLink: ShareLink,
        val message: String = "Password required to access this share."
    ) : ShareResolveResult

    /**
     * Password was provided but rejected by the host.
     */
    data class WrongPassword(
        val shareLink: ShareLink,
        val message: String = "Incorrect extraction code/password."
    ) : ShareResolveResult

    /**
     * Share requires an authorized user account or active session cookie.
     */
    data class AuthRequired(
        val message: String = "Authentication or login session required to access this share."
    ) : ShareResolveResult

    /**
     * Anti-bot check, CAPTCHA, or rate-limiting encountered.
     */
    data class CaptchaOrRateLimited(
        val message: String = "Rate limit or CAPTCHA verification requested by the service."
    ) : ShareResolveResult

    /**
     * The share has expired.
     */
    data class Expired(
        val message: String = "This share link has expired."
    ) : ShareResolveResult

    /**
     * The share was cancelled or deleted by the owner.
     */
    data class Cancelled(
        val message: String = "This share was cancelled or removed by the owner."
    ) : ShareResolveResult

    /**
     * The share was blocked by the service.
     */
    data class Blocked(
        val message: String = "This share has been blocked or violates platform terms."
    ) : ShareResolveResult

    /**
     * The share requires an interaction flow not supported directly.
     */
    data class UnsupportedAccessFlow(
        val message: String = "This share requires an interactive login or web flow not supported directly."
    ) : ShareResolveResult

    /**
     * Metadata was found but no legitimate direct download URL is available for the files.
     */
    data class DirectDownloadUnavailable(
        val shareInfo: ShareInfo,
        val items: List<ShareFileItem>,
        val message: String = "No direct download URLs are exposed for these files (stream or preview only)."
    ) : ShareResolveResult

    /**
     * Network or system error during resolution.
     */
    data class Failure(
        val errorType: ShareResolveErrorType,
        val message: String,
        val cause: Throwable? = null
    ) : ShareResolveResult
}
