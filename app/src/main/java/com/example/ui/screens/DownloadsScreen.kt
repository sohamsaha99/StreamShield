package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.formatBytesHuman
import com.example.data.formatDurationSeconds
import com.example.engine.StorageStats
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    storageStats: StorageStats,
    onPlayDownload: (DownloadEntity) -> Unit,
    onPlayInPip: (DownloadEntity) -> Unit,
    onPauseDownload: (Long) -> Unit,
    onResumeDownload: (Long) -> Unit,
    onDeleteDownload: (DownloadEntity) -> Unit,
    onExploreStreams: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableIntStateOf(0) } // 0=All, 1=Video, 2=Audio, 3=In Progress

    val filteredDownloads = remember(downloads, selectedFilter) {
        when (selectedFilter) {
            1 -> downloads.filter { !it.isAudioOnly }
            2 -> downloads.filter { it.isAudioOnly }
            3 -> downloads.filter { it.status != DownloadStatus.COMPLETED.name }
            else -> downloads
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Storage Space Optimization Dashboard Card
        item {
            StorageOptimizerCard(storageStats = storageStats, downloadCount = downloads.size)
        }

        // 2. Filter Chips Row
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    0 to "All (${downloads.size})",
                    1 to "Video MP4/WebM (${downloads.count { !it.isAudioOnly }})",
                    2 to "Audio M4A/Opus (${downloads.count { it.isAudioOnly }})",
                    3 to "Active (${downloads.count { it.status != DownloadStatus.COMPLETED.name }})"
                ).forEach { (idx, title) ->
                    item {
                        FilterChip(
                            selected = selectedFilter == idx,
                            onClick = { selectedFilter = idx },
                            label = { Text(title, style = MaterialTheme.typography.labelLarge) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ShieldCoral,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("downloads_filter_chip_$idx")
                        )
                    }
                }
            }
        }

        // 3. Empty State or Download Items
        if (filteredDownloads.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(ShieldCoral.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = ShieldCoral,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Offline Media in This View",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Download videos in 1080p MP4, 720p MP4, compact 480p WebM, 360p Data Saver, or Audio-only M4A/Opus to watch offline anytime.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = onExploreStreams,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ShieldCoral,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("explore_streams_button")
                        ) {
                            Icon(imageVector = Icons.Default.Explore, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Browse Videos to Download")
                        }
                    }
                }
            }
        } else {
            items(filteredDownloads, key = { it.id }) { entity ->
                DownloadItemCard(
                    entity = entity,
                    onPlay = { onPlayDownload(entity) },
                    onPlayPip = { onPlayInPip(entity) },
                    onPause = { onPauseDownload(entity.id) },
                    onResume = { onResumeDownload(entity.id) },
                    onDelete = { onDeleteDownload(entity) }
                )
            }
        }
    }
}

@Composable
private fun StorageOptimizerCard(
    storageStats: StorageStats,
    downloadCount: Int
) {
    val usedRatio = if (storageStats.totalDeviceBytes > 0L) {
        ((storageStats.totalDeviceBytes - storageStats.freeDeviceBytes).toFloat() /
            storageStats.totalDeviceBytes.toFloat()).coerceIn(0.05f, 0.95f)
    } else 0.35f

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage_optimizer_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Offline Storage Optimizer",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$downloadCount saved items • Multi-codec compression active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = EmeraldSafe.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Saved ${formatBytesHuman(storageStats.totalSavedByCompressionBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldSafe,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { usedRatio },
                color = ShieldCoral,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "App Downloads: ${formatBytesHuman(storageStats.appDownloadsUsedBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShieldCoral
                )
                Text(
                    text = "Device Free: ${formatBytesHuman(storageStats.freeDeviceBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    entity: DownloadEntity,
    onPlay: () -> Unit,
    onPlayPip: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    val isCompleted = entity.status == DownloadStatus.COMPLETED.name
    val isDownloading = entity.status == DownloadStatus.DOWNLOADING.name

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_item_card_${entity.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail Preview
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 60.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = entity.thumbnailUrl,
                        contentDescription = entity.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = formatDurationSeconds(entity.durationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entity.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Quality & Container Badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (entity.isAudioOnly) CyberCyan.copy(alpha = 0.16f)
                            else ShieldCoral.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (entity.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.VideoFile,
                                    contentDescription = null,
                                    tint = if (entity.isAudioOnly) CyberCyan else ShieldCoral,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${entity.qualityBadge} • ${entity.formatContainer}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entity.isAudioOnly) CyberCyan else ShieldCoral
                                )
                            }
                        }

                        Text(
                            text = if (isCompleted) {
                                formatBytesHuman(entity.totalBytes)
                            } else {
                                "${formatBytesHuman(entity.downloadedBytes)} / ${formatBytesHuman(entity.totalBytes)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action Icons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCompleted) {
                        FilledTonalIconButton(
                            onClick = onPlay,
                            modifier = Modifier.testTag("play_offline_button_${entity.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play offline",
                                tint = EmeraldSafe
                            )
                        }
                        IconButton(
                            onClick = onPlayPip,
                            modifier = Modifier.testTag("pip_offline_button_${entity.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Play in Picture-in-Picture",
                                tint = CyberCyan
                            )
                        }
                    } else if (isDownloading) {
                        FilledTonalIconButton(
                            onClick = onPause,
                            modifier = Modifier.testTag("pause_download_button_${entity.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause download",
                                tint = AmberWarning
                            )
                        }
                    } else {
                        FilledTonalIconButton(
                            onClick = onResume,
                            modifier = Modifier.testTag("resume_download_button_${entity.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Resume download",
                                tint = CyberCyan
                            )
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_download_button_${entity.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete offline file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!isCompleted) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { entity.progressFraction },
                    color = if (isDownloading) CyberCyan else AmberWarning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}
