package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DownloadFormatCatalog
import com.example.data.ShieldPreferencesState
import com.example.data.WatchHistoryEntity
import com.example.data.formatSpeedBadge
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShieldsAndSettingsScreen(
    preferences: ShieldPreferencesState,
    watchHistory: List<WatchHistoryEntity>,
    onToggleDarkMode: (Boolean) -> Unit,
    onToggleAmoledBlack: (Boolean) -> Unit,
    onToggleAdBlock: (Boolean) -> Unit,
    onToggleSponsorSkip: (Boolean) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onToggleAutoPip: (Boolean) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onSelectDefaultQuality: (String) -> Unit,
    onOpenHistoryUrl: (WatchHistoryEntity) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Brave Shield & Playback Capabilities Section
        item {
            SettingsSectionCard(title = "Brave Shield & Multitasking Engine") {
                SettingToggleRow(
                    icon = Icons.Default.Shield,
                    iconTint = ShieldCoral,
                    title = "YouTube Ad & Tracker Shield",
                    subtitle = "Blocks video pre-rolls, mid-rolls, banner ads, and telemetry domains",
                    checked = preferences.adBlockEnabled,
                    onCheckedChange = onToggleAdBlock,
                    testTag = "setting_adblock_switch"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                SettingToggleRow(
                    icon = Icons.Default.FastForward,
                    iconTint = CyberCyan,
                    title = "Auto-Skip Ad & Sponsor Segments",
                    subtitle = "Automatically clicks skip buttons and fast-forwards promotional segments",
                    checked = preferences.sponsorSkipEnabled,
                    onCheckedChange = onToggleSponsorSkip,
                    testTag = "setting_sponsor_skip_switch"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                SettingToggleRow(
                    icon = Icons.Default.Headphones,
                    iconTint = EmeraldSafe,
                    title = "Background Audio Playback",
                    subtitle = "Keep YouTube and offline videos playing when switching apps or locking screen",
                    checked = preferences.backgroundPlayEnabled,
                    onCheckedChange = onToggleBackgroundPlay,
                    testTag = "setting_bg_play_switch"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                SettingToggleRow(
                    icon = Icons.Default.PictureInPictureAlt,
                    iconTint = CyberCyan,
                    title = "Auto Picture-in-Picture (PiP)",
                    subtitle = "Automatically float video into a corner window when pressing Home",
                    checked = preferences.autoPipOnLeave,
                    onCheckedChange = onToggleAutoPip,
                    testTag = "setting_auto_pip_switch"
                )
            }
        }

        // 2. Dark Mode & Appearance Section
        item {
            SettingsSectionCard(title = "Dark Mode & Visual Theme") {
                SettingToggleRow(
                    icon = Icons.Default.DarkMode,
                    iconTint = ShieldCoral,
                    title = "Sleek Dark Mode",
                    subtitle = "Obsidian low-glare dark interface optimized for cinema & night viewing",
                    checked = preferences.darkMode,
                    onCheckedChange = onToggleDarkMode,
                    testTag = "setting_dark_mode_switch"
                )
                if (preferences.darkMode) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    SettingToggleRow(
                        icon = Icons.Default.Contrast,
                        iconTint = CyberCyan,
                        title = "True AMOLED Pitch Black",
                        subtitle = "Pure #000000 backgrounds to save OLED battery during video playback",
                        checked = preferences.amoledBlackMode,
                        onCheckedChange = onToggleAmoledBlack,
                        testTag = "setting_amoled_switch"
                    )
                }
            }
        }

        // 3. Playback Speed & Default Storage Format Optimization
        item {
            SettingsSectionCard(title = "Playback Speed & Storage Format Defaults") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSpeedSheet() }
                        .padding(vertical = 6.dp)
                        .testTag("setting_open_speed_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = ShieldCoral,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Custom Playback Speed Controller",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Fine-tune from 0.25x to 3.0x in 0.05x increments",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = formatSpeedBadge(preferences.playbackSpeed),
                        style = MaterialTheme.typography.titleMedium,
                        color = ShieldCoral,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Default Offline Quality & Format",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Pre-select your preferred resolution & container to optimize storage space",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DownloadFormatCatalog.ALL_OPTIONS.forEach { option ->
                        val isSelected = preferences.defaultQualityOptionId == option.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectDefaultQuality(option.id) },
                            label = {
                                Text(
                                    text = "${option.qualityBadge} (${option.formatContainer})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ShieldCoral,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("default_quality_chip_${option.id}")
                        )
                    }
                }
            }
        }

        // 4. Recent Watch History
        item {
            SettingsSectionCard(title = "Recent Stream History") {
                if (watchHistory.isEmpty()) {
                    Text(
                        text = "Videos you watch in Brave Tube or the Native Player appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        watchHistory.take(6).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenHistoryUrl(item) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.channelName} • Watched at ${formatSpeedBadge(item.playbackSpeedUsed)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onClearHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clear_history_button")
                        ) {
                            Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Watch History")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ShieldCoral,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
