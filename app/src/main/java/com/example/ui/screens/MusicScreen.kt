package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.WatchedVideo
import com.example.ui.DownloadTarget
import com.example.ui.components.MenuAction
import com.example.ui.components.SectionHeader
import androidx.compose.material.icons.filled.Download
import com.example.ui.components.VideoRow

/**
 * Shown in the Music tab only until YouTube Music is open (then the tab shows the YouTube Music
 * page itself): while it loads, or when a video is playing and opening YouTube Music would stop it.
 */
@Composable
fun MusicScreen(
    history: List<WatchedVideo>,
    videoPlaying: Boolean,
    playingTitle: String,
    onOpenYouTubeMusic: () -> Unit,
    onOpenTrack: (videoId: String) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val music = history.filter { it.isMusic }.take(30)
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp))
                Text("YouTube Music", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (videoPlaying) {
                    Text(
                        "\"${playingTitle.ifBlank { "A video" }}\" is playing. Opening YouTube Music will stop it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onOpenYouTubeMusic, modifier = Modifier.testTag("music_open_ytm")) {
                        Text("Open YouTube Music")
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Opening…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (music.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            items(music, key = { it.videoId }) { track ->
                VideoRow(
                    videoId = track.videoId,
                    title = track.title,
                    subtitle = track.channelName,
                    durationSec = track.durationSec,
                    thumbnailWidth = 96.dp,
                    onClick = { onOpenTrack(track.videoId) },
                    menu = listOf(
                        MenuAction("Download", Icons.Default.Download) {
                            onDownload(DownloadTarget(track.videoId, track.title, track.channelName, track.channelId, track.durationSec, isMusic = true))
                        }
                    )
                )
            }
        }
    }
}
