package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.BookmarkEntity
import com.example.data.ShieldPreferencesState
import com.example.data.formatSpeedBadge
import com.example.engine.AdBlockShieldEngine
import com.example.ui.BrowserUiState
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

/**
 * Custom WebView that keeps HTML5 video and audio rendering and playing continuously
 * during Picture-in-Picture mode and background screen-off playback.
 */
class KeepAliveYouTubeWebView(context: Context) : WebView(context) {
    var keepPlayingInBackground: Boolean = true
    var isInPipWindow: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if ((keepPlayingInBackground || isInPipWindow) &&
            (visibility == View.GONE || visibility == View.INVISIBLE)
        ) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if ((keepPlayingInBackground || isInPipWindow) && !hasWindowFocus) {
            super.onWindowFocusChanged(true)
        } else {
            super.onWindowFocusChanged(hasWindowFocus)
        }
    }

    override fun onPause() {
        if (keepPlayingInBackground || isInPipWindow) {
            // Do not pause Chromium compositor or media pipeline when in PiP or BG Play
            return
        }
        super.onPause()
    }
}

private class StreamShieldJsBridge(
    private val onAdSkippedCallback: () -> Unit,
    private val onVideoDetectedCallback: (String, String, String, Int, Boolean, String) -> Unit
) {
    @JavascriptInterface
    fun onAdSkipped() {
        onAdSkippedCallback()
    }

    @JavascriptInterface
    fun onVideoStateUpdate(
        title: String?,
        channel: String?,
        url: String?,
        durationSec: Int,
        isPlaying: Boolean,
        directStreamUrl: String?
    ) {
        onVideoDetectedCallback(
            title ?: "YouTube Stream",
            channel ?: "YouTube",
            url ?: "https://m.youtube.com",
            durationSec,
            isPlaying,
            directStreamUrl ?: ""
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BraveBrowserScreen(
    browserState: BrowserUiState,
    preferences: ShieldPreferencesState,
    bookmarks: List<BookmarkEntity>,
    isInPipMode: Boolean,
    onOmniboxInputChange: (String) -> Unit,
    onOmniboxSubmit: (String) -> Unit,
    onPageChanged: (url: String, title: String?, canBack: Boolean, canForward: Boolean, loading: Boolean) -> Unit,
    onVideoMetadataDetected: (title: String, channel: String, url: String, durationSec: Int, isPlaying: Boolean, directStreamUrl: String) -> Unit,
    onAdOrTrackerBlocked: (Boolean) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenDownloadSheet: () -> Unit,
    onToggleAdBlock: (Boolean) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onEnterPipMode: () -> Unit,
    onAddBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var webViewRef by remember { mutableStateOf<KeepAliveYouTubeWebView?>(null) }
    var lastLoadedTargetUrl by remember { mutableStateOf("") }

    BackHandler(enabled = browserState.canGoBack && !isInPipMode) {
        webViewRef?.goBack()
    }

    // Re-inject JS Shield, PiP full-bleed pinning, & Speed whenever state changes
    LaunchedEffect(
        isInPipMode,
        preferences.playbackSpeed,
        preferences.adBlockEnabled,
        preferences.sponsorSkipEnabled,
        preferences.backgroundPlayEnabled
    ) {
        val wv = webViewRef ?: return@LaunchedEffect
        wv.keepPlayingInBackground = preferences.backgroundPlayEnabled
        wv.isInPipWindow = isInPipMode
        if (isInPipMode || preferences.backgroundPlayEnabled) {
            wv.onResume()
            wv.resumeTimers()
        }
        val js = AdBlockShieldEngine.buildShieldInjectionJs(
            adBlockEnabled = preferences.adBlockEnabled,
            sponsorSkipEnabled = preferences.sponsorSkipEnabled,
            backgroundPlayEnabled = preferences.backgroundPlayEnabled,
            playbackSpeed = preferences.playbackSpeed,
            isInPipMode = isInPipMode
        )
        wv.evaluateJavascript(js, null)
    }

    // Navigate when browserState.currentUrl is updated externally
    LaunchedEffect(browserState.currentUrl) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (browserState.currentUrl.isNotBlank() && browserState.currentUrl != lastLoadedTargetUrl) {
            lastLoadedTargetUrl = browserState.currentUrl
            wv.loadUrl(browserState.currentUrl)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Hide browser chrome when in Picture-in-Picture mode
        if (!isInPipMode) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Brave Shield Toggle Pill
                        Surface(
                            color = if (preferences.adBlockEnabled) ShieldCoral.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clickable { onToggleAdBlock(!preferences.adBlockEnabled) }
                                .testTag("shield_status_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "AdBlock Shield",
                                    tint = if (preferences.adBlockEnabled) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${preferences.adsBlockedCount + browserState.sessionBlockedCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (preferences.adBlockEnabled) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // URL / YouTube Search Input
                        OutlinedTextField(
                            value = browserState.omniboxInput,
                            onValueChange = onOmniboxInputChange,
                            placeholder = {
                                Text(
                                    text = "Search YouTube or paste URL…",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { webViewRef?.reload() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Reload page",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    onOmniboxSubmit(browserState.omniboxInput)
                                }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedBorderColor = ShieldCoral,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("browser_omnibox_input")
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = onAddBookmark,
                            modifier = Modifier.testTag("bookmark_page_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "Bookmark current video"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Quick Bookmarks & Navigation Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            enabled = browserState.canGoBack,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { webViewRef?.goForward() },
                            enabled = browserState.canGoForward,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(end = 8.dp)
                        ) {
                            items(bookmarks, key = { it.id }) { bm ->
                                AssistChip(
                                    onClick = { onOmniboxSubmit(bm.url) },
                                    label = {
                                        Text(
                                            text = bm.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    border = null,
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (browserState.isLoading) {
                LinearProgressIndicator(
                    color = ShieldCoral,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
        }

        // Main YouTube WebView with AdBlock, Zero-Skip-Screen Pruning, PiP Pinning & Background Play
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    KeepAliveYouTubeWebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(AndroidColor.BLACK)
                        keepPlayingInBackground = preferences.backgroundPlayEnabled
                        isInPipWindow = isInPipMode

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = false
                            displayZoomControls = false
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        }

                        addJavascriptInterface(
                            StreamShieldJsBridge(
                                onAdSkippedCallback = {
                                    post { onAdOrTrackerBlocked(true) }
                                },
                                onVideoDetectedCallback = { title, channel, url, dur, playing, directStream ->
                                    post {
                                        onVideoMetadataDetected(title, channel, url, dur, playing, directStream)
                                    }
                                }
                            ),
                            "StreamShieldBridge"
                        )

                        webChromeClient = object : WebChromeClient() {}

                        webViewClient = object : WebViewClient() {
                            private fun injectShieldNow(view: WebView?) {
                                val js = AdBlockShieldEngine.buildShieldInjectionJs(
                                    adBlockEnabled = preferences.adBlockEnabled,
                                    sponsorSkipEnabled = preferences.sponsorSkipEnabled,
                                    backgroundPlayEnabled = preferences.backgroundPlayEnabled,
                                    playbackSpeed = preferences.playbackSpeed,
                                    isInPipMode = isInPipWindow
                                )
                                view?.evaluateJavascript(js, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                val decision = AdBlockShieldEngine.evaluateRequest(
                                    url = reqUrl,
                                    adBlockEnabled = preferences.adBlockEnabled
                                )
                                if (decision.blocked) {
                                    view?.post { onAdOrTrackerBlocked(decision.isVideoAd) }
                                    return AdBlockShieldEngine.createEmptyResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // Inject immediately on page start so Object.prototype.adPlacements trap is active before scripts run
                                injectShieldNow(view)
                                if (url != null) {
                                    lastLoadedTargetUrl = url
                                    onPageChanged(
                                        url,
                                        view?.title,
                                        view?.canGoBack() ?: false,
                                        view?.canGoForward() ?: false,
                                        true
                                    )
                                }
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                injectShieldNow(view)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                injectShieldNow(view)
                                if (url != null) {
                                    lastLoadedTargetUrl = url
                                    onPageChanged(
                                        url,
                                        view?.title,
                                        view?.canGoBack() ?: false,
                                        view?.canGoForward() ?: false,
                                        false
                                    )
                                }
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                injectShieldNow(view)
                                if (url != null) {
                                    lastLoadedTargetUrl = url
                                    onPageChanged(
                                        url,
                                        view?.title,
                                        view?.canGoBack() ?: false,
                                        view?.canGoForward() ?: false,
                                        false
                                    )
                                }
                            }
                        }

                        lastLoadedTargetUrl = browserState.currentUrl
                        loadUrl(browserState.currentUrl)
                        webViewRef = this
                    }
                },
                update = { view ->
                    view.keepPlayingInBackground = preferences.backgroundPlayEnabled
                    view.isInPipWindow = isInPipMode
                    webViewRef = view
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom StreamShield Floating Control Bar (Speed, Background Audio, PiP, Multi-Quality Download)
        if (!isInPipMode) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (browserState.isWebVideoPlaying) EmeraldSafe else ShieldCoral)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = browserState.detectedVideoTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${browserState.detectedVideoChannel} • Zero-Ad Shield & BG Play Active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action Pills: Speed, BG Play, PiP, Download
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Playback Speed Pill
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clickable { onOpenSpeedSheet() }
                                .testTag("browser_speed_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
                                    text = formatSpeedBadge(preferences.playbackSpeed),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 2. Background Playback Toggle Pill
                        Surface(
                            color = if (preferences.backgroundPlayEnabled) {
                                EmeraldSafe.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clickable { onToggleBackgroundPlay(!preferences.backgroundPlayEnabled) }
                                .testTag("browser_bg_play_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = "Background Play",
                                    tint = if (preferences.backgroundPlayEnabled) EmeraldSafe
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (preferences.backgroundPlayEnabled) "BG ON" else "BG OFF",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (preferences.backgroundPlayEnabled) EmeraldSafe
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 3. Picture-in-Picture (PiP) Pill
                        Surface(
                            color = CyberCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clickable { onEnterPipMode() }
                                .testTag("browser_pip_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in Picture",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "PiP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 4. Primary Multi-Quality Download Button
                        Button(
                            onClick = onOpenDownloadSheet,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ShieldCoral,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .testTag("browser_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download Video",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Download",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
