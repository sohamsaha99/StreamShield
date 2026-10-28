package com.example.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import com.example.browser.BrowsePageController
import com.example.ui.BrowsePageState
import com.example.ui.PULL_DRAG_RATE
import com.example.ui.PULL_REFRESH_THRESHOLD_DP
import com.example.ui.WebPull
import com.example.ui.components.PullIndicator
import com.example.ui.components.webPullOffsetPx
import com.example.ui.theme.ShieldCoral

/**
 * The browse page inside a tab (search results, channels, playlists): YouTube's own page with a
 * thin loading bar; the tab bar and mini-player stay under it. Back walks this page's history
 * for the current visit, then returns to the tab's own screen.
 */
@Composable
fun BrowsePageScreen(
    controller: BrowsePageController,
    state: BrowsePageState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Off while this page is not the one in front (the player is open, or it is leaving). */
    backEnabled: Boolean = true,
    pull: WebPull? = null,
    refreshing: Boolean = false
) {
    BackHandler(enabled = backEnabled) {
        if (controller.canGoBackInSession()) controller.goBack() else onClose()
    }
    val density = LocalDensity.current.density
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            if (state.loading) LinearProgressIndicator(color = ShieldCoral, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { controller.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize()
            )
            PullIndicator(
                offsetPx = webPullOffsetPx(pull?.distance ?: 0f, refreshing, density),
                progress = (pull?.distance ?: 0f) * PULL_DRAG_RATE / PULL_REFRESH_THRESHOLD_DP,
                refreshing = refreshing
            )
        }
    }
}
