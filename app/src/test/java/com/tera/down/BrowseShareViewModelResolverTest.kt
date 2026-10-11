package com.tera.down

import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink
import com.tera.down.core.model.ShareStatus
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.resolver.ShareLinkResolver
import com.tera.down.feature.resolver.ShareLinkResolverRegistryImpl
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.ui.browse.BrowseContentState
import com.tera.down.ui.browse.BrowseShareViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseShareViewModelResolverTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepository

    private var resolverBehavior: (ShareLink, String?, String, Int) -> ShareResolveResult = { _, _, _, _ ->
        ShareResolveResult.Success(
            shareInfo = ShareInfo("surl1", "Test Share"),
            items = listOf(
                ShareFileItem(
                    fsId = "f1",
                    name = "video.mp4",
                    size = 1000L,
                    relPath = "video.mp4",
                    mediaType = MediaType.VIDEO,
                    directUrl = "https://d.terabox.com/file/video.mp4",
                    isSelected = true
                )
            )
        )
    }

    private val fakeResolver = object : ShareLinkResolver {
        override val supportedDomains: Set<String> = setOf("terabox.com")
        override fun canHandle(host: String): Boolean = host.contains("terabox.com")
        override suspend fun resolve(
            shareLink: ShareLink,
            password: String?,
            dirPath: String,
            page: Int
        ): ShareResolveResult = resolverBehavior(shareLink, password, dirPath, page)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()
        queueRepository = DownloadQueueRepositoryImpl(fakeTaskDao, fakeLibDao, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadShare_resolvesSuccessfully_updatesStateToSuccess() = runTest(testDispatcher) {
        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(
            queueRepository = queueRepository,
            resolverRegistry = registry
        )

        viewModel.loadShare("https://terabox.com/s/1validUrl")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.contentState
        assertTrue("Expected Success, got $state", state is BrowseContentState.Success)
        val success = state as BrowseContentState.Success
        assertEquals(1, success.files.size)
        assertEquals("video.mp4", success.files[0].name)
    }

    @Test
    fun loadShare_needsPassword_allowsPasswordSubmissionToResolve() = runTest(testDispatcher) {
        resolverBehavior = { link, pwd, _, _ ->
            if (pwd == "pass123") {
                ShareResolveResult.Success(
                    shareInfo = ShareInfo(link.surl, "Unlocked Share"),
                    items = listOf(
                        ShareFileItem("10", "unlocked.mp4", 500L, "unlocked.mp4", MediaType.VIDEO, directUrl = "https://d.terabox.com/unlocked.mp4", isSelected = true)
                    )
                )
            } else {
                ShareResolveResult.NeedsPassword(link, "Password required")
            }
        }

        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1protected")
        testScheduler.advanceUntilIdle()

        var state = viewModel.uiState.value.contentState
        assertTrue("Expected PasswordRequired, got $state", state is BrowseContentState.PasswordRequired)

        // Submit the correct password
        viewModel.submitPassword("pass123")
        testScheduler.advanceUntilIdle()

        state = viewModel.uiState.value.contentState
        assertTrue("Expected Success after password, got $state", state is BrowseContentState.Success)
        val success = state as BrowseContentState.Success
        assertEquals("Unlocked Share", success.shareInfo.title)
    }

    @Test
    fun loadShare_directDownloadUnavailable_displaysUnavailableState() = runTest(testDispatcher) {
        resolverBehavior = { link, _, _, _ ->
            ShareResolveResult.DirectDownloadUnavailable(
                shareInfo = ShareInfo(link.surl, "Stream Only"),
                items = listOf(
                    ShareFileItem("99", "preview.mp4", 5000L, "preview.mp4", MediaType.VIDEO, directUrl = null)
                ),
                message = "Preview only"
            )
        }

        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1streamonly")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.contentState
        assertTrue("Expected DirectDownloadUnavailable, got $state", state is BrowseContentState.DirectDownloadUnavailable)
    }

    @Test
    fun openDirectory_and_navigateUp_updatesCurrentDirectory() = runTest(testDispatcher) {
        resolverBehavior = { link, _, dir, _ ->
            ShareResolveResult.Success(
                shareInfo = ShareInfo(link.surl, "Folder Share"),
                items = listOf(
                    ShareFileItem("1", "sub.mp4", 100L, "$dir/sub.mp4", MediaType.VIDEO, directUrl = "https://d.terabox.com/sub.mp4")
                ),
                currentDirectory = dir
            )
        }

        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1tree")
        testScheduler.advanceUntilIdle()

        viewModel.openDirectory("/tree/season1")
        testScheduler.advanceUntilIdle()

        var state = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/tree/season1", state.currentDirectory)
        assertTrue(state.canNavigateUp)

        viewModel.navigateUp()
        testScheduler.advanceUntilIdle()

        state = viewModel.uiState.value.contentState as BrowseContentState.Success
        assertEquals("/", state.currentDirectory)
    }

    @Test
    fun downloadSelected_onlyEnqueuesItemsWithDirectUrls() = runTest(testDispatcher) {
        resolverBehavior = { link, _, _, _ ->
            ShareResolveResult.Success(
                shareInfo = ShareInfo(link.surl, "Mixed Share"),
                items = listOf(
                    ShareFileItem("f1", "has_url.mp4", 1000L, "has_url.mp4", MediaType.VIDEO, directUrl = "https://d.terabox.com/has_url.mp4", isSelected = true),
                    ShareFileItem("f2", "no_url.mp4", 2000L, "no_url.mp4", MediaType.VIDEO, directUrl = null, isSelected = true)
                )
            )
        }

        val registry = ShareLinkResolverRegistryImpl(listOf(fakeResolver))
        val viewModel = BrowseShareViewModel(queueRepository, registry)

        viewModel.loadShare("https://terabox.com/s/1mixed")
        testScheduler.advanceUntilIdle()

        var navigated = false
        viewModel.downloadSelected { navigated = true }
        testScheduler.advanceUntilIdle()

        assertTrue(navigated)
        val allTasks = fakeTaskDao.getAllTasks()
        assertEquals(1, allTasks.size)
        assertEquals("has_url.mp4", allTasks[0].name)
        assertEquals("https://d.terabox.com/has_url.mp4", allTasks[0].originalUrl)
    }
}
