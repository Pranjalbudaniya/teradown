package com.tera.down.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tera.down.TeradownApplication
import com.tera.down.core.model.LocalMediaItem
import com.tera.down.core.model.MediaType
import com.tera.down.feature.queue.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryFilter {
    ALL,
    VIDEOS,
    PHOTOS,
    OTHER
}

enum class LibrarySortOrder {
    DATE_DESC,
    DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC
}

data class LibraryUiState(
    val items: List<LocalMediaItem> = emptyList(),
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.DATE_DESC,
    val isLoading: Boolean = false,
    val actionMessage: String? = null,
    val itemToRename: LocalMediaItem? = null,
    val itemToDelete: LocalMediaItem? = null
) {
    val filteredAndSortedItems: List<LocalMediaItem>
        get() {
            // 1. Filter by category
            val typeFiltered = when (filter) {
                LibraryFilter.ALL -> items
                LibraryFilter.VIDEOS -> items.filter { it.mediaType == MediaType.VIDEO }
                LibraryFilter.PHOTOS -> items.filter { it.mediaType == MediaType.PHOTO }
                LibraryFilter.OTHER -> items.filter { it.mediaType != MediaType.VIDEO && it.mediaType != MediaType.PHOTO }
            }

            // 2. Filter by search query
            val searchFiltered = if (searchQuery.isBlank()) {
                typeFiltered
            } else {
                val query = searchQuery.trim().lowercase()
                typeFiltered.filter { it.name.lowercase().contains(query) }
            }

            // 3. Sort
            return when (sortOrder) {
                LibrarySortOrder.DATE_DESC -> searchFiltered.sortedByDescending { it.dateAdded }
                LibrarySortOrder.DATE_ASC -> searchFiltered.sortedBy { it.dateAdded }
                LibrarySortOrder.NAME_ASC -> searchFiltered.sortedBy { it.name.lowercase() }
                LibrarySortOrder.NAME_DESC -> searchFiltered.sortedByDescending { it.name.lowercase() }
                LibrarySortOrder.SIZE_DESC -> searchFiltered.sortedWith(
                    compareByDescending<LocalMediaItem> { it.size > 0L }.thenByDescending { it.size }
                )
                LibrarySortOrder.SIZE_ASC -> searchFiltered.sortedWith(
                    compareByDescending<LocalMediaItem> { it.size > 0L }.thenBy { it.size }
                )
            }
        }

    val videoCount: Int get() = items.count { it.mediaType == MediaType.VIDEO }
    val photoCount: Int get() = items.count { it.mediaType == MediaType.PHOTO }
    val otherCount: Int get() = items.count { it.mediaType != MediaType.VIDEO && it.mediaType != MediaType.PHOTO }

    val totalOccupiedBytes: Long
        get() = items.filter { it.existsOnDisk && it.size > 0L }.sumOf { it.size }

    val missingCount: Int
        get() = items.count { !it.existsOnDisk }
}

/**
 * ViewModel managing local library items, reactive searching, filtering, sorting,
 * and safe file actions (rename, record-only removal, and physical file deletion).
 */
class LibraryViewModel(
    private val libraryRepository: LibraryRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        if (libraryRepository != null) {
            viewModelScope.launch {
                libraryRepository.observeAllMedia().collect { mediaList ->
                    _uiState.update { it.copy(items = mediaList) }
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: LibraryFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setSortOrder(order: LibrarySortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
    }

    fun clearSearchAndFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                filter = LibraryFilter.ALL,
                sortOrder = LibrarySortOrder.DATE_DESC
            )
        }
    }

    fun setItemToRename(item: LocalMediaItem?) {
        _uiState.update { it.copy(itemToRename = item) }
    }

    fun setItemToDelete(item: LocalMediaItem?) {
        _uiState.update { it.copy(itemToDelete = item) }
    }

    fun renameMedia(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            if (libraryRepository != null) {
                val success = libraryRepository.renameMedia(id, newName)
                if (success) {
                    _uiState.update { it.copy(itemToRename = null, actionMessage = "File renamed successfully.") }
                } else {
                    _uiState.update { it.copy(actionMessage = "Failed to rename file. Check permissions or destination.") }
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        items = state.items.map { if (it.id == id) it.copy(name = newName) else it },
                        itemToRename = null,
                        actionMessage = "File renamed successfully."
                    )
                }
            }
        }
    }

    fun deleteMediaRecordOnly(id: String) {
        viewModelScope.launch {
            if (libraryRepository != null) {
                libraryRepository.deleteMedia(id)
            } else {
                _uiState.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }
            }
            _uiState.update { it.copy(itemToDelete = null, actionMessage = "Removed entry from library (file kept on disk).") }
        }
    }

    fun deleteMediaFileAndRecord(id: String) {
        viewModelScope.launch {
            if (libraryRepository != null) {
                libraryRepository.deleteMediaFileAndRecord(id)
            } else {
                _uiState.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }
            }
            _uiState.update { it.copy(itemToDelete = null, actionMessage = "Deleted file from disk and removed library record.") }
        }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    companion object {
        fun provideFactory(
            libraryRepository: LibraryRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(libraryRepository) as T
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? TeradownApplication
                LibraryViewModel(app?.container?.libraryRepository)
            }
        }
    }
}
