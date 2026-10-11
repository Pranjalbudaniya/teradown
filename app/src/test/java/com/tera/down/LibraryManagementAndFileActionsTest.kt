package com.tera.down

import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.LocalMediaItem
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.util.LocalFileActions
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.queue.LibraryRepositoryImpl
import com.tera.down.ui.library.LibraryFilter
import com.tera.down.ui.library.LibrarySortOrder
import com.tera.down.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryManagementAndFileActionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibraryDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepositoryImpl
    private lateinit var libraryRepository: LibraryRepositoryImpl

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibraryDao = FakeLibraryMediaDao()

        queueRepository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = testDispatcher
        )
        libraryRepository = LibraryRepositoryImpl(
            libraryMediaDao = fakeLibraryDao,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun mappingCompletedTask_intoLibraryRecord() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "fs_1001",
                name = "MyAwesomeVideo.mp4",
                size = 4096L,
                relPath = "Videos/MyAwesomeVideo.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = queueRepository.enqueueShareFiles(
            surl = "surl_map_test",
            originalUrl = "https://terabox.com/s/surl_map_test",
            items = files
        )
        val taskId = taskIds.first()

        queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)

        val targetFile = tempFolder.newFile("MyAwesomeVideo.mp4").apply {
            writeBytes(ByteArray(4096))
        }

        val completed = queueRepository.completeTask(taskId, targetFile.absolutePath)
        assertTrue(completed)

        val libraryItems = libraryRepository.observeAllMedia().first()
        assertEquals(1, libraryItems.size)
        val item = libraryItems.first()
        assertEquals("MyAwesomeVideo.mp4", item.name)
        assertEquals(targetFile.absolutePath, item.path)
        assertEquals(4096L, item.size)
        assertEquals(MediaType.VIDEO, item.mediaType)
        assertTrue(item.existsOnDisk)
    }

    @Test
    fun duplicateCompletion_preservesSingleRecord() = runBlocking {
        val files = listOf(
            ShareFileItem(
                fsId = "fs_2002",
                name = "DuplicateTest.mp4",
                size = 2048L,
                relPath = "DuplicateTest.mp4",
                mediaType = MediaType.VIDEO,
                category = 1
            )
        )
        val taskIds = queueRepository.enqueueShareFiles(
            surl = "surl_dup_test",
            originalUrl = "https://terabox.com/s/surl_dup_test",
            items = files
        )
        val taskId = taskIds.first()
        queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)

        val destination = tempFolder.newFile("DuplicateTest.mp4").absolutePath

        // First completion
        queueRepository.completeTask(taskId, destination)
        val itemsAfterFirst = libraryRepository.observeAllMedia().first()
        assertEquals(1, itemsAfterFirst.size)
        val firstId = itemsAfterFirst.first().id

        // Worker retry / observer re-trigger invoking completeTask again
        queueRepository.completeTask(taskId, destination)
        val itemsAfterSecond = libraryRepository.observeAllMedia().first()
        assertEquals(1, itemsAfterSecond.size)
        assertEquals(firstId, itemsAfterSecond.first().id)
    }

    @Test
    fun searchFilterAndSort_comprehensive() = runBlocking {
        val f1 = tempFolder.newFile("Action Movie 1080p.mp4")
        val f2 = tempFolder.newFile("Family Holiday Photo.jpg")
        val f3 = tempFolder.newFile("Action Game Soundtrack.mp3")

        libraryRepository.addMedia(
            LocalMediaItem("1", "Action Movie 1080p.mp4", f1.absolutePath, 3000L, 1000L, MediaType.VIDEO)
        )
        libraryRepository.addMedia(
            LocalMediaItem("2", "Family Holiday Photo.jpg", f2.absolutePath, 1000L, 2000L, MediaType.PHOTO)
        )
        libraryRepository.addMedia(
            LocalMediaItem("3", "Action Game Soundtrack.mp3", f3.absolutePath, 2000L, 3000L, MediaType.OTHER)
        )

        val viewModel = LibraryViewModel(libraryRepository)

        // 1. Search
        viewModel.onSearchQueryChange("Action")
        val searchResults = viewModel.uiState.value.filteredAndSortedItems
        assertEquals(2, searchResults.size)
        assertTrue(searchResults.any { it.name.contains("Movie") })
        assertTrue(searchResults.any { it.name.contains("Soundtrack") })

        // 2. Filter by category
        viewModel.setFilter(LibraryFilter.VIDEOS)
        assertEquals(1, viewModel.uiState.value.filteredAndSortedItems.size)
        assertEquals("Action Movie 1080p.mp4", viewModel.uiState.value.filteredAndSortedItems[0].name)

        // 3. Clear filters
        viewModel.clearSearchAndFilters()
        assertEquals(3, viewModel.uiState.value.filteredAndSortedItems.size)

        // 4. Sort by size desc
        viewModel.setSortOrder(LibrarySortOrder.SIZE_DESC)
        assertEquals(
            listOf("Action Movie 1080p.mp4", "Action Game Soundtrack.mp3", "Family Holiday Photo.jpg"),
            viewModel.uiState.value.filteredAndSortedItems.map { it.name }
        )
    }

    @Test
    fun missingOrInaccessibleFiles_gracefullyHandled() = runBlocking {
        val existing = tempFolder.newFile("Exists.mp4").apply { writeBytes(ByteArray(512)) }
        val missingPath = File(tempFolder.root, "DoesNotExist.mp4").absolutePath

        libraryRepository.addMedia(
            LocalMediaItem("id_exists", "Exists.mp4", existing.absolutePath, 512L, 1000L, MediaType.VIDEO)
        )
        libraryRepository.addMedia(
            LocalMediaItem("id_missing", "DoesNotExist.mp4", missingPath, 2048L, 2000L, MediaType.VIDEO)
        )

        val items = libraryRepository.observeAllMedia().first()
        assertEquals(2, items.size)

        val existsItem = items.first { it.id == "id_exists" }
        assertTrue(existsItem.existsOnDisk)

        val missingItem = items.first { it.id == "id_missing" }
        assertFalse(missingItem.existsOnDisk)

        // Verify ViewModel handles missing file storage calculation
        val viewModel = LibraryViewModel(libraryRepository)
        val state = viewModel.uiState.value
        assertEquals(1, state.missingCount)
        assertEquals(512L, state.totalOccupiedBytes) // Missing file's 2048L is NOT counted!
    }

    @Test
    fun renameBehavior_onDiskAndInDatabase() = runBlocking {
        val file = tempFolder.newFile("OldName.mp4").apply {
            writeBytes(ByteArray(128))
        }

        libraryRepository.addMedia(
            LocalMediaItem("id_rename", "OldName.mp4", file.absolutePath, 128L, 1000L, MediaType.VIDEO)
        )

        // Rename without specifying extension (should auto-append extension)
        val renameSuccess = libraryRepository.renameMedia("id_rename", "BrandNewName")
        assertTrue(renameSuccess)

        val updated = libraryRepository.getMediaById("id_rename")
        assertNotNull(updated)
        assertEquals("BrandNewName.mp4", updated?.name)

        val oldFile = File(file.parentFile, "OldName.mp4")
        val newFile = File(file.parentFile, "BrandNewName.mp4")
        assertFalse(oldFile.exists())
        assertTrue(newFile.exists())

        // Collision protection: cannot rename to an existing file name
        val conflictingFile = tempFolder.newFile("ExistingTarget.mp4")
        val failRename = libraryRepository.renameMedia("id_rename", "ExistingTarget")
        assertFalse(failRename)
    }

    @Test
    fun recordOnlyRemoval_versus_physicalFileDeletion() = runBlocking {
        val file1 = tempFolder.newFile("RecordOnly.mp4").apply { writeBytes(ByteArray(64)) }
        val file2 = tempFolder.newFile("PhysicalDelete.mp4").apply { writeBytes(ByteArray(64)) }

        libraryRepository.addMedia(
            LocalMediaItem("id_1", "RecordOnly.mp4", file1.absolutePath, 64L, 1000L, MediaType.VIDEO)
        )
        libraryRepository.addMedia(
            LocalMediaItem("id_2", "PhysicalDelete.mp4", file2.absolutePath, 64L, 2000L, MediaType.VIDEO)
        )

        // 1. Record-only removal
        val recordDeleted = libraryRepository.deleteMedia("id_1")
        assertTrue(recordDeleted)
        assertTrue(file1.exists()) // File still safely on disk!
        val remainingAfterRecord = libraryRepository.observeAllMedia().first()
        assertEquals(1, remainingAfterRecord.size)

        // 2. Physical deletion
        val fileDeleted = libraryRepository.deleteMediaFileAndRecord("id_2")
        assertTrue(fileDeleted)
        assertFalse(file2.exists()) // File removed from disk!
        val remainingAfterPhysical = libraryRepository.observeAllMedia().first()
        assertTrue(remainingAfterPhysical.isEmpty())
    }

    @Test
    fun mimeTypeResolution_forVariousExtensions() {
        assertEquals("video/*", LocalFileActions.getMimeType(MediaType.OTHER, File("test.mp4")))
        assertEquals("video/*", LocalFileActions.getMimeType(MediaType.OTHER, File("movie.mkv")))
        assertEquals("image/*", LocalFileActions.getMimeType(MediaType.OTHER, File("pic.jpg")))
        assertEquals("image/*", LocalFileActions.getMimeType(MediaType.OTHER, File("photo.png")))
        assertEquals("audio/*", LocalFileActions.getMimeType(MediaType.OTHER, File("song.mp3")))
        assertEquals("application/pdf", LocalFileActions.getMimeType(MediaType.OTHER, File("document.pdf")))
        assertEquals("application/zip", LocalFileActions.getMimeType(MediaType.OTHER, File("archive.zip")))
        assertEquals("*/*", LocalFileActions.getMimeType(MediaType.OTHER, File("unknown.xyz")))
    }

    @Test
    fun persistenceAcrossRecreation() = runBlocking {
        val sharedStore = mutableMapOf<String, LibraryMediaEntity>()

        val repo1 = LibraryRepositoryImpl(
            libraryMediaDao = FakeLibraryMediaDao(sharedStore = sharedStore),
            ioDispatcher = testDispatcher
        )

        repo1.addMedia(
            LocalMediaItem(
                id = "persist_item",
                name = "Persistent.mp4",
                path = "/storage/Persistent.mp4",
                size = 5000L,
                dateAdded = 1000L,
                mediaType = MediaType.VIDEO
            )
        )
        repo1.updatePlaybackPosition("persist_item", 45000L)

        // Recreate repository with second instance pointing to same backing store
        val repo2 = LibraryRepositoryImpl(
            libraryMediaDao = FakeLibraryMediaDao(sharedStore = sharedStore),
            ioDispatcher = testDispatcher
        )

        val item = repo2.getMediaById("persist_item")
        assertNotNull(item)
        assertEquals("Persistent.mp4", item?.name)
        assertEquals(45000L, item?.lastPositionMs)
    }
}
