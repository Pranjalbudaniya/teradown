package com.tera.down

import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.resolver.ShareLinkResolver
import com.tera.down.feature.resolver.ShareLinkResolverRegistryImpl
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.ui.browse.BrowseContentState
import com.tera.down.ui.browse.BrowseFilter
import com.tera.down.ui.browse.BrowseShareViewModel
import com.tera.down.ui.browse.BrowseSortOrder
import com.tera.down.ui.components.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserAndBatchDownloadTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepository

    private var resolverMap = mutableMapOf<String, List<ShareFileItem>>()

    private val fakeResolver = object : ShareLinkResolver {
        override val supportedDomains: Set<String> = setOf("terabox.com")
        override fun canHandle(host: String): Boolean = true
        override suspend fun resolve(
            shareLink: ShareLink,
            password: String?,
            dirPath: String,
            page: Int
        ): ShareResolveResult {
            val items = resolverMap[dirPath] ?: emptyList()
            return ShareResolveResult.Success(
                shareInfo = ShareInfo(shareLink.surl, "Test Share"),
                items = items,
                currentDirectory = dirPath
            )
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()
        queueRepository = DownloadQueueRepositoryImpl(fakeTaskDao, fakeLibDao, testDispatcher)

        resolverMap = mutableMapOf(
            "/" to listOf(
                ShareFileItem("d1", "Holiday", 0L, "/Holiday", MediaType.OTHER, isDirectory = true),
                ShareFileItem("f1", "zebra.mp4", 150000000L, "/zebra.mp4", MediaType.VIDEO, directUrl = "https://d.com/zebra.mp4"),
                ShareFileItem("f2", "alpha.jpg", 2000000L, "/alpha.jpg", MediaType.PHOTO, directUrl = "https://d.com/alpha.jpg"),
                ShareFileItem("f3", "notes.txt", 1000L, "/notes.txt", MediaType.OTHER, directUrl = "https://d.com/notes.txt"),
                ShareFileItem("f4", "stream_only.mp4", 50000000L, "/stream_only.mp4", MediaType.VIDEO, directUrl = null),
                ShareFileItem("f5", "unknown_size.mp4", -1L, "/unknown_size.mp4", MediaType.VIDEO, directUrl = "https://d.com/unknown.mp4")
            ),
            "/Holiday" to listOf(
                ShareFileItem("d2", "2026", 0L, "/Holiday/2026", MediaType.OTHER, isDirectory = true),
                ShareFileItem("f6", "beach.mov", 80000000L, "/Holiday/beach.mov", MediaType.VIDEO, directUrl = "https://d.com/beach.mov")
            ),
            "/Holiday/2026" to listOf(
                ShareFileItem("f7", "sunset.png", 5000000L, "/Holiday/2026/sunset.png", MediaType.PHOTO, directUrl = "https://d.com/sunset.png")
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun folderNavigation_parentAndRoot_updatesStateAndResetsSelection() = runTest(testDispatcher) {
        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1browse")
        testScheduler.advanceUntilIdle()

        // 1. Initial Root State
        var success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/", success.currentDirectory)
        assertFalse(success.canNavigateUp)

        // Select an item in root
        viewModel.toggleItemSelection("f1")
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals(1, success.selectedCount)

        // 2. Open /Holiday
        viewModel.openDirectory("/Holiday")
        testScheduler.advanceUntilIdle()

        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/Holiday", success.currentDirectory)
        assertTrue(success.canNavigateUp)
        // Selection is reset upon entering another folder
        assertEquals(0, success.selectedCount)

        // 3. Open /Holiday/2026
        viewModel.openDirectory("/Holiday/2026")
        testScheduler.advanceUntilIdle()

        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/Holiday/2026", success.currentDirectory)

        // 4. Navigate Up to /Holiday
        viewModel.navigateUp()
        testScheduler.advanceUntilIdle()

        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/Holiday", success.currentDirectory)

        // 5. Navigate to Root (/)
        viewModel.navigateToRoot()
        testScheduler.advanceUntilIdle()

        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/", success.currentDirectory)
    }

    @Test
    fun selection_individualToggle_selectAll_andClear() = runTest(testDispatcher) {
        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1browse")
        testScheduler.advanceUntilIdle()

        var success = viewModel.uiState.value.contentState as BrowseContentState.Success

        // Folders cannot be selected
        viewModel.toggleItemSelection("d1")
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertFalse(success.selectedFsIds.contains("d1"))

        // File without direct URL cannot be selected
        viewModel.toggleItemSelection("f4")
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertFalse(success.selectedFsIds.contains("f4"))

        // Select individual valid files
        viewModel.toggleItemSelection("f1")
        viewModel.toggleItemSelection("f2")
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals(2, success.selectedCount)
        assertTrue(success.selectedFsIds.contains("f1"))
        assertTrue(success.selectedFsIds.contains("f2"))

        // Select All eligible files
        viewModel.selectAll()
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        // f1, f2, f5 are eligible (video/photo with direct URL); d1 is folder, f3 is OTHER, f4 has no direct URL
        assertEquals(3, success.selectedCount)
        assertTrue(success.isAllSelected)

        // Clear Selection
        viewModel.clearSelection()
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals(0, success.selectedCount)
        assertFalse(success.isAllSelected)
    }

    @Test
    fun filtering_and_sorting_preservesFoldersAndOrder() = runTest(testDispatcher) {
        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1browse")
        testScheduler.advanceUntilIdle()

        // 1. Filter by VIDEOS
        viewModel.setFilter(BrowseFilter.VIDEOS)
        var success = viewModel.uiState.value.contentState as BrowseContentState.Success
        val videoDisplay = success.displayFiles
        // Folders are kept at the top
        assertTrue(videoDisplay.first().isDirectory)
        assertEquals("Holiday", videoDisplay.first().name)
        // All non-folder items must be videos
        val nonDirVideos = videoDisplay.filter { !it.isDirectory }
        assertTrue(nonDirVideos.all { it.mediaType == MediaType.VIDEO })

        // 2. Filter by PHOTOS
        viewModel.setFilter(BrowseFilter.PHOTOS)
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        val photoDisplay = success.displayFiles
        assertTrue(photoDisplay.first().isDirectory)
        val nonDirPhotos = photoDisplay.filter { !it.isDirectory }
        assertEquals(1, nonDirPhotos.size)
        assertEquals("alpha.jpg", nonDirPhotos[0].name)

        // 3. Reset Filter to ALL, test Sort by Name ASC
        viewModel.setFilter(BrowseFilter.ALL)
        viewModel.setSortOrder(BrowseSortOrder.NAME_ASC)
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        val nameAscNonDirs = success.displayFiles.filter { !it.isDirectory }
        assertEquals("alpha.jpg", nameAscNonDirs.first().name)
        assertEquals("zebra.mp4", nameAscNonDirs.last().name)

        // 4. Sort by Size DESC
        viewModel.setSortOrder(BrowseSortOrder.SIZE_DESC)
        success = viewModel.uiState.value.contentState as BrowseContentState.Success
        val sizeDescNonDirs = success.displayFiles.filter { !it.isDirectory }
        assertEquals("zebra.mp4", sizeDescNonDirs.first().name) // 150 MB
        assertEquals("unknown_size.mp4", sizeDescNonDirs.last().name) // -1L placed at bottom
    }

    @Test
    fun unknownSize_isFormattedGracefully() {
        assertEquals("Unknown size", Formatters.formatFileSize(-1L))
        assertEquals("Unknown size", Formatters.formatFileSize(-100L))
        assertEquals("0 B", Formatters.formatFileSize(0L))
        assertEquals("1.0 KB", Formatters.formatFileSize(1024L))
    }

    @Test
    fun batchQueue_enqueuesEligible_skipsDuplicates_andRejectsUnavailable() = runTest(testDispatcher) {
        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1browse")
        testScheduler.advanceUntilIdle()

        // Select all eligible files in root
        viewModel.selectAll()
        testScheduler.advanceUntilIdle()

        var navigated = false
        viewModel.queueSelected { navigated = true }
        testScheduler.advanceUntilIdle()

        assertTrue(navigated)
        val tasks = fakeTaskDao.getAllTasks()
        assertEquals(3, tasks.size) // f1, f2, f5
        val taskNames = tasks.map { it.name }.toSet()
        assertTrue(taskNames.contains("zebra.mp4"))
        assertTrue(taskNames.contains("alpha.jpg"))
        assertTrue(taskNames.contains("unknown_size.mp4"))

        // Try queueing again: all 3 should be detected as duplicates
        viewModel.selectAll()
        navigated = false
        viewModel.queueSelected { navigated = true }
        testScheduler.advanceUntilIdle()

        assertFalse(navigated)
        val uiState = viewModel.uiState.value
        assertTrue(uiState.actionMessage!!.contains("duplicate(s) skipped"))
    }
}
