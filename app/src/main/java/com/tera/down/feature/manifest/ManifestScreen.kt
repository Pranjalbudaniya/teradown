package com.tera.down.feature.manifest

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tera.down.core.manifest.DownloadManifestItem
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.MediaType
import com.tera.down.ui.components.Formatters
import com.tera.down.ui.components.TeledownEmptyState
import com.tera.down.ui.components.TeledownTopBar
import com.tera.down.ui.theme.StatusError
import com.tera.down.ui.theme.StatusSuccess
import com.tera.down.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ManifestScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManifestViewModel = viewModel(factory = ManifestViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // SAF launchers for JSON and CSV export
    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    viewModel.exportToStream(ManifestExportFormat.JSON, stream)
                } ?: viewModel.reportExportError("Unable to open export destination stream.")
            } catch (e: Exception) {
                viewModel.reportExportError("Export error: ${e.message}")
            }
        } else {
            viewModel.reportExportCancelled()
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    viewModel.exportToStream(ManifestExportFormat.CSV, stream)
                } ?: viewModel.reportExportError("Unable to open export destination stream.")
            } catch (e: Exception) {
                viewModel.reportExportError("Export error: ${e.message}")
            }
        } else {
            viewModel.reportExportCancelled()
        }
    }

    Scaffold(
        topBar = {
            TeledownTopBar(
                title = "Download Manifest",
                subtitle = "${uiState.records.size} total activity records",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState.records.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showClearConfirm(true) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear manifest history",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.exportMessage != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissMessage() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(uiState.exportMessage.orEmpty())
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Action & Export Bar
            ExportActionBar(
                hasRecords = uiState.records.isNotEmpty(),
                isExporting = uiState.isExporting,
                onExportJson = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    jsonLauncher.launch("download_manifest_$timestamp.json")
                },
                onExportCsv = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    csvLauncher.launch("download_manifest_$timestamp.csv")
                }
            )

            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.filter == ManifestFilter.ALL,
                    onClick = { viewModel.setFilter(ManifestFilter.ALL) },
                    label = { Text("All (${uiState.records.size})") }
                )
                FilterChip(
                    selected = uiState.filter == ManifestFilter.COMPLETED,
                    onClick = { viewModel.setFilter(ManifestFilter.COMPLETED) },
                    label = { Text("Done (${uiState.completedCount})") }
                )
                FilterChip(
                    selected = uiState.filter == ManifestFilter.FAILED,
                    onClick = { viewModel.setFilter(ManifestFilter.FAILED) },
                    label = { Text("Failed (${uiState.failedCount})") }
                )
                FilterChip(
                    selected = uiState.filter == ManifestFilter.ACTIVE,
                    onClick = { viewModel.setFilter(ManifestFilter.ACTIVE) },
                    label = { Text("Active (${uiState.activeCount})") }
                )
            }

            // Records List or Empty State
            if (uiState.filteredRecords.isEmpty()) {
                TeledownEmptyState(
                    icon = Icons.Default.FileDownload,
                    title = "No Manifest Records",
                    description = if (uiState.records.isEmpty()) {
                        "Activity records will be created automatically whenever downloads are enqueued."
                    } else {
                        "No manifest records match the selected filter."
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.filteredRecords,
                        key = { it.id }
                    ) { item ->
                        ManifestItemCard(item = item)
                    }
                }
            }
        }
    }

    // Confirmation dialog before clearing manifest history
    if (uiState.showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showClearConfirm(false) },
            title = { Text("Clear Manifest History?") },
            text = {
                Text("This action will erase your local download activity logs. Completed downloads and downloaded files on your device will NOT be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearManifestHistory() }
                ) {
                    Text("Clear Records")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showClearConfirm(false) }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ExportActionBar(
    hasRecords: Boolean,
    isExporting: Boolean,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Export Records:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportJson,
                    enabled = hasRecords && !isExporting,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("JSON", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onExportCsv,
                    enabled = hasRecords && !isExporting,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("CSV", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ManifestItemCard(
    item: DownloadManifestItem
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
                .padding(14.dp)
        ) {
            // Header Row: Type Icon, File Name, and Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.mediaType == MediaType.VIDEO) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.mediaType == MediaType.VIDEO) Icons.Default.Movie else Icons.Default.Photo,
                        contentDescription = null,
                        tint = if (item.mediaType == MediaType.VIDEO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Source: ${item.sourceUrlRedacted}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                ManifestStatusBadge(status = item.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata Row: Size, Transfer Details, Resume, Attempt Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Size: ${Formatters.formatBytes(item.fileSize)} • Received: ${Formatters.formatBytes(item.bytesReceived)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (item.attemptCount > 1) "Attempt ${item.attemptCount}" else "Attempt 1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamps and Resume Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Created: ${formatDate(item.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.resumed) {
                    Text(
                        text = "Resumed: Yes",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (item.completedAt != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Completed: ${formatDate(item.completedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Destination Path
            if (!item.destinationPath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Path: ${item.destinationPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Error details if Failed
            if (item.status == ManifestStatus.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = StatusError.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Error Category: ${item.errorCategory ?: "UNKNOWN"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusError
                        )
                        if (!item.errorMessage.isNullOrBlank()) {
                            Text(
                                text = item.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusError
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManifestStatusBadge(status: ManifestStatus) {
    val (label, containerColor, contentColor) = when (status) {
        ManifestStatus.COMPLETED -> Triple("COMPLETED", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
        ManifestStatus.FAILED -> Triple("FAILED", StatusError.copy(alpha = 0.15f), StatusError)
        ManifestStatus.DOWNLOADING -> Triple("DOWNLOADING", MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.colorScheme.primary)
        ManifestStatus.QUEUED -> Triple("QUEUED", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        ManifestStatus.CANCELLED -> Triple("CANCELLED", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.outline)
        ManifestStatus.INTERRUPTED -> Triple("INTERRUPTED", StatusWarning.copy(alpha = 0.15f), StatusWarning)
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
