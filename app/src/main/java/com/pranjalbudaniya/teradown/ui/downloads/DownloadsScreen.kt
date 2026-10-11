package com.pranjalbudaniya.teradown.ui.downloads

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pranjalbudaniya.teradown.core.model.DownloadStatus
import com.pranjalbudaniya.teradown.core.model.DownloadTaskItem
import com.pranjalbudaniya.teradown.core.model.MediaType
import com.pranjalbudaniya.teradown.ui.components.Formatters
import com.pranjalbudaniya.teradown.ui.components.TeledownEmptyState
import com.pranjalbudaniya.teradown.ui.components.TeledownTopBar
import com.pranjalbudaniya.teradown.ui.theme.StatusError
import com.pranjalbudaniya.teradown.ui.theme.StatusSuccess
import com.pranjalbudaniya.teradown.ui.theme.StatusWarning
import com.pranjalbudaniya.teradown.ui.theme.TeledownTheme

@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TeledownTopBar(
                title = "Downloads",
                subtitle = if (uiState.activeCount > 0) "${uiState.activeCount} active • ${Formatters.formatSpeed(uiState.totalSpeedBytes)}" else "Queue idle"
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Queue Summary Header Bar
            if (uiState.tasks.isNotEmpty()) {
                QueueSummaryHeader(
                    activeCount = uiState.activeCount,
                    completedCount = uiState.completedCount,
                    failedCount = uiState.failedCount,
                    totalSpeed = uiState.totalSpeedBytes,
                    onRetryFailed = { viewModel.retryFailedTasks() },
                    onClearCompleted = { viewModel.clearCompleted() }
                )
            }

            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.filter == DownloadsFilter.ALL,
                    onClick = { viewModel.setFilter(DownloadsFilter.ALL) },
                    label = { Text("All (${uiState.tasks.size})") }
                )
                FilterChip(
                    selected = uiState.filter == DownloadsFilter.ACTIVE,
                    onClick = { viewModel.setFilter(DownloadsFilter.ACTIVE) },
                    label = { Text("Active (${uiState.activeCount})") }
                )
                FilterChip(
                    selected = uiState.filter == DownloadsFilter.COMPLETED,
                    onClick = { viewModel.setFilter(DownloadsFilter.COMPLETED) },
                    label = { Text("Done (${uiState.completedCount})") }
                )
                FilterChip(
                    selected = uiState.filter == DownloadsFilter.FAILED,
                    onClick = { viewModel.setFilter(DownloadsFilter.FAILED) },
                    label = { Text("Failed (${uiState.failedCount})") }
                )
            }

            // Task List or Empty State
            if (uiState.filteredTasks.isEmpty()) {
                TeledownEmptyState(
                    icon = when (uiState.filter) {
                        DownloadsFilter.FAILED -> Icons.Default.ErrorOutline
                        DownloadsFilter.COMPLETED -> Icons.Default.DownloadDone
                        else -> Icons.Default.Download
                    },
                    title = when (uiState.filter) {
                        DownloadsFilter.FAILED -> "No Failed Downloads"
                        DownloadsFilter.COMPLETED -> "No Completed Downloads Yet"
                        DownloadsFilter.ACTIVE -> "No Active Downloads"
                        DownloadsFilter.ALL -> "Download Queue Empty"
                    },
                    description = when (uiState.filter) {
                        DownloadsFilter.FAILED -> "All tasks are running smoothly or completed."
                        DownloadsFilter.COMPLETED -> "Finished downloads will be listed here."
                        else -> "Inspect a Terabox share link on the Home screen to queue downloads."
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.filteredTasks,
                        key = { it.id }
                    ) { task ->
                        DownloadTaskCard(
                            task = task,
                            onPause = { viewModel.pauseTask(task.id) },
                            onResume = { viewModel.resumeTask(task.id) },
                            onCancel = { viewModel.cancelTask(task.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSummaryHeader(
    activeCount: Int,
    completedCount: Int,
    failedCount: Int,
    totalSpeed: Long,
    onRetryFailed: () -> Unit,
    onClearCompleted: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = Formatters.formatSpeed(totalSpeed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failedCount > 0) {
                    OutlinedButton(
                        onClick = onRetryFailed,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry failed",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry ($failedCount)", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (completedCount > 0) {
                    OutlinedButton(
                        onClick = onClearCompleted,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Clear Done", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
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
            // Row 1: Icon, Title, Status Badge, and Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Media Type Icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (task.mediaType == MediaType.VIDEO) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (task.mediaType == MediaType.VIDEO) Icons.Default.Movie else Icons.Default.Image,
                        contentDescription = null,
                        tint = if (task.mediaType == MediaType.VIDEO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title and Status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (task.status) {
                            DownloadStatus.DOWNLOADING -> "Downloading • ${Formatters.formatSpeed(task.speedBytesPerSec)}"
                            DownloadStatus.QUEUED -> "Queued"
                            DownloadStatus.PAUSED -> "Paused"
                            DownloadStatus.COMPLETED -> "Completed"
                            DownloadStatus.FAILED -> "Failed"
                            DownloadStatus.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (task.status) {
                            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                            DownloadStatus.COMPLETED -> StatusSuccess
                            DownloadStatus.PAUSED -> StatusWarning
                            DownloadStatus.FAILED -> StatusError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Action Controls: Pause, Resume, Cancel
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Pause download",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.QUEUED -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Resume download",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DownloadStatus.COMPLETED, DownloadStatus.CANCELLED -> {
                            // No play/pause needed
                        }
                    }

                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel or remove download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Row 2: Progress bar (for active, queued, or paused tasks)
            if (task.status != DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when (task.status) {
                        DownloadStatus.FAILED -> StatusError
                        DownloadStatus.PAUSED -> StatusWarning
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Row 3: Progress text / error message
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${Formatters.formatBytes(task.downloadedBytes)} / ${Formatters.formatBytes(task.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(task.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Error details if failed
            if (task.status == DownloadStatus.FAILED && task.errorMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusError
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsScreenPreview() {
    TeledownTheme {
        Surface {
            DownloadsScreen()
        }
    }
}
