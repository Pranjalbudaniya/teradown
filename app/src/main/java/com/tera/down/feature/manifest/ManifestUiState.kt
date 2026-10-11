package com.tera.down.feature.manifest

import com.tera.down.core.manifest.DownloadManifestItem
import com.tera.down.core.manifest.ManifestStatus

/**
 * Filter mode for the manifest history display.
 */
enum class ManifestFilter {
    ALL,
    COMPLETED,
    FAILED,
    ACTIVE
}

/**
 * UI state for the Download Manifest screen.
 */
data class ManifestUiState(
    val records: List<DownloadManifestItem> = emptyList(),
    val filter: ManifestFilter = ManifestFilter.ALL,
    val isLoading: Boolean = false,
    val exportMessage: String? = null,
    val isExporting: Boolean = false,
    val showClearConfirmDialog: Boolean = false
) {
    val filteredRecords: List<DownloadManifestItem>
        get() = when (filter) {
            ManifestFilter.ALL -> records
            ManifestFilter.COMPLETED -> records.filter { it.status == ManifestStatus.COMPLETED }
            ManifestFilter.FAILED -> records.filter { it.status == ManifestStatus.FAILED }
            ManifestFilter.ACTIVE -> records.filter {
                it.status == ManifestStatus.DOWNLOADING ||
                    it.status == ManifestStatus.QUEUED ||
                    it.status == ManifestStatus.INTERRUPTED
            }
        }

    val completedCount: Int get() = records.count { it.status == ManifestStatus.COMPLETED }
    val failedCount: Int get() = records.count { it.status == ManifestStatus.FAILED }
    val activeCount: Int get() = records.count {
        it.status == ManifestStatus.DOWNLOADING ||
            it.status == ManifestStatus.QUEUED ||
            it.status == ManifestStatus.INTERRUPTED
    }
}
