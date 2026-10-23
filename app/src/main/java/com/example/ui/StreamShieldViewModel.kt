package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivePlaybackItem
import com.example.data.AppTab
import com.example.data.BookmarkEntity
import com.example.data.DownloadEntity
import com.example.data.DownloadFormatOption
import com.example.data.DownloadTargetInfo
import com.example.data.ShieldPreferencesState
import com.example.data.StreamShieldDatabase
import com.example.data.UserPreferencesRepository
import com.example.data.WatchHistoryEntity
import com.example.data.extractYouTubeVideoId
import com.example.data.formatSpeedBadge
import com.example.engine.DownloadAndStreamEngine
import com.example.engine.StorageStats
import com.example.service.BackgroundPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class BrowserUiState(
    val currentUrl: String = "https://m.youtube.com",
    val omniboxInput: String = "https://m.youtube.com",
    val pageTitle: String = "YouTube (Brave Shield Active)",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val detectedVideoTitle: String = "YouTube Stream",
    val detectedVideoChannel: String = "YouTube",
    val detectedVideoDurationSec: Int = 240,
    val detectedDirectStreamUrl: String = "",
    val isWebVideoPlaying: Boolean = false,
    val sessionBlockedCount: Int = 14
)

class StreamShieldViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StreamShieldDatabase.getInstance(application)
    private val prefsRepo = UserPreferencesRepository(application)
    private val engine = DownloadAndStreamEngine(
        context = application,
        downloadDao = database.downloadDao(),
        scope = viewModelScope
    )

    val preferences: StateFlow<ShieldPreferencesState> = prefsRepo.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ShieldPreferencesState()
        )

    val allDownloads: StateFlow<List<DownloadEntity>> = database.downloadDao()
        .observeAllDownloads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val bookmarks: StateFlow<List<BookmarkEntity>> = database.bookmarkDao()
        .observeAllBookmarks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val watchHistory: StateFlow<List<WatchHistoryEntity>> = database.watchHistoryDao()
        .observeRecentHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val storageStats: StateFlow<StorageStats> = allDownloads
        .combine(preferences) { list, _ ->
            engine.computeStorageStats(list)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = engine.computeStorageStats(emptyList())
        )

    private val _selectedTab = MutableStateFlow(AppTab.BRAVE_BROWSER)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    private val _activePlayback = MutableStateFlow<ActivePlaybackItem?>(null)
    val activePlayback: StateFlow<ActivePlaybackItem?> = _activePlayback.asStateFlow()

    private val _isNativePlaying = MutableStateFlow(false)
    val isNativePlaying: StateFlow<Boolean> = _isNativePlaying.asStateFlow()

    private val _isNativeMinimized = MutableStateFlow(false)
    val isNativeMinimized: StateFlow<Boolean> = _isNativeMinimized.asStateFlow()

    private val _downloadSheetTarget = MutableStateFlow<DownloadTargetInfo?>(null)
    val downloadSheetTarget: StateFlow<DownloadTargetInfo?> = _downloadSheetTarget.asStateFlow()

    private val _showSpeedSheet = MutableStateFlow(false)
    val showSpeedSheet: StateFlow<Boolean> = _showSpeedSheet.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    private var bannerJob: Job? = null

    init {
        seedInitialBookmarksIfEmpty()
    }

    private fun seedInitialBookmarksIfEmpty() {
        viewModelScope.launch {
            if (database.bookmarkDao().countBookmarks() == 0) {
                val defaults = listOf(
                    BookmarkEntity(title = "YouTube Home", url = "https://m.youtube.com", category = "Home"),
                    BookmarkEntity(title = "Music & Lo-Fi", url = "https://m.youtube.com/results?search_query=lofi+hip+hop+radio", category = "Music"),
                    BookmarkEntity(title = "4K Nature & Space", url = "https://m.youtube.com/results?search_query=4k+nature+relaxation+video", category = "4K"),
                    BookmarkEntity(title = "Tech & Coding", url = "https://m.youtube.com/results?search_query=android+kotlin+development", category = "Tech"),
                    BookmarkEntity(title = "Podcasts", url = "https://m.youtube.com/results?search_query=science+technology+podcast", category = "Podcasts")
                )
                defaults.forEach { database.bookmarkDao().insertBookmark(it) }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun setPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun showBanner(message: String) {
        bannerJob?.cancel()
        _statusBanner.value = message
        bannerJob = viewModelScope.launch {
            delay(3200L)
            _statusBanner.value = null
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    // Browser Actions
    fun updateOmniboxInput(input: String) {
        _browserState.value = _browserState.value.copy(omniboxInput = input)
    }

    fun submitOmniboxQueryOrUrl(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val targetUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains("youtube.com/") || trimmed.contains("youtu.be/") -> "https://$trimmed"
            else -> "https://m.youtube.com/results?search_query=${Uri.encode(trimmed)}"
        }
        _browserState.value = _browserState.value.copy(
            currentUrl = targetUrl,
            omniboxInput = targetUrl
        )
        _selectedTab.value = AppTab.BRAVE_BROWSER
    }

    fun openVideoInBraveBrowser(youtubeUrl: String, title: String, channel: String, durationSec: Int) {
        _browserState.value = _browserState.value.copy(
            currentUrl = youtubeUrl,
            omniboxInput = youtubeUrl,
            detectedVideoTitle = title,
            detectedVideoChannel = channel,
            detectedVideoDurationSec = durationSec
        )
        _selectedTab.value = AppTab.BRAVE_BROWSER
        recordHistory(youtubeUrl, title, channel)
    }

    fun onWebPageChanged(url: String, title: String?, canBack: Boolean, canForward: Boolean, loading: Boolean) {
        _browserState.value = _browserState.value.copy(
            currentUrl = url,
            omniboxInput = url,
            pageTitle = title?.takeIf { it.isNotBlank() } ?: "YouTube (Shield Active)",
            canGoBack = canBack,
            canGoForward = canForward,
            isLoading = loading
        )
    }

    fun onWebVideoMetadataDetected(
        title: String,
        channel: String,
        url: String,
        durationSec: Int,
        isPlaying: Boolean,
        directStreamUrl: String
    ) {
        val cleanTitle = title.trim().ifEmpty { "YouTube Video Stream" }
        val cleanChannel = channel.trim().ifEmpty { "YouTube Creator" }
        val prev = _browserState.value
        _browserState.value = prev.copy(
            detectedVideoTitle = cleanTitle,
            detectedVideoChannel = cleanChannel,
            detectedVideoDurationSec = durationSec.coerceAtLeast(60),
            detectedDirectStreamUrl = directStreamUrl.ifBlank { prev.detectedDirectStreamUrl },
            isWebVideoPlaying = isPlaying
        )

        if (isPlaying && url.contains("watch") && prev.currentUrl != url) {
            recordHistory(url, cleanTitle, cleanChannel)
        }

        if (isPlaying && preferences.value.backgroundPlayEnabled) {
            BackgroundPlaybackService.startOrUpdate(
                context = getApplication(),
                title = cleanTitle,
                channelName = cleanChannel,
                speedBadge = formatSpeedBadge(preferences.value.playbackSpeed)
            )
        }
    }

    fun onAdOrTrackerBlocked(isVideoAd: Boolean) {
        _browserState.value = _browserState.value.copy(
            sessionBlockedCount = _browserState.value.sessionBlockedCount + 1
        )
        viewModelScope.launch {
            prefsRepo.recordAdOrTrackerBlocked(isVideoAd)
        }
    }

    // Native / Offline Player Actions
    fun playDownloadedItem(entity: DownloadEntity) {
        val file = File(entity.localFilePath)
        val hasRealLocalVideo = file.exists() && file.length() > 50_000L
        val uriOrUrl = if (hasRealLocalVideo) {
            file.absolutePath
        } else {
            entity.sourceStreamUrl
        }
        val playbackItem = ActivePlaybackItem(
            id = entity.videoId,
            title = entity.title,
            channelName = entity.channelName,
            mediaUriOrUrl = uriOrUrl,
            sourcePageUrl = entity.sourcePageUrl,
            isLocalFile = hasRealLocalVideo,
            isAudioOnly = entity.isAudioOnly,
            qualityLabel = entity.qualityBadge,
            formatContainer = entity.formatContainer,
            durationSeconds = entity.durationSeconds,
            thumbnailUrl = entity.thumbnailUrl
        )
        _activePlayback.value = playbackItem
        _isNativeMinimized.value = false
        _isNativePlaying.value = true

        if (preferences.value.backgroundPlayEnabled) {
            BackgroundPlaybackService.startOrUpdate(
                context = getApplication(),
                title = entity.title,
                channelName = entity.channelName,
                speedBadge = formatSpeedBadge(preferences.value.playbackSpeed)
            )
        }
    }

    fun setNativePlaying(playing: Boolean) {
        _isNativePlaying.value = playing
        if (!playing && !_browserState.value.isWebVideoPlaying) {
            BackgroundPlaybackService.stopService(getApplication())
        }
    }

    fun toggleMinimizeNativePlayer(minimized: Boolean) {
        _isNativeMinimized.value = minimized
    }

    fun closeNativePlayer() {
        _activePlayback.value = null
        _isNativePlaying.value = false
        BackgroundPlaybackService.stopService(getApplication())
    }

    // Download & Quality Selector Sheet Actions
    fun openDownloadSheetForCurrentWebVideo() {
        val bState = _browserState.value
        val ytVideoId = extractYouTubeVideoId(bState.currentUrl)
            ?: "yt_${System.currentTimeMillis() % 100000}"

        val thumbUrl = if (!ytVideoId.startsWith("yt_")) {
            "https://i.ytimg.com/vi/$ytVideoId/hqdefault.jpg"
        } else {
            "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg"
        }

        val target = DownloadTargetInfo(
            videoId = ytVideoId,
            title = bState.detectedVideoTitle.ifBlank { bState.pageTitle.replace(" - YouTube", "") },
            channelName = bState.detectedVideoChannel.ifBlank { "YouTube" },
            durationSeconds = bState.detectedVideoDurationSec.coerceAtLeast(180),
            thumbnailUrl = thumbUrl,
            sourcePageUrl = bState.currentUrl,
            directStreamUrl = bState.detectedDirectStreamUrl
        )
        _downloadSheetTarget.value = target
    }

    fun dismissDownloadSheet() {
        _downloadSheetTarget.value = null
    }

    fun confirmStartDownload(target: DownloadTargetInfo, formatOption: DownloadFormatOption) {
        engine.startDownload(target, formatOption)
        _downloadSheetTarget.value = null
        showBanner("Downloading '${target.title.take(26)}…' in ${formatOption.qualityBadge} (${formatOption.formatContainer})")
    }

    fun pauseDownload(id: Long) = engine.pauseDownload(id)
    fun resumeDownload(id: Long) = engine.resumeDownload(id)
    fun deleteDownload(entity: DownloadEntity) {
        engine.deleteDownload(entity)
        showBanner("Removed '${entity.title.take(24)}' from offline storage")
    }

    // Playback Speed & Preferences Actions
    fun openSpeedSheet() {
        _showSpeedSheet.value = true
    }

    fun dismissSpeedSheet() {
        _showSpeedSheet.value = false
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        viewModelScope.launch {
            prefsRepo.setPlaybackSpeed(clamped)
            if (_isNativePlaying.value || _browserState.value.isWebVideoPlaying) {
                val activeTitle = _activePlayback.value?.title ?: _browserState.value.detectedVideoTitle
                val activeChannel = _activePlayback.value?.channelName ?: _browserState.value.detectedVideoChannel
                if (preferences.value.backgroundPlayEnabled) {
                    BackgroundPlaybackService.startOrUpdate(
                        context = getApplication(),
                        title = activeTitle,
                        channelName = activeChannel,
                        speedBadge = formatSpeedBadge(clamped)
                    )
                }
            }
        }
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
            showBanner(if (enabled) "Brave Ad-Shield Activated" else "Ad-Shield Paused")
        }
    }

    fun toggleSponsorSkip(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setSponsorSkipEnabled(enabled) }
    }

    fun toggleBackgroundPlay(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setBackgroundPlayEnabled(enabled)
            if (!enabled) {
                BackgroundPlaybackService.stopService(getApplication())
            } else {
                val activeTitle = _activePlayback.value?.title ?: _browserState.value.detectedVideoTitle
                val activeChannel = _activePlayback.value?.channelName ?: _browserState.value.detectedVideoChannel
                BackgroundPlaybackService.startOrUpdate(
                    context = getApplication(),
                    title = activeTitle,
                    channelName = activeChannel,
                    speedBadge = formatSpeedBadge(preferences.value.playbackSpeed)
                )
            }
            showBanner(
                if (enabled) "Background Playback Enabled (Screen-off audio active)"
                else "Background Playback Disabled"
            )
        }
    }

    fun toggleAutoPip(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAutoPipOnLeave(enabled) }
    }

    fun toggleRememberSpeed(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setRememberSpeed(enabled) }
    }

    fun setDefaultDownloadQuality(optionId: String) {
        viewModelScope.launch { prefsRepo.setDefaultQualityOptionId(optionId) }
    }

    fun addCurrentPageBookmark() {
        val b = _browserState.value
        viewModelScope.launch {
            database.bookmarkDao().insertBookmark(
                BookmarkEntity(
                    title = b.detectedVideoTitle.ifBlank { b.pageTitle }.take(50),
                    url = b.currentUrl,
                    category = "Saved"
                )
            )
            showBanner("Saved to Quick Bookmarks")
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
}
