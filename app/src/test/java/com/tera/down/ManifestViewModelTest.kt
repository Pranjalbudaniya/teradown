package com.tera.down

import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.MediaType
import com.tera.down.feature.manifest.ManifestExportFormat
import com.tera.down.feature.manifest.ManifestFilter
import com.tera.down.feature.manifest.ManifestRepositoryImpl
import com.tera.down.feature.manifest.ManifestViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ManifestViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeDao: FakeDownloadManifestDao
    private lateinit var repository: ManifestRepositoryImpl
    private lateinit var viewModel: ManifestViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeDownloadManifestDao()
        repository = ManifestRepositoryImpl(
            downloadManifestDao = fakeDao,
            ioDispatcher = testDispatcher
        )
        viewModel = ManifestViewModel(manifestRepository = repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_reflectsRepositoryRecordsAndCounts() = runTest(testDispatcher) {
        repository.recordEnqueued("t-1", "https://example.com/1", "file1.mp4", 1000L, MediaType.VIDEO)
        repository.recordEnqueued("t-2", "https://example.com/2", "file2.mp4", 2000L, MediaType.VIDEO)
        repository.recordCompleted("t-1", "/path/1.mp4", 1000L, false)

        val state = viewModel.uiState.value
        assertEquals(2, state.records.size)
        assertEquals(1, state.completedCount)
        assertEquals(1, state.activeCount)
        assertEquals(0, state.failedCount)
    }

    @Test
    fun filter_filtersRecordsCorrectly() = runTest(testDispatcher) {
        repository.recordEnqueued("t-1", "https://example.com/1", "file1.mp4", 1000L, MediaType.VIDEO)
        repository.recordEnqueued("t-2", "https://example.com/2", "file2.mp4", 2000L, MediaType.VIDEO)
        repository.recordCompleted("t-1", "/path/1.mp4", 1000L, false)
        repository.recordFailed("t-2", com.tera.down.core.manifest.ManifestErrorCategory.NETWORK_ERROR, "Timeout")

        viewModel.setFilter(ManifestFilter.COMPLETED)
        assertEquals(1, viewModel.uiState.value.filteredRecords.size)
        assertEquals("file1.mp4", viewModel.uiState.value.filteredRecords[0].fileName)

        viewModel.setFilter(ManifestFilter.FAILED)
        assertEquals(1, viewModel.uiState.value.filteredRecords.size)
        assertEquals("file2.mp4", viewModel.uiState.value.filteredRecords[0].fileName)

        viewModel.setFilter(ManifestFilter.ALL)
        assertEquals(2, viewModel.uiState.value.filteredRecords.size)
    }

    @Test
    fun clearManifestHistory_clearsRecordsAndUpdatesMessage() = runTest(testDispatcher) {
        repository.recordEnqueued("t-1", "https://example.com/1", "file1.mp4", 1000L, MediaType.VIDEO)
        viewModel.showClearConfirm(true)
        assertTrue(viewModel.uiState.value.showClearConfirmDialog)

        viewModel.clearManifestHistory()
        assertFalse(viewModel.uiState.value.showClearConfirmDialog)
        assertEquals(0, viewModel.uiState.value.records.size)
        assertNotNull(viewModel.uiState.value.exportMessage)
    }

    @Test
    fun exportToStream_exportsSuccessfullyAndUpdatesMessage() = runTest(testDispatcher) {
        repository.recordEnqueued("t-1", "https://example.com/1", "file1.mp4", 1000L, MediaType.VIDEO)
        val stream = ByteArrayOutputStream()

        viewModel.exportToStream(ManifestExportFormat.JSON, stream)

        val state = viewModel.uiState.value
        assertFalse(state.isExporting)
        assertNotNull(state.exportMessage)
        assertTrue(state.exportMessage!!.contains("Successfully exported 1 records"))
        assertTrue(stream.size() > 0)
    }

    @Test
    fun dismissMessage_clearsMessage() {
        viewModel.reportExportCancelled()
        assertNotNull(viewModel.uiState.value.exportMessage)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.exportMessage)
    }
}
