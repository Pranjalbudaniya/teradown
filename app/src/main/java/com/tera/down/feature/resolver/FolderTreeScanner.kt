package com.tera.down.feature.resolver

import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Result of a recursive folder tree scan.
 */
sealed interface ScanResult {
    data class Success(
        val allFiles: List<ShareFileItem>,
        val totalFoldersScanned: Int
    ) : ScanResult

    data class PartialLimitReached(
        val filesCollected: List<ShareFileItem>,
        val reason: String
    ) : ScanResult

    data class Cancelled(
        val filesCollected: List<ShareFileItem>
    ) : ScanResult

    data class Error(
        val message: String
    ) : ScanResult
}

/**
 * Domain scanner for deep folder traversal with cycle detection, max nesting depth guards,
 * and result set cardinality limits.
 */
class FolderTreeScanner(
    private val resolver: ShareLinkResolver,
    private val maxDepth: Int = 30,
    private val maxFiles: Int = 1000
) {

    /**
     * Traverses the folder tree starting at [startDir], recursively collecting media files.
     * Reports progress via [onProgress] and respects coroutine cancellation.
     */
    suspend fun scanRecursive(
        shareLink: ShareLink,
        password: String?,
        startDir: String = "/",
        onProgress: (scannedFolders: Int, foundFiles: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.add(startDir to 0)

        val seenDirs = mutableSetOf<String>()
        val collectedFiles = mutableListOf<ShareFileItem>()
        var scannedFolders = 0

        while (stack.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) {
                return ScanResult.Cancelled(collectedFiles)
            }

            val (currentPath, depth) = stack.removeLast()
            if (currentPath in seenDirs) continue
            seenDirs.add(currentPath)
            scannedFolders++

            if (depth > maxDepth) {
                // Safeguard against runaway or circular directory nesting
                continue
            }

            onProgress(scannedFolders, collectedFiles.size)

            val resolveResult = try {
                resolver.resolve(
                    shareLink = shareLink,
                    password = password,
                    dirPath = currentPath
                )
            } catch (_: CancellationException) {
                return ScanResult.Cancelled(collectedFiles)
            } catch (e: Exception) {
                // Continue scanning remaining branches if one folder encounters a transient error
                continue
            }

            when (resolveResult) {
                is ShareResolveResult.Success -> {
                    for (item in resolveResult.items) {
                        if (item.isDirectory) {
                            val subPath = if (item.relPath.startsWith("/")) item.relPath else "/${item.relPath}"
                            if (subPath !in seenDirs) {
                                stack.add(subPath to depth + 1)
                            }
                        } else {
                            collectedFiles.add(item)
                            if (collectedFiles.size >= maxFiles) {
                                return ScanResult.PartialLimitReached(
                                    filesCollected = collectedFiles,
                                    reason = "Safety limit of $maxFiles items reached."
                                )
                            }
                        }
                    }
                }
                is ShareResolveResult.DirectDownloadUnavailable -> {
                    // Collect items even if stream-only for viewing purposes
                    for (item in resolveResult.items) {
                        if (item.isDirectory) {
                            val subPath = if (item.relPath.startsWith("/")) item.relPath else "/${item.relPath}"
                            if (subPath !in seenDirs) {
                                stack.add(subPath to depth + 1)
                            }
                        } else {
                            collectedFiles.add(item)
                        }
                    }
                }
                is ShareResolveResult.NeedsPassword -> {
                    return ScanResult.Error("Extraction password required.")
                }
                is ShareResolveResult.AuthRequired -> {
                    return ScanResult.Error("Authentication or login required.")
                }
                is ShareResolveResult.CaptchaOrRateLimited -> {
                    return ScanResult.Error("Rate limit or CAPTCHA check encountered.")
                }
                else -> {
                    // Ignore and continue other branches
                }
            }
        }

        return ScanResult.Success(
            allFiles = collectedFiles,
            totalFoldersScanned = scannedFolders
        )
    }
}
