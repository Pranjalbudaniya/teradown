package com.tera.down.ui.library

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tera.down.core.model.LocalMediaItem
import com.tera.down.core.model.MediaType
import com.tera.down.core.util.LocalFileActions
import com.tera.down.ui.components.Formatters
import com.tera.down.ui.components.TeledownEmptyState
import com.tera.down.ui.components.TeledownTopBar
import com.tera.down.ui.theme.StatusError
import com.tera.down.ui.theme.TeledownTheme
import java.io.File

@Composable
fun LibraryScreen(
    onNavigateToHome: () -> Unit,
    onPlayMedia: ((LocalMediaItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.actionMessage) {
        uiState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TeledownTopBar(
                title = "Library",
                subtitle = if (uiState.items.isNotEmpty()) {
                    "${uiState.items.size} files • ${Formatters.formatBytes(uiState.totalOccupiedBytes)} storage"
                } else {
                    "Saved Media"
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            if (uiState.items.isNotEmpty()) {
                // Search Bar
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    placeholder = { Text("Search downloaded files...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                // Filters and Sort Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = uiState.filter == LibraryFilter.ALL,
                                onClick = { viewModel.setFilter(LibraryFilter.ALL) },
                                label = { Text("All (${uiState.items.size})") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = uiState.filter == LibraryFilter.VIDEOS,
                                onClick = { viewModel.setFilter(LibraryFilter.VIDEOS) },
                                label = { Text("Videos (${uiState.videoCount})") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = uiState.filter == LibraryFilter.PHOTOS,
                                onClick = { viewModel.setFilter(LibraryFilter.PHOTOS) },
                                label = { Text("Photos (${uiState.photoCount})") }
                            )
                        }
                        if (uiState.otherCount > 0) {
                            item {
                                FilterChip(
                                    selected = uiState.filter == LibraryFilter.OTHER,
                                    onClick = { viewModel.setFilter(LibraryFilter.OTHER) },
                                    label = { Text("Other (${uiState.otherCount})") }
                                )
                            }
                        }
                    }

                    // Sort Button
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort library items"
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Newest added") },
                                onClick = {
                                    viewModel.setSortOrder(LibrarySortOrder.DATE_DESC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Oldest added") },
                                onClick = {
                                    viewModel.setSortOrder(LibrarySortOrder.DATE_ASC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Name (A to Z)") },
                                onClick = {
                                    viewModel.setSortOrder(LibrarySortOrder.NAME_ASC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Name (Z to A)") },
                                onClick = {
                                    viewModel.setSortOrder(LibrarySortOrder.NAME_DESC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Size (Largest)") },
                                onClick = {
                                    viewModel.setSortOrder(LibrarySortOrder.SIZE_DESC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Size (Smallest)") },
                                onClick = {
                                    viewModel.setSortOrder(LibrarySortOrder.SIZE_ASC)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Main Content Area
            if (uiState.items.isEmpty()) {
                TeledownEmptyState(
                    icon = Icons.Default.VideoLibrary,
                    title = "No Local Media Found",
                    description = "Videos and photos downloaded from Terabox will be safely saved and indexed here.",
                    actionButtonText = "Inspect a Share Link",
                    onActionClick = onNavigateToHome
                )
            } else if (uiState.filteredAndSortedItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Matching Files",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No downloaded files match your active query or filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.clearSearchAndFilters() }) {
                        Text("Clear Search & Filters")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = uiState.filteredAndSortedItems,
                        key = { it.id }
                    ) { item ->
                        LocalMediaGridItem(
                            item = item,
                            onClick = {
                                if (item.existsOnDisk) {
                                    if (item.mediaType == MediaType.VIDEO) {
                                        onPlayMedia?.invoke(item)
                                    } else {
                                        openExternalFile(context, item)
                                    }
                                }
                            },
                            onRename = { viewModel.setItemToRename(item) },
                            onDelete = { viewModel.setItemToDelete(item) },
                            onShare = { shareFile(context, item) },
                            onOpen = { openExternalFile(context, item) }
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    uiState.itemToRename?.let { item ->
        var renameText by remember(item.id) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { viewModel.setItemToRename(null) },
            title = { Text("Rename File") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.renameMedia(item.id, renameText) },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setItemToRename(null) }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog with explicit distinction
    uiState.itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { viewModel.setItemToDelete(null) },
            title = { Text("Delete or Remove File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Choose whether to delete the physical file from device storage, or remove its library entry only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { viewModel.deleteMediaFileAndRecord(item.id) }
                ) {
                    Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete from Disk")
                }
            },
            dismissButton = {
                Row {
                    OutlinedButton(
                        onClick = { viewModel.deleteMediaRecordOnly(item.id) }
                    ) {
                        Text("Remove Record Only")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    TextButton(onClick = { viewModel.setItemToDelete(null) }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
private fun LocalMediaGridItem(
    item: LocalMediaItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Thumbnail / Icon Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val icon = when (item.mediaType) {
                    MediaType.VIDEO -> Icons.Default.PlayCircleOutline
                    MediaType.PHOTO -> Icons.Default.Image
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                }
                val iconTint = when (item.mediaType) {
                    MediaType.VIDEO -> MaterialTheme.colorScheme.primary
                    MediaType.PHOTO -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.secondary
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(36.dp)
                )

                // Missing File Badge
                if (!item.existsOnDisk) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(
                                color = StatusError.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Missing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Video Duration Badge
                if (item.durationMs != null && item.existsOnDisk) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = Formatters.formatDuration(item.durationMs),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = Formatters.formatBytes(item.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action Menu Button
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "File actions",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (item.existsOnDisk) {
                            DropdownMenuItem(
                                text = { Text("Open in External App") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showMenu = false
                                    onOpen()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showMenu = false
                                    onShare()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete / Remove") },
                            leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun openExternalFile(context: Context, item: LocalMediaItem) {
    val file = File(item.path)
    val intent = LocalFileActions.getViewIntent(context, file, item.mediaType)
    if (intent != null) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // No app found to open file
        }
    }
}

private fun shareFile(context: Context, item: LocalMediaItem) {
    val file = File(item.path)
    val intent = LocalFileActions.getShareIntent(context, file, item.mediaType)
    if (intent != null) {
        try {
            context.startActivity(android.content.Intent.createChooser(intent, "Share ${item.name}"))
        } catch (_: Exception) {
            // Ignore share failure
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    TeledownTheme {
        Surface {
            LibraryScreen(onNavigateToHome = {})
        }
    }
}
