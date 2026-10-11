package com.tera.down.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tera.down.TeradownApplication
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.model.DownloadTaskItem
import com.tera.down.core.model.MediaType
import com.tera.down.feature.downloader.DownloadQueueCoordinator
import com.tera.down.feature.queue.DownloadQueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DownloadsFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    FAILED
}

data class DownloadsUiState(
    val tasks: List<DownloadTaskItem> = emptyList(),
    val filter: DownloadsFilter = DownloadsFilter.ALL,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val filteredTasks: List<DownloadTaskItem>
        get() = when (filter) {
            DownloadsFilter.ALL -> tasks
            DownloadsFilter.ACTIVE -> tasks.filter {
                it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PAUSED
            }
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

/**
 * ViewModel managing download queue visualization and user actions.
 * Backed by [DownloadQueueRepository] for persistent local storage in Room.
 *
 * NOTE: Queue controls (pause, resume, cancel, retry) manage queue scheduling state only.
 * Transfer execution over network is implemented in subsequent phases.
 */
class DownloadsViewModel(
    private val queueRepository: DownloadQueueRepository? = null,
    private val queueCoordinator: DownloadQueueCoordinator? = null
) : ViewModel() {

    // Fallback items used exclusively in standalone/preview modes without repository
    private val previewTasks = listOf(
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

    private val _uiState = MutableStateFlow(
        DownloadsUiState(
            tasks = if (queueRepository == null) previewTasks else emptyList()
        )
    )
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.observeAllTasks().collect { taskList ->
                    _uiState.update { it.copy(tasks = taskList) }
                }
            }
        }
    }

    fun setFilter(filter: DownloadsFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun pauseTask(id: String) {
        if (queueCoordinator != null) {
            queueCoordinator.pauseDownload(id)
        } else if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.pauseTask(id)
            }
        } else {
            _uiState.update { state ->
                val updated = state.tasks.map {
                    if (it.id == id && it.status == DownloadStatus.DOWNLOADING) {
                        it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    } else it
                }
                state.copy(tasks = updated)
            }
        }
    }

    fun resumeTask(id: String) {
        if (queueCoordinator != null) {
            queueCoordinator.startDownload(id)
        } else if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.resumeTask(id)
            }
        } else {
            _uiState.update { state ->
                val updated = state.tasks.map {
                    if (it.id == id && (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED)) {
                        it.copy(status = DownloadStatus.DOWNLOADING, speedBytesPerSec = 3800000L, errorMessage = null)
                    } else it
                }
                state.copy(tasks = updated)
            }
        }
    }

    fun cancelTask(id: String) {
        if (queueCoordinator != null) {
            queueCoordinator.cancelDownload(id)
        } else if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.cancelTask(id)
            }
        } else {
            _uiState.update { state ->
                state.copy(tasks = state.tasks.filterNot { it.id == id })
            }
        }
    }

    fun retryFailedTasks() {
        if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.retryAllFailedTasks()
            }
        } else {
            _uiState.update { state ->
                val updated = state.tasks.map {
                    if (it.status == DownloadStatus.FAILED) {
                        it.copy(status = DownloadStatus.QUEUED, errorMessage = null)
                    } else it
                }
                state.copy(tasks = updated)
            }
        }
    }

    fun retryTask(id: String) {
        if (queueCoordinator != null) {
            queueCoordinator.retryDownload(id)
        } else if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.retryTask(id)
            }
        } else {
            _uiState.update { state ->
                val updated = state.tasks.map {
                    if (it.id == id) it.copy(status = DownloadStatus.QUEUED, errorMessage = null) else it
                }
                state.copy(tasks = updated)
            }
        }
    }

    fun clearCompleted() {
        if (queueRepository != null) {
            viewModelScope.launch {
                queueRepository.clearCompletedTasks()
            }
        } else {
            _uiState.update { state ->
                state.copy(tasks = state.tasks.filterNot { it.status == DownloadStatus.COMPLETED })
            }
        }
    }

    companion object {
        fun provideFactory(
            queueRepository: DownloadQueueRepository,
            queueCoordinator: DownloadQueueCoordinator? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DownloadsViewModel(queueRepository, queueCoordinator) as T
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? TeradownApplication
                DownloadsViewModel(
                    queueRepository = app?.container?.downloadQueueRepository,
                    queueCoordinator = app?.container?.downloadQueueCoordinator
                )
            }
        }
    }
}

