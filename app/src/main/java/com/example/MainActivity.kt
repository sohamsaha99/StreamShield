package com.example

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon as GraphicsIcon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.browser.BrowserController
import com.example.service.BackgroundPlaybackService
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import com.example.ui.StreamShieldViewModel
import com.example.ui.components.PlaybackSpeedBottomSheet
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: StreamShieldViewModel by viewModels()
    private lateinit var browser: BrowserController
    private var pipJustClosed = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Play/pause button inside the picture-in-picture window. */
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE) PlaybackCommands.send(PlaybackCommand.TOGGLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        browser = BrowserController(this, viewModel)
        handleIncomingIntent(intent)

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_TOGGLE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        observePlaybackForWindow()
        requestNotificationPermissionOnce()

        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val darkTheme = preferences.darkMode

            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            MyApplicationTheme(darkTheme = darkTheme, amoledBlack = preferences.amoledBlackMode) {
                StreamShieldApp(
                    viewModel = viewModel,
                    browser = browser,
                    onEnterPip = { enterPip() },
                    onExit = { exitToBackground() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.data?.toString() ?: return
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            viewModel.closeSettings()
            browser.load(url)
        }
    }

    override fun onResume() {
        super.onResume()
        pipJustClosed = false
    }

    override fun onStop() {
        super.onStop()
        viewModel.flushCountsNow()
        if (pipJustClosed) {
            // The PiP window was dismissed (expanding it resumes instead of stopping): stop watching.
            pipJustClosed = false
            browser.pause()
        } else {
            browser.onActivityStopped()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(pipActionReceiver)
        browser.destroy()
        // The WebView is gone, so leftover media controls would control nothing.
        if (isFinishing) BackgroundPlaybackService.stop(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Picture-in-picture
    // ---------------------------------------------------------------------------------------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP on its own via setAutoEnterEnabled (smoother animation).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (viewModel.preferences.value.autoPipOnLeave && viewModel.videoState.value.isPlaying) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPipMode(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) pipJustClosed = true
        if (isInPictureInPictureMode) {
            viewModel.closeSettings()
            viewModel.dismissSpeedSheet()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            viewModel.showBanner("Picture-in-picture needs Android 8.0 or newer")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            viewModel.showBanner("This device does not support picture-in-picture")
            return
        }
        viewModel.closeSettings()
        // Switch the page to its PiP layout first so the window does not show a frame of app UI.
        viewModel.setPipMode(true)
        val entered = try {
            enterPictureInPictureMode(buildPipParams())
        } catch (_: Exception) {
            false
        }
        if (!entered) {
            viewModel.setPipMode(false)
            viewModel.showBanner("Picture-in-picture is turned off for this app in system settings")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val video = viewModel.videoState.value
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio(video.videoWidth, video.videoHeight))
            .setActions(listOf(playPauseAction(video.isPlaying)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prefs = viewModel.preferences.value
            builder.setAutoEnterEnabled(prefs.autoPipOnLeave && video.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playPauseAction(playing: Boolean): RemoteAction {
        val intent = Intent(ACTION_PIP_TOGGLE).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(
            this, 200, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = if (playing) "Pause" else "Play"
        val icon = GraphicsIcon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        return RemoteAction(icon, label, label, pending)
    }

    /** Keeps PiP params (aspect ratio, play/pause, auto-enter) and keep-screen-on in sync. */
    private fun observePlaybackForWindow() {
        lifecycleScope.launch {
            combine(viewModel.videoState, viewModel.preferences) { video, prefs ->
                PipInputs(video.isPlaying, video.videoWidth, video.videoHeight, prefs.autoPipOnLeave)
            }.distinctUntilChanged().collect { inputs ->
                if (inputs.playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                ) {
                    try {
                        setPictureInPictureParams(buildPipParams())
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private data class PipInputs(val playing: Boolean, val width: Int, val height: Int, val autoPip: Boolean)

    // ---------------------------------------------------------------------------------------

    /** Back on the root page: keep playing in the background if that is on, otherwise close. */
    private fun exitToBackground() {
        val keepPlaying = viewModel.preferences.value.backgroundPlayEnabled && viewModel.videoState.value.isPlaying
        if (keepPlaying) moveTaskToBack(true) else finish()
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("streamshield_ui", MODE_PRIVATE)
        if (!granted && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val ACTION_PIP_TOGGLE = "com.example.action.PIP_TOGGLE"

        /** Android only accepts PiP aspect ratios between 1:2.39 and 2.39:1. */
        fun pipAspectRatio(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) return Rational(16, 9)
            val ratio = width.toFloat() / height.toFloat()
            return when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 1f / 2.39f -> Rational(100, 239)
                else -> Rational(width, height)
            }
        }
    }
}

@Composable
fun StreamShieldApp(
    viewModel: StreamShieldViewModel,
    browser: BrowserController,
    onEnterPip: () -> Unit,
    onExit: () -> Unit
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    val omniboxInput by viewModel.omniboxInput.collectAsStateWithLifecycle()
    val blockCounts by viewModel.blockCounts.collectAsStateWithLifecycle()
    val lastStart by viewModel.lastStart.collectAsStateWithLifecycle()
    val recentBlocked by viewModel.recentBlocked.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val watchHistory by viewModel.watchHistory.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val showSpeedSheet by viewModel.showSpeedSheet.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val isInPipMode by viewModel.isInPipMode.collectAsStateWithLifecycle()

    // Lowest-priority back handler: the page and settings register their own after this one.
    BackHandler(enabled = !isInPipMode, onBack = onExit)

    val openUrl: (String) -> Unit = { raw ->
        viewModel.resolveOmniboxInput(raw)?.let { url ->
            viewModel.closeSettings()
            browser.load(url)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isInPipMode) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        ) {
            BrowserScreen(
                controller = browser,
                browserState = browserState,
                omniboxInput = omniboxInput,
                preferences = preferences,
                adsBlocked = blockCounts.ads,
                bookmarks = bookmarks,
                isInPipMode = isInPipMode,
                onOmniboxInputChange = viewModel::updateOmniboxInput,
                onOmniboxSubmit = openUrl,
                onToggleAdBlock = viewModel::toggleAdBlock,
                onAddBookmark = viewModel::addCurrentPageBookmark,
                onOpenSpeedSheet = viewModel::openSpeedSheet,
                onToggleBackgroundPlay = viewModel::toggleBackgroundPlay,
                onEnterPip = onEnterPip,
                onOpenSettings = viewModel::openSettings
            )

            // Settings slide over the browser; the WebView stays alive (and playing) underneath.
            AnimatedVisibility(
                visible = showSettings && !isInPipMode,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                SettingsScreen(
                    preferences = preferences,
                    blockCounts = blockCounts,
                    lastStart = lastStart,
                    recentBlocked = recentBlocked,
                    bookmarks = bookmarks,
                    watchHistory = watchHistory,
                    onClose = viewModel::closeSettings,
                    onToggleAdBlock = viewModel::toggleAdBlock,
                    onToggleSponsorSkip = viewModel::toggleSponsorSkip,
                    onToggleBackgroundPlay = viewModel::toggleBackgroundPlay,
                    onToggleAutoPip = viewModel::toggleAutoPip,
                    onOpenSpeedSheet = viewModel::openSpeedSheet,
                    onToggleDarkMode = viewModel::toggleDarkMode,
                    onToggleAmoledBlack = viewModel::toggleAmoledBlack,
                    onResetCounts = viewModel::resetBlockCounts,
                    onOpenUrl = openUrl,
                    onDeleteBookmark = viewModel::deleteBookmark,
                    onClearHistory = viewModel::clearWatchHistory
                )
            }

            AnimatedVisibility(
                visible = statusBanner != null && !isInPipMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
            ) {
                statusBanner?.let { message ->
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
                            Text(text = message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    if (showSpeedSheet && !isInPipMode) {
        PlaybackSpeedBottomSheet(
            currentSpeed = preferences.playbackSpeed,
            rememberSpeed = preferences.rememberSpeedAcrossVideos,
            onSpeedSelected = viewModel::setPlaybackSpeed,
            onToggleRememberSpeed = viewModel::toggleRememberSpeed,
            onDismiss = viewModel::dismissSpeedSheet
        )
    }
}
