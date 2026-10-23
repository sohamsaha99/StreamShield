package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BookmarkEntity
import com.example.data.ShieldPreferencesState
import com.example.data.StreamShieldDatabase
import com.example.data.UserPreferencesRepository
import com.example.data.WatchHistoryEntity
import com.example.data.WebVideoState
import com.example.engine.SponsorBlockClient
import com.example.service.BackgroundPlaybackService
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val HOME_URL = "https://m.youtube.com"

data class BrowserUiState(
    val url: String = HOME_URL,
    val title: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

data class BlockCounts(val ads: Long = 0L, val requests: Long = 0L)

data class SponsorSegments(val videoId: String, val json: String)

/** How the most recent video started; shown in Settings to diagnose slow starts. */
data class StartDiagnostics(
    val title: String,
    val startMs: Int,
    val adMs: Int,
    val adsPruned: Int,
    val adBlockEnabled: Boolean
)

class StreamShieldViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StreamShieldDatabase.getInstance(application)
    private val prefsRepo = UserPreferencesRepository(application)
    private val sponsorBlock = SponsorBlockClient()

    // Eager: the page script reads the current config synchronously at document start.
    val preferences: StateFlow<ShieldPreferencesState> = prefsRepo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShieldPreferencesState())

    val bookmarks: StateFlow<List<BookmarkEntity>> = database.bookmarkDao()
        .observeAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchHistory: StateFlow<List<WatchHistoryEntity>> = database.watchHistoryDao()
        .observeRecentHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    private val _videoState = MutableStateFlow(WebVideoState())
    val videoState: StateFlow<WebVideoState> = _videoState.asStateFlow()

    private val _omniboxInput = MutableStateFlow(HOME_URL)
    val omniboxInput: StateFlow<String> = _omniboxInput.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showSpeedSheet = MutableStateFlow(false)
    val showSpeedSheet: StateFlow<Boolean> = _showSpeedSheet.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    private val _lastStart = MutableStateFlow<StartDiagnostics?>(null)
    val lastStart: StateFlow<StartDiagnostics?> = _lastStart.asStateFlow()

    // Most recent blocked requests (host + path), newest first.
    private val _recentBlocked = MutableStateFlow<List<String>>(emptyList())
    val recentBlocked: StateFlow<List<String>> = _recentBlocked.asStateFlow()

    private val _sponsorSegments = MutableStateFlow<SponsorSegments?>(null)
    val sponsorSegments: StateFlow<SponsorSegments?> = _sponsorSegments.asStateFlow()

    // Block counters: increments stay in memory and are flushed to DataStore in batches.
    private val pendingCounts = MutableStateFlow(BlockCounts())
    val blockCounts: StateFlow<BlockCounts> = combine(preferences, pendingCounts) { prefs, pending ->
        BlockCounts(prefs.adsBlockedCount + pending.ads, prefs.requestsBlockedCount + pending.requests)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BlockCounts())
    private var flushJob: Job? = null
    private val flushMutex = Mutex()

    private var bannerJob: Job? = null
    private var sponsorJob: Job? = null
    private var bgSessionActive = false
    private var lastBgSignature = ""
    private var lastHistoryKey = ""

    init {
        seedInitialBookmarksIfEmpty()
        viewModelScope.launch {
            PlaybackCommands.flow.collect { command ->
                if (command == PlaybackCommand.STOP) {
                    bgSessionActive = false
                    lastBgSignature = ""
                }
            }
        }
    }

    private fun seedInitialBookmarksIfEmpty() {
        viewModelScope.launch {
            if (database.bookmarkDao().countBookmarks() == 0) {
                val defaults = listOf(
                    BookmarkEntity(title = "Home", url = HOME_URL, category = "Home"),
                    BookmarkEntity(title = "Subscriptions", url = "$HOME_URL/feed/subscriptions", category = "Home"),
                    BookmarkEntity(title = "Lo-Fi", url = "$HOME_URL/results?search_query=lofi+hip+hop+radio", category = "Music")
                )
                defaults.forEach { database.bookmarkDao().insertBookmark(it) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation & page state
    // ---------------------------------------------------------------------------------------

    /** Turns omnibox text into a URL: full URLs pass through, anything else is a YouTube search. */
    fun resolveOmniboxInput(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("youtube.com/") || trimmed.startsWith("m.youtube.com/") ||
                trimmed.startsWith("www.youtube.com/") || trimmed.startsWith("youtu.be/") -> "https://$trimmed"
            else -> "$HOME_URL/results?search_query=${Uri.encode(trimmed)}"
        }
    }

    fun updateOmniboxInput(input: String) {
        _omniboxInput.value = input
    }

    fun onPageChanged(url: String, title: String?, canBack: Boolean, canForward: Boolean, loading: Boolean) {
        val previousUrl = _browserState.value.url
        _browserState.value = BrowserUiState(
            url = url,
            title = title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: _browserState.value.title,
            isLoading = loading,
            canGoBack = canBack,
            canGoForward = canForward
        )
        if (url != previousUrl) _omniboxInput.value = url
    }

    fun onVideoState(state: WebVideoState) {
        val previous = _videoState.value
        _videoState.value = state

        if (state.startMs >= 0 && (state.videoId != previous.videoId || previous.startMs < 0)) {
            _lastStart.value = StartDiagnostics(
                title = state.title,
                startMs = state.startMs,
                adMs = state.adMs,
                adsPruned = state.adsPruned,
                adBlockEnabled = preferences.value.adBlockEnabled
            )
        }

        if (state.videoId.isNotBlank() && state.videoId != previous.videoId) {
            val prefs = preferences.value
            if (!prefs.rememberSpeedAcrossVideos && previous.videoId.isNotBlank() && prefs.playbackSpeed != 1.0f) {
                setPlaybackSpeed(1.0f)
            }
            loadSponsorSegments(state.videoId)
        }

        if (state.isPlaying && state.videoId.isNotBlank() && state.title.isNotBlank()) {
            val key = "${state.videoId}|${state.title}"
            if (key != lastHistoryKey) {
                lastHistoryKey = key
                recordHistory("$HOME_URL/watch?v=${state.videoId}", state.title, state.channel)
            }
        }

        syncBackgroundService(state)
    }

    private fun loadSponsorSegments(videoId: String) {
        sponsorJob?.cancel()
        if (!preferences.value.sponsorSkipEnabled) return
        sponsorJob = viewModelScope.launch {
            val segments = withContext(Dispatchers.IO) { sponsorBlock.fetchSegments(videoId) }
            _sponsorSegments.value = SponsorSegments(videoId, SponsorBlockClient.segmentsToJson(segments))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ad-block counters (called from WebView threads)
    // ---------------------------------------------------------------------------------------

    /** An ad/tracker network request was refused (called on WebView network threads). */
    fun onRequestBlocked(url: String) {
        val entry = url.substringBefore('?').removePrefix("https://").removePrefix("http://").take(90)
        _recentBlocked.update { (listOf(entry) + it).take(12) }
        pendingCounts.update { it.copy(requests = it.requests + 1) }
        scheduleCountFlush()
    }

    /** A whole ad was prevented (removed from a YouTube response, or skipped in the player). */
    fun onAdBlocked() {
        pendingCounts.update { it.copy(ads = it.ads + 1) }
        scheduleCountFlush()
    }

    private fun scheduleCountFlush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(5_000L)
            flushCounts()
        }
    }

    private suspend fun flushCounts() = flushMutex.withLock {
        val snapshot = pendingCounts.value
        if (snapshot.ads == 0L && snapshot.requests == 0L) return@withLock
        prefsRepo.addBlockedCounts(snapshot.ads, snapshot.requests)
        pendingCounts.update { BlockCounts(it.ads - snapshot.ads, it.requests - snapshot.requests) }
    }

    /** Called when the app goes to the background so a process kill loses nothing. */
    fun flushCountsNow() {
        viewModelScope.launch { flushCounts() }
    }

    fun resetBlockCounts() {
        viewModelScope.launch {
            flushMutex.withLock {
                pendingCounts.value = BlockCounts()
                prefsRepo.resetBlockedCounts()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Background playback
    // ---------------------------------------------------------------------------------------

    private fun syncBackgroundService(state: WebVideoState) {
        if (!preferences.value.backgroundPlayEnabled) {
            stopBackgroundSession()
            return
        }
        // Nothing to show until something actually plays; after that keep the controls updated.
        if (!state.isPlaying && !bgSessionActive) return
        val signature = "${state.title}|${state.channel}|${state.durationSec}|${state.isPlaying}"
        if (signature == lastBgSignature) return
        BackgroundPlaybackService.update(
            context = getApplication(),
            title = state.title,
            artist = state.channel,
            durationSec = state.durationSec,
            playing = state.isPlaying,
            alreadyRunning = bgSessionActive
        )
        bgSessionActive = true
        lastBgSignature = signature
    }

    private fun stopBackgroundSession() {
        if (bgSessionActive) BackgroundPlaybackService.stop(getApplication())
        bgSessionActive = false
        lastBgSignature = ""
    }

    // ---------------------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------------------

    fun setPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun openSettings() {
        _showSettings.value = true
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    fun openSpeedSheet() {
        _showSpeedSheet.value = true
    }

    fun dismissSpeedSheet() {
        _showSpeedSheet.value = false
    }

    fun showBanner(message: String) {
        bannerJob?.cancel()
        _statusBanner.value = message
        bannerJob = viewModelScope.launch {
            delay(2500L)
            _statusBanner.value = null
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    // ---------------------------------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------------------------------

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch { prefsRepo.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f)) }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setDarkMode(enabled) }
    }

    fun toggleAmoledBlack(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAmoledBlackMode(enabled) }
    }

    fun toggleAdBlock(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAdBlockEnabled(enabled)
            showBanner(if (enabled) "Ad shield on" else "Ad shield off (reload to see ads)")
        }
    }

    fun toggleSponsorSkip(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setSponsorSkipEnabled(enabled)
            val videoId = _videoState.value.videoId
            if (enabled && videoId.isNotBlank()) loadSponsorSegments(videoId)
        }
    }

    fun toggleBackgroundPlay(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setBackgroundPlayEnabled(enabled)
            if (enabled) {
                syncBackgroundService(_videoState.value)
            } else {
                stopBackgroundSession()
            }
            showBanner(if (enabled) "Background play on" else "Background play off")
        }
    }

    fun toggleAutoPip(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAutoPipOnLeave(enabled) }
    }

    fun toggleRememberSpeed(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setRememberSpeed(enabled) }
    }

    // ---------------------------------------------------------------------------------------
    // Bookmarks & history
    // ---------------------------------------------------------------------------------------

    fun addCurrentPageBookmark() {
        val page = _browserState.value
        val video = _videoState.value
        val onVideo = video.videoId.isNotBlank() && page.url.contains(video.videoId)
        val title = (if (onVideo) video.title else page.title.removeSuffix(" - YouTube"))
            .ifBlank { "YouTube" }
            .take(40)
        viewModelScope.launch {
            database.bookmarkDao().insertBookmark(
                BookmarkEntity(title = title, url = page.url, category = "Saved")
            )
            showBanner("Bookmarked")
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { database.bookmarkDao().deleteBookmarkById(id) }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            database.watchHistoryDao().clearAllHistory()
            showBanner("Watch history cleared")
        }
    }

    private fun recordHistory(url: String, title: String, channel: String) {
        viewModelScope.launch {
            database.watchHistoryDao().upsertHistory(
                WatchHistoryEntity(
                    videoUrl = url,
                    title = title,
                    channelName = channel,
                    playbackSpeedUsed = preferences.value.playbackSpeed
                )
            )
        }
    }

    override fun onCleared() {
        // Best effort: persist counts that have not been flushed yet.
        val snapshot = pendingCounts.value
        if (snapshot.ads > 0L || snapshot.requests > 0L) {
            kotlinx.coroutines.runBlocking { prefsRepo.addBlockedCounts(snapshot.ads, snapshot.requests) }
        }
        super.onCleared()
    }
}
