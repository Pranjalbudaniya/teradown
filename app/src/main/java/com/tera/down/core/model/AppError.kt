package com.tera.down.core.model

import com.tera.down.feature.downloader.DownloadErrorType
import com.tera.down.feature.downloader.DownloadResult
import com.tera.down.feature.resolver.ShareResolveErrorType
import com.tera.down.feature.resolver.ShareResolveResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Consistent, typed error categories across the application as defined by Phase 13 specification.
 */
enum class AppErrorCode {
    INVALID_SHARE_LINK,
    UNSUPPORTED_HOST,
    PASSWORD_REQUIRED,
    AUTHENTICATION_REQUIRED,
    SHARE_EXPIRED,
    SHARE_REMOVED_OR_INACCESSIBLE,
    UNSUPPORTED_ACCESS_FLOW,
    DIRECT_DOWNLOAD_UNAVAILABLE,
    NETWORK_TIMEOUT,
    CONNECTION_FAILURE,
    SERVER_REJECTION_OR_RATE_LIMIT,
    INSUFFICIENT_STORAGE,
    DESTINATION_PERMISSION_REVOKED,
    FILE_WRITE_FAILURE,
    FILE_VERIFICATION_FAILURE,
    UNSUPPORTED_MEDIA_FORMAT,
    CANCELLED_OPERATION,
    UNEXPECTED_INTERNAL_ERROR
}

/**
 * Domain error representation ensuring safe, actionable user messages
 * and redacting sensitive internal details (tokens, cookies, auth headers).
 */
