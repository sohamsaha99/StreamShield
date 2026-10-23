package com.example

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppTab
import com.example.data.formatSpeedBadge
import com.example.ui.StreamShieldViewModel
import com.example.ui.components.DownloadQualityBottomSheet
import com.example.ui.components.NativePlayerOverlay
import com.example.ui.components.PlaybackSpeedBottomSheet
import com.example.ui.screens.BraveBrowserScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.ShieldsAndSettingsScreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ShieldCoral

class MainActivity : ComponentActivity() {

    private val viewModel: StreamShieldViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            MyApplicationTheme(
                darkTheme = preferences.darkMode,
                amoledBlack = preferences.amoledBlackMode
            ) {
                StreamShieldApp(
                    viewModel = viewModel,
                    onRequestPipMode = { triggerPictureInPicture() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val dataUri = intent?.data?.toString()
        if (!dataUri.isNullOrBlank() && (dataUri.contains("youtube.com") || dataUri.contains("youtu.be"))) {
            viewModel.submitOmniboxQueryOrUrl(dataUri)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val prefs = viewModel.preferences.value
        val bState = viewModel.browserState.value
        val onWatchPage = bState.currentUrl.contains("/watch") || bState.currentUrl.contains("/shorts")
        val isPlayingVideo = viewModel.isNativePlaying.value || bState.isWebVideoPlaying || onWatchPage
        if (prefs.autoPipOnLeave && isPlayingVideo) {
            triggerPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPipMode(isInPictureInPictureMode)
    }

    private fun triggerPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Switch to Brave Browser tab if no offline video is playing so PiP shows the active YouTube stream
                if (viewModel.activePlayback.value == null && viewModel.selectedTab.value != AppTab.BRAVE_BROWSER) {
                    viewModel.selectTab(AppTab.BRAVE_BROWSER)
                }
                viewModel.setPipMode(true)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                val entered = enterPictureInPictureMode(params)
                if (!entered) {
                    viewModel.setPipMode(false)
                    viewModel.showBanner("Picture-in-Picture floating window ready")
                }
            } catch (_: Exception) {
                viewModel.setPipMode(false)
                viewModel.showBanner("Picture-in-Picture mode requested")
            }
        } else {
            viewModel.showBanner("Picture-in-Picture requires Android 8.0+")
        }
    }
}

@Composable
fun StreamShieldApp(
    viewModel: StreamShieldViewModel,
    onRequestPipMode: () -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val allDownloads by viewModel.allDownloads.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val watchHistory by viewModel.watchHistory.collectAsStateWithLifecycle()
    val activePlayback by viewModel.activePlayback.collectAsStateWithLifecycle()
    val isNativePlaying by viewModel.isNativePlaying.collectAsStateWithLifecycle()
    val isNativeMinimized by viewModel.isNativeMinimized.collectAsStateWithLifecycle()
    val downloadSheetTarget by viewModel.downloadSheetTarget.collectAsStateWithLifecycle()
    val showSpeedSheet by viewModel.showSpeedSheet.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val isInPipMode by viewModel.isInPipMode.collectAsStateWithLifecycle()

    // BackHandler for non-home tabs or expanded native player
    BackHandler(enabled = activePlayback != null && !isNativeMinimized && !isInPipMode) {
        viewModel.toggleMinimizeNativePlayer(true)
    }
    BackHandler(enabled = (activePlayback == null || isNativeMinimized) && selectedTab != AppTab.BRAVE_BROWSER && !isInPipMode) {
        viewModel.selectTab(AppTab.BRAVE_BROWSER)
    }

    Scaffold(
        contentWindowInsets = if (isInPipMode) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
        topBar = {
            if (!isInPipMode && selectedTab != AppTab.BRAVE_BROWSER) {
                StreamShieldTopBar(
                    currentTab = selectedTab,
                    darkMode = preferences.darkMode,
                    playbackSpeed = preferences.playbackSpeed,
                    adsBlockedCount = preferences.adsBlockedCount + browserState.sessionBlockedCount,
                    onToggleDarkMode = { viewModel.toggleDarkMode(!preferences.darkMode) },
                    onOpenSpeedSheet = { viewModel.openSpeedSheet() },
                    onEnterPip = onRequestPipMode
                )
            }
        },
        bottomBar = {
            if (!isInPipMode) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    val navItems = listOf(
                        Triple(AppTab.BRAVE_BROWSER, R.string.nav_browser, Icons.Default.Public to Icons.Outlined.Public),
                        Triple(AppTab.DOWNLOADS, R.string.nav_downloads, Icons.Default.CloudDownload to Icons.Outlined.CloudDownload),
                        Triple(AppTab.SHIELDS_SETTINGS, R.string.nav_shields, Icons.Default.Shield to Icons.Outlined.Shield)
                    )

                    navItems.forEach { (tab, labelRes, icons) ->
                        val isSelected = selectedTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { viewModel.selectTab(tab) },
                            icon = {
                                if (tab == AppTab.DOWNLOADS && allDownloads.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(containerColor = ShieldCoral, contentColor = Color.White) {
                                                Text("${allDownloads.size}")
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) icons.first else icons.second,
                                            contentDescription = stringResource(labelRes)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (isSelected) icons.first else icons.second,
                                        contentDescription = stringResource(labelRes)
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ShieldCoral,
                                selectedTextColor = ShieldCoral,
                                indicatorColor = ShieldCoral.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isInPipMode) PaddingValues(0.dp) else innerPadding)
        ) {
            // Keep BraveBrowserScreen mounted at a consistent tree location so WebView never resets on PiP
            val showBrowserScreen = selectedTab == AppTab.BRAVE_BROWSER || (isInPipMode && activePlayback == null)

            if (showBrowserScreen) {
                BraveBrowserScreen(
                    browserState = browserState,
                    preferences = preferences,
                    bookmarks = bookmarks,
                    isInPipMode = isInPipMode,
                    onOmniboxInputChange = viewModel::updateOmniboxInput,
                    onOmniboxSubmit = viewModel::submitOmniboxQueryOrUrl,
                    onPageChanged = viewModel::onWebPageChanged,
                    onVideoMetadataDetected = viewModel::onWebVideoMetadataDetected,
                    onAdOrTrackerBlocked = viewModel::onAdOrTrackerBlocked,
                    onOpenSpeedSheet = viewModel::openSpeedSheet,
                    onOpenDownloadSheet = viewModel::openDownloadSheetForCurrentWebVideo,
                    onToggleAdBlock = viewModel::toggleAdBlock,
                    onToggleBackgroundPlay = viewModel::toggleBackgroundPlay,
                    onEnterPipMode = onRequestPipMode,
                    onAddBookmark = viewModel::addCurrentPageBookmark
                )
            } else if (selectedTab == AppTab.DOWNLOADS) {
                DownloadsScreen(
                    downloads = allDownloads,
                    storageStats = storageStats,
                    onPlayDownload = { entity ->
                        viewModel.playDownloadedItem(entity)
                    },
                    onPlayInPip = { entity ->
                        viewModel.playDownloadedItem(entity)
                        onRequestPipMode()
                    },
                    onPauseDownload = viewModel::pauseDownload,
                    onResumeDownload = viewModel::resumeDownload,
                    onDeleteDownload = viewModel::deleteDownload,
                    onExploreStreams = { viewModel.selectTab(AppTab.BRAVE_BROWSER) }
                )
            } else if (selectedTab == AppTab.SHIELDS_SETTINGS) {
                ShieldsAndSettingsScreen(
                    preferences = preferences,
                    watchHistory = watchHistory,
                    onToggleDarkMode = viewModel::toggleDarkMode,
                    onToggleAmoledBlack = viewModel::toggleAmoledBlack,
                    onToggleAdBlock = viewModel::toggleAdBlock,
                    onToggleSponsorSkip = viewModel::toggleSponsorSkip,
                    onToggleBackgroundPlay = viewModel::toggleBackgroundPlay,
                    onToggleAutoPip = viewModel::toggleAutoPip,
                    onOpenSpeedSheet = viewModel::openSpeedSheet,
                    onSelectDefaultQuality = viewModel::setDefaultDownloadQuality,
                    onOpenHistoryUrl = { history ->
                        viewModel.openVideoInBraveBrowser(
                            youtubeUrl = history.videoUrl,
                            title = history.title,
                            channel = history.channelName,
                            durationSec = 300
                        )
                    },
                    onClearHistory = viewModel::clearWatchHistory
                )
            }

            // Native Video / Offline Audio Player Overlay (Expanded, Docked Mini-Player, or Full-Bleed PiP)
            AnimatedVisibility(
                visible = activePlayback != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                activePlayback?.let { item ->
                    NativePlayerOverlay(
                        item = item,
                        isPlaying = isNativePlaying,
                        isMinimized = isNativeMinimized,
                        isInPipMode = isInPipMode,
                        playbackSpeed = preferences.playbackSpeed,
                        backgroundPlayEnabled = preferences.backgroundPlayEnabled,
                        onPlayPauseChange = viewModel::setNativePlaying,
                        onMinimizeToggle = viewModel::toggleMinimizeNativePlayer,
                        onOpenSpeedSheet = viewModel::openSpeedSheet,
                        onEnterPip = onRequestPipMode,
                        onToggleBackgroundPlay = viewModel::toggleBackgroundPlay,
                        onClosePlayer = viewModel::closeNativePlayer
                    )
                }
            }

            // Floating Status Toast / Banner
            AnimatedVisibility(
                visible = statusBanner != null && !isInPipMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp)
            ) {
                statusBanner?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .clickable { viewModel.dismissBanner() }
                            .testTag("status_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = EmeraldSafe,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet: Playback Speed Customizer
    if (showSpeedSheet && !isInPipMode) {
        PlaybackSpeedBottomSheet(
            currentSpeed = preferences.playbackSpeed,
            rememberSpeed = preferences.rememberSpeedAcrossVideos,
            onSpeedSelected = viewModel::setPlaybackSpeed,
            onToggleRememberSpeed = viewModel::toggleRememberSpeed,
            onDismiss = viewModel::dismissSpeedSheet
        )
    }

    // Modal Bottom Sheet: Multi-Quality & Multi-Format Downloader
    if (!isInPipMode) {
        downloadSheetTarget?.let { target ->
            DownloadQualityBottomSheet(
                target = target,
                defaultOptionId = preferences.defaultQualityOptionId,
                freeDeviceBytes = storageStats.freeDeviceBytes,
                onConfirmDownload = { formatOption ->
                    viewModel.confirmStartDownload(target, formatOption)
                },
                onDismiss = viewModel::dismissDownloadSheet
            )
        }
    }
}

@Composable
private fun StreamShieldTopBar(
    currentTab: AppTab,
    darkMode: Boolean,
    playbackSpeed: Float,
    adsBlockedCount: Long,
    onToggleDarkMode: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onEnterPip: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(listOf(ShieldCoral, CyberCyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = when (currentTab) {
                            AppTab.DOWNLOADS -> "Offline Vault"
                            AppTab.SHIELDS_SETTINGS -> "Shields & Speed"
                            AppTab.BRAVE_BROWSER -> "Brave Tube"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "$adsBlockedCount ads & trackers blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Speed Pill
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .clickable { onOpenSpeedSheet() }
                        .testTag("topbar_speed_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Playback Speed",
                            tint = ShieldCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatSpeedBadge(playbackSpeed),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Picture-in-Picture IconButton
                IconButton(
                    onClick = onEnterPip,
                    modifier = Modifier.testTag("topbar_pip_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureInPictureAlt,
                        contentDescription = "Picture-in-Picture Mode",
                        tint = CyberCyan
                    )
                }

                // Dark Mode Toggle IconButton
                IconButton(
                    onClick = onToggleDarkMode,
                    modifier = Modifier.testTag("topbar_dark_mode_toggle")
                ) {
                    Icon(
                        imageVector = if (darkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = if (darkMode) "Switch to Light Mode" else "Switch to Dark Mode",
                        tint = if (darkMode) ShieldCoral else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
