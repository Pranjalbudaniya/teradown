package com.pranjalbudaniya.teradown.ui.library

import androidx.lifecycle.ViewModel
import com.pranjalbudaniya.teradown.core.model.LocalMediaItem
import com.pranjalbudaniya.teradown.core.model.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LibraryFilter {
    ALL,
    VIDEOS,
    PHOTOS
}

data class LibraryUiState(
    val items: List<LocalMediaItem> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val isLoading: Boolean = false
) {
    val filteredItems: List<LocalMediaItem>
        get() = when (filter) {
            LibraryFilter.ALL -> items
            LibraryFilter.VIDEOS -> items.filter { it.mediaType == MediaType.VIDEO }
            LibraryFilter.PHOTOS -> items.filter { it.mediaType == MediaType.PHOTO }
        }

    val videoCount: Int
        get() = items.count { it.mediaType == MediaType.VIDEO }

    val photoCount: Int
        get() = items.count { it.mediaType == MediaType.PHOTO }
}

class LibraryViewModel : ViewModel() {

    // Strictly empty by default: no fake files presented as real user files
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun setFilter(filter: LibraryFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun refresh() {
        // Will scan device download folder in storage phase
        _uiState.update { it.copy(isLoading = false) }
    }
}
