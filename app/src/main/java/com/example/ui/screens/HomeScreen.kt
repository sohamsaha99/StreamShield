package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import com.example.recommend.ForYouVideo
import com.example.data.SearchHistoryEntity
import com.example.data.WatchedVideo
import com.example.data.formatAgo
import com.example.data.formatCount
import com.example.ui.components.EmptyState
import com.example.ui.components.SectionHeader
import com.example.ui.components.MenuAction
import com.example.ui.DownloadTarget
import androidx.compose.material.icons.filled.Download
import com.example.ui.components.VideoCard
import com.example.ui.components.VideoRow
import com.example.ui.theme.ShieldCoral

/**
 * Home: search, continue watching, "For you" (new uploads from subscriptions mixed with related
 * videos, see ForYouRule) and recently watched. YouTube's own signed-out home page is empty, so
 * this is built from the local library.
 */
@Composable
fun HomeScreen(
    continueWatching: List<WatchedVideo>,
    forYou: List<ForYouVideo>,
    forYouRefreshing: Boolean,
    forYouNote: String,
    history: List<WatchedVideo>,
    recentSearches: List<SearchHistoryEntity>,
    onSearch: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onOpenVideo: (videoId: String, startSec: Int) -> Unit,
    onSeeHistory: () -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    onNotInterested: (ForYouVideo) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search YouTube or paste a link") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    onSearch(query)
                }),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ShieldCoral),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .testTag("home_search_input")
            )
        }

        if (searchFocused && recentSearches.isNotEmpty()) {
            items(recentSearches, key = { "search_" + it.query }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            focusManager.clearFocus()
                            onSearch(entry.query)
                        }
                        .padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        entry.query,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    IconButton(onClick = { onDeleteSearch(entry.query) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove search", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item { SectionHeader("Continue watching") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(continueWatching, key = { it.videoId }) { video ->
                        VideoCard(
                            videoId = video.videoId,
                            title = video.title,
                            subtitle = video.channelName,
                            durationSec = video.durationSec,
                            progress = video.watchedFraction,
                            onClick = { onOpenVideo(video.videoId, video.positionSec) },
                            menu = listOf(downloadAction(video, onDownload))
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("For you") {
                if (forYouRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp).size(18.dp),
                        strokeWidth = 2.dp,
                        color = ShieldCoral
                    )
                }
            }
        }
        if (forYouNote.isNotBlank()) {
            item {
                Text(
                    forYouNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        if (forYou.isEmpty()) {
            item {
                EmptyState(
                    icon = if (forYouRefreshing) Icons.Default.AutoAwesome else Icons.Default.Subscriptions,
                    title = if (forYouRefreshing) "Finding videos for you…" else "Nothing here yet",
                    message = "Built from what you watch and the channels you subscribe to (Subscriptions tab: " +
                        "import from YouTube, or Subscribe on any video). Pull down to refresh."
                )
            }
        } else {
            items(forYou, key = { "foryou_" + it.videoId }) { item ->
                VideoRow(
                    videoId = item.videoId,
                    title = item.title,
                    subtitle = listOfNotNull(
                        item.channelName.takeIf { it.isNotBlank() },
                        item.viewCount.takeIf { it > 0 }?.let { "${formatCount(it)} views" },
                        (if (item.publishedAt > 0) formatAgo(item.publishedAt) else item.publishedText).takeIf { it.isNotBlank() }
                    ).joinToString(" • "),
                    durationSec = item.durationSec,
                    onClick = { onOpenVideo(item.videoId, 0) },
                    menu = listOf(
                        MenuAction("Download", Icons.Default.Download) {
                            onDownload(DownloadTarget(item.videoId, item.title, item.channelName, item.channelId, item.durationSec))
                        },
                        MenuAction("Not interested", Icons.Default.ThumbDown) { onNotInterested(item) }
                    )
                )
            }
        }

        val recent = history.filter { !it.isMusic }.take(12)
        if (recent.isNotEmpty()) {
            item {
                SectionHeader("Recently watched") {
                    TextButton(onClick = onSeeHistory) { Text("History", color = ShieldCoral) }
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(recent, key = { "recent_" + it.videoId }) { video ->
                        VideoCard(
                            videoId = video.videoId,
                            title = video.title,
                            subtitle = video.channelName,
                            durationSec = video.durationSec,
                            progress = video.watchedFraction,
                            width = 160.dp,
                            onClick = { onOpenVideo(video.videoId, 0) },
                            menu = listOf(downloadAction(video, onDownload))
                        )
                    }
                }
            }
        }
    }
}

private fun downloadAction(video: WatchedVideo, onDownload: (DownloadTarget) -> Unit) =
    MenuAction("Download", Icons.Default.Download) {
        onDownload(DownloadTarget(video.videoId, video.title, video.channelName, video.channelId, video.durationSec, video.isMusic))
    }
