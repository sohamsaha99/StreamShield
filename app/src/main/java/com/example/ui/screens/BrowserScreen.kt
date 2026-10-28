package com.example.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.example.data.thumbnailUrl
import com.example.ui.PULL_REFRESH_THRESHOLD_DP
import com.example.ui.PULL_DRAG_RATE
import com.example.ui.PendingVideo
import com.example.ui.WebPull
import com.example.ui.components.PullIndicator
import com.example.ui.components.webPullOffsetPx
import com.example.browser.BrowserController
import com.example.data.ShieldPreferencesState
import com.example.data.formatSpeedBadge
import com.example.ui.BrowserUiState
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

/**
 * The player page: the YouTube WebView (YouTube's own header and search stay visible) with a thin
 * loading indicator on top and one row of controls at the bottom (minimize to the tabs, library
 * actions, speed, picture-in-picture, settings). In picture-in-picture only the WebView is shown.
 */
@Composable
fun BrowserScreen(
    controller: BrowserController,
    browserState: BrowserUiState,
    preferences: ShieldPreferencesState,
    isInPipMode: Boolean,
    backEnabled: Boolean,
    actionsEnabled: Boolean,
    onBack: () -> Unit,
    onMinimize: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    /** A video being opened: its thumbnail and title cover the page until it starts. */
    pendingVideo: PendingVideo? = null,
    /** A pull-to-refresh in progress on this page (YouTube Music), and the refresh itself. */
    pull: WebPull? = null,
    refreshing: Boolean = false,
    /** Replaces the player controls (the Music tab shows the app's bottom navigation here). */
    bottomBar: (@Composable () -> Unit)? = null
) {
    // Only while the page is shown full screen; with the tabs on top, Back belongs to them.
    BackHandler(enabled = backEnabled && !isInPipMode, onBack = onBack)
    val density = LocalDensity.current.density

    Column(modifier = modifier.fillMaxSize()) {
        if (!isInPipMode) {
            // Fixed-height slot so the page does not jump when loading starts/stops.
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                if (browserState.isLoading) {
                    LinearProgressIndicator(color = ShieldCoral, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = {
                    // The WebView outlives this composable (it belongs to the activity), so make
                    // sure it is not still attached to a previous host.
                    controller.webView.also { (it.parent as? ViewGroup)?.removeView(it) }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!isInPipMode) {
                // No pointer handling here: touches still reach the page underneath.
                // The last video stays drawn while the placeholder fades out.
                val shownPending = remember { arrayOfNulls<PendingVideo>(1) }
                if (pendingVideo != null) shownPending[0] = pendingVideo
                // Qualified: inside this Box (in a Column) the ColumnScope variant must not be used.
                androidx.compose.animation.AnimatedVisibility(
                    visible = pendingVideo != null,
                    enter = fadeIn(tween(90)),
                    exit = fadeOut(tween(260))
                ) {
                    shownPending[0]?.let { VideoPlaceholder(it) }
                }
                val refreshPull = pull?.takeIf { it.mode == "refresh" }
                PullIndicator(
                    offsetPx = webPullOffsetPx(refreshPull?.distance ?: 0f, refreshing, density),
                    progress = (refreshPull?.distance ?: 0f) * PULL_DRAG_RATE / PULL_REFRESH_THRESHOLD_DP,
                    refreshing = refreshing
                )
            }
        }

        if (!isInPipMode && bottomBar != null) {
            bottomBar()
        } else if (!isInPipMode) {
            PlayerControlBar(
                playbackSpeed = preferences.playbackSpeed,
                actionsEnabled = actionsEnabled,
                onMinimize = onMinimize,
                onOpenActions = onOpenActions,
                onOpenSpeedSheet = onOpenSpeedSheet,
                onEnterPip = onEnterPip,
                onOpenSettings = onOpenSettings,
                onDownload = onDownload
            )
        }
    }
}

/**
 * What the video page will look like (YouTube's top bar height, the 16:9 thumbnail, the title)
 * while YouTube loads it, so opening a video does not flash an empty or old page.
 */
@Composable
private fun VideoPlaceholder(video: PendingVideo) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = thumbnailUrl(video.videoId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
        }
        if (video.title.isNotBlank()) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
            )
        }
        if (video.channel.isNotBlank()) {
            Text(
                video.channel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PlayerControlBar(
    playbackSpeed: Float,
    actionsEnabled: Boolean,
    onMinimize: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    label = "Minimize",
                    active = false,
                    activeColor = ShieldCoral,
                    onClick = onMinimize,
                    testTag = "browser_minimize_button"
                )
                ControlButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Save",
                    active = false,
                    activeColor = EmeraldSafe,
                    onClick = { if (actionsEnabled) onOpenActions() },
                    testTag = "browser_actions_button",
                    enabled = actionsEnabled
                )
                ControlButton(
                    icon = Icons.Default.Download,
                    label = "Download",
                    active = false,
                    activeColor = EmeraldSafe,
                    onClick = { if (actionsEnabled) onDownload() },
                    testTag = "browser_download_button",
                    enabled = actionsEnabled
                )
                ControlButton(
                    icon = Icons.Default.Speed,
                    label = formatSpeedBadge(playbackSpeed),
                    active = playbackSpeed != 1.0f,
                    activeColor = ShieldCoral,
                    onClick = onOpenSpeedSheet,
                    testTag = "browser_speed_button"
                )
                ControlButton(
                    icon = Icons.Default.PictureInPictureAlt,
                    label = "Pop out",
                    active = false,
                    activeColor = CyberCyan,
                    onClick = onEnterPip,
                    testTag = "browser_pip_button"
                )
                ControlButton(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    active = false,
                    activeColor = ShieldCoral,
                    onClick = onOpenSettings,
                    testTag = "browser_settings_button"
                )
            }
        }
    }
}

/** One consistent style for every control in the bottom bar. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        active -> activeColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
