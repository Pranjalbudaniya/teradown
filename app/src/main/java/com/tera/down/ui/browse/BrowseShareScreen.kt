package com.tera.down.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ShareFileItem
import com.tera.down.core.model.ShareInfo
import com.tera.down.ui.components.Formatters
import com.tera.down.ui.components.TeledownEmptyState
import com.tera.down.ui.components.TeledownTopBar
import com.tera.down.ui.theme.TeledownTheme

@Composable
fun BrowseShareScreen(
    url: String,
    pwd: String?,
    onNavigateBack: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onPlayDirectVideo: ((url: String, title: String, fsId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: BrowseShareViewModel = viewModel(factory = BrowseShareViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(url, pwd) {
        viewModel.loadShare(url, pwd)
    }

    LaunchedEffect(uiState.actionMessage) {
        uiState.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    if (uiState.showBatchConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissBatchConfirmation() },
            title = { Text("Confirm Batch Download") },
            text = {
                Text("You are about to queue ${uiState.pendingBatchCount} files for download. Do you want to proceed?")
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmBatchEnqueue(onNavigateToDownloads) }) {
                    Text("Queue All")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissBatchConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TeledownTopBar(
                title = "Browse Share",
                subtitle = if (url.length > 36) url.take(33) + "..." else url,
                canNavigateBack = true,
                onNavigateBack = onNavigateBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.contentState is BrowseContentState.Success) {
                val success = uiState.contentState as BrowseContentState.Success
                BottomActionBar(
                    selectedCount = success.selectedCount,
                    totalBytes = success.totalSelectedBytes,
                    onQueueClick = { viewModel.queueSelected(onNavigateToDownloads) }
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState.contentState) {
                is BrowseContentState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is BrowseContentState.PasswordRequired -> {
                    PasswordPromptContent(
                        state = state,
                        onSubmitPassword = { viewModel.submitPassword(it) },
                        onCancel = onNavigateBack
                    )
                }

                is BrowseContentState.DirectDownloadUnavailable -> {
                    DirectDownloadUnavailableContent(
                        state = state,
                        onBack = onNavigateBack
                    )
                }

                is BrowseContentState.Error -> {
                    TeledownEmptyState(
                        icon = Icons.Default.Block,
                        title = state.title,
                        description = state.message,
                        actionButtonText = if (state.canRetry) "Retry Inspection" else null,
                        onActionClick = { viewModel.loadShare(url, pwd) }
                    )
                }

                is BrowseContentState.Empty -> {
                    TeledownEmptyState(
                        icon = Icons.Default.Folder,
                        title = "No Files Found",
                        description = "This folder is empty or does not contain any accessible files.",
                        actionButtonText = if (uiState.directoryHistory.isNotEmpty()) "Return to Parent" else "Back to Home",
                        onActionClick = {
                            if (uiState.directoryHistory.isNotEmpty()) viewModel.navigateUp()
                            else onNavigateBack()
                        }
                    )
                }

                is BrowseContentState.Success -> {
                    SuccessContent(
                        successState = state,
                        isScanningTree = uiState.isScanningTree,
                        scanProgressText = uiState.scanProgressText,
                        onToggleSelection = { viewModel.toggleItemSelection(it) },
                        onSelectAll = { viewModel.setAllSelected(it) },
                        onFilterChanged = { viewModel.setFilter(it) },
                        onSortOrderChanged = { viewModel.setSortOrder(it) },
                        onOpenFolder = { viewModel.openDirectory(it) },
                        onNavigateUp = { viewModel.navigateUp() },
                        onNavigateToRoot = { viewModel.navigateToRoot() },
                        onScanAllSubfolders = { viewModel.scanAllSubfolders() },
                        onCancelScan = { viewModel.cancelScan() },
                        onPlayDirectVideo = onPlayDirectVideo
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordPromptContent(
    state: BrowseContentState.PasswordRequired,
    onSubmitPassword: (String) -> Unit,
    onCancel: () -> Unit
) {
    var passwordText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (state.isWrongPassword) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = if (state.isWrongPassword) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (state.isWrongPassword) "Incorrect Extraction Password" else "Password Required",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = passwordText,
            onValueChange = { passwordText = it },
            label = { Text("Extraction password (pwd)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (passwordText.isNotBlank()) onSubmitPassword(passwordText.trim())
                }
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            Button(
                onClick = { onSubmitPassword(passwordText.trim()) },
                enabled = passwordText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Unlock")
            }
        }
    }
}

@Composable
private fun DirectDownloadUnavailableContent(
    state: BrowseContentState.DirectDownloadUnavailable,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Direct Download Unavailable",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        item {
            ShareHeaderCard(
                shareInfo = state.shareInfo,
                totalFiles = state.files.size
            )
        }

        items(
            items = state.files,
            key = { it.fsId }
        ) { file ->
            FileItemRow(
                file = file,
                isSelected = false,
                onToggleSelection = {},
                onOpenFolder = {}
            )
        }

        item {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Home")
            }
        }
    }
}

@Composable
private fun SuccessContent(
    successState: BrowseContentState.Success,
    isScanningTree: Boolean,
    scanProgressText: String?,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onFilterChanged: (BrowseFilter) -> Unit,
    onSortOrderChanged: (BrowseSortOrder) -> Unit,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToRoot: () -> Unit,
    onScanAllSubfolders: () -> Unit,
    onCancelScan: () -> Unit,
    onPlayDirectVideo: ((url: String, title: String, fsId: String) -> Unit)? = null
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Share Header Summary
        item {
            ShareHeaderCard(
                shareInfo = successState.shareInfo,
                totalFiles = successState.files.size
            )
        }

        // Deep Scan Progress Banner
        if (isScanningTree) {
            item {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = scanProgressText ?: "Scanning folders...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(onClick = onCancelScan, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel scan",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Folder Navigation Bar
        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateToRoot,
                        enabled = successState.currentDirectory != "/"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Share Root",
                            tint = if (successState.currentDirectory != "/") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }

                    if (successState.canNavigateUp) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Parent Folder",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = if (successState.isDeepScanView) "All folders (Flat scan)" else successState.currentDirectory,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (!successState.isDeepScanView && !isScanningTree) {
                        IconButton(onClick = onScanAllSubfolders) {
                            Icon(
                                imageVector = Icons.Default.TravelExplore,
                                contentDescription = "Scan subfolders recursively",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Filter and Sorting Controls Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            FilterChip(
                                selected = successState.filter == BrowseFilter.ALL,
                                onClick = { onFilterChanged(BrowseFilter.ALL) },
                                label = { Text("All (${successState.files.size})") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = successState.filter == BrowseFilter.VIDEOS,
                                onClick = { onFilterChanged(BrowseFilter.VIDEOS) },
                                label = { Text("Videos") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = successState.filter == BrowseFilter.PHOTOS,
                                onClick = { onFilterChanged(BrowseFilter.PHOTOS) },
                                label = { Text("Photos") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = successState.filter == BrowseFilter.OTHER,
                                onClick = { onFilterChanged(BrowseFilter.OTHER) },
                                label = { Text("Other") }
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort order",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Name (A to Z)") },
                                onClick = {
                                    onSortOrderChanged(BrowseSortOrder.NAME_ASC)
                                    sortMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Name (Z to A)") },
                                onClick = {
                                    onSortOrderChanged(BrowseSortOrder.NAME_DESC)
                                    sortMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Size (Largest first)") },
                                onClick = {
                                    onSortOrderChanged(BrowseSortOrder.SIZE_DESC)
                                    sortMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Size (Smallest first)") },
                                onClick = {
                                    onSortOrderChanged(BrowseSortOrder.SIZE_ASC)
                                    sortMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Media Type") },
                                onClick = {
                                    onSortOrderChanged(BrowseSortOrder.TYPE)
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Batch Selection Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${successState.selectedCount} of ${successState.selectableCount} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onSelectAll(!successState.isAllSelected) }
                    ) {
                        Text(
                            text = if (successState.isAllSelected) "Deselect All" else "Select All",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Checkbox(
                            checked = successState.isAllSelected,
                            onCheckedChange = { onSelectAll(it) }
                        )
                    }
                }
            }
        }

        // File & Folder Items
        items(
            items = successState.displayFiles,
            key = { "${it.fsId}_${it.relPath}" }
        ) { file ->
            FileItemRow(
                file = file,
                isSelected = file.fsId in successState.selectedFsIds,
                onToggleSelection = { onToggleSelection(file.fsId) },
                onOpenFolder = { onOpenFolder(file.relPath) },
                onPlayDirectVideo = if (file.directUrl != null) {
                    { onPlayDirectVideo?.invoke(file.directUrl, file.name, file.fsId) }
                } else null
            )
        }

        item {
            Spacer(modifier = Modifier.height(76.dp))
        }
    }
}

@Composable
private fun ShareHeaderCard(
    shareInfo: ShareInfo,
    totalFiles: Int
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = shareInfo.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$totalFiles item(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Total: ${Formatters.formatFileSize(shareInfo.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (shareInfo.requiresPassword) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Password protected",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Protected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileItemRow(
    file: ShareFileItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onOpenFolder: () -> Unit,
    onPlayDirectVideo: (() -> Unit)? = null
) {
    val isFolder = file.isDirectory
    val hasNoDirectUrl = !isFolder && file.directUrl.isNullOrBlank()
    val isUnsupportedType = file.mediaType == MediaType.OTHER && !isFolder

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isFolder) onOpenFolder()
                else onToggleSelection()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isFolder -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            file.mediaType == MediaType.VIDEO -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            file.mediaType == MediaType.PHOTO -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isFolder -> Icons.Default.Folder
                        file.mediaType == MediaType.VIDEO -> Icons.Default.Movie
                        file.mediaType == MediaType.PHOTO -> Icons.Default.Image
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = if (isFolder) "Folder" else file.mediaType.name,
                    tint = when {
                        isFolder -> MaterialTheme.colorScheme.secondary
                        file.mediaType == MediaType.VIDEO -> MaterialTheme.colorScheme.primary
                        file.mediaType == MediaType.PHOTO -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasNoDirectUrl || isUnsupportedType) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isFolder) {
                        Text(
                            text = Formatters.formatFileSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        isFolder -> {
                            Text(
                                text = "Folder",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        hasNoDirectUrl -> {
                            Text(
                                text = "(Preview only)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        isUnsupportedType -> {
                            Text(
                                text = "Other",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        else -> {
                            Text(
                                text = file.mediaType.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (!isFolder && file.mediaType == MediaType.VIDEO && onPlayDirectVideo != null) {
                IconButton(
                    onClick = onPlayDirectVideo,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Stream Online",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            if (!isFolder && !hasNoDirectUrl && !isUnsupportedType) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
            }
        }
    }
}

@Composable
private fun BottomActionBar(
    selectedCount: Int,
    totalBytes: Long,
    onQueueClick: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = Formatters.formatBytes(totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onQueueClick,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Queue Selected"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Queue Selected",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BrowseShareScreenPreview() {
    TeledownTheme {
        Surface {
            BrowseShareScreen(
                url = "https://terabox.com/s/1demo1234",
                pwd = null,
                onNavigateBack = {},
                onNavigateToDownloads = {}
            )
        }
    }
}
