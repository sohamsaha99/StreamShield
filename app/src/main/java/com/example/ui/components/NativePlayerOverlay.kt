package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.ActivePlaybackItem
import com.example.data.extractYouTubeVideoId
import com.example.data.formatDurationSeconds
import com.example.data.formatSpeedBadge
import com.example.engine.AdBlockShieldEngine
import com.example.ui.screens.KeepAliveYouTubeWebView
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun NativePlayerOverlay(
    item: ActivePlaybackItem,
    isPlaying: Boolean,
    isMinimized: Boolean,
    isInPipMode: Boolean,
    playbackSpeed: Float,
    backgroundPlayEnabled: Boolean,
    onPlayPauseChange: (Boolean) -> Unit,
    onMinimizeToggle: (Boolean) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ytVideoId = remember(item.id, item.sourcePageUrl) {
        extractYouTubeVideoId(item.sourcePageUrl)
            ?: item.id.takeIf { !it.startsWith("yt_") && it.length == 11 }
    }
    val useEmbeddedYouTubeStream = remember(item.id, item.isLocalFile, ytVideoId) {
        !item.isLocalFile && !ytVideoId.isNullOrBlank()
    }

    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var embeddedWebViewRef by remember { mutableStateOf<KeepAliveYouTubeWebView?>(null) }
    var currentPosSec by remember(item.id) { mutableIntStateOf(0) }
    var totalDurationSec by remember(item.id) { mutableIntStateOf(item.durationSeconds.coerceAtLeast(30)) }

    // Sync playback speed to MediaPlayer or Embedded WebView
    LaunchedEffect(playbackSpeed, mediaPlayerRef, embeddedWebViewRef) {
        mediaPlayerRef?.let { mp ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mp.playbackParams = PlaybackParams().setSpeed(playbackSpeed.coerceIn(0.25f, 3.0f))
                    if (!isPlaying) mp.pause()
                } catch (_: Exception) {
                }
            }
        }
        embeddedWebViewRef?.evaluateJavascript(
            "if(window.setStreamShieldSpeed) window.setStreamShieldSpeed($playbackSpeed); else { var v=document.querySelector('video'); if(v) v.playbackRate=$playbackSpeed; }",
            null
        )
    }

    // Sync play/pause to VideoView or Embedded WebView
    LaunchedEffect(isPlaying, videoViewRef, embeddedWebViewRef) {
        videoViewRef?.let { vv ->
            try {
                if (isPlaying && !vv.isPlaying) vv.start()
                else if (!isPlaying && vv.isPlaying) vv.pause()
            } catch (_: Exception) {
            }
        }
        embeddedWebViewRef?.evaluateJavascript(
            "var v=document.querySelector('video'); if(v) { if($isPlaying) v.play(); else v.pause(); }",
            null
        )
    }

    // Poll playback position
    LaunchedEffect(isPlaying, videoViewRef, useEmbeddedYouTubeStream) {
        while (isPlaying) {
            val vv = videoViewRef
            if (vv != null && !useEmbeddedYouTubeStream) {
                try {
                    val pos = vv.currentPosition / 1000
                    val dur = vv.duration / 1000
                    if (pos >= 0) currentPosSec = pos
                    if (dur > 0) totalDurationSec = dur
                } catch (_: Exception) {
                }
            } else if (useEmbeddedYouTubeStream) {
                currentPosSec = (currentPosSec + 1).coerceAtMost(totalDurationSec)
            }
            delay(1000L)
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            mediaPlayerRef = null
            videoViewRef = null
            embeddedWebViewRef = null
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isInPipMode) Color.Black
            else if (isMinimized) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        shape = when {
            isInPipMode -> RoundedCornerShape(0.dp)
            isMinimized -> RoundedCornerShape(16.dp)
            else -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isInPipMode) 0.dp else 12.dp),
        modifier = modifier
            .then(
                if (isInPipMode) Modifier.fillMaxSize()
                else if (isMinimized) Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onMinimizeToggle(false) }
                    .testTag("mini_player_bar")
                else Modifier
                    .fillMaxWidth()
                    .testTag("expanded_native_player")
            )
    ) {
        Column(modifier = if (isInPipMode) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            // Top Player Bar (Only in Expanded, Non-PiP mode)
            if (!isInPipMode && !isMinimized) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMinimizeToggle(true) }) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Minimize player"
                            )
                        }
                        Surface(
                            color = EmeraldSafe.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "SAVED • ${item.qualityLabel} (${item.formatContainer})",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldSafe,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clickable { onOpenSpeedSheet() }
                                .testTag("native_player_speed_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Playback speed",
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

                        IconButton(
                            onClick = onEnterPip,
                            modifier = Modifier.testTag("native_player_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture-in-Picture mode",
                                tint = CyberCyan
                            )
                        }

                        IconButton(
                            onClick = onClosePlayer,
                            modifier = Modifier.testTag("native_player_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close player"
                            )
                        }
                    }
                }
            }

            // Persistent Video Surface Row/Box (Never unmounted across PiP / Minimized / Expanded!)
            Row(
                modifier = if (isInPipMode) {
                    Modifier.fillMaxSize()
                } else if (isMinimized) {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                } else {
                    Modifier.fillMaxWidth()
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = when {
                        isInPipMode -> Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                        isMinimized -> Modifier
                            .size(width = 72.dp, height = 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                        else -> Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    if (useEmbeddedYouTubeStream && ytVideoId != null) {
                        EmbeddedYouTubePlayerSurface(
                            videoId = ytVideoId,
                            playbackSpeed = playbackSpeed,
                            onWebViewReady = { embeddedWebViewRef = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        NativeVideoSurface(
                            item = item,
                            playbackSpeed = playbackSpeed,
                            isPlaying = isPlaying,
                            onVideoViewReady = { videoViewRef = it },
                            onMediaPlayerReady = { mp ->
                                mediaPlayerRef = mp
                                if (mp.duration > 0) {
                                    totalDurationSec = mp.duration / 1000
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (item.isAudioOnly && !isMinimized && !isInPipMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            ShieldCoral.copy(alpha = 0.35f),
                                            Color(0xFF0A0D16)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = null,
                                    tint = CyberCyan,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Studio Audio-Only Mode (${item.formatContainer})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Mini-Player Controls on the right when minimized
                if (isMinimized && !isInPipMode) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.channelName} • ${item.qualityLabel} • ${formatSpeedBadge(playbackSpeed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { onPlayPauseChange(!isPlaying) }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                    }
                    IconButton(onClick = onEnterPip) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Enter Picture-in-Picture",
                            tint = CyberCyan
                        )
                    }
                    IconButton(onClick = onClosePlayer) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close player"
                        )
                    }
                }
            }

            if (isMinimized && !isInPipMode) {
                LinearProgressIndicator(
                    progress = {
                        if (totalDurationSec > 0) {
                            (currentPosSec.toFloat() / totalDurationSec.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                    },
                    color = ShieldCoral,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }

            // Expanded Transport Controls (Only when expanded and not in PiP)
            if (!isInPipMode && !isMinimized) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Slider(
                        value = currentPosSec.toFloat().coerceIn(0f, totalDurationSec.toFloat().coerceAtLeast(1f)),
                        onValueChange = { newSec ->
                            currentPosSec = newSec.toInt()
                            if (useEmbeddedYouTubeStream) {
                                embeddedWebViewRef?.evaluateJavascript(
                                    "var v=document.querySelector('video'); if(v) v.currentTime=$currentPosSec;",
                                    null
                                )
                            } else {
                                try {
                                    videoViewRef?.seekTo(currentPosSec * 1000)
                                } catch (_: Exception) {
                                }
                            }
                        },
                        valueRange = 0f..totalDurationSec.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = ShieldCoral,
                            activeTrackColor = ShieldCoral
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDurationSeconds(currentPosSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDurationSeconds(totalDurationSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                val target = (currentPosSec - 10).coerceAtLeast(0)
                                currentPosSec = target
                                if (useEmbeddedYouTubeStream) {
                                    embeddedWebViewRef?.evaluateJavascript(
                                        "var v=document.querySelector('video'); if(v) v.currentTime=Math.max(0, v.currentTime-10);",
                                        null
                                    )
                                } else {
                                    try {
                                        videoViewRef?.seekTo(target * 1000)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Rewind 10 seconds"
                            )
                        }

                        IconButton(
                            onClick = { onPlayPauseChange(!isPlaying) },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(ShieldCoral)
                                .testTag("native_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = {
                                val target = (currentPosSec + 10).coerceAtMost(totalDurationSec)
                                currentPosSec = target
                                if (useEmbeddedYouTubeStream) {
                                    embeddedWebViewRef?.evaluateJavascript(
                                        "var v=document.querySelector('video'); if(v) v.currentTime=v.currentTime+10;",
                                        null
                                    )
                                } else {
                                    try {
                                        videoViewRef?.seekTo(target * 1000)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Fast-forward 10 seconds"
                            )
                        }

                        Surface(
                            color = if (backgroundPlayEnabled) EmeraldSafe.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { onToggleBackgroundPlay(!backgroundPlayEnabled) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = "Background Audio",
                                    tint = if (backgroundPlayEnabled) EmeraldSafe else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (backgroundPlayEnabled) "BG Audio ON" else "BG Audio OFF",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (backgroundPlayEnabled) EmeraldSafe else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedYouTubePlayerSurface(
    videoId: String,
    playbackSpeed: Float,
    onWebViewReady: (KeepAliveYouTubeWebView) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            KeepAliveYouTubeWebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(AndroidColor.BLACK)
                keepPlayingInBackground = true
                isInPipWindow = true

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                }

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    private fun injectFullBleedAndShield(view: WebView?) {
                        val js = AdBlockShieldEngine.buildShieldInjectionJs(
                            adBlockEnabled = true,
                            sponsorSkipEnabled = true,
                            backgroundPlayEnabled = true,
                            playbackSpeed = playbackSpeed,
                            isInPipMode = true
                        )
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (AdBlockShieldEngine.evaluateRequest(reqUrl, true).blocked) {
                            return AdBlockShieldEngine.createEmptyResponse()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        injectFullBleedAndShield(view)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        injectFullBleedAndShield(view)
                    }
                }

                loadUrl("https://m.youtube.com/watch?v=$videoId")
                onWebViewReady(this)
            }
        },
        update = { view ->
            onWebViewReady(view)
        },
        modifier = modifier
    )
}

@Composable
private fun NativeVideoSurface(
    item: ActivePlaybackItem,
    playbackSpeed: Float,
    isPlaying: Boolean,
    onVideoViewReady: (VideoView) -> Unit,
    onMediaPlayerReady: (MediaPlayer) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                onVideoViewReady(this)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            mp.playbackParams = PlaybackParams().setSpeed(playbackSpeed.coerceIn(0.25f, 3.0f))
                        } catch (_: Exception) {
                        }
                    }
                    onMediaPlayerReady(mp)
                    if (isPlaying) {
                        start()
                    }
                }
                setOnErrorListener { _, _, _ -> true }
                val uri = if (item.isLocalFile && File(item.mediaUriOrUrl).exists()) {
                    Uri.fromFile(File(item.mediaUriOrUrl))
                } else {
                    Uri.parse(item.mediaUriOrUrl)
                }
                setVideoURI(uri)
            }
        },
        update = { view ->
            onVideoViewReady(view)
        },
        modifier = modifier
    )
}
