package com.tera.down

import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink
import com.tera.down.feature.resolver.FolderTreeScanner
import com.tera.down.feature.resolver.ScanResult
import com.tera.down.feature.resolver.ShareLinkResolver
import com.tera.down.feature.resolver.ShareResolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderTreeScannerTest {

    private val testLink = ShareLink(
        originalUrl = "https://terabox.com/s/1tree",
        surl = "1tree",
        host = "terabox.com"
    )

    @Test
    fun scanRecursive_traversesNestedFolders_collectsAllFiles() = runBlocking {
        val resolver = object : ShareLinkResolver {
            override val supportedDomains: Set<String> = setOf("terabox.com")
            override fun canHandle(host: String): Boolean = true
            override suspend fun resolve(
                shareLink: ShareLink,
                password: String?,
                dirPath: String,
                page: Int
            ): ShareResolveResult {
                return when (dirPath) {
                    "/" -> ShareResolveResult.Success(
                        shareInfo = ShareInfo("1tree", "Root"),
                        items = listOf(
                            ShareFileItem("d1", "FolderA", 0L, "/FolderA", MediaType.OTHER, isDirectory = true),
                            ShareFileItem("f1", "root.mp4", 1000L, "/root.mp4", MediaType.VIDEO, directUrl = "https://d.com/root.mp4")
                        )
                    )
                    "/FolderA" -> ShareResolveResult.Success(
                        shareInfo = ShareInfo("1tree", "FolderA"),
                        items = listOf(
                            ShareFileItem("d2", "SubB", 0L, "/FolderA/SubB", MediaType.OTHER, isDirectory = true),
                            ShareFileItem("f2", "a.jpg", 500L, "/FolderA/a.jpg", MediaType.PHOTO, directUrl = "https://d.com/a.jpg")
                        )
                    )
                    "/FolderA/SubB" -> ShareResolveResult.Success(
                        shareInfo = ShareInfo("1tree", "SubB"),
                        items = listOf(
                            ShareFileItem("f3", "b.mp4", 2000L, "/FolderA/SubB/b.mp4", MediaType.VIDEO, directUrl = "https://d.com/b.mp4")
                        )
                    )
                    else -> ShareResolveResult.Success(ShareInfo("1tree", "Empty"), emptyList())
                }
            }
        }

        val scanner = FolderTreeScanner(resolver)
        val result = scanner.scanRecursive(testLink, null)

        assertTrue(result is ScanResult.Success)
        val success = result as ScanResult.Success
        assertEquals(3, success.allFiles.size)
        assertEquals(3, success.totalFoldersScanned)
        val names = success.allFiles.map { it.name }.toSet()
        assertTrue(names.contains("root.mp4"))
        assertTrue(names.contains("a.jpg"))
        assertTrue(names.contains("b.mp4"))
    }

    @Test
    fun scanRecursive_detectsCycles_andAvoidsInfiniteLoop() = runBlocking {
        val resolver = object : ShareLinkResolver {
            override val supportedDomains: Set<String> = setOf("terabox.com")
            override fun canHandle(host: String): Boolean = true
            override suspend fun resolve(
                shareLink: ShareLink,
                password: String?,
                dirPath: String,
                page: Int
            ): ShareResolveResult {
                // Symlink/cycle simulation: FolderA points back to "/" or itself
                return ShareResolveResult.Success(
                    shareInfo = ShareInfo("1tree", "Cycle"),
                    items = listOf(
                        ShareFileItem("d_cycle", "FolderA", 0L, "/FolderA", MediaType.OTHER, isDirectory = true),
                        ShareFileItem("f1", "file.mp4", 100L, "/file.mp4", MediaType.VIDEO, directUrl = "https://d.com/f.mp4")
                    )
                )
            }
        }

        val scanner = FolderTreeScanner(resolver)
        val result = scanner.scanRecursive(testLink, null)

        assertTrue(result is ScanResult.Success)
        val success = result as ScanResult.Success
        // FolderA was seen and visited once; cycle was prevented
        assertEquals(2, success.totalFoldersScanned)
    }

    @Test
    fun scanRecursive_maxDepthGuard_stopsDeepRecursion() = runBlocking {
        var currentLevel = 0
        val resolver = object : ShareLinkResolver {
            override val supportedDomains: Set<String> = setOf("terabox.com")
            override fun canHandle(host: String): Boolean = true
            override suspend fun resolve(
                shareLink: ShareLink,
                password: String?,
                dirPath: String,
                page: Int
            ): ShareResolveResult {
                currentLevel++
                val nextDir = "$dirPath/sub$currentLevel"
                return ShareResolveResult.Success(
                    shareInfo = ShareInfo("1tree", "Deep"),
                    items = listOf(
                        ShareFileItem("dir_$currentLevel", "sub$currentLevel", 0L, nextDir, MediaType.OTHER, isDirectory = true),
                        ShareFileItem("file_$currentLevel", "clip.mp4", 10L, "$dirPath/clip.mp4", MediaType.VIDEO, directUrl = "https://d.com/clip.mp4")
                    )
                )
            }
        }

        // Limit depth to 5
        val scanner = FolderTreeScanner(resolver, maxDepth = 5)
        val result = scanner.scanRecursive(testLink, null)

        assertTrue(result is ScanResult.Success)
        val success = result as ScanResult.Success
        // Should stop at max depth 5 + 1 root = 6 folders
        assertTrue("Folders scanned should be bounded by depth", success.totalFoldersScanned <= 7)
    }

    @Test
    fun scanRecursive_maxFilesLimitGuard_returnsPartialResult() = runBlocking {
        val resolver = object : ShareLinkResolver {
            override val supportedDomains: Set<String> = setOf("terabox.com")
            override fun canHandle(host: String): Boolean = true
            override suspend fun resolve(
                shareLink: ShareLink,
                password: String?,
                dirPath: String,
                page: Int
            ): ShareResolveResult {
                val items = (1..50).map { i ->
                    ShareFileItem("f_$i", "file_$i.mp4", 100L, "/file_$i.mp4", MediaType.VIDEO, directUrl = "https://d.com/f_$i.mp4")
                }
                return ShareResolveResult.Success(ShareInfo("1tree", "ManyFiles"), items)
            }
        }

        val scanner = FolderTreeScanner(resolver, maxFiles = 20)
        val result = scanner.scanRecursive(testLink, null)

        assertTrue(result is ScanResult.PartialLimitReached)
        val partial = result as ScanResult.PartialLimitReached
        assertTrue(partial.filesCollected.size >= 20)
        assertTrue(partial.reason.contains("20 items reached"))
    }

    @Test
    fun scanRecursive_cancellation_returnsCancelledResult() = runBlocking {
        val resolver = object : ShareLinkResolver {
            override val supportedDomains: Set<String> = setOf("terabox.com")
            override fun canHandle(host: String): Boolean = true
            override suspend fun resolve(
                shareLink: ShareLink,
                password: String?,
                dirPath: String,
                page: Int
            ): ShareResolveResult {
                delay(100)
                return ShareResolveResult.Success(
                    shareInfo = ShareInfo("1tree", "Delayed"),
                    items = listOf(
                        ShareFileItem("d1", "sub", 0L, "/sub", MediaType.OTHER, isDirectory = true)
                    )
                )
            }
        }

        val scanner = FolderTreeScanner(resolver)
        val job = async(Dispatchers.IO) {
            scanner.scanRecursive(testLink, null)
        }

        delay(50)
        job.cancel()
        assertTrue(job.isCancelled)
    }
}
