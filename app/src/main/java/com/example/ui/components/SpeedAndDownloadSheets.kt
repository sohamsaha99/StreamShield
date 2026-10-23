package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DownloadFormatCatalog
import com.example.data.DownloadFormatOption
import com.example.data.DownloadTargetInfo
import com.example.data.formatBytesHuman
import com.example.data.formatDurationSeconds
import com.example.data.formatSpeedBadge
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSpeedBottomSheet(
    currentSpeed: Float,
    rememberSpeed: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onToggleRememberSpeed: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Playback Speed",
                        tint = ShieldCoral,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Custom Playback Speed",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Applies live to YouTube WebView & Native Player",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = { onSpeedSelected(1.0f) },
                    modifier = Modifier.testTag("reset_speed_button"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset to 1.0x",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("1.0x", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Fine-tune Stepper Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { onSpeedSelected((currentSpeed - 0.05f).coerceAtLeast(0.25f)) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("speed_decrease_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Decrease speed by 0.05x"
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatSpeedBadge(currentSpeed),
                                style = MaterialTheme.typography.displayMedium,
                                color = ShieldCoral
                            )
                            Text(
                                text = when {
                                    currentSpeed < 0.95f -> "Slow-Motion Study Mode"
                                    currentSpeed <= 1.05f -> "Normal Cinema Speed"
                                    currentSpeed <= 1.5f -> "Brisk Lecture / Podcast Pace"
                                    else -> "Hyper-Speed Skimming"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { onSpeedSelected((currentSpeed + 0.05f).coerceAtMost(3.0f)) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("speed_increase_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Increase speed by 0.05x"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Slider(
                        value = currentSpeed,
                        onValueChange = { onSpeedSelected(it) },
                        valueRange = 0.25f..3.0f,
                        steps = 54,
                        colors = SliderDefaults.colors(
                            thumbColor = ShieldCoral,
                            activeTrackColor = ShieldCoral
                        ),
                        modifier = Modifier.testTag("speed_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.25x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1.0x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("2.0x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("3.0x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DownloadFormatCatalog.SPEED_PRESETS.forEach { preset ->
                    val isSelected = abs(currentSpeed - preset) < 0.02f
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpeedSelected(preset) },
                        label = {
                            Text(
                                text = formatSpeedBadge(preset),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ShieldCoral,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("speed_preset_${preset}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lock Speed Across Videos",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Automatically apply ${formatSpeedBadge(currentSpeed)} when navigating to a new YouTube video",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rememberSpeed,
                    onCheckedChange = onToggleRememberSpeed,
                    modifier = Modifier.testTag("remember_speed_switch")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualityBottomSheet(
    target: DownloadTargetInfo,
    defaultOptionId: String,
    freeDeviceBytes: Long,
    onConfirmDownload: (DownloadFormatOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedOption by remember(defaultOptionId) {
        mutableStateOf(
            DownloadFormatCatalog.ALL_OPTIONS.find { it.id == defaultOptionId }
                ?: DownloadFormatCatalog.ALL_OPTIONS[1]
        )
    }
    var filterTab by remember { mutableIntStateOf(0) } // 0 = All, 1 = Video, 2 = Audio Only

    val filteredOptions = remember(filterTab) {
        when (filterTab) {
            1 -> DownloadFormatCatalog.ALL_OPTIONS.filter { !it.isAudioOnly }
            2 -> DownloadFormatCatalog.ALL_OPTIONS.filter { it.isAudioOnly }
            else -> DownloadFormatCatalog.ALL_OPTIONS
        }
    }

    val baseline1080pBytes = DownloadFormatCatalog.ALL_OPTIONS.first().estimateSizeBytes(target.durationSeconds)
    val selectedSizeBytes = selectedOption.estimateSizeBytes(target.durationSeconds)
    val savedBytes = (baseline1080pBytes - selectedSizeBytes).coerceAtLeast(0L)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(ShieldCoral, CyberCyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${target.channelName} • Duration ${formatDurationSeconds(target.durationSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Storage Optimization Live Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SdStorage,
                            contentDescription = "Storage status",
                            tint = CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Free Space: ${formatBytesHuman(freeDeviceBytes)}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = if (savedBytes > 0L) {
                                    "Saves ${formatBytesHuman(savedBytes)} (-${selectedOption.storageSavingsPercent}%) vs 1080p"
                                } else {
                                    "Uncompressed Full HD Master Quality"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (savedBytes > 0L) EmeraldSafe else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = ShieldCoral.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = formatBytesHuman(selectedSizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = ShieldCoral,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Pills: All / Video (MP4 & WebM) / Audio (M4A & Opus)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    0 to "All Formats (6)",
                    1 to "Video (MP4/WebM)",
                    2 to "Audio (M4A/Opus)"
                ).forEach { (idx, label) ->
                    FilterChip(
                        selected = filterTab == idx,
                        onClick = { filterTab = idx },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("download_filter_tab_$idx")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Format / Quality Options List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(270.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredOptions, key = { it.id }) { option ->
                    val isSelected = selectedOption.id == option.id
                    val estSize = option.estimateSizeBytes(target.durationSeconds)

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                            }
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 1.8.dp else 1.dp,
                            color = if (isSelected) ShieldCoral else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = option }
                            .testTag("quality_option_${option.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (option.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.VideoFile,
                                contentDescription = null,
                                tint = if (option.isAudioOnly) CyberCyan else ShieldCoral,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option.qualityBadge,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = option.formatContainer,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberCyan,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (option.storageSavingsPercent > 0) {
                                        Surface(
                                            color = EmeraldSafe.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "-${option.storageSavingsPercent}% space",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = EmeraldSafe,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${option.title} • ${option.codecDescription}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatBytesHuman(estSize),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = ShieldCoral,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { onConfirmDownload(selectedOption) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ShieldCoral,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_download_button")
            ) {
                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Download ${selectedOption.qualityBadge} (${selectedOption.formatContainer}) • ${formatBytesHuman(selectedSizeBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
