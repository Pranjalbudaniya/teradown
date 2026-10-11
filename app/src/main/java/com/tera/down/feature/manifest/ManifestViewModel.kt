package com.tera.down.feature.manifest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.tera.down.TeradownApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.OutputStream

class ManifestViewModel(
    private val manifestRepository: ManifestRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManifestUiState())
    val uiState: StateFlow<ManifestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            manifestRepository.observeAllManifest().collect { list ->
                _uiState.update { it.copy(records = list, isLoading = false) }
            }
        }
    }

    fun setFilter(filter: ManifestFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun showClearConfirm(show: Boolean) {
        _uiState.update { it.copy(showClearConfirmDialog = show) }
    }

    fun clearManifestHistory() {
        viewModelScope.launch {
            manifestRepository.clearManifestHistory()
            _uiState.update {
                it.copy(
                    showClearConfirmDialog = false,
                    exportMessage = "Manifest records cleared. (Downloaded files were not touched)"
                )
            }
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            manifestRepository.deleteManifestRecord(id)
        }
    }

    fun exportToStream(format: ManifestExportFormat, outputStream: OutputStream) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            try {
                val records = manifestRepository.getAllManifest()
                val result = ManifestExporter.export(
                    format = format,
                    items = records,
                    outputStream = outputStream
                )
                outputStream.flush()
                when (result) {
                    is ManifestExportResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                exportMessage = "Successfully exported ${result.recordCount} records to ${result.format.name}."
                            )
                        }
                    }
                    is ManifestExportResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                exportMessage = "Export failed: ${result.message}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportMessage = "Export failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun reportExportCancelled() {
        _uiState.update { it.copy(exportMessage = "Export cancelled by user.") }
    }

    fun reportExportError(errorMessage: String) {
        _uiState.update { it.copy(exportMessage = errorMessage) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TeradownApplication
                return ManifestViewModel(
                    manifestRepository = application.container.manifestRepository
                ) as T
            }
        }
    }
}
