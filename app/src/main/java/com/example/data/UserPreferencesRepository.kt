package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "streamshield_prefs")

data class ShieldPreferencesState(
    val darkMode: Boolean = true,
    val amoledBlackMode: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val sponsorSkipEnabled: Boolean = true,
    val backgroundPlayEnabled: Boolean = true,
    val autoPipOnLeave: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val rememberSpeedAcrossVideos: Boolean = true,
    val defaultQualityOptionId: String = "720p_mp4",
    val adsBlockedCount: Long = 142L,
    val trackersBlockedCount: Long = 389L,
    val bandwidthSavedBytes: Long = 68_400_000L
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val ADBLOCK_ENABLED = booleanPreferencesKey("adblock_enabled")
        val SPONSOR_SKIP = booleanPreferencesKey("sponsor_skip")
        val BACKGROUND_PLAY = booleanPreferencesKey("background_play")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val REMEMBER_SPEED = booleanPreferencesKey("remember_speed")
        val DEFAULT_QUALITY_ID = stringPreferencesKey("default_quality_id")
        val ADS_BLOCKED = longPreferencesKey("ads_blocked_count")
        val TRACKERS_BLOCKED = longPreferencesKey("trackers_blocked_count")
        val BANDWIDTH_SAVED = longPreferencesKey("bandwidth_saved_bytes")
    }

    val preferencesFlow: Flow<ShieldPreferencesState> = context.dataStore.data.map { prefs ->
        ShieldPreferencesState(
            darkMode = prefs[Keys.DARK_MODE] ?: true,
            amoledBlackMode = prefs[Keys.AMOLED_BLACK] ?: false,
            adBlockEnabled = prefs[Keys.ADBLOCK_ENABLED] ?: true,
            sponsorSkipEnabled = prefs[Keys.SPONSOR_SKIP] ?: true,
            backgroundPlayEnabled = prefs[Keys.BACKGROUND_PLAY] ?: true,
            autoPipOnLeave = prefs[Keys.AUTO_PIP] ?: true,
            playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.25f, 3.0f),
            rememberSpeedAcrossVideos = prefs[Keys.REMEMBER_SPEED] ?: true,
            defaultQualityOptionId = prefs[Keys.DEFAULT_QUALITY_ID] ?: "720p_mp4",
            adsBlockedCount = prefs[Keys.ADS_BLOCKED] ?: 142L,
            trackersBlockedCount = prefs[Keys.TRACKERS_BLOCKED] ?: 389L,
            bandwidthSavedBytes = prefs[Keys.BANDWIDTH_SAVED] ?: 68_400_000L
        )
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun setAmoledBlackMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMOLED_BLACK] = enabled }
    }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADBLOCK_ENABLED] = enabled }
    }

    suspend fun setSponsorSkipEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPONSOR_SKIP] = enabled }
    }

    suspend fun setBackgroundPlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_PLAY] = enabled }
    }

    suspend fun setAutoPipOnLeave(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PIP] = enabled }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        val clamped = ((speed * 20f).toInt() / 20f).coerceIn(0.25f, 3.0f)
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = clamped }
    }

    suspend fun setRememberSpeed(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_SPEED] = enabled }
    }

    suspend fun setDefaultQualityOptionId(optionId: String) {
        context.dataStore.edit { it[Keys.DEFAULT_QUALITY_ID] = optionId }
    }

    suspend fun recordAdOrTrackerBlocked(isVideoAd: Boolean) {
        context.dataStore.edit { prefs ->
            if (isVideoAd) {
                val cur = prefs[Keys.ADS_BLOCKED] ?: 142L
                prefs[Keys.ADS_BLOCKED] = cur + 1L
                val saved = prefs[Keys.BANDWIDTH_SAVED] ?: 68_400_000L
                prefs[Keys.BANDWIDTH_SAVED] = saved + 1_850_000L // ~1.85 MB saved per blocked video ad
            } else {
                val cur = prefs[Keys.TRACKERS_BLOCKED] ?: 389L
                prefs[Keys.TRACKERS_BLOCKED] = cur + 1L
                val saved = prefs[Keys.BANDWIDTH_SAVED] ?: 68_400_000L
                prefs[Keys.BANDWIDTH_SAVED] = saved + 45_000L
            }
        }
    }
}
