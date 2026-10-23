package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

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
    /** Ads removed from YouTube responses or skipped in the player. */
    val adsBlockedCount: Long = 0L,
    /** Ad / tracker network requests refused. */
    val requestsBlockedCount: Long = 0L
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
        // v2 keys: the v1 counters were seeded with made-up numbers, so they are not carried over.
        val ADS_BLOCKED = longPreferencesKey("ads_blocked_count_v2")
        val REQUESTS_BLOCKED = longPreferencesKey("requests_blocked_count_v2")
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
            adsBlockedCount = prefs[Keys.ADS_BLOCKED] ?: 0L,
            requestsBlockedCount = prefs[Keys.REQUESTS_BLOCKED] ?: 0L
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
        // Snap to 0.05 steps; round (not truncate) so 1.15f does not become 1.10.
        val clamped = ((speed * 20f).roundToInt() / 20f).coerceIn(0.25f, 3.0f)
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = clamped }
    }

    suspend fun setRememberSpeed(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_SPEED] = enabled }
    }

    /** Adds counts accumulated in memory; called in batches to avoid a disk write per event. */
    suspend fun addBlockedCounts(ads: Long, requests: Long) {
        if (ads == 0L && requests == 0L) return
        context.dataStore.edit { prefs ->
            prefs[Keys.ADS_BLOCKED] = (prefs[Keys.ADS_BLOCKED] ?: 0L) + ads
            prefs[Keys.REQUESTS_BLOCKED] = (prefs[Keys.REQUESTS_BLOCKED] ?: 0L) + requests
        }
    }

    suspend fun resetBlockedCounts() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ADS_BLOCKED] = 0L
            prefs[Keys.REQUESTS_BLOCKED] = 0L
        }
    }
}
