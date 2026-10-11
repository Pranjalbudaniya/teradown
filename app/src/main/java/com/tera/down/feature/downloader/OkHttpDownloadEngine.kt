package com.tera.down.feature.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Production [DownloadEngine] implementation using [OkHttpClient].
 *
 * Implements:
 * - Direct HTTPS streaming via 32KB chunks without loading files into memory.
 * - HTTP byte-range request verification: validates HTTP 206 Partial Content before appending;
 *   safely falls back to clean restart from byte 0 when range requests are not honored (HTTP 200).
 * - Automatic retry from scratch upon HTTP 416 Range Not Satisfiable.
 * - Coercive progress calculation and smoothed transfer speed estimates.
 * - Cooperative Coroutine cancellation with partial file cleanup safeguards.
 */
class OkHttpDownloadEngine(
    private val storageManager: StorageManager,
    client: OkHttpClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowHttpForTesting: Boolean = false
) : DownloadEngine {

    private val client: OkHttpClient = client ?: defaultClient(allowHttpForTesting)

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_THROTTLE_MS = 200L

        fun defaultClient(allowHttpForTesting: Boolean = false): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false) // Reject redirection from HTTPS down to cleartext HTTP

            if (!allowHttpForTesting) {
                builder.addNetworkInterceptor(
                    com.tera.down.core.network.SafeRedirectInterceptor(requireHttps = true)
                )
            }
            return builder.build()
        }
    }

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): DownloadResult = withContext(ioDispatcher) {
        // 1. URL Validation
        try {
            DownloadUrlValidator.validateUrl(request.url, allowHttpForTesting)
        } catch (e: Exception) {
            return@withContext DownloadResult.Error(
                type = DownloadErrorType.INVALID_URL,
                message = e.message ?: "Invalid download URL: ${DownloadUrlValidator.toSafeLogUrl(request.url)}",
                cause = e
            )
        }

        // 2. Storage space verification if expected size is known
        if (request.expectedSize > 0) {
            val freeBytes = storageManager.getAvailableStorageBytes()
            val safetyBuffer = 5L * 1024 * 1024 // 5MB margin
            if (freeBytes > 0 && freeBytes < request.expectedSize + safetyBuffer) {
                return@withContext DownloadResult.Error(
                    type = DownloadErrorType.INSUFFICIENT_SPACE,
                    message = "Insufficient device storage space for download (${request.expectedSize} bytes required, $freeBytes bytes available)."
                )
            }
        }

        val tempFile = request.tempFile
        tempFile.parentFile?.mkdirs()

        // 3. Determine if attempting range resume
        val existingBytes = if (tempFile.exists() && request.allowResume) tempFile.length() else 0L
        val attemptResume = if (existingBytes > 0L) {
            if (request.expectedSize > 0L && existingBytes >= request.expectedSize) {
                // Staging file is larger than or equal to expected size; reset cleanly to avoid corruption
                tempFile.delete()
                false
            } else {
                true
            }
        } else {
            false
        }

        val actualOffset = if (attemptResume) existingBytes else 0L

        // Execute download attempt (with single automatic restart from 0 if 416 occurs)
        executeTransfer(
            request = request,
            tempFile = tempFile,
            startOffset = actualOffset,
            isResumeAttempt = attemptResume,
            onProgress = onProgress
        )
    }

    private suspend fun executeTransfer(
        request: DownloadRequest,
        tempFile: File,
        startOffset: Long,
        isResumeAttempt: Boolean,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): DownloadResult {
        val requestBuilder = Request.Builder().url(request.url)
        request.headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        if (isResumeAttempt && startOffset > 0L) {
            requestBuilder.addHeader("Range", "bytes=$startOffset-")
        }

        val httpRequest = requestBuilder.build()
        val call = client.newCall(httpRequest)

        var response: Response? = null
        try {
            response = call.execute()

            // Handle HTTP Range Not Satisfiable (416): partial file is stale, restart from byte 0
            if (response.code == 416 && isResumeAttempt) {
                response.close()
                tempFile.delete()
                return executeTransfer(
                    request = request,
                    tempFile = tempFile,
                    startOffset = 0L,
                    isResumeAttempt = false,
                    onProgress = onProgress
                )
            }

            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.message.ifBlank { "HTTP Error $code" }
                response.close()
                return DownloadResult.Error(
                    type = if (code in 500..599) DownloadErrorType.SERVER_ERROR else DownloadErrorType.HTTP_ERROR,
                    message = "Server rejected download with HTTP $code: $msg",
                    httpStatusCode = code,
                    isResumable = code in setOf(408, 429, 502, 503, 504)
                )
            }

            val body = response.body
                ?: return DownloadResult.Error(
                    type = DownloadErrorType.HTTP_ERROR,
                    message = "Server response body is empty."
                )

            val contentLength = body.contentLength()
            val responseCode = response.code
            val appendToFile: Boolean
            val totalBytes: Long
            var downloadedBytesSoFar: Long

            if (isResumeAttempt && responseCode == 206) {
                // Verified safe resume: server acknowledged Range header
                appendToFile = true
                downloadedBytesSoFar = startOffset
                totalBytes = if (contentLength >= 0) startOffset + contentLength else -1L
            } else {
                // If server sent 200 OK despite resume request, server DOES NOT honor byte ranges.
                // Discard partial file and restart cleanly from 0 to avoid file corruption.
                if (isResumeAttempt && startOffset > 0L) {
                    tempFile.delete()
                }
                appendToFile = false
                downloadedBytesSoFar = 0L
                totalBytes = if (contentLength >= 0) contentLength else -1L
            }

            val mimeType = body.contentType()?.toString()
            val destName = request.destinationFile.name.lowercase()
            val isExpectedHtml = destName.endsWith(".html") || destName.endsWith(".htm")

            if (!isExpectedHtml && mimeType != null && mimeType.contains("text/html", ignoreCase = true)) {
                return DownloadResult.Error(
                    type = DownloadErrorType.HTTP_ERROR,
                    message = "Server returned HTML webpage instead of binary file stream (link may have expired or requires login).",
                    httpStatusCode = 401,
                    isResumable = false
                )
            }

            // Stream response directly to storage
            body.byteStream().use { inputStream ->
                FileOutputStream(tempFile, appendToFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastProgressUpdate = System.currentTimeMillis()
                    var bytesSinceLastProgress = 0L

                    while (true) {
                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("Download cancelled by caller")
                        }

                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break

                        if (downloadedBytesSoFar == 0L && !isExpectedHtml && bytesRead >= 6) {
                            val prefix = String(buffer, 0, minOf(bytesRead, 128)).trimStart().lowercase()
                            if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html")) {
                                return DownloadResult.Error(
                                    type = DownloadErrorType.HTTP_ERROR,
                                    message = "Server returned HTML error/promo page instead of file stream.",
                                    httpStatusCode = 401,
                                    isResumable = false
                                )
                            }
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytesSoFar += bytesRead
                        bytesSinceLastProgress += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastProgressUpdate
                        if (elapsed >= PROGRESS_THROTTLE_MS) {
                            val speed = if (elapsed > 0) (bytesSinceLastProgress * 1000L) / elapsed else 0L
                            onProgress(downloadedBytesSoFar, totalBytes, speed)
                            lastProgressUpdate = now
                            bytesSinceLastProgress = 0L
                        }
                    }

                    outputStream.flush()
                }
            }

            // Final progress emission at completion
            onProgress(downloadedBytesSoFar, totalBytes, 0L)

            // Verification against known content length
            if (totalBytes > 0 && downloadedBytesSoFar != totalBytes) {
                return DownloadResult.Error(
                    type = DownloadErrorType.NETWORK_FAILURE,
                    message = "Incomplete transfer: expected $totalBytes bytes but received $downloadedBytesSoFar bytes."
                )
            }

            // Finalize temporary file to destination
            val finalizedFile = storageManager.finalizeDownload(tempFile, request.destinationFile)

            return DownloadResult.Success(
                file = finalizedFile,
                totalBytes = downloadedBytesSoFar,
                mimeType = mimeType,
                resumed = appendToFile
            )

        } catch (e: CancellationException) {
            call.cancel()
            return DownloadResult.Cancelled
        } catch (e: IOException) {
            call.cancel()
            val safeMsg = com.tera.down.core.model.AppError.redactSensitive(e.message ?: "Network error")
            return DownloadResult.Error(
                type = DownloadErrorType.NETWORK_FAILURE,
                message = "Network error during download: $safeMsg",
                cause = e,
                isResumable = true
            )
        } catch (e: Exception) {
            call.cancel()
            val safeMsg = com.tera.down.core.model.AppError.redactSensitive(e.message ?: "Unknown failure")
            return DownloadResult.Error(
                type = DownloadErrorType.UNKNOWN,
                message = "Unexpected download failure: $safeMsg",
                cause = e
            )
        } finally {
            response?.close()
        }
    }
}
