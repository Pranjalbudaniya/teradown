package com.pranjalbudaniya.teradown.ui.downloads

import androidx.lifecycle.ViewModel
import com.pranjalbudaniya.teradown.core.model.DownloadStatus
import com.pranjalbudaniya.teradown.core.model.DownloadTaskItem
import com.pranjalbudaniya.teradown.core.model.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DownloadsFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    FAILED
}

data class DownloadsUiState(
    val tasks: List<DownloadTaskItem> = emptyList(),
    val filter: DownloadsFilter = DownloadsFilter.ALL
) {
    val filteredTasks: List<DownloadTaskItem>
        get() = when (filter) {
            DownloadsFilter.ALL -> tasks
            DownloadsFilter.ACTIVE -> tasks.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
            DownloadsFilter.COMPLETED -> tasks.filter { it.status == DownloadStatus.COMPLETED }
            DownloadsFilter.FAILED -> tasks.filter { it.status == DownloadStatus.FAILED }
        }

    val activeCount: Int
        get() = tasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }

    val completedCount: Int
        get() = tasks.count { it.status == DownloadStatus.COMPLETED }

    val failedCount: Int
        get() = tasks.count { it.status == DownloadStatus.FAILED }

    val totalSpeedBytes: Long
        get() = tasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSec }
}

class DownloadsViewModel : ViewModel() {

    // Initial representative UI placeholders to showcase all states
    private val initialTasks = listOf(
        DownloadTaskItem(
            id = "task_1",
            fsId = "1001",
            name = "Vacation_Drone_4K.mp4",
            size = 1450000000L,
            downloadedBytes = 620000000L,
            speedBytesPerSec = 4500000L,
            status = DownloadStatus.DOWNLOADING,
            mediaType = MediaType.VIDEO
        ),
        DownloadTaskItem(
            id = "task_2",
            fsId = "1002",
            name = "Sunset_Beach_Clip.mov",
            size = 850000000L,
            downloadedBytes = 210000000L,
            speedBytesPerSec = 0L,
            status = DownloadStatus.PAUSED,
            mediaType = MediaType.VIDEO
        ),
        DownloadTaskItem(
            id = "task_3",
            fsId = "1003",
            name = "Family_Portrait_RAW.jpg",
            size = 18500000L,
            downloadedBytes = 18500000L,
            speedBytesPerSec = 0L,
            status = DownloadStatus.COMPLETED,
            mediaType = MediaType.PHOTO
        ),
        DownloadTaskItem(
            id = "task_4",
            fsId = "1004",
            name = "Mountain_Pass_Timelapse.mp4",
            size = 980000000L,
            downloadedBytes = 120000000L,
            speedBytesPerSec = 0L,
            status = DownloadStatus.FAILED,
            mediaType = MediaType.VIDEO,
            errorMessage = "HTTP 403 Forbidden: Direct link expired. (Will auto-refresh in queue engine)"
        )
    )

    private val _uiState = MutableStateFlow(DownloadsUiState(tasks = initialTasks))
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    fun setFilter(filter: DownloadsFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun pauseTask(id: String) {
        _uiState.update { state ->
            val updated = state.tasks.map { task ->
                if (task.id == id && task.status == DownloadStatus.DOWNLOADING) {
                    task.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                } else task
            }
            state.copy(tasks = updated)
        }
    }

    fun resumeTask(id: String) {
        _uiState.update { state ->
            val updated = state.tasks.map { task ->
                if (task.id == id && (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED)) {
                    task.copy(status = DownloadStatus.DOWNLOADING, speedBytesPerSec = 3800000L, errorMessage = null)
                } else task
            }
            state.copy(tasks = updated)
        }
    }

    fun cancelTask(id: String) {
        _uiState.update { state ->
            val updated = state.tasks.filterNot { it.id == id }
            state.copy(tasks = updated)
        }
    }

    fun retryFailedTasks() {
        _uiState.update { state ->
            val updated = state.tasks.map { task ->
                if (task.status == DownloadStatus.FAILED) {
                    task.copy(status = DownloadStatus.QUEUED, errorMessage = null)
                } else task
            }
            state.copy(tasks = updated)
        }
    }

    fun clearCompleted() {
        _uiState.update { state ->
            val updated = state.tasks.filterNot { it.status == DownloadStatus.COMPLETED }
            state.copy(tasks = updated)
        }
    }
}
