package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.example.ui.AppTab

private data class TabSpec(val tab: AppTab, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val TABS = listOf(
    TabSpec(AppTab.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TabSpec(AppTab.MUSIC, "Music", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    TabSpec(AppTab.SUBSCRIPTIONS, "Subscriptions", Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions),
    TabSpec(AppTab.LIBRARY, "Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary)
)

/**
 * The native tabs, drawn opaquely over the (still attached) player page: tab content, the
 * mini-player when something is loaded, and the bottom navigation bar.
 */
@Composable
fun TabsScaffold(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    miniPlayer: (@Composable () -> Unit)?,
    content: @Composable (Modifier) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(Modifier.fillMaxSize())
            }
            miniPlayer?.invoke()
            AppNavigationBar(currentTab = currentTab, onSelectTab = onSelectTab)
        }
    }
}

/** Bottom navigation; also shown under the YouTube Music page in the Music tab. */
@Composable
fun AppNavigationBar(currentTab: AppTab, onSelectTab: (AppTab) -> Unit) {
    NavigationBar {
        TABS.forEach { spec ->
            val selected = spec.tab == currentTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(spec.tab) },
                icon = { Icon(if (selected) spec.selected else spec.unselected, contentDescription = null) },
                label = { Text(spec.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag("tab_" + spec.tab.name.lowercase())
            )
        }
    }
}
