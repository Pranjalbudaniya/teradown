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
    private val mediaClassifier: (String, Int?) -> MediaType = { name, cat -> MediaClassifier.classify(name, cat) }
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

        // Build target endpoint URL
        val base = baseUrlOverride?.trimEnd('/') ?: "https://$host"
        val effectivePassword = password?.trim()?.ifBlank { null } ?: shareLink.pwd?.trim()?.ifBlank { null }
        val rootFlag = if (dirPath == "/" || dirPath.isBlank()) "1" else "0"

        val endpointUrl = "$base/api/share/list".toHttpUrlOrNull()
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

        if (endpointUrl == null) {
            return@withContext ShareResolveResult.Failure(
                errorType = ShareResolveErrorType.INVALID_URL,
                message = "Unable to construct resolver endpoint URL."
            )
        }

        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .get()
            .header("User-Agent", "Teradown/1.0 (Android)")
            .header("Accept", "application/json, text/plain, */*")

        // Attach authorized session cookie if available
        cookieProvider?.invoke()?.let { cookieStr ->
            if (cookieStr.isNotBlank()) {
                requestBuilder.header("Cookie", cookieStr)
            }
        }

        val request = requestBuilder.build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Resolution cancelled during network execution.")
                }

                // Handle HTTP level statuses
                when (response.code) {
                    429 -> return@withContext ShareResolveResult.CaptchaOrRateLimited(
                        "Service returned rate limit (HTTP 429). Please wait before retrying."
                    )
                    401 -> return@withContext ShareResolveResult.AuthRequired(
                        "Authentication required to view this share (HTTP 401)."
                    )
                    403 -> return@withContext ShareResolveResult.AuthRequired(
                        "Access forbidden or login required (HTTP 403)."
                    )
                    404 -> return@withContext ShareResolveResult.Cancelled(
                        "Share link not found or deleted (HTTP 404)."
                    )
                    in 500..599 -> return@withContext ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.HTTP_ERROR,
                        message = "Terabox service error (HTTP ${response.code}). Please try again later."
                    )
                }

                val bodyString = response.body?.string().orEmpty().trim()
                if (bodyString.isEmpty()) {
                    return@withContext ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.MALFORMED_RESPONSE,
                        message = "Server returned an empty response body."
                    )
                }

                // If response is HTML, it is typically a web login or human verification gate
                if (bodyString.startsWith("<html", ignoreCase = true) ||
                    bodyString.startsWith("<!DOCTYPE", ignoreCase = true)
                ) {
                    return@withContext ShareResolveResult.UnsupportedAccessFlow(
                        "The server returned an interactive web page instead of metadata. Login or browser check required."
                    )
                }

                val json = SimpleJsonParser.parse(bodyString)
                if (json == null) {
                    return@withContext ShareResolveResult.Failure(
                        errorType = ShareResolveErrorType.MALFORMED_RESPONSE,
                        message = "Failed to parse service response as JSON."
                    )
                }

                return@withContext parseJsonResponse(
                    json = json,
                    shareLink = shareLink,
                    providedPassword = effectivePassword,
                    dirPath = dirPath,
                    page = page
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return@withContext ShareResolveResult.Failure(
                errorType = ShareResolveErrorType.TIMEOUT,
                message = "Connection timed out while resolving share link.",
                cause = e
            )
        } catch (e: IOException) {
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
