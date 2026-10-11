package com.pranjalbudaniya.teradown.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pranjalbudaniya.teradown.core.model.MediaType
import com.pranjalbudaniya.teradown.core.model.ShareFileItem
import com.pranjalbudaniya.teradown.core.model.ShareInfo
import com.pranjalbudaniya.teradown.core.model.ShareState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BrowseFilter {
    ALL,
    VIDEOS,
    PHOTOS
}

sealed interface BrowseContentState {
    object Empty : BrowseContentState
    data class Loading(val message: String = "Analyzing share metadata...") : BrowseContentState
    data class Success(
        val shareInfo: ShareInfo,
        val files: List<ShareFileItem>,
        val filter: BrowseFilter = BrowseFilter.ALL
    ) : BrowseContentState {
        val filteredFiles: List<ShareFileItem>
            get() = when (filter) {
                BrowseFilter.ALL -> files
                BrowseFilter.VIDEOS -> files.filter { it.mediaType == MediaType.VIDEO }
                BrowseFilter.PHOTOS -> files.filter { it.mediaType == MediaType.PHOTO }
            }

        val selectedCount: Int
            get() = files.count { it.isSelected }

        val totalSelectedBytes: Long
            get() = files.filter { it.isSelected }.sumOf { it.size }

        val isAllSelected: Boolean
            get() = files.isNotEmpty() && files.all { it.isSelected }
    }
    data class Error(
        val title: String,
        val message: String,
        val canRetry: Boolean = true
    ) : BrowseContentState
}

data class BrowseShareUiState(
    val currentUrl: String = "",
    val currentPwd: String? = null,
    val contentState: BrowseContentState = BrowseContentState.Loading(),
    val isDemoData: Boolean = false,
    val actionMessage: String? = null
)

class BrowseShareViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseShareUiState())
    val uiState: StateFlow<BrowseShareUiState> = _uiState.asStateFlow()

    fun loadShare(url: String, pwd: String? = null, forceDemo: Boolean = false) {
        _uiState.update {
            it.copy(
                currentUrl = url,
                currentPwd = pwd,
                contentState = BrowseContentState.Loading("Resolving share parameters...")
            )
        }

        viewModelScope.launch {
            // Simulate inspection phase
            delay(600)

            // As specified in engineering rules, we do not call real undocumented endpoints
            // until the resolver phase. If forceDemo or testing, we display clearly-marked UI demonstration data.
            val surlShort = if (url.contains("/s/")) {
                url.substringAfter("/s/").substringBefore("?").substringBefore("/")
            } else {
                url.takeLast(12)
            }

            val mockShareInfo = ShareInfo(
                surl = surlShort,
                title = "Shared Media Collection ($surlShort)",
                shareId = "mock_share_109283",
                uk = "user_491823",
                fileCount = 5,
                totalBytes = 2840000000L,
                state = ShareState.OK,
                requiresPassword = !pwd.isNullOrBlank(),
                password = pwd
            )

            val mockFiles = listOf(
                ShareFileItem(
                    fsId = "1001",
                    name = "Vacation_Drone_4K.mp4",
                    size = 1450000000L,
                    relPath = "Holiday/Vacation_Drone_4K.mp4",
                    mediaType = MediaType.VIDEO,
                    category = 1,
                    isSelected = true
                ),
                ShareFileItem(
                    fsId = "1002",
                    name = "Sunset_Beach_Clip.mov",
                    size = 850000000L,
                    relPath = "Holiday/Sunset_Beach_Clip.mov",
                    mediaType = MediaType.VIDEO,
                    category = 1,
                    isSelected = true
                ),
                ShareFileItem(
                    fsId = "1003",
                    name = "Family_Portrait_RAW.jpg",
                    size = 18500000L,
                    relPath = "Holiday/Photos/Family_Portrait_RAW.jpg",
                    mediaType = MediaType.PHOTO,
                    category = 3,
                    isSelected = false
                ),
                ShareFileItem(
                    fsId = "1004",
                    name = "Landscape_Panorama.png",
                    size = 24500000L,
                    relPath = "Holiday/Photos/Landscape_Panorama.png",
                    mediaType = MediaType.PHOTO,
                    category = 3,
                    isSelected = false
                ),
                ShareFileItem(
                    fsId = "1005",
                    name = "Notes_Archive.zip",
                    size = 497000000L,
                    relPath = "Holiday/Notes_Archive.zip",
                    mediaType = MediaType.OTHER,
                    category = 6,
                    isSelected = false
                )
            )

            _uiState.update {
                it.copy(
                    contentState = BrowseContentState.Success(
                        shareInfo = mockShareInfo,
                        files = mockFiles
                    ),
                    isDemoData = true
                )
            }
        }
    }

    fun toggleItemSelection(fsId: String) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val updatedFiles = current.files.map {
            if (it.fsId == fsId) it.copy(isSelected = !it.isSelected) else it
        }
        _uiState.update {
            it.copy(contentState = current.copy(files = updatedFiles))
        }
    }

    fun setAllSelected(selected: Boolean) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val updatedFiles = current.files.map {
            // Only select supported types
            if (it.mediaType != MediaType.OTHER) {
                it.copy(isSelected = selected)
            } else it
        }
        _uiState.update {
            it.copy(contentState = current.copy(files = updatedFiles))
        }
    }

    fun setFilter(filter: BrowseFilter) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        _uiState.update {
            it.copy(contentState = current.copy(filter = filter))
        }
    }

    fun downloadSelected(onNavigateToDownloads: () -> Unit) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val count = current.selectedCount
        if (count == 0) {
            _uiState.update { it.copy(actionMessage = "Please select at least one media file to download.") }
            return
        }

        // Navigate to downloads screen where queue items will be scheduled
        onNavigateToDownloads()
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }
}
