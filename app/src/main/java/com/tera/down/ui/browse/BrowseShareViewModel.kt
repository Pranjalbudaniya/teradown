package com.tera.down.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tera.down.TeradownApplication
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.core.model.ShareLink
import com.tera.down.core.model.ShareState
import com.tera.down.feature.queue.BatchEnqueueResult
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.resolver.FolderTreeScanner
import com.tera.down.feature.resolver.ScanResult
import com.tera.down.feature.resolver.ShareLinkParseResult
import com.tera.down.feature.resolver.ShareLinkResolver
import com.tera.down.feature.resolver.ShareLinkResolverRegistry
import com.tera.down.feature.resolver.ShareResolveErrorType
import com.tera.down.feature.resolver.ShareResolveResult
import com.tera.down.feature.resolver.TeraboxShareLinkParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BrowseFilter {
    ALL,
    VIDEOS,
    PHOTOS,
    OTHER
}

enum class BrowseSortOrder {
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC,
    TYPE
}

sealed interface BrowseContentState {
    object Empty : BrowseContentState
    data class Loading(val message: String = "Analyzing share metadata...") : BrowseContentState

    data class Success(
        val shareInfo: ShareInfo,
        val files: List<ShareFileItem>,
        val filter: BrowseFilter = BrowseFilter.ALL,
        val sortOrder: BrowseSortOrder = BrowseSortOrder.NAME_ASC,
        val currentDirectory: String = "/",
        val directoryHistory: List<String> = emptyList(),
        val selectedFsIds: Set<String> = emptySet(),
        val isDeepScanView: Boolean = false
    ) : BrowseContentState {

        val displayFiles: List<ShareFileItem>
            get() {
                val filtered = when (filter) {
                    BrowseFilter.ALL -> files
                    BrowseFilter.VIDEOS -> files.filter { it.isDirectory || it.mediaType == MediaType.VIDEO }
                    BrowseFilter.PHOTOS -> files.filter { it.isDirectory || it.mediaType == MediaType.PHOTO }
                    BrowseFilter.OTHER -> files.filter { it.isDirectory || it.mediaType == MediaType.OTHER }
                }

                // Partition so directories always stay at the top
                val (dirs, nonDirs) = filtered.partition { it.isDirectory }
                val sortedDirs = dirs.sortedBy { it.name.lowercase() }

                val sortedNonDirs = when (sortOrder) {
                    BrowseSortOrder.NAME_ASC -> nonDirs.sortedBy { it.name.lowercase() }
                    BrowseSortOrder.NAME_DESC -> nonDirs.sortedByDescending { it.name.lowercase() }
                    BrowseSortOrder.SIZE_DESC -> nonDirs.sortedWith(
                        compareByDescending<ShareFileItem> { it.size > 0L }.thenByDescending { it.size }
                    )
                    BrowseSortOrder.SIZE_ASC -> nonDirs.sortedWith(
                        compareByDescending<ShareFileItem> { it.size > 0L }.thenBy { it.size }
                    )
                    BrowseSortOrder.TYPE -> nonDirs.sortedWith(
                        compareBy<ShareFileItem> { it.mediaType.name }.thenBy { it.name.lowercase() }
                    )
                }

                return sortedDirs + sortedNonDirs
            }

        val filteredFiles: List<ShareFileItem> get() = displayFiles

        val selectedCount: Int
            get() = files.count { it.fsId in selectedFsIds && !it.isDirectory }

        val totalSelectedBytes: Long
            get() = files.filter { it.fsId in selectedFsIds && !it.isDirectory && it.size > 0L }.sumOf { it.size }

        val selectableCount: Int
            get() = files.count { !it.isDirectory && !it.directUrl.isNullOrBlank() && it.mediaType != MediaType.OTHER }

        val isAllSelected: Boolean
            get() = selectableCount > 0 && selectedCount == selectableCount

        val canNavigateUp: Boolean
            get() = currentDirectory != "/" && currentDirectory.isNotBlank()
    }

    data class PasswordRequired(
        val shareLink: ShareLink,
        val message: String = "Password required to access this share.",
        val isWrongPassword: Boolean = false
    ) : BrowseContentState

