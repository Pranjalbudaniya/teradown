package com.tera.down

import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var repository: DownloadQueueRepository
    private lateinit var viewModel: DownloadsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()

        repository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibDao,
            ioDispatcher = testDispatcher
        )
        viewModel = DownloadsViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialEmptyQueueState() {
        val state = viewModel.uiState.value
        assertTrue(state.tasks.isEmpty())
        assertEquals(0, state.activeCount)
        assertEquals(0, state.completedCount)
        assertEquals(0, state.failedCount)
    }

    @Test
    fun reactiveTaskUpdates_whenTaskEnqueuedAndModified() = runBlocking {
        val file = ShareFileItem(
            fsId = "101",
            name = "TestVideo.mp4",
            size = 2000000L,
            relPath = "TestVideo.mp4",
            mediaType = MediaType.VIDEO,
            category = 1
        )
        val taskIds = repository.enqueueShareFiles("surl_test", "https://terabox.com/s/surl_test", listOf(file))
        val taskId = taskIds.first()

        // Reactive reflection in ViewModel
        val state = viewModel.uiState.value
        assertEquals(1, state.tasks.size)
        assertEquals("TestVideo.mp4", state.tasks[0].name)
        assertEquals(DownloadStatus.QUEUED, state.tasks[0].status)
        assertEquals(1, state.activeCount)

        // Move to DOWNLOADING
        repository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)
        assertEquals(DownloadStatus.DOWNLOADING, viewModel.uiState.value.tasks[0].status)

        // Pause via ViewModel
        viewModel.pauseTask(taskId)
        assertEquals(DownloadStatus.PAUSED, viewModel.uiState.value.tasks[0].status)

        // Resume via ViewModel
        viewModel.resumeTask(taskId)
        assertEquals(DownloadStatus.QUEUED, viewModel.uiState.value.tasks[0].status)

        // Cancel via ViewModel
        viewModel.cancelTask(taskId)
        assertTrue(viewModel.uiState.value.tasks.isEmpty())
    }

    @Test
    fun retryFailedAndClearCompleted_viaViewModel() = runBlocking {
        val file1 = ShareFileItem(
            fsId = "201",
            name = "FailedVideo.mp4",
            size = 1000L,
            relPath = "FailedVideo.mp4",
            mediaType = MediaType.VIDEO,
            category = 1
        )
        val file2 = ShareFileItem(
            fsId = "202",
            name = "CompletedVideo.mp4",
            size = 2000L,
            relPath = "CompletedVideo.mp4",
            mediaType = MediaType.VIDEO,
            category = 1
        )
        val ids = repository.enqueueShareFiles("surl_multi", "https://terabox.com/s/surl_multi", listOf(file1, file2))
        val failId = ids[0]
        val completeId = ids[1]

        repository.updateTaskStatus(failId, DownloadStatus.DOWNLOADING)
        repository.updateTaskStatus(failId, DownloadStatus.FAILED, "Network failure")

        repository.updateTaskStatus(completeId, DownloadStatus.DOWNLOADING)
        repository.completeTask(completeId, "/path/CompletedVideo.mp4")

        assertEquals(1, viewModel.uiState.value.failedCount)
        assertEquals(1, viewModel.uiState.value.completedCount)

        // Retry failed
        viewModel.retryFailedTasks()
        assertEquals(0, viewModel.uiState.value.failedCount)
        assertEquals(1, viewModel.uiState.value.activeCount) // Now queued

        // Clear completed
        viewModel.clearCompleted()
        assertEquals(0, viewModel.uiState.value.completedCount)
        assertEquals(1, viewModel.uiState.value.tasks.size) // Only queued remains
    }
}
