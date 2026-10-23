package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DownloadFormatCatalog
import com.example.engine.AdBlockShieldEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `verify app_name and shield engine blocks youtube ads and trackers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("StreamShield", appName)

        // Verify YouTube video ad endpoints are blocked when AdBlock is enabled
        val adDecision = AdBlockShieldEngine.evaluateRequest(
            url = "https://m.youtube.com/api/stats/ads?adformat=1_2",
            adBlockEnabled = true
        )
        assertTrue(adDecision.blocked)
        assertTrue(adDecision.isVideoAd)

        // Verify regular YouTube video watch URLs are allowed
        val videoDecision = AdBlockShieldEngine.evaluateRequest(
            url = "https://m.youtube.com/watch?v=MnCf88b1hG0",
            adBlockEnabled = true
        )
        assertFalse(videoDecision.blocked)
    }

    @Test
    fun `verify download format catalog optimizes storage space across qualities`() {
        val fullHdOption = DownloadFormatCatalog.ALL_OPTIONS.first { it.id == "1080p_mp4" }
        val webmOption = DownloadFormatCatalog.ALL_OPTIONS.first { it.id == "480p_webm" }
        val audioOption = DownloadFormatCatalog.ALL_OPTIONS.first { it.id == "64k_opus" }

        val durationSec = 600 // 10-minute video
        val fullHdBytes = fullHdOption.estimateSizeBytes(durationSec)
        val webmBytes = webmOption.estimateSizeBytes(durationSec)
        val audioBytes = audioOption.estimateSizeBytes(durationSec)

        assertTrue(webmBytes < fullHdBytes)
        assertTrue(audioBytes < webmBytes)
        assertTrue(audioOption.isAudioOnly)
    }
}
