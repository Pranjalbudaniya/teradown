package com.pranjalbudaniya.teradown.ui.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.pranjalbudaniya.teradown.core.model.LocalMediaItem
import com.pranjalbudaniya.teradown.core.model.MediaType
import com.pranjalbudaniya.teradown.ui.components.Formatters
import com.pranjalbudaniya.teradown.ui.components.TeledownEmptyState
import com.pranjalbudaniya.teradown.ui.components.TeledownTopBar
import com.pranjalbudaniya.teradown.ui.theme.TeledownTheme

@Composable
fun LibraryScreen(
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TeledownTopBar(
                title = "Library",
                subtitle = if (uiState.items.isNotEmpty()) "${uiState.items.size} downloaded files" else "Saved Media"
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter Chips
            if (uiState.items.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.filter == LibraryFilter.ALL,
                        onClick = { viewModel.setFilter(LibraryFilter.ALL) },
                        label = { Text("All (${uiState.items.size})") }
                    )
                    FilterChip(
                        selected = uiState.filter == LibraryFilter.VIDEOS,
                        onClick = { viewModel.setFilter(LibraryFilter.VIDEOS) },
                        label = { Text("Videos (${uiState.videoCount})") }
                    )
                    FilterChip(
                        selected = uiState.filter == LibraryFilter.PHOTOS,
                        onClick = { viewModel.setFilter(LibraryFilter.PHOTOS) },
                        label = { Text("Photos (${uiState.photoCount})") }
                    )
                }
            }

            // Empty State (Strictly truthful: no fake media)
            if (uiState.filteredItems.isEmpty()) {
                TeledownEmptyState(
                    icon = Icons.Default.VideoLibrary,
                    title = "No Local Media Found",
                    description = "Videos and photos downloaded from Terabox will be safely saved and indexed here.",
                    actionButtonText = "Inspect a Share Link",
                    onActionClick = onNavigateToHome
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = uiState.filteredItems,
                        key = { it.id }
                    ) { item ->
                        LocalMediaGridItem(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalMediaGridItem(
    item: LocalMediaItem,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable { /* Handled in player phase */ }
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
                Icon(
                    imageVector = if (item.mediaType == MediaType.VIDEO) Icons.Default.PlayCircleOutline else Icons.Default.Image,
                    contentDescription = null,
                    tint = if (item.mediaType == MediaType.VIDEO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(36.dp)
                )

                if (item.durationMs != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
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
