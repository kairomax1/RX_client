package com.v2ray.ang.ui.main

import androidx.compose.material3.LocalContentColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.RxDropdownMenu
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled

private val UpdateDot = Color(0xFFFF4D4D)

/**
 * Header of the main screen. Pinned left to right in every locale, as in the design: the three
 * actions (more, add, search) on the left, the app name and the menu button on the right.
 * Transparent, so the live wallpaper shows through. Search swaps in the regular search bar.
 */
@Composable
fun MainTopBar(
    isLoading: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    updatePending: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onAction: (MainAction) -> Unit,
    onMoreMenuAction: (MainMoreMenuAction) -> Unit
) {
    if (showSearch) {
        AppTopBar(
            title = stringResource(R.string.app_name),
            onBackClick = onSearchClose,
            isLoading = isLoading,
            isSearchActive = true,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onSearchClose = onSearchClose,
            searchPlaceholder = stringResource(R.string.menu_item_search),
        )
        return
    }

    var showImportMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // Menu bodies keep the locale's own direction; only the header row is pinned.
    val localeDirection = LocalLayoutDirection.current

    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert_24dp),
                                contentDescription = stringResource(R.string.acc_more),
                            )
                        }
                        RxDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            CompositionLocalProvider(LocalLayoutDirection provides localeDirection) {
                                MoreMenuContent { action ->
                                    showMenu = false
                                    onMoreMenuAction(action)
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_add_24dp),
                                contentDescription = stringResource(R.string.acc_add),
                            )
                        }
                        RxDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides localeDirection) {
                                ImportMenuContent(
                                    onAction = { action ->
                                        showImportMenu = false
                                        onAction(action)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onSearchToggle(true) }) {
                        Icon(
                            painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.acc_search),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Box {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            painterResource(R.drawable.ic_menu_24dp),
                            contentDescription = stringResource(
                                if (updatePending) R.string.acc_open_menu_update else R.string.acc_open_menu
                            ),
                        )
                    }
                    if (updatePending) {
                        UpdateDotBadge(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = isLoading,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** Red dot that breathes, so a pending update is noticed without being loud. */
@Composable
fun UpdateDotBadge(modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "updateDot")
        val value by transition.animateFloat(
            initialValue = 0.75f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "updateDotScale",
        )
        value
    } else {
        1f
    }
    Box(
        modifier
            .size(9.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .clip(CircleShape)
            .background(UpdateDot)
    )
}