    data class DirectDownloadUnavailable(
        val shareInfo: ShareInfo,
        val files: List<ShareFileItem>,
        val message: String = "Direct download is not available for these files (stream/preview only)."
    ) : BrowseContentState

    data class Error(
        val title: String,
        val message: String,
        val canRetry: Boolean = true
    ) : BrowseContentState
}

data class BrowseShareUiState(
    val currentUrl: String = "",
    val currentPwd: String? = null,
    val currentDir: String = "/",
    val directoryHistory: List<String> = emptyList(),
    val parsedShareLink: ShareLink? = null,
    val contentState: BrowseContentState = BrowseContentState.Loading(),
    val isScanningTree: Boolean = false,
    val scanProgressText: String? = null,
    val isDemoData: Boolean = false,
    val actionMessage: String? = null,
    val showBatchConfirmation: Boolean = false,
    val pendingBatchCount: Int = 0
)

class BrowseShareViewModel(
    private val queueRepository: DownloadQueueRepository? = null,
    private val resolverRegistry: ShareLinkResolverRegistry? = null,
    private val fallbackResolver: ShareLinkResolver? = null,
    private val settingsRepository: com.tera.down.feature.settings.SettingsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseShareUiState())
    val uiState: StateFlow<BrowseShareUiState> = _uiState.asStateFlow()

    private var activeScanJob: Job? = null

    fun loadShare(
        url: String,
        pwd: String? = null,
        dirPath: String = "/",
        forceDemo: Boolean = false
    ) {
        _uiState.update {
            it.copy(
                currentUrl = url,
                currentPwd = pwd,
                currentDir = dirPath,
                contentState = BrowseContentState.Loading("Resolving share parameters...")
            )
        }

        viewModelScope.launch {
            when (val parseResult = TeraboxShareLinkParser.parse(url)) {
                is ShareLinkParseResult.InvalidUrl -> {
                    _uiState.update {
                        it.copy(
                            contentState = BrowseContentState.Error(
                                title = "Invalid URL",
                                message = parseResult.reason,
                                canRetry = false
                            )
                        )
                    }
                    return@launch
                }

                is ShareLinkParseResult.UnsupportedHost -> {
                    _uiState.update {
                        it.copy(
                            contentState = BrowseContentState.Error(
                                title = "Unsupported Host",
                                message = parseResult.reason,
                                canRetry = false
                            )
                        )
                    }
                    return@launch
                }

                is ShareLinkParseResult.Success -> {
                    val shareLink = parseResult.shareLink.copy(
                        pwd = pwd ?: parseResult.shareLink.pwd
                    )
                    _uiState.update { it.copy(parsedShareLink = shareLink) }

                    val resolver = (resolverRegistry?.getResolverForHost(shareLink.host ?: ""))
                        ?: fallbackResolver

                    if (resolver == null && forceDemo) {
                        loadDemoPreview(url, pwd)
                    } else if (resolver == null) {
                        // Fallback to demo data only when testing without any resolver
                        loadDemoPreview(url, pwd)
                    } else {
                        executeResolution(resolver, shareLink, pwd, dirPath)
                    }
                }
            }
        }
    }

    private suspend fun executeResolution(
        resolver: ShareLinkResolver,
        shareLink: ShareLink,
        password: String?,
        dirPath: String
    ) {
        val result = resolver.resolve(
            shareLink = shareLink,
            password = password ?: shareLink.pwd,
            dirPath = dirPath
        )

        when (result) {
            is ShareResolveResult.Success -> {
                if (result.items.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            contentState = BrowseContentState.Empty,
                            isDemoData = false
                        )
                    }
                } else {
                    val initialSelection = result.items
                        .filter { it.isSelected && !it.isDirectory && !it.directUrl.isNullOrBlank() }
                        .map { it.fsId }
                        .toSet()

                    _uiState.update {
                        it.copy(
                            contentState = BrowseContentState.Success(
                                shareInfo = result.shareInfo,
                                files = result.items,
                                currentDirectory = result.currentDirectory,
                                directoryHistory = it.directoryHistory,
                                selectedFsIds = initialSelection
                            ),
                            isDemoData = false
                        )
                    }
                }
            }

            is ShareResolveResult.NeedsPassword -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.PasswordRequired(
                            shareLink = result.shareLink,
                            message = result.message,
                            isWrongPassword = false
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.WrongPassword -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.PasswordRequired(
                            shareLink = result.shareLink,
                            message = result.message,
                            isWrongPassword = true
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.AuthRequired -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Authentication Required",
                            message = result.message,
                            canRetry = true
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.CaptchaOrRateLimited -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Rate Limited / Security Check",
                            message = result.message,
                            canRetry = true
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.Expired -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Share Expired",
                            message = result.message,
                            canRetry = false
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.Cancelled -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Share Unavailable",
                            message = result.message,
                            canRetry = false
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.Blocked -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Share Blocked",
                            message = result.message,
                            canRetry = false
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.UnsupportedAccessFlow -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Unsupported Access Flow",
                            message = result.message,
                            canRetry = false
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.DirectDownloadUnavailable -> {
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.DirectDownloadUnavailable(
                            shareInfo = result.shareInfo,
                            files = result.items,
                            message = result.message
                        ),
                        isDemoData = false
                    )
                }
            }

            is ShareResolveResult.Failure -> {
                val appError = com.tera.down.core.model.AppError.fromShareResolveResult(result)
                val canRetry = appError?.isRetryable ?: (result.errorType !in setOf(
                    ShareResolveErrorType.INVALID_URL,
                    ShareResolveErrorType.UNSUPPORTED_SCHEME,
                    ShareResolveErrorType.UNSUPPORTED_HOST
                ))
                val safeMessage = appError?.userMessage ?: result.message
                _uiState.update {
                    it.copy(
                        contentState = BrowseContentState.Error(
                            title = "Resolution Error",
                            message = safeMessage,
                            canRetry = canRetry
                        ),
                        isDemoData = false
                    )
                }
            }
        }
    }

    fun submitPassword(password: String) {
        val link = _uiState.value.parsedShareLink ?: return
        loadShare(
            url = link.originalUrl,
            pwd = password,
            dirPath = _uiState.value.currentDir
        )
    }

    fun openDirectory(dirPath: String) {
        val currentHistory = _uiState.value.directoryHistory + _uiState.value.currentDir
        _uiState.update { it.copy(directoryHistory = currentHistory) }
        loadShare(
            url = _uiState.value.currentUrl,
            pwd = _uiState.value.currentPwd,
            dirPath = dirPath
        )
    }

    fun navigateUp() {
        val history = _uiState.value.directoryHistory
        if (history.isEmpty()) {
            val current = _uiState.value.currentDir
            if (current != "/" && current.isNotBlank()) {
                val parent = current.substringBeforeLast('/', "").ifEmpty { "/" }
                loadShare(
                    url = _uiState.value.currentUrl,
                    pwd = _uiState.value.currentPwd,
                    dirPath = parent
                )
            } else {
                loadShare(
                    url = _uiState.value.currentUrl,
                    pwd = _uiState.value.currentPwd,
                    dirPath = "/"
                )
            }
        } else {
            val parentDir = history.last()
            val newHistory = history.dropLast(1)
            _uiState.update { it.copy(directoryHistory = newHistory) }
            loadShare(
                url = _uiState.value.currentUrl,
                pwd = _uiState.value.currentPwd,
                dirPath = parentDir
            )
        }
    }

    fun navigateToRoot() {
        _uiState.update { it.copy(directoryHistory = emptyList()) }
        loadShare(
            url = _uiState.value.currentUrl,
            pwd = _uiState.value.currentPwd,
            dirPath = "/"
        )
    }

    fun toggleItemSelection(fsId: String) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val target = current.files.firstOrNull { it.fsId == fsId } ?: return

        if (target.isDirectory) return

        if (target.directUrl.isNullOrBlank()) {
            _uiState.update {
                it.copy(actionMessage = "Direct download is unavailable for '${target.name}' (preview/stream only).")
            }
            return
        }

        val updatedSet = if (fsId in current.selectedFsIds) {
            current.selectedFsIds - fsId
        } else {
            current.selectedFsIds + fsId
        }

        _uiState.update {
            it.copy(contentState = current.copy(selectedFsIds = updatedSet))
        }
    }

    fun selectAll() {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val eligibleFsIds = current.files
            .filter { !it.isDirectory && !it.directUrl.isNullOrBlank() && it.mediaType != MediaType.OTHER }
            .map { it.fsId }
            .toSet()

        _uiState.update {
            it.copy(contentState = current.copy(selectedFsIds = eligibleFsIds))
        }
    }

    fun clearSelection() {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        _uiState.update {
            it.copy(contentState = current.copy(selectedFsIds = emptySet()))
        }
    }

    fun setAllSelected(selected: Boolean) {
        if (selected) selectAll() else clearSelection()
    }

    fun setFilter(filter: BrowseFilter) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        _uiState.update {
            it.copy(contentState = current.copy(filter = filter))
        }
    }

    fun setSortOrder(sortOrder: BrowseSortOrder) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        _uiState.update {
            it.copy(contentState = current.copy(sortOrder = sortOrder))
        }
    }

    fun scanAllSubfolders() {
        val link = _uiState.value.parsedShareLink ?: return
        val resolver = (resolverRegistry?.getResolverForHost(link.host ?: "")) ?: fallbackResolver ?: return
        val currentSuccess = _uiState.value.contentState as? BrowseContentState.Success ?: return

        activeScanJob?.cancel()
        activeScanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanningTree = true,
                    scanProgressText = "Scanning folders..."
                )
            }

            val scanner = FolderTreeScanner(resolver)
            val result = scanner.scanRecursive(
                shareLink = link,
                password = _uiState.value.currentPwd,
                startDir = _uiState.value.currentDir,
                onProgress = { folders, files ->
                    _uiState.update {
                        it.copy(scanProgressText = "Scanned $folders folder(s), found $files file(s)...")
                    }
                }
            )

            when (result) {
                is ScanResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isScanningTree = false,
                            scanProgressText = null,
                            contentState = currentSuccess.copy(
                                files = result.allFiles,
                                isDeepScanView = true,
                                selectedFsIds = emptySet()
                            ),
                            actionMessage = "Discovered ${result.allFiles.size} media files across ${result.totalFoldersScanned} folders."
                        )
                    }
                }
                is ScanResult.PartialLimitReached -> {
                    _uiState.update {
                        it.copy(
                            isScanningTree = false,
                            scanProgressText = null,
                            contentState = currentSuccess.copy(
                                files = result.filesCollected,
                                isDeepScanView = true,
                                selectedFsIds = emptySet()
                            ),
                            actionMessage = "${result.reason} Showing ${result.filesCollected.size} files."
                        )
                    }
                }
                is ScanResult.Cancelled -> {
                    _uiState.update {
                        it.copy(
                            isScanningTree = false,
                            scanProgressText = null,
                            actionMessage = "Folder scan was cancelled."
                        )
                    }
                }
                is ScanResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isScanningTree = false,
                            scanProgressText = null,
                            actionMessage = "Scan error: ${result.message}"
                        )
                    }
                }
            }
        }
    }

    fun cancelScan() {
        activeScanJob?.cancel()
        activeScanJob = null
        _uiState.update {
            it.copy(isScanningTree = false, scanProgressText = null)
        }
    }

    fun queueSelected(onNavigateToDownloads: () -> Unit) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val selectedFiles = current.files.filter { it.fsId in current.selectedFsIds && !it.isDirectory }

        if (selectedFiles.isEmpty()) {
            _uiState.update { it.copy(actionMessage = "Please select at least one media file to download.") }
            return
        }

        viewModelScope.launch {
            val settings = settingsRepository?.getSettings() ?: com.tera.down.core.model.AppSettings()
            if (settings.confirmLargeBatches && selectedFiles.size > com.tera.down.core.model.AppSettings.LARGE_BATCH_THRESHOLD && !_uiState.value.showBatchConfirmation) {
                _uiState.update { it.copy(showBatchConfirmation = true, pendingBatchCount = selectedFiles.size) }
                return@launch
            }
            executeEnqueue(current, selectedFiles, onNavigateToDownloads)
        }
    }

    fun confirmBatchEnqueue(onNavigateToDownloads: () -> Unit) {
        val current = _uiState.value.contentState as? BrowseContentState.Success ?: return
        val selectedFiles = current.files.filter { it.fsId in current.selectedFsIds && !it.isDirectory }
        _uiState.update { it.copy(showBatchConfirmation = false, pendingBatchCount = 0) }
        viewModelScope.launch {
            executeEnqueue(current, selectedFiles, onNavigateToDownloads)
        }
    }

    fun dismissBatchConfirmation() {
        _uiState.update { it.copy(showBatchConfirmation = false, pendingBatchCount = 0) }
    }

    private suspend fun executeEnqueue(
        current: BrowseContentState.Success,
        selectedFiles: List<ShareFileItem>,
        onNavigateToDownloads: () -> Unit
    ) {
        if (queueRepository == null) {
            _uiState.update { it.copy(actionMessage = "Queue repository is not available.") }
            return
        }

        val result: BatchEnqueueResult = queueRepository.enqueueShareFilesWithResult(
            surl = current.shareInfo.surl,
            originalUrl = _uiState.value.currentUrl,
            items = selectedFiles
        )

        val msg = buildString {
            if (result.addedCount > 0) {
                append("Queued ${result.addedCount} file(s).")
            } else {
                append("No new files were queued.")
            }
            if (result.duplicateCount > 0) {
                append(" (${result.duplicateCount} duplicate(s) skipped)")
            }
            if (result.unavailableCount > 0) {
                append(" (${result.unavailableCount} without direct URL skipped)")
            }
        }

        _uiState.update {
            it.copy(
                actionMessage = msg,
                contentState = current.copy(selectedFsIds = emptySet())
            )
        }

        if (result.addedCount > 0) {
            onNavigateToDownloads()
        }
    }

    fun downloadSelected(onNavigateToDownloads: () -> Unit) {
        queueSelected(onNavigateToDownloads)
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    private suspend fun loadDemoPreview(url: String, pwd: String?) {
        delay(600)
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
                directUrl = "https://d.terabox.com/mock/Vacation_Drone_4K.mp4",
                isSelected = true
            ),
            ShareFileItem(
                fsId = "1002",
                name = "Sunset_Beach_Clip.mov",
                size = 850000000L,
                relPath = "Holiday/Sunset_Beach_Clip.mov",
                mediaType = MediaType.VIDEO,
                category = 1,
                directUrl = "https://d.terabox.com/mock/Sunset_Beach_Clip.mov",
                isSelected = true
            ),
            ShareFileItem(
                fsId = "1003",
                name = "Family_Portrait_RAW.jpg",
                size = 18500000L,
                relPath = "Holiday/Photos/Family_Portrait_RAW.jpg",
                mediaType = MediaType.PHOTO,
                category = 3,
                directUrl = "https://d.terabox.com/mock/Family_Portrait_RAW.jpg",
                isSelected = false
            ),
            ShareFileItem(
                fsId = "1004",
                name = "Landscape_Panorama.png",
                size = 24500000L,
                relPath = "Holiday/Photos/Landscape_Panorama.png",
                mediaType = MediaType.PHOTO,
                category = 3,
                directUrl = "https://d.terabox.com/mock/Landscape_Panorama.png",
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
                    files = mockFiles,
                    selectedFsIds = setOf("1001", "1002")
                ),
                isDemoData = true
            )
        }
    }

    companion object {
        fun provideFactory(
            queueRepository: DownloadQueueRepository,
            resolverRegistry: ShareLinkResolverRegistry? = null,
            fallbackResolver: ShareLinkResolver? = null,
            settingsRepository: com.tera.down.feature.settings.SettingsRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BrowseShareViewModel(queueRepository, resolverRegistry, fallbackResolver, settingsRepository) as T
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? TeradownApplication
                BrowseShareViewModel(
                    queueRepository = app?.container?.downloadQueueRepository,
                    resolverRegistry = app?.container?.shareLinkResolverRegistry,
                    fallbackResolver = app?.container?.teraboxShareLinkResolver,
                    settingsRepository = app?.container?.settingsRepository
                )
            }
        }
    }
}
