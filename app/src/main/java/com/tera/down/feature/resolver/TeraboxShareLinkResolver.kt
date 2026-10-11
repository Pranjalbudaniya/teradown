package com.tera.down.feature.resolver

import com.tera.down.core.classifier.MediaClassifier
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink
import com.tera.down.core.model.ShareStatus
import com.tera.down.feature.downloader.FilenameSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Host-isolated resolver implementation for Terabox and verified mirror share links.
 *
 * Implements authorized metadata retrieval and direct-download detection.
 * Encapsulates all Terabox-specific network endpoints, parameter serialization, and error mapping.
 */
class TeraboxShareLinkResolver(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val cookieProvider: (() -> String?)? = null,
    private val baseUrlOverride: String? = null,
    private val mediaClassifier: (String, Int?) -> MediaType = { name, cat -> MediaClassifier.classify(name, cat) },
    private val sessionRepository: com.tera.down.feature.auth.SessionRepository? = null,
    private val authCoordinator: com.tera.down.feature.auth.AuthCoordinator? = null,
    private val classifier: com.tera.down.feature.resolver.classification.TeraboxResponseClassifier =
        com.tera.down.feature.resolver.classification.TeraboxResponseClassifier(),
    private val maxAuthAttempts: Int = 2
) : ShareLinkResolver {

    override val supportedDomains: Set<String> get() = TeraboxShareLinkParser.supportedDomains

    override fun canHandle(host: String): Boolean = TeraboxShareLinkParser.isRecognizedHost(host)

    override suspend fun resolve(
        shareLink: ShareLink,
        password: String?,
        dirPath: String,
        page: Int
    ): ShareResolveResult = withContext(Dispatchers.IO) {
        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Resolution cancelled before starting.")
        }

        // Scheme security validation: require HTTPS by default unless testing with local override
        if (baseUrlOverride == null && !shareLink.originalUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext ShareResolveResult.Failure(
                errorType = ShareResolveErrorType.UNSUPPORTED_SCHEME,
                message = "Only secure HTTPS URLs are permitted for share resolution."
            )
        }

        // Host security validation
        val host = shareLink.host ?: ""
        if (baseUrlOverride == null && !canHandle(host)) {
            return@withContext ShareResolveResult.Failure(
                errorType = ShareResolveErrorType.UNSUPPORTED_HOST,
                message = "Host '$host' is not a recognized or supported Terabox domain."
            )
        }

        // Check for expired session before initiating resolution
        val currentSession = sessionRepository?.getSession()
        if (currentSession != null && currentSession.isExpired && authCoordinator != null) {
            val preAuthResult = authCoordinator.requestInteractiveAuth(
                shareLink.originalUrl,
                "Saved session has expired. Please refresh your login session."
            )
            if (preAuthResult is com.tera.down.feature.auth.InteractiveAuthResult.Cancelled) {
                return@withContext ShareResolveResult.AuthRequired("Authentication cancelled by user.")
            }
        }

        // Build target endpoint URL
        val effectivePassword = password?.trim()?.ifBlank { null } ?: shareLink.pwd?.trim()?.ifBlank { null }
        val rootFlag = if (dirPath == "/" || dirPath.isBlank()) "1" else "0"

        fun buildEndpointUrl(targetHost: String): okhttp3.HttpUrl? {
            val base = baseUrlOverride?.trimEnd('/') ?: "https://$targetHost"
            return "$base/api/share/list".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("shorturl", shareLink.surlWithoutLeadingOne)
                ?.addQueryParameter("dir", dirPath)
                ?.addQueryParameter("page", page.toString())
                ?.addQueryParameter("num", "100")
                ?.addQueryParameter("root", rootFlag)
                ?.apply {
                    if (!effectivePassword.isNullOrBlank()) {
                        addQueryParameter("pwd", effectivePassword)
                    }
                }
                ?.build()
        }

        val alternateForms = AlternateUrlFormGenerator.generateForms(shareLink)
        var alternateFormIndex = 0

        // Prioritize working/saved session domain, then link host, then verified mirrors
        val candidateHosts = (listOfNotNull(
            sessionRepository?.getSession()?.domain?.takeIf { it.isNotBlank() },
            host
        ) + com.tera.down.feature.auth.DefaultSessionValidator.VERIFIED_MIRROR_DOMAINS)
            .distinct()
            .filter { canHandle(it) || it.contains("terabox") || it.contains("1024tera") }
            .toMutableList()

        var candidateHostIndex = 0
        var currentHost = candidateHosts.firstOrNull() ?: host
        var endpointUrl: HttpUrl = buildEndpointUrl(currentHost)
            ?: return@withContext ShareResolveResult.Failure(
                errorType = ShareResolveErrorType.INVALID_URL,
                message = "Unable to construct resolver endpoint URL."
            )

        var authAttempts = 0
        while (true) {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Resolution cancelled before request.")
            }

            val session = sessionRepository?.getSession()
            val effectiveUserAgent = session?.userAgent?.ifBlank { null }
                ?: com.tera.down.ui.auth.DesktopWebViewConfigurator.desktopUserAgent(null)

            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .get()
                .header("User-Agent", effectiveUserAgent)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", session?.acceptLanguage ?: "en-US,en;q=0.9")
                .header("Referer", shareLink.originalUrl)

            // Attach authorized session cookie if available
            val cookieStr = cookieProvider?.invoke() ?: sessionRepository?.getCookieHeader(currentHost)
            if (!cookieStr.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookieStr)
            }

            val request = requestBuilder.build()

            val verdict = try {
                httpClient.newCall(request).execute().use { response ->
                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("Resolution cancelled during network execution.")
                    }

                    val statusCode = response.code
                    val finalUrl = response.request.url.toString()
                    val contentType = response.header("Content-Type")
                    val bodyString = response.body?.string().orEmpty()

                    classifier.classify(
                        statusCode = statusCode,
                        finalUrl = finalUrl,
                        contentType = contentType,
                        rawBody = bodyString,
                        shareLink = shareLink,
                        providedPassword = effectivePassword
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                if (candidateHostIndex < candidateHosts.size - 1) {
                    candidateHostIndex++
                    currentHost = candidateHosts[candidateHostIndex]
                    val newEndpoint = buildEndpointUrl(currentHost)
                    if (newEndpoint != null) {
                        endpointUrl = newEndpoint
                        continue
                    }
                }
                return@withContext ShareResolveResult.Failure(
                    errorType = ShareResolveErrorType.TIMEOUT,
                    message = "Connection timed out while resolving share link on all mirror domains.",
                    cause = e
                )
            } catch (e: IOException) {
                if (candidateHostIndex < candidateHosts.size - 1) {
                    candidateHostIndex++
                    currentHost = candidateHosts[candidateHostIndex]
                    val newEndpoint = buildEndpointUrl(currentHost)
                    if (newEndpoint != null) {
                        endpointUrl = newEndpoint
                        continue
                    }
                }
                return@withContext ShareResolveResult.Failure(
                    errorType = ShareResolveErrorType.NETWORK_ERROR,
                    message = "Network error while reaching share resolver: ${e.message ?: "Unknown I/O error"}",
                    cause = e
                )
            } catch (e: Exception) {
                return@withContext ShareResolveResult.Failure(
                    errorType = ShareResolveErrorType.MALFORMED_RESPONSE,
                    message = "Unexpected error parsing share metadata: ${e.message ?: "Unknown error"}",
                    cause = e
                )
            }

            when (verdict) {
                is com.tera.down.feature.resolver.classification.ResponseVerdict.Ok -> {
                    sessionRepository?.recordSuccess(currentHost)
                    return@withContext parseJsonResponse(
                        json = verdict.json,
                        shareLink = shareLink,
                        providedPassword = effectivePassword,
                        dirPath = dirPath,
                        page = page
                    )
                }

                is com.tera.down.feature.resolver.classification.ResponseVerdict.NotLoggedIn,
                is com.tera.down.feature.resolver.classification.ResponseVerdict.CaptchaOrRateLimit,
                is com.tera.down.feature.resolver.classification.ResponseVerdict.UnknownHtml -> {
                    if (authCoordinator != null && authAttempts < maxAuthAttempts) {
                        val reason = when (verdict) {
                            is com.tera.down.feature.resolver.classification.ResponseVerdict.NotLoggedIn -> verdict.reason
                            is com.tera.down.feature.resolver.classification.ResponseVerdict.CaptchaOrRateLimit -> verdict.reason
                            is com.tera.down.feature.resolver.classification.ResponseVerdict.UnknownHtml -> verdict.reason
                            else -> "Authentication required"
                        }
                        val authResult = authCoordinator.requestInteractiveAuth(shareLink.originalUrl, reason)
                        when (authResult) {
                            is com.tera.down.feature.auth.InteractiveAuthResult.Success -> {
                                authAttempts++
                                continue
                            }
                            is com.tera.down.feature.auth.InteractiveAuthResult.Cancelled -> {
                                return@withContext if (verdict is com.tera.down.feature.resolver.classification.ResponseVerdict.CaptchaOrRateLimit) {
                                    ShareResolveResult.CaptchaOrRateLimited("Verification was cancelled by the user.")
                                } else {
                                    ShareResolveResult.AuthRequired("Authentication was cancelled by the user.")
                                }
                            }
                            is com.tera.down.feature.auth.InteractiveAuthResult.MaxAttemptsExceeded -> {
                                return@withContext if (verdict is com.tera.down.feature.resolver.classification.ResponseVerdict.CaptchaOrRateLimit) {
                                    ShareResolveResult.CaptchaOrRateLimited(authResult.message)
                                } else {
                                    ShareResolveResult.AuthRequired(authResult.message)
                                }
                            }
                            is com.tera.down.feature.auth.InteractiveAuthResult.Error -> {
                                return@withContext ShareResolveResult.Failure(
                                    errorType = ShareResolveErrorType.HTTP_ERROR,
                                    message = authResult.message
                                )
                            }
                        }
                    }

                    return@withContext when (verdict) {
                        is com.tera.down.feature.resolver.classification.ResponseVerdict.NotLoggedIn -> ShareResolveResult.AuthRequired(verdict.reason)
                        is com.tera.down.feature.resolver.classification.ResponseVerdict.CaptchaOrRateLimit -> ShareResolveResult.CaptchaOrRateLimited(verdict.reason)
                        is com.tera.down.feature.resolver.classification.ResponseVerdict.UnknownHtml -> ShareResolveResult.UnsupportedAccessFlow(verdict.reason)
                        else -> ShareResolveResult.UnsupportedAccessFlow("Interactive web page returned instead of metadata.")
                    }
                }

                is com.tera.down.feature.resolver.classification.ResponseVerdict.AppPromo -> {
                    // Section 18.6 Recovery sequence:
                    // 1. Confirm client's UA equals session.user_agent and is desktop UA
                    val currentSession = sessionRepository?.getSession()
                    val defaultDesktopUa = com.tera.down.ui.auth.DesktopWebViewConfigurator.desktopUserAgent(null)
                    var uaFixed = false
                    if (currentSession != null && (currentSession.userAgent.isBlank() || !currentSession.userAgent.contains("Linux x86_64"))) {
                        sessionRepository.saveSession(currentSession.copy(userAgent = defaultDesktopUa))
                        uaFixed = true
                    }

                    // 2. Retry using next alternate URL form / mirror if available
                    if (alternateFormIndex < alternateForms.size - 1) {
                        alternateFormIndex++
                        val nextUrl = alternateForms[alternateFormIndex]
                        val nextHost = nextUrl.toHttpUrlOrNull()?.host
                        if (!nextHost.isNullOrBlank() && canHandle(nextHost) && nextHost != currentHost) {
                            val newEndpoint = buildEndpointUrl(nextHost)
                            if (newEndpoint != null) {
                                currentHost = nextHost
                                endpointUrl = newEndpoint
                                continue
                            }
                        }
                    }

                    if (uaFixed) {
                        continue
                    }

                    // 3. Request interactive auth so user can dismiss overlay in WebView (counts toward 2-attempt cap)
                    if (authCoordinator != null && authAttempts < maxAuthAttempts) {
                        val authResult = authCoordinator.requestInteractiveAuth(
                            shareLink.originalUrl,
                            "Terabox app promo screen encountered. Please dismiss the promo overlay to continue."
                        )
                        when (authResult) {
                            is com.tera.down.feature.auth.InteractiveAuthResult.Success -> {
                                authAttempts++
                                continue
                            }
                            is com.tera.down.feature.auth.InteractiveAuthResult.Cancelled -> {
                                return@withContext ShareResolveResult.AppOnly("Verification cancelled by user during app promo screen.")
                            }
                            is com.tera.down.feature.auth.InteractiveAuthResult.MaxAttemptsExceeded -> {
                                return@withContext ShareResolveResult.AppOnly(authResult.message)
                            }
                            is com.tera.down.feature.auth.InteractiveAuthResult.Error -> {
                                return@withContext ShareResolveResult.Failure(
                                    errorType = ShareResolveErrorType.HTTP_ERROR,
                                    message = authResult.message
                                )
                            }
                        }
                    }

                    // 4. Still APP_PROMO -> return APP_ONLY (Section 18.6 / 18.8)
                    return@withContext ShareResolveResult.AppOnly(
                        "Terabox app promo screen persisted. Content is offered in the official app only."
                    )
                }

                is com.tera.down.feature.resolver.classification.ResponseVerdict.AppOnly -> {
                    return@withContext ShareResolveResult.AppOnly(verdict.reason)
                }

                is com.tera.down.feature.resolver.classification.ResponseVerdict.ResolverError -> {
                    return@withContext verdict.result
                }

                is com.tera.down.feature.resolver.classification.ResponseVerdict.HttpError -> {
                    return@withContext ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.HTTP_ERROR,
                        message = verdict.message
                    )
                }
            }
        }
        error("Unreachable resolution loop termination.")
    }

    private fun parseJsonResponse(
        json: SimpleJsonObject,
        shareLink: ShareLink,
        providedPassword: String?,
        dirPath: String,
        page: Int
    ): ShareResolveResult {
        val errno = json.optInt("errno", 0)
        val errmsg = json.optString("errmsg", "")

        // Map Terabox errno codes according to specification
        if (errno != 0) {
            return when {
                errno == -9 || errno == 2153 || errmsg.contains("pwd", ignoreCase = true) || errmsg.contains("password", ignoreCase = true) -> {
                    if (!providedPassword.isNullOrBlank()) {
                        ShareResolveResult.WrongPassword(shareLink, "Incorrect extraction password.")
                    } else {
                        ShareResolveResult.NeedsPassword(shareLink, "Password required to access this share.")
                    }
                }
                errno == -6 || errno == 401 || errmsg.contains("login", ignoreCase = true) -> {
                    ShareResolveResult.AuthRequired("Login session or account cookie required.")
                }
                errno == 9000 || errno == -2 || errmsg.contains("captcha", ignoreCase = true) || errmsg.contains("risk", ignoreCase = true) -> {
                    ShareResolveResult.CaptchaOrRateLimited("Service requested CAPTCHA verification or rate limit.")
                }
                errno == -1 || errno == 71000 || errmsg.contains("expired", ignoreCase = true) -> {
                    ShareResolveResult.Expired("This share link has expired.")
                }
                errno == -7 || errno == 110 || errmsg.contains("cancelled", ignoreCase = true) || errmsg.contains("deleted", ignoreCase = true) -> {
                    ShareResolveResult.Cancelled("This share has been cancelled or removed by the owner.")
                }
                errno == -12 || errno == -10 || errmsg.contains("block", ignoreCase = true) || errmsg.contains("violation", ignoreCase = true) -> {
                    ShareResolveResult.Blocked("This share has been blocked or violates platform policies.")
                }
                else -> {
                    ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.HTTP_ERROR,
                        message = "Terabox error (errno $errno): ${errmsg.ifBlank { "Unmapped error code" }}"
                    )
                }
            }
        }

        // Success payload parsing
        val shareId = json.optString("shareid").ifBlank { json.optString("share_id") }.ifBlank { null }
        val uk = json.optString("uk").ifBlank { null }
        val title = json.optString("title").ifBlank { "Shared Files (${shareLink.surl})" }
        val listArray = json.optArray("list")

        val items = mutableListOf<ShareFileItem>()
        var totalBytes = 0L

        for (i in listArray.indices) {
            val itemObj = listArray[i]
            val fsId = itemObj.optString("fs_id").ifBlank { itemObj.optLong("fs_id", 0L).toString() }
            val rawName = itemObj.optString("server_filename").ifBlank { itemObj.optString("filename", "file_$i") }
            val sanitizedName = FilenameSanitizer.sanitize(rawName)
            val size = itemObj.optLong("size", 0L)
            val isDir = itemObj.optInt("isdir", 0) == 1
            val category = itemObj.optInt("category", 0)
            val path = itemObj.optString("path", "/$sanitizedName")
            val rawDlink = itemObj.optString("dlink").trim().ifBlank { null }
            // Validate directUrl: accept only valid HTTPS URLs, reject unsafe schemes
            val dlink = rawDlink?.takeIf { com.tera.down.feature.downloader.DownloadUrlValidator.isValidUrl(it) }

            val mediaType = if (isDir) MediaType.OTHER else mediaClassifier(sanitizedName, category)
            val relPath = path.removePrefix("/")

            if (!isDir) {
                totalBytes += size
            }

            items.add(
                ShareFileItem(
                    fsId = fsId,
                    name = sanitizedName,
                    size = size,
                    relPath = relPath,
                    mediaType = mediaType,
                    category = category,
                    directUrl = dlink,
                    isSelected = !isDir && mediaType != MediaType.OTHER && dlink != null,
                    isDirectory = isDir
                )
            )
        }

        val hasMore = json.optInt("has_more", 0) == 1
        val pagination = FolderPagination(
            currentPage = page,
            totalPages = if (hasMore) page + 1 else page,
            hasMore = hasMore,
            pageSize = listArray.size
        )

        val shareInfo = ShareInfo(
            surl = shareLink.surl,
            title = title,
            shareId = shareId,
            uk = uk,
            fileCount = items.count { !it.isDirectory },
            totalBytes = totalBytes,
            state = ShareStatus.OK,
            requiresPassword = !providedPassword.isNullOrBlank(),
            password = providedPassword
        )

        // Check if any non-directory file has direct download URL available
        val nonDirFiles = items.filter { !it.isDirectory }
        val hasAnyDirectLinks = nonDirFiles.any { it.directUrl != null }

        return if (nonDirFiles.isNotEmpty() && !hasAnyDirectLinks) {
            ShareResolveResult.DirectDownloadUnavailable(
                shareInfo = shareInfo,
                items = items,
                message = "No direct download links exposed by the service for these files."
            )
        } else {
            ShareResolveResult.Success(
                shareInfo = shareInfo,
                items = items,
                pagination = pagination,
                currentDirectory = dirPath
            )
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false) // Reject redirection down to cleartext HTTP
                .addNetworkInterceptor(
                    com.tera.down.core.network.SafeRedirectInterceptor(
                        allowedHostsPredicate = { host -> TeraboxShareLinkParser.isRecognizedHost(host) },
                        requireHttps = true
                    )
                )
                .build()
        }
    }
}
