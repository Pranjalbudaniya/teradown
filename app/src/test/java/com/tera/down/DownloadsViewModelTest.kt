package com.tera.down

import com.tera.down.core.model.DownloadStatus
import com.tera.down.ui.downloads.DownloadsFilter
import com.tera.down.ui.downloads.DownloadsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadsViewModelTest {

    private lateinit var viewModel: DownloadsViewModel

    @Before
    fun setup() {
        viewModel = DownloadsViewModel()
    }

    @Test
    fun initialTasks_arePopulated() {
        val state = viewModel.uiState.value
        assertTrue(state.tasks.isNotEmpty())
        assertTrue(state.activeCount > 0)
    }

    @Test
    fun pauseTask_updatesStatus() {
        val activeTask = viewModel.uiState.value.tasks.first { it.status == DownloadStatus.DOWNLOADING }
        viewModel.pauseTask(activeTask.id)

        val updated = viewModel.uiState.value.tasks.first { it.id == activeTask.id }
        assertEquals(DownloadStatus.PAUSED, updated.status)
        assertEquals(0L, updated.speedBytesPerSec)
    }

    @Test
    fun resumeTask_updatesStatus() {
        val pausedTask = viewModel.uiState.value.tasks.first { it.status == DownloadStatus.PAUSED }
        viewModel.resumeTask(pausedTask.id)

        val updated = viewModel.uiState.value.tasks.first { it.id == pausedTask.id }
        assertEquals(DownloadStatus.DOWNLOADING, updated.status)
    }

    @Test
    fun cancelTask_removesFromList() {
        val taskToCancel = viewModel.uiState.value.tasks.first()
        viewModel.cancelTask(taskToCancel.id)

        assertTrue(viewModel.uiState.value.tasks.none { it.id == taskToCancel.id })
    }

    @Test
    fun filter_filtersCorrectly() {
        viewModel.setFilter(DownloadsFilter.COMPLETED)
        val completed = viewModel.uiState.value.filteredTasks
        assertTrue(completed.all { it.status == DownloadStatus.COMPLETED })
    }
}