data class AppError(
    val code: AppErrorCode,
    val userMessage: String,
    val technicalMessage: String? = null,
    val isRetryable: Boolean = false,
    val httpStatusCode: Int? = null
) {
    companion object {

        private val SENSITIVE_QUERY_REGEX = Regex(
            """(?i)([?&](?:token|auth|sign|signature|key|access_token|session|pwd|password|cookie|secret|credential)=)[^&#\s]+"""
        )

        private val BEARER_REGEX = Regex("""(?i)bearer\s+[a-zA-Z0-9_\-\.]+""")

        /**
         * Redacts sensitive tokens, passwords, cookies, or authorization query parameters from text.
         */
        fun redactSensitive(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            var sanitized = SENSITIVE_QUERY_REGEX.replace(raw, "$1***")
            sanitized = BEARER_REGEX.replace(sanitized, "Bearer ***")
            return sanitized
        }

        /**
         * Maps generic throwables into safe, typed [AppError]s.
         */
        fun fromThrowable(throwable: Throwable): AppError {
            val safeTechnical = redactSensitive(throwable.message ?: throwable.javaClass.simpleName)
            return when (throwable) {
                is SocketTimeoutException -> AppError(
                    code = AppErrorCode.NETWORK_TIMEOUT,
                    userMessage = "Connection timed out. Please check your network and try again.",
                    technicalMessage = safeTechnical,
                    isRetryable = true
                )
                is UnknownHostException -> AppError(
                    code = AppErrorCode.CONNECTION_FAILURE,
                    userMessage = "Unable to connect to service host. Check your internet connection.",
                    technicalMessage = safeTechnical,
                    isRetryable = true
                )
                is java.io.FileNotFoundException -> AppError(
                    code = AppErrorCode.SHARE_REMOVED_OR_INACCESSIBLE,
                    userMessage = "The requested file or destination could not be found.",
                    technicalMessage = safeTechnical,
                    isRetryable = false
                )
                is SecurityException -> AppError(
                    code = AppErrorCode.DESTINATION_PERMISSION_REVOKED,
                    userMessage = "Storage permission revoked or access restricted.",
                    technicalMessage = safeTechnical,
                    isRetryable = false
                )
                is IOException -> AppError(
                    code = AppErrorCode.CONNECTION_FAILURE,
                    userMessage = "Network or storage I/O error occurred. Please try again.",
                    technicalMessage = safeTechnical,
                    isRetryable = true
                )
                else -> AppError(
                    code = AppErrorCode.UNEXPECTED_INTERNAL_ERROR,
                    userMessage = "An unexpected error occurred. Please try again.",
                    technicalMessage = safeTechnical,
                    isRetryable = false
                )
            }
        }

        /**
         * Maps HTTP response status codes into typed [AppError]s.
         */
        fun fromHttpStatus(statusCode: Int, detail: String? = null): AppError {
            val safeDetail = redactSensitive(detail)
            return when (statusCode) {
                401 -> AppError(
                    code = AppErrorCode.AUTHENTICATION_REQUIRED,
                    userMessage = "Authentication or login session required.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = false
                )
                403 -> AppError(
                    code = AppErrorCode.AUTHENTICATION_REQUIRED,
                    userMessage = "Access forbidden. Account authorization may be required.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = false
                )
                404 -> AppError(
                    code = AppErrorCode.SHARE_REMOVED_OR_INACCESSIBLE,
                    userMessage = "The requested file or share was removed or does not exist.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = false
                )
                408 -> AppError(
                    code = AppErrorCode.NETWORK_TIMEOUT,
                    userMessage = "Request timed out on the server. Please try again.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = true
                )
                429 -> AppError(
                    code = AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT,
                    userMessage = "Server rate limit reached. Please wait a few moments before retrying.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = true
                )
                in 500..599 -> AppError(
                    code = AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT,
                    userMessage = "Remote server encountered an error (HTTP $statusCode). Please try again later.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = true
                )
                else -> AppError(
                    code = AppErrorCode.UNEXPECTED_INTERNAL_ERROR,
                    userMessage = "Server returned HTTP $statusCode.",
                    technicalMessage = safeDetail,
                    httpStatusCode = statusCode,
                    isRetryable = false
                )
            }
        }

        /**
         * Maps [DownloadResult.Error] into typed [AppError].
         */
        fun fromDownloadError(error: DownloadResult.Error): AppError {
            val safeMessage = redactSensitive(error.message)
            return when (error.type) {
                DownloadErrorType.INVALID_URL -> AppError(
                    code = AppErrorCode.INVALID_SHARE_LINK,
                    userMessage = "Invalid or malformed download URL.",
                    technicalMessage = safeMessage,
                    isRetryable = false
                )
                DownloadErrorType.UNSUPPORTED_SCHEME -> AppError(
                    code = AppErrorCode.INVALID_SHARE_LINK,
                    userMessage = "Unsupported URL scheme. Only secure HTTPS is allowed.",
                    technicalMessage = safeMessage,
                    isRetryable = false
                )
                DownloadErrorType.INSUFFICIENT_SPACE -> AppError(
                    code = AppErrorCode.INSUFFICIENT_STORAGE,
                    userMessage = "Insufficient device storage space for download.",
                    technicalMessage = safeMessage,
                    isRetryable = false
                )
                DownloadErrorType.NETWORK_FAILURE -> AppError(
                    code = AppErrorCode.CONNECTION_FAILURE,
                    userMessage = "Network connection failed during download.",
                    technicalMessage = safeMessage,
                    isRetryable = true
                )
                DownloadErrorType.HTTP_ERROR, DownloadErrorType.SERVER_ERROR -> {
                    if (error.httpStatusCode != null) {
                        fromHttpStatus(error.httpStatusCode, safeMessage)
                    } else {
                        AppError(
                            code = AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT,
                            userMessage = "Server rejected download request.",
                            technicalMessage = safeMessage,
                            isRetryable = error.isResumable
                        )
                    }
                }
                DownloadErrorType.FILE_IO_ERROR -> AppError(
                    code = AppErrorCode.FILE_WRITE_FAILURE,
                    userMessage = "Failed writing file to destination storage.",
                    technicalMessage = safeMessage,
                    isRetryable = false
                )
                DownloadErrorType.UNKNOWN -> AppError(
                    code = AppErrorCode.UNEXPECTED_INTERNAL_ERROR,
                    userMessage = "An unexpected error occurred during download.",
                    technicalMessage = safeMessage,
                    isRetryable = false
                )
            }
        }

        /**
         * Maps [ShareResolveResult] into typed [AppError], or null if successful.
         */
        fun fromShareResolveResult(result: ShareResolveResult): AppError? {
            return when (result) {
                is ShareResolveResult.Success -> null
                is ShareResolveResult.NeedsPassword -> AppError(
                    code = AppErrorCode.PASSWORD_REQUIRED,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.WrongPassword -> AppError(
                    code = AppErrorCode.PASSWORD_REQUIRED,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.AuthRequired -> AppError(
                    code = AppErrorCode.AUTHENTICATION_REQUIRED,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.CaptchaOrRateLimited -> AppError(
                    code = AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT,
                    userMessage = redactSensitive(result.message),
                    isRetryable = true
                )
                is ShareResolveResult.Expired -> AppError(
                    code = AppErrorCode.SHARE_EXPIRED,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.Cancelled -> AppError(
                    code = AppErrorCode.SHARE_REMOVED_OR_INACCESSIBLE,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.Blocked -> AppError(
                    code = AppErrorCode.SHARE_REMOVED_OR_INACCESSIBLE,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.UnsupportedAccessFlow -> AppError(
                    code = AppErrorCode.UNSUPPORTED_ACCESS_FLOW,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.DirectDownloadUnavailable -> AppError(
                    code = AppErrorCode.DIRECT_DOWNLOAD_UNAVAILABLE,
                    userMessage = redactSensitive(result.message),
                    isRetryable = false
                )
                is ShareResolveResult.Failure -> {
                    val safeMsg = redactSensitive(result.message)
                    when (result.errorType) {
                        ShareResolveErrorType.INVALID_URL -> AppError(
                            code = AppErrorCode.INVALID_SHARE_LINK,
                            userMessage = safeMsg,
                            isRetryable = false
                        )
                        ShareResolveErrorType.UNSUPPORTED_SCHEME -> AppError(
                            code = AppErrorCode.INVALID_SHARE_LINK,
                            userMessage = safeMsg,
                            isRetryable = false
                        )
                        ShareResolveErrorType.UNSUPPORTED_HOST -> AppError(
                            code = AppErrorCode.UNSUPPORTED_HOST,
                            userMessage = safeMsg,
                            isRetryable = false
                        )
                        ShareResolveErrorType.TIMEOUT -> AppError(
                            code = AppErrorCode.NETWORK_TIMEOUT,
                            userMessage = safeMsg,
                            isRetryable = true
                        )
                        ShareResolveErrorType.RATE_LIMITED -> AppError(
                            code = AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT,
                            userMessage = safeMsg,
                            isRetryable = true
                        )
                        ShareResolveErrorType.NETWORK_ERROR -> AppError(
                            code = AppErrorCode.CONNECTION_FAILURE,
                            userMessage = safeMsg,
                            isRetryable = true
                        )
                        ShareResolveErrorType.HTTP_ERROR -> AppError(
                            code = AppErrorCode.SERVER_REJECTION_OR_RATE_LIMIT,
                            userMessage = safeMsg,
                            isRetryable = true
                        )
                        ShareResolveErrorType.CANCELLED -> AppError(
                            code = AppErrorCode.CANCELLED_OPERATION,
                            userMessage = "Operation was cancelled.",
                            isRetryable = false
                        )
                        ShareResolveErrorType.MALFORMED_RESPONSE -> AppError(
                            code = AppErrorCode.UNEXPECTED_INTERNAL_ERROR,
                            userMessage = safeMsg,
                            isRetryable = false
                        )
                    }
                }
            }
        }
    }
}
