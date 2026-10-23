package com.example.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.browser.BrowserController
import com.example.data.BookmarkEntity
import com.example.data.ShieldPreferencesState
import com.example.data.formatCount
import com.example.data.formatSpeedBadge
import com.example.ui.BrowserUiState
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

/**
 * The main screen: omnibox and bookmarks on top, the YouTube WebView, and one row of player
 * controls at the bottom. In picture-in-picture only the WebView is shown.
 */
@Composable
fun BrowserScreen(
    controller: BrowserController,
    browserState: BrowserUiState,
    omniboxInput: String,
    preferences: ShieldPreferencesState,
    adsBlocked: Long,
    bookmarks: List<BookmarkEntity>,
    isInPipMode: Boolean,
    onOmniboxInputChange: (String) -> Unit,
    onOmniboxSubmit: (String) -> Unit,
    onToggleAdBlock: (Boolean) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onEnterPip: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = browserState.canGoBack && !isInPipMode) {
        controller.goBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!isInPipMode) {
            BrowserTopBar(
                controller = controller,
                browserState = browserState,
                omniboxInput = omniboxInput,
                adBlockEnabled = preferences.adBlockEnabled,
                adsBlocked = adsBlocked,
                bookmarks = bookmarks,
                onOmniboxInputChange = onOmniboxInputChange,
                onOmniboxSubmit = onOmniboxSubmit,
                onToggleAdBlock = onToggleAdBlock,
                onAddBookmark = onAddBookmark
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = {
                    // The WebView outlives this composable (it belongs to the activity), so make
                    // sure it is not still attached to a previous host.
                    controller.webView.also { (it.parent as? ViewGroup)?.removeView(it) }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!isInPipMode) {
            PlayerControlBar(
                playbackSpeed = preferences.playbackSpeed,
                backgroundPlayEnabled = preferences.backgroundPlayEnabled,
                onOpenSpeedSheet = onOpenSpeedSheet,
                onToggleBackgroundPlay = onToggleBackgroundPlay,
                onEnterPip = onEnterPip,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

@Composable
private fun BrowserTopBar(
    controller: BrowserController,
    browserState: BrowserUiState,
    omniboxInput: String,
    adBlockEnabled: Boolean,
    adsBlocked: Long,
    bookmarks: List<BookmarkEntity>,
    onOmniboxInputChange: (String) -> Unit,
    onOmniboxSubmit: (String) -> Unit,
    onToggleAdBlock: (Boolean) -> Unit,
    onAddBookmark: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var omniboxFocused by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShieldBadge(
                    enabled = adBlockEnabled,
                    adsBlocked = adsBlocked,
                    onClick = { onToggleAdBlock(!adBlockEnabled) }
                )

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedTextField(
                    value = omniboxInput,
                    onValueChange = onOmniboxInputChange,
                    placeholder = {
                        Text("Search YouTube or paste a link", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (omniboxFocused && omniboxInput.isNotEmpty()) {
                            IconButton(onClick = { onOmniboxInputChange("") }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        } else {
                            IconButton(onClick = { controller.reload() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            focusManager.clearFocus()
                            onOmniboxSubmit(omniboxInput)
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = ShieldCoral,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .onFocusChanged { omniboxFocused = it.isFocused }
                        .testTag("browser_omnibox_input")
                )

                IconButton(onClick = onAddBookmark, modifier = Modifier.testTag("bookmark_page_button")) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = "Bookmark this page")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { controller.goBack() },
                    enabled = browserState.canGoBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { controller.goForward() },
                    enabled = browserState.canGoForward,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(18.dp))
                }
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(start = 4.dp, end = 8.dp)
                ) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        AssistChip(
                            onClick = { onOmniboxSubmit(bookmark.url) },
                            label = {
                                Text(
                                    text = bookmark.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            border = null,
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
            }

            // Fixed-height slot so the page does not jump when loading starts/stops.
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                if (browserState.isLoading) {
                    LinearProgressIndicator(color = ShieldCoral, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ShieldBadge(enabled: Boolean, adsBlocked: Long, onClick: () -> Unit) {
    val tint = if (enabled) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = if (enabled) ShieldCoral.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("shield_status_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Default.Shield else Icons.Outlined.Shield,
                contentDescription = if (enabled) "Ad shield on, tap to turn off" else "Ad shield off, tap to turn on",
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (enabled) formatCount(adsBlocked) else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PlayerControlBar(
    playbackSpeed: Float,
    backgroundPlayEnabled: Boolean,
    onOpenSpeedSheet: () -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onEnterPip: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    icon = Icons.Default.Speed,
                    label = formatSpeedBadge(playbackSpeed),
                    active = playbackSpeed != 1.0f,
                    activeColor = ShieldCoral,
                    onClick = onOpenSpeedSheet,
                    testTag = "browser_speed_button"
                )
                ControlButton(
                    icon = Icons.Default.Headphones,
                    label = if (backgroundPlayEnabled) "Background on" else "Background off",
                    active = backgroundPlayEnabled,
                    activeColor = EmeraldSafe,
                    onClick = { onToggleBackgroundPlay(!backgroundPlayEnabled) },
                    testTag = "browser_bg_play_button"
                )
                ControlButton(
                    icon = Icons.Default.PictureInPictureAlt,
                    label = "Pop out",
                    active = false,
                    activeColor = CyberCyan,
                    onClick = onEnterPip,
                    testTag = "browser_pip_button"
                )
                ControlButton(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    active = false,
                    activeColor = ShieldCoral,
                    onClick = onOpenSettings,
                    testTag = "browser_settings_button"
                )
            }
        }
    }
}

/** One consistent style for every control in the bottom bar. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    val tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
