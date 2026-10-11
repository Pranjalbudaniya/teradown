package com.tera.down

import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.ui.browse.BrowseContentState
import com.tera.down.ui.browse.BrowseShareViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseShareViewModelRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeTaskDao: FakeDownloadTaskDao
    private lateinit var fakeLibDao: FakeLibraryMediaDao
    private lateinit var queueRepository: DownloadQueueRepository
    private lateinit var viewModel: BrowseShareViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeTaskDao = FakeDownloadTaskDao()
        fakeLibDao = FakeLibraryMediaDao()

        queueRepository = DownloadQueueRepositoryImpl(
            downloadTaskDao = fakeTaskDao,
            libraryMediaDao = fakeLibDao,
            ioDispatcher = testDispatcher
        )
        viewModel = BrowseShareViewModel(queueRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun downloadSelected_enqueuesTasksIntoRepository_andNavigates() = runTest(testDispatcher) {
        // Load share demo data
        viewModel.loadShare("https://terabox.com/s/1testurl")

        // Advance past delay(600) in loadShare
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val success = state.contentState as? BrowseContentState.Success
        assertTrue("Expected content to be Success", success != null)

        var navigated = false
        viewModel.downloadSelected {
            navigated = true
        }

        testScheduler.advanceUntilIdle()

        assertTrue("Navigation callback should be triggered", navigated)

        // Verify repository now has the queued tasks
        val tasks = queueRepository.observeAllTasks().first()
        assertTrue("Queue should have items enqueued from Browse", tasks.isNotEmpty())
        assertTrue(tasks.any { it.name == "Vacation_Drone_4K.mp4" })
        assertTrue(tasks.any { it.name == "Sunset_Beach_Clip.mov" })
    }
}
