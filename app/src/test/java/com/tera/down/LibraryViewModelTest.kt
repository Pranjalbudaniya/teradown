package com.tera.down

import com.tera.down.core.model.LocalMediaItem
import com.tera.down.core.model.MediaType
import com.tera.down.feature.queue.LibraryRepository
import com.tera.down.feature.queue.LibraryRepositoryImpl
import com.tera.down.ui.library.LibraryFilter
import com.tera.down.ui.library.LibrarySortOrder
import com.tera.down.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var repository: LibraryRepository
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeLibDao = FakeLibraryMediaDao()
        repository = LibraryRepositoryImpl(
            libraryMediaDao = fakeLibDao,
            ioDispatcher = testDispatcher
        )
        viewModel = LibraryViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialEmptyState() {
        val state = viewModel.uiState.value
        assertTrue(state.items.isEmpty())
        assertTrue(state.filteredAndSortedItems.isEmpty())
        assertEquals(0, state.videoCount)
        assertEquals(0, state.photoCount)
        assertEquals(0, state.otherCount)
        assertEquals(0L, state.totalOccupiedBytes)
        assertEquals(0, state.missingCount)
    }

    @Test
    fun reactiveUpdate_whenMediaAdded() = runBlocking {
        val tempFile = tempFolder.newFile("SampleVideo.mp4").apply {
            writeBytes(ByteArray(1024))
        }

        repository.addMedia(
            LocalMediaItem(
                id = "item_1",
                name = "SampleVideo.mp4",
                path = tempFile.absolutePath,
                size = 1024L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )

        val state = viewModel.uiState.value
        assertEquals(1, state.items.size)
        assertEquals(1, state.videoCount)
        assertEquals(0, state.photoCount)
        assertEquals(1024L, state.totalOccupiedBytes)
        assertEquals(0, state.missingCount)
        assertTrue(state.items[0].existsOnDisk)
    }

    @Test
    fun filtering_byMediaType_worksCorrectly() = runBlocking {
        val vid = tempFolder.newFile("Vid.mp4")
        val pic = tempFolder.newFile("Pic.jpg")
        val doc = tempFolder.newFile("Doc.pdf")

        repository.addMedia(
            LocalMediaItem(
                id = "vid_1",
                name = "Vid.mp4",
                path = vid.absolutePath,
                size = 1000L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )
        repository.addMedia(
            LocalMediaItem(
                id = "pic_1",
                name = "Pic.jpg",
                path = pic.absolutePath,
                size = 500L,
                dateAdded = 2000L,
                mediaType = MediaType.PHOTO
            )
        )
        repository.addMedia(
            LocalMediaItem(
                id = "doc_1",
                name = "Doc.pdf",
                path = doc.absolutePath,
                size = 300L,
                dateAdded = 3000L,
                mediaType = MediaType.OTHER
            )
        )

        viewModel.setFilter(LibraryFilter.VIDEOS)
        assertEquals(1, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("Vid.mp4", viewModel.uiState.value.filteredAndSortedItems[0].name)

        viewModel.setFilter(LibraryFilter.PHOTOS)
        assertEquals(1, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("Pic.jpg", viewModel.uiState.value.filteredAndSortedItems[0].name)

        viewModel.setFilter(LibraryFilter.OTHER)
        assertEquals(1, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("Doc.pdf", viewModel.uiState.value.filteredAndSortedItems[0].name)

        viewModel.setFilter(LibraryFilter.ALL)
        assertEquals(3, viewModel.uiState.value.filteredAndSortedItems.size)
    }

    @Test
    fun searchByName_filtersCorrectly() = runBlocking {
        repository.addMedia(
            LocalMediaItem(
                id = "item_1",
                name = "Nature Documentary 1080p.mp4",
                path = "/path/1",
                size = 1000L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )
        repository.addMedia(
            LocalMediaItem(
                id = "item_2",
                name = "City Skylines.mp4",
                path = "/path/2",
                size = 2000L,
                dateAdded = 2000L,
                mediaType = MediaType.VIDEO
            )
        )

        viewModel.onSearchQueryChange("Nature")
        assertEquals(1, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("Nature Documentary 1080p.mp4", viewModel.uiState.value.filteredAndSortedItems[0].name)

        // Case insensitivity
        viewModel.onSearchQueryChange("sky")
        assertEquals(1, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("City Skylines.mp4", viewModel.uiState.value.filteredAndSortedItems[0].name)

        // Clear query
        viewModel.clearSearchAndFilters()
        assertEquals(2, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun sorting_ordersItemsProperly() = runBlocking {
        repository.addMedia(LocalMediaItem("1", "Alpha.mp4", "/p/1", 100L, 1000L, MediaType.VIDEO))
        repository.addMedia(LocalMediaItem("2", "Charlie.mp4", "/p/2", 300L, 3000L, MediaType.VIDEO))
        repository.addMedia(LocalMediaItem("3", "Bravo.mp4", "/p/3", 200L, 2000L, MediaType.VIDEO))

        // DATE_DESC (default)
        viewModel.setSortOrder(LibrarySortOrder.DATE_DESC)
        assertEquals(listOf("Charlie.mp4", "Bravo.mp4", "Alpha.mp4"), viewModel.uiState.value.filteredAndSortedItems.map { it.name })

        // DATE_ASC
        viewModel.setSortOrder(LibrarySortOrder.DATE_ASC)
        assertEquals(listOf("Alpha.mp4", "Bravo.mp4", "Charlie.mp4"), viewModel.uiState.value.filteredAndSortedItems.map { it.name })

        // NAME_ASC
        viewModel.setSortOrder(LibrarySortOrder.NAME_ASC)
        assertEquals(listOf("Alpha.mp4", "Bravo.mp4", "Charlie.mp4"), viewModel.uiState.value.filteredAndSortedItems.map { it.name })

        // NAME_DESC
        viewModel.setSortOrder(LibrarySortOrder.NAME_DESC)
        assertEquals(listOf("Charlie.mp4", "Bravo.mp4", "Alpha.mp4"), viewModel.uiState.value.filteredAndSortedItems.map { it.name })

        // SIZE_DESC
        viewModel.setSortOrder(LibrarySortOrder.SIZE_DESC)
        assertEquals(listOf("Charlie.mp4", "Bravo.mp4", "Alpha.mp4"), viewModel.uiState.value.filteredAndSortedItems.map { it.name })

        // SIZE_ASC
        viewModel.setSortOrder(LibrarySortOrder.SIZE_ASC)
        assertEquals(listOf("Alpha.mp4", "Bravo.mp4", "Charlie.mp4"), viewModel.uiState.value.filteredAndSortedItems.map { it.name })
    }

    @Test
    fun missingFile_isMarkedAsMissing_andExcludedFromOccupiedStorage() = runBlocking {
        val existingFile = tempFolder.newFile("Present.mp4").apply {
            writeBytes(ByteArray(500))
        }

        repository.addMedia(
            LocalMediaItem("present", "Present.mp4", existingFile.absolutePath, 500L, 1000L, MediaType.VIDEO)
        )
        repository.addMedia(
            LocalMediaItem("missing", "Missing.mp4", "/non/existent/path/Missing.mp4", 9000L, 2000L, MediaType.VIDEO)
        )

        val state = viewModel.uiState.value
        assertEquals(2, state.items.size)
        assertEquals(1, state.missingCount)
        // Only 500 bytes counted, 9000 bytes for missing file is ignored!
        assertEquals(500L, state.totalOccupiedBytes)

        val presentItem = state.items.first { it.id == "present" }
        assertTrue(presentItem.existsOnDisk)

        val missingItem = state.items.first { it.id == "missing" }
        assertFalse(missingItem.existsOnDisk)
    }

    @Test
    fun deleteMediaRecordOnly_removesFromLibrary_keepsActionMessage() = runBlocking {
        repository.addMedia(
            LocalMediaItem(
                id = "to_remove",
                name = "RecordOnly.mp4",
                path = "/storage/RecordOnly.mp4",
                size = 1000L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )

        assertEquals(1, viewModel.uiState.value.items.size)

        viewModel.deleteMediaRecordOnly("to_remove")

        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertNotNull(viewModel.uiState.value.actionMessage)
        assertTrue(viewModel.uiState.value.actionMessage!!.contains("Removed entry"))
    }

    @Test
    fun deleteMediaFileAndRecord_deletesFromLibrary() = runBlocking {
        val tempFile = tempFolder.newFile("RealFile.mp4").apply {
            writeBytes(ByteArray(100))
        }

        repository.addMedia(
            LocalMediaItem(
                id = "to_delete_file",
                name = "RealFile.mp4",
                path = tempFile.absolutePath,
                size = 100L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )

        assertTrue(tempFile.exists())
        assertEquals(1, viewModel.uiState.value.items.size)

        viewModel.deleteMediaFileAndRecord("to_delete_file")

        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertFalse(tempFile.exists())
    }

    @Test
    fun renameMedia_updatesName() = runBlocking {
        val tempFile = tempFolder.newFile("OriginalName.mp4").apply {
            writeBytes(ByteArray(100))
        }

        repository.addMedia(
            LocalMediaItem(
                id = "rename_id",
                name = "OriginalName.mp4",
                path = tempFile.absolutePath,
                size = 100L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )

        viewModel.renameMedia("rename_id", "NewRenamedVideo")

        val updatedItem = viewModel.uiState.value.items.first()
        assertEquals("NewRenamedVideo.mp4", updatedItem.name)
        assertNotNull(viewModel.uiState.value.actionMessage)
    }
}
