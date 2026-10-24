package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.FeedItemEntity
import com.example.data.SubscriptionEntity
import com.example.data.formatAgo
import com.example.data.formatCount
import com.example.ui.DownloadTarget
import com.example.ui.FeedRefreshState
import com.example.ui.components.MenuAction
import androidx.compose.material.icons.filled.Download
import com.example.ui.components.ChannelAvatar
import com.example.ui.components.EmptyState
import com.example.ui.components.VideoRow
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ShieldCoral

/**
 * Local subscriptions: channel row (tap = channel page, long-press = star / unsubscribe; starred
 * channels come first and get a boost in Home's "For you") and the chronological feed built from
 * each channel's RSS feed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubscriptionsScreen(
    subscriptions: List<SubscriptionEntity>,
    starred: Set<String>,
    feed: List<FeedItemEntity>,
    refreshState: FeedRefreshState,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onOpenChannel: (channelId: String) -> Unit,
    onUnsubscribe: (channelId: String) -> Unit,
    onToggleStar: (channelId: String) -> Unit,
    onOpenVideo: (videoId: String) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmUnsubscribe by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    // Starred channels first, otherwise as before.
    val ordered = remember(subscriptions, starred) { subscriptions.sortedByDescending { it.channelId in starred } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Subscriptions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onImport, modifier = Modifier.testTag("subscriptions_import")) {
                Icon(Icons.Default.FileUpload, contentDescription = "Import from Google Takeout")
            }
            if (refreshState.running) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                Spacer12()
            } else {
                IconButton(onClick = onRefresh, enabled = subscriptions.isNotEmpty(), modifier = Modifier.testTag("subscriptions_refresh")) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }
        refreshState.lastMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = AmberWarning,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (subscriptions.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Subscriptions,
                title = "No subscriptions",
                message = "Subscriptions are stored on this phone only (no Google account). Open a video and tap " +
                    "Save → Subscribe, or import the subscriptions.csv from Google Takeout (YouTube and YouTube Music data)."
            ) {
                OutlinedButton(onClick = onImport) { Text("Import subscriptions.csv") }
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(ordered, key = { it.channelId }) { sub ->
                        val isStarred = sub.channelId in starred
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(68.dp)
                                .combinedClickable(
                                    onClick = { onOpenChannel(sub.channelId) },
                                    onLongClick = { menuFor = sub.channelId }
                                )
                        ) {
                            Box {
                                ChannelAvatar(sub.name)
                                if (isStarred) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "Starred",
                                        tint = ShieldCoral,
                                        modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                                    )
                                }
                                DropdownMenu(expanded = menuFor == sub.channelId, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text(if (isStarred) "Remove star" else "Star (more in For you)") },
                                        leadingIcon = { Icon(if (isStarred) Icons.Default.StarBorder else Icons.Default.Star, contentDescription = null) },
                                        onClick = {
                                            menuFor = null
                                            onToggleStar(sub.channelId)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Unsubscribe") },
                                        leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null) },
                                        onClick = {
                                            menuFor = null
                                            confirmUnsubscribe = sub
                                        }
                                    )
                                }
                            }
                            Text(
                                sub.name.ifBlank { "Channel" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = if (sub.stale) AmberWarning else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            if (starred.isEmpty()) {
                item {
                    Text(
                        "Long-press a channel to star it: starred channels show up more in For you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
            if (feed.isEmpty()) {
                item {
                    Text(
                        if (refreshState.running) "Loading latest uploads…" else "No uploads loaded yet. Pull down to refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(feed, key = { it.videoId }) { item ->
                VideoRow(
                    videoId = item.videoId,
                    title = item.title,
                    subtitle = listOfNotNull(
                        item.channelName.takeIf { it.isNotBlank() },
                        item.viewCount.takeIf { it > 0 }?.let { "${formatCount(it)} views" },
                        formatAgo(item.publishedAt).takeIf { it.isNotBlank() }
                    ).joinToString(" • "),
                    durationSec = 0,
                    onClick = { onOpenVideo(item.videoId) },
                    menu = listOf(
                        MenuAction("Download", Icons.Default.Download) {
                            onDownload(DownloadTarget(item.videoId, item.title, item.channelName, item.channelId))
                        }
                    )
                )
            }
        }
    }

    confirmUnsubscribe?.let { sub ->
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = null },
            title = { Text("Unsubscribe?") },
            text = { Text("Remove ${sub.name.ifBlank { "this channel" }} and its videos from your feed.") },
            confirmButton = {
                TextButton(onClick = {
                    onUnsubscribe(sub.channelId)
                    confirmUnsubscribe = null
                }) { Text("Unsubscribe") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Spacer12() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp).height(1.dp))
}
