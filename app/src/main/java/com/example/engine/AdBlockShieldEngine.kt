package com.example.engine

import android.content.Context
import android.webkit.WebResourceResponse
import com.example.data.ShieldPreferencesState
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Glue between the WebView and the shield: blocked-request responses and the page script. */
object AdBlockShieldEngine {

    @Volatile
    private var cachedScript: String? = null

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun createScriptResponse(script: String): WebResourceResponse {
        return WebResourceResponse(
            "application/javascript",
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(script.toByteArray())
        )
    }

    /** The page script (assets/shield.js), read once and cached. */
    fun shieldScript(context: Context): String {
        cachedScript?.let { return it }
        val script = context.applicationContext.assets.open("shield.js").bufferedReader().use { it.readText() }
        cachedScript = script
        return script
    }

    /**
     * Config for the page script; [role] is "player" (plays videos) or "browse" (see shield.js).
     * [pull] is what a pull-down from the top of the page does: "refresh", "minimize" or "off".
     */
    fun configJson(
        prefs: ShieldPreferencesState,
        inPip: Boolean,
        role: String = "player",
        appVisible: Boolean = true,
        pull: String = "off"
    ): String {
        val browse = role == "browse"
        return JSONObject()
            .put("role", role)
            .put("appVisible", appVisible)
            .put("pull", if (inPip) "off" else pull)
            .put("adBlock", prefs.adBlockEnabled)
            .put("bgPlay", prefs.backgroundPlayEnabled && !browse)
            .put("speed", prefs.playbackSpeed.toDouble())
            .put("pip", inPip && !browse)
            .put("sponsorSkip", prefs.sponsorSkipEnabled)
            .put("autoplay", prefs.autoplayEnabled)
            .toString()
    }
}
