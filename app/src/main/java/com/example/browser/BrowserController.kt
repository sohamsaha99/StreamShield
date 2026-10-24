package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.data.WebVideoState
import com.example.engine.AdBlockShieldEngine
import com.example.engine.RequestFilter
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import com.example.ui.StreamShieldViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * WebView that keeps media running while the activity is hidden (background play) or shown in a
 * picture-in-picture window. The page side of this lives in assets/shield.js.
 */
class KeepAliveWebView(context: Context) : WebView(context) {
    @Volatile
    var keepAlive: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    // Chromium treats a WebView whose view (or an ancestor) is not VISIBLE as hidden and pauses
    // its video; keep reporting VISIBLE while playback should continue.
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(if (keepAlive) true else hasWindowFocus)
    }

    override fun onPause() {
        if (!keepAlive) super.onPause()
    }
}

/**
 * Owns the single YouTube WebView for the lifetime of the activity: navigation, the ad-block
 * network filter, the page script, fullscreen video, and media commands. Settings are read from
 * the ViewModel at the time they are needed, never captured, so toggles apply immediately.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    private val activity: ComponentActivity,
    private val viewModel: StreamShieldViewModel
) {
    val webView = KeepAliveWebView(activity)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usesDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitFullscreen()
    }
    private var destroyed = false

    init {
        configureWebView()
        observeViewModel()
        webView.loadUrl(viewModel.browserState.value.url)
    }

    // -------------------------------------------------------------------------------------------
    // Public API used by the UI
    // -------------------------------------------------------------------------------------------

    fun load(url: String) {
        webView.loadUrl(url)
    }

    fun reload() = webView.reload()

    fun canGoBack(): Boolean = webView.canGoBack()

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun play() = runPageCommand("play()")

    fun pause() = runPageCommand("pause()")

    fun togglePlay() = runPageCommand("togglePlay()")

    val isFullscreen: Boolean get() = fullscreenView != null

    fun exitFullscreen() {
        if (fullscreenView == null) return
        runPageCommand("exitFullscreen()")
        fullscreenCallback?.onCustomViewHidden()
        hideFullscreenView()
    }

    /** Called when the activity stops (screen off, home, PiP window closed). */
    fun onActivityStopped() {
        if (!viewModel.preferences.value.backgroundPlayEnabled) pause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        hideFullscreenView()
        webView.keepAlive = false
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    // -------------------------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------------------------

    private fun configureWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.keepAlive = viewModel.preferences.value.backgroundPlayEnabled

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = chromeUserAgent(WebSettings.getDefaultUserAgent(activity))
        }

        webView.addJavascriptInterface(PageBridge(), BRIDGE_NAME)

        if (usesDocumentStartScript) {
            // Runs before any of YouTube's scripts, in every YouTube frame, on every navigation.
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AdBlockShieldEngine.shieldScript(activity),
                setOf("https://*.youtube.com", "https://youtube.com")
            )
        }

        webView.webViewClient = ShieldWebViewClient()
        webView.webChromeClient = ShieldChromeClient()
    }

    private fun observeViewModel() {
        activity.lifecycleScope.launch {
            combine(viewModel.preferences, viewModel.isInPipMode) { prefs, pip ->
                webView.keepAlive = prefs.backgroundPlayEnabled || pip
                if (pip) {
                    // As the original app did: make sure Chromium's media pipeline and timers run
                    // in the PiP window.
                    webView.onResume()
                    webView.resumeTimers()
                }
                AdBlockShieldEngine.configJson(prefs, pip)
            }.distinctUntilChanged().collect { pushConfig(it) }
        }
        activity.lifecycleScope.launch {
            viewModel.sponsorSegments.collect { pushSponsorSegments() }
        }
        activity.lifecycleScope.launch {
            PlaybackCommands.flow.collect { command ->
                when (command) {
                    PlaybackCommand.PLAY -> play()
                    PlaybackCommand.PAUSE, PlaybackCommand.STOP -> pause()
                    PlaybackCommand.TOGGLE -> togglePlay()
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Page script plumbing
    // -------------------------------------------------------------------------------------------

    private fun currentConfigJson(): String =
        AdBlockShieldEngine.configJson(viewModel.preferences.value, viewModel.isInPipMode.value)

    private fun pushConfig(json: String = currentConfigJson()) {
        if (destroyed) return
        webView.evaluateJavascript("window.__streamShield && window.__streamShield.setConfig($json);", null)
    }

    private fun pushSponsorSegments() {
        val segments = viewModel.sponsorSegments.value ?: return
        if (destroyed) return
        val videoId = JSONObject.quote(segments.videoId)
        webView.evaluateJavascript(
            "window.__streamShield && window.__streamShield.setSponsorSegments($videoId, ${segments.json});",
            null
        )
    }

    private fun runPageCommand(call: String) {
        if (destroyed) return
        webView.evaluateJavascript("window.__streamShield && window.__streamShield.$call;", null)
    }

    /** Older WebViews without document-start scripts: inject as early as the callbacks allow. */
    private fun injectFallback(view: WebView?) {
        if (usesDocumentStartScript || view == null) return
        val url = view.url ?: return
        if (!isYouTubeUrl(url)) return
        view.evaluateJavascript(AdBlockShieldEngine.shieldScript(activity), null)
    }

    private fun reportPage(view: WebView?, url: String?, loading: Boolean) {
        if (view == null || url == null || url == "about:blank") return
        viewModel.onPageChanged(url, view.title, view.canGoBack(), view.canGoForward(), loading)
    }

    private inner class PageBridge {
        // Called on the WebView's JavaBridge thread.
        @JavascriptInterface
        fun getConfig(): String = currentConfigJson()

        @JavascriptInterface
        fun onAdBlocked(kind: String?) {
            mainHandler.post { viewModel.onAdBlocked() }
        }

        @JavascriptInterface
        fun onState(json: String?) {
            val state = parseState(json) ?: return
            mainHandler.post { viewModel.onVideoState(state) }
        }

        @JavascriptInterface
        fun onSpeedChanged(speed: String?) {
            val value = speed?.toFloatOrNull() ?: return
            mainHandler.post { viewModel.setPlaybackSpeed(value) }
        }

        @JavascriptInterface
        fun onSponsorSkipped(seconds: String?) {
            mainHandler.post { viewModel.showBanner("Skipped sponsor segment (${seconds ?: "?"}s)") }
        }
    }

    private inner class ShieldWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request == null || request.isForMainFrame) return null
            val adBlock = viewModel.preferences.value.adBlockEnabled
            val url = request.url.toString()
            RequestFilter.surrogateFor(url, adBlock)?.let { script ->
                viewModel.onRequestBlocked(url)
                return AdBlockShieldEngine.createScriptResponse(script)
            }
            RequestFilter.evaluate(url, adBlock) ?: return null
            viewModel.onRequestBlocked(url)
            return AdBlockShieldEngine.createEmptyResponse()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme ?: return false
            // YouTube's "open in app" links (intent://, vnd.youtube://) would fail with an error page.
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            reportPage(view, url, loading = true)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            injectFallback(view)
            pushConfig()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            injectFallback(view)
            pushConfig()
            pushSponsorSegments()
            reportPage(view, url, loading = false)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            reportPage(view, url, loading = false)
        }
    }

    private inner class ShieldChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) return
            if (fullscreenView != null) {
                callback?.onCustomViewHidden()
                return
            }
            showFullscreenView(view, callback)
        }

        override fun onHideCustomView() {
            hideFullscreenView()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            reportPage(view, view?.url, loading = view?.progress?.let { it < 100 } ?: false)
        }

        // Without this WebView draws a grey play-button placeholder before videos load.
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // -------------------------------------------------------------------------------------------
    // Fullscreen video
    // -------------------------------------------------------------------------------------------

    private fun showFullscreenView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        val decor = activity.window.decorView as? FrameLayout ?: run {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        decor.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        WindowCompat.getInsetsController(activity.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val video = viewModel.videoState.value
        val portraitVideo = video.videoWidth in 1 until video.videoHeight
        activity.requestedOrientation = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // Re-register so this callback is the most recent one and wins over Compose BackHandlers.
        fullscreenBackCallback.remove()
        activity.onBackPressedDispatcher.addCallback(activity, fullscreenBackCallback)
        fullscreenBackCallback.isEnabled = true
    }

    private fun hideFullscreenView() {
        val view = fullscreenView ?: return
        fullscreenView = null
        fullscreenCallback = null
        (view.parent as? ViewGroup)?.removeView(view)

        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreenBackCallback.isEnabled = false
        runPageCommand("nudgeLayout()")
    }

    companion object {
        private const val BRIDGE_NAME = "StreamShieldBridge"

        fun isYouTubeUrl(url: String): Boolean {
            val host = RequestFilter.hostOf(url) ?: return false
            return host == "youtube.com" || host.endsWith(".youtube.com")
        }

        /**
         * WebView's default UA marks itself as a WebView ("; wv", "Version/4.0"), which makes
         * YouTube serve "open the app" prompts and older layouts. Present as the matching Chrome.
         */
        fun chromeUserAgent(defaultUa: String): String {
            return defaultUa
                .replace("; wv)", ")")
                .replace(Regex(" Build/[^;)]+"), "")
                .replace(" Version/4.0", "")
        }

        private fun parseState(json: String?): WebVideoState? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                WebVideoState(
                    videoId = o.optString("videoId"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    channel = o.optString("channel"),
                    durationSec = o.optInt("duration"),
                    isPlaying = o.optBoolean("playing"),
                    videoWidth = o.optInt("width"),
                    videoHeight = o.optInt("height"),
                    loadMs = o.optInt("loadMs", -1),
                    startMs = o.optInt("startMs", -1),
                    adMs = o.optInt("adMs"),
                    adsPruned = o.optInt("pruned"),
                    pipBlockedPauses = o.optInt("pipBlocked"),
                    pipSystemPauses = o.optInt("pipSystem")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
