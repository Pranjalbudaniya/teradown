package com.tera.down

import com.tera.down.core.model.LocalMediaItem
import com.tera.down.core.model.MediaType
import com.tera.down.feature.player.PlaybackPositionTracker
import com.tera.down.feature.queue.LibraryRepository
import com.tera.down.feature.queue.LibraryRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionTrackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeDao: FakeLibraryMediaDao
    private lateinit var repository: LibraryRepository
    private lateinit var tracker: PlaybackPositionTracker

    @Before
    fun setup() {
        fakeDao = FakeLibraryMediaDao()
        repository = LibraryRepositoryImpl(fakeDao, testDispatcher)
        tracker = PlaybackPositionTracker(
            libraryRepository = repository,
            scope = testScope,
            ioDispatcher = testDispatcher,
            minPersistenceIntervalMs = 5000L
        )
    }

    @Test
    fun getInitialPosition_whenNoPriorPlayback_returnsZero() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("sample.mp4")
        testFile.writeText("data")

        val position = tracker.getInitialPosition(
            libraryId = "lib_1",
            filePath = testFile.absolutePath,
            knownDurationMs = 60_000L
        )

        assertEquals(0L, position)
    }

    @Test
    fun persistAndRestorePosition_succeeds() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("video.mp4")
        testFile.writeText("data")

        repository.addMedia(
            LocalMediaItem(
                id = "item_1",
                name = "video.mp4",
                path = testFile.absolutePath,
                size = 1000L,
                dateAdded = System.currentTimeMillis(),
                mediaType = MediaType.VIDEO
            )
        )

        // Update position and flush
        tracker.flush(
            libraryId = "item_1",
            positionMs = 25_000L,
            durationMs = 60_000L
        )

        val restored = tracker.getInitialPosition(
            libraryId = "item_1",
            filePath = testFile.absolutePath,
            knownDurationMs = 60_000L
        )

        assertEquals(25_000L, restored)
    }

    @Test
    fun restorePosition_whenNearEnd_resetsToZero() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("completed_video.mp4")
        testFile.writeText("data")

        repository.addMedia(
            LocalMediaItem(
                id = "item_near_end",
                name = "completed_video.mp4",
                path = testFile.absolutePath,
                size = 1000L,
                dateAdded = System.currentTimeMillis(),
                mediaType = MediaType.VIDEO,
                lastPositionMs = 59_000L, // 59s out of 60s (within 3s / >95%)
                durationMs = 60_000L
            )
        )

        val position = tracker.getInitialPosition(
            libraryId = "item_near_end",
            filePath = testFile.absolutePath,
            knownDurationMs = 60_000L
        )

        assertEquals(0L, position)
    }

    @Test
    fun restorePosition_whenFileDeleted_returnsZero() = runTest(testDispatcher) {
        val deletedPath = "/non/existent/deleted_file.mp4"

        repository.addMedia(
            LocalMediaItem(
                id = "item_deleted",
                name = "deleted_file.mp4",
                path = deletedPath,
                size = 1000L,
                dateAdded = System.currentTimeMillis(),
                mediaType = MediaType.VIDEO,
                lastPositionMs = 30_000L
            )
        )

        val position = tracker.getInitialPosition(
            libraryId = "item_deleted",
            filePath = deletedPath,
            knownDurationMs = 60_000L
        )

        assertEquals(0L, position)
    }

    @Test
    fun resetPosition_setsSavedPositionToZero() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("to_reset.mp4")
        testFile.writeText("data")

        repository.addMedia(
            LocalMediaItem(
                id = "item_reset",
                name = "to_reset.mp4",
                path = testFile.absolutePath,
                size = 1000L,
                dateAdded = System.currentTimeMillis(),
                mediaType = MediaType.VIDEO,
                lastPositionMs = 45_000L
            )
        )

        tracker.resetPosition("item_reset")

        val restored = tracker.getInitialPosition(
            libraryId = "item_reset",
            filePath = testFile.absolutePath,
            knownDurationMs = 60_000L
        )

        assertEquals(0L, restored)
    }
}
