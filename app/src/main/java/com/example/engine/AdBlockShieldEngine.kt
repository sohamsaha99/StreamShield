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

    /** The page script (assets/shield.js), read once and cached. */
    fun shieldScript(context: Context): String {
        cachedScript?.let { return it }
        val script = context.applicationContext.assets.open("shield.js").bufferedReader().use { it.readText() }
        cachedScript = script
        return script
    }

    fun configJson(prefs: ShieldPreferencesState, inPip: Boolean): String {
        return JSONObject()
            .put("adBlock", prefs.adBlockEnabled)
            .put("bgPlay", prefs.backgroundPlayEnabled)
            .put("speed", prefs.playbackSpeed.toDouble())
            .put("pip", inPip)
            .put("sponsorSkip", prefs.sponsorSkipEnabled)
            .toString()
    }
}
