package com.pranjalbudaniya.teradown.ui.browse

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pranjalbudaniya.teradown.core.model.MediaType
import com.pranjalbudaniya.teradown.core.model.ShareFileItem
import com.pranjalbudaniya.teradown.core.model.ShareInfo
import com.pranjalbudaniya.teradown.core.model.ShareState
import com.pranjalbudaniya.teradown.ui.components.Formatters
import com.pranjalbudaniya.teradown.ui.components.TeledownEmptyState
import com.pranjalbudaniya.teradown.ui.components.TeledownTopBar
import com.pranjalbudaniya.teradown.ui.theme.TeledownTheme

@Composable
fun BrowseShareScreen(
    url: String,
    pwd: String?,
    onNavigateBack: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseShareViewModel = viewModel()
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
                    onDownloadClick = { viewModel.downloadSelected(onNavigateToDownloads) }
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
                        description = "This share link is empty or does not contain any accessible files.",
                        actionButtonText = "Back to Home",
                        onActionClick = onNavigateBack
                    )
                }

                is BrowseContentState.Success -> {
                    SuccessContent(
                        successState = state,
                        isDemoData = uiState.isDemoData,
                        onToggleSelection = { viewModel.toggleItemSelection(it) },
                        onSelectAll = { viewModel.setAllSelected(it) },
                        onFilterChanged = { viewModel.setFilter(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    successState: BrowseContentState.Success,
    isDemoData: Boolean,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onFilterChanged: (BrowseFilter) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // UI Demonstration notice banner
        if (isDemoData) {
            item {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "UI Demonstration Preview: Real Terabox endpoints will be connected in Phase 3.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Share Info Header Card
        item {
            ShareHeaderCard(
                shareInfo = successState.shareInfo,
                totalFiles = successState.files.size
            )
        }

        // Filter & Selection Controls Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = successState.filter == BrowseFilter.ALL,
                        onClick = { onFilterChanged(BrowseFilter.ALL) },
                        label = { Text("All (${successState.files.size})") }
                    )
                    FilterChip(
                        selected = successState.filter == BrowseFilter.VIDEOS,
                        onClick = { onFilterChanged(BrowseFilter.VIDEOS) },
                        label = { Text("Videos") }
                    )
                    FilterChip(
                        selected = successState.filter == BrowseFilter.PHOTOS,
                        onClick = { onFilterChanged(BrowseFilter.PHOTOS) },
                        label = { Text("Photos") }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onSelectAll(!successState.isAllSelected) }
                ) {
                    Text(
                        text = if (successState.isAllSelected) "Deselect" else "Select All",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Checkbox(
                        checked = successState.isAllSelected,
                        onCheckedChange = { onSelectAll(it) }
                    )
                }
            }
        }

        // File Item Rows
        items(
            items = successState.filteredFiles,
            key = { it.fsId }
        ) { file ->
            FileItemRow(
                file = file,
                onToggleSelection = { onToggleSelection(file.fsId) }
            )
        }

        // Spacer for bottom bar clearance
        item {
            Spacer(modifier = Modifier.height(72.dp))
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
                    text = "$totalFiles items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Total: ${Formatters.formatBytes(shareInfo.totalBytes)}",
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
    onToggleSelection: () -> Unit
) {
    val isUnsupported = file.mediaType == MediaType.OTHER

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (file.isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isUnsupported) { onToggleSelection() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        when (file.mediaType) {
                            MediaType.VIDEO -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            MediaType.PHOTO -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            MediaType.OTHER -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (file.mediaType) {
                        MediaType.VIDEO -> Icons.Default.Movie
                        MediaType.PHOTO -> Icons.Default.Image
                        MediaType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = file.mediaType.name,
                    tint = when (file.mediaType) {
                        MediaType.VIDEO -> MaterialTheme.colorScheme.primary
                        MediaType.PHOTO -> MaterialTheme.colorScheme.tertiary
                        MediaType.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUnsupported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = Formatters.formatBytes(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isUnsupported) {
                        Text(
                            text = "(Skipped type)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = file.mediaType.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Selection Checkbox
            if (!isUnsupported) {
                Checkbox(
                    checked = file.isSelected,
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
    onDownloadClick: () -> Unit
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
                onClick = onDownloadClick,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download Selected"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Download Selected",
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
