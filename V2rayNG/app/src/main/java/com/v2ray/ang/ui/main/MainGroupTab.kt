package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxDropdownMenu
import com.v2ray.ang.ui.compose.RxMenuItem
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ServersCache
import kotlinx.coroutines.flow.StateFlow

private val TAB_BAR_PADDING = 4.dp
private val TAB_BAR_SHAPE = RoundedCornerShape(22.dp)
private val TAB_SPACING = 2.dp
private val TAB_HEIGHT = 40.dp
private val TAB_SHAPE = RoundedCornerShape(18.dp)
private const val LONG_PRESS_FILL_MS = 700
private val SELECTED_TAB_MARGIN = 12.dp

/**
 * Subscription group tabs: a floating rounded bar whose selected tab is a tinted pill and whose
 * server count is a small badge.
 *
 * While all tabs fit, they share the bar width equally (like Telegram's folder tabs); once they
 * no longer fit, they keep their natural width and the bar scrolls horizontally, keeping the
 * selected tab in view.
 */
@Composable
fun GroupTabBar(
    groups: List<GroupMapItem>,
    selectedTabIndex: Int,
    mainViewModel: MainViewModel,
    onTabClick: (Int) -> Unit,
    onUpdateSubscription: (String) -> Unit,
    onPingGroup: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Groups fed by a subscription link get the "update" entry in their long-press menu.
    val subscriptionGroupIds = remember(groups) {
        mainViewModel.getSubscriptions()
            .filter { it.subscription.url.isNotBlank() }
            .map { it.guid }
            .toSet()
    }
    val selectedIndex = selectedTabIndex.coerceIn(0, groups.lastIndex)
    val colors = MaterialTheme.colorScheme
    // The plain surface color equals the screen background in dark mode, so the bar needs its own tone there.
    val dark = colors.surface.luminance() < 0.5f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        shape = TAB_BAR_SHAPE,
        color = if (dark) colors.surfaceContainerHigh else colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (dark) 1f else 0.35f)),
        shadowElevation = if (dark) 0.dp else 3.dp
    ) {
        BoxWithConstraints {
            val barPaddingPx = with(LocalDensity.current) { TAB_BAR_PADDING.roundToPx() }
            val availablePx = constraints.maxWidth - 2 * barPaddingPx
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(TAB_BAR_PADDING)
            ) {
                EqualOrNaturalTabsLayout(availablePx = availablePx) {
                    groups.forEachIndexed { index, group ->
                        key(group.id) {
                            val serverFlow = remember(group.id, mainViewModel) {
                                mainViewModel.serversForGroup(group.id)
                            }
                            GroupTabItem(
                                group = group,
                                selected = index == selectedIndex,
                                serverFlow = serverFlow,
                                hasSubscriptionUrl = group.id in subscriptionGroupIds,
                                onClick = { onTabClick(index) },
                                onUpdateSubscription = { onUpdateSubscription(group.id) },
                                onPingGroup = { onPingGroup(group.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays the tabs out in a row. If every tab fits in an equal share of [availablePx] they all get
 * that share; otherwise, if they still fit together, the spare space is spread evenly; otherwise
 * they keep their natural width (the parent scrolls).
 */
@Composable
private fun EqualOrNaturalTabsLayout(
    availablePx: Int,
    content: @Composable () -> Unit
) {
    Layout(content = content) { measurables, _ ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}

        val spacing = TAB_SPACING.roundToPx()
        val tabHeight = TAB_HEIGHT.roundToPx()
        val count = measurables.size
        val gaps = spacing * (count - 1)
        val natural = measurables.map { it.maxIntrinsicWidth(tabHeight) }
        val equalShare = (availablePx - gaps) / count

        val widths = when {
            availablePx <= 0 -> natural
            natural.all { it <= equalShare } -> List(count) { equalShare }
            natural.sum() + gaps <= availablePx -> {
                val extra = (availablePx - gaps - natural.sum()) / count
                natural.map { it + extra }
            }
            else -> natural
        }

        val placeables = measurables.mapIndexed { i, measurable ->
            measurable.measure(Constraints.fixed(widths[i], tabHeight))
        }
        layout(widths.sum() + gaps, tabHeight) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + spacing
            }
        }
    }
}

/**
 * One tab. A tap selects it. Holding it fills it with a soft white light that grows stronger; when
 * the light is full (about 0.7 s) the phone ticks and a small menu opens for that group only.
 * Letting go earlier cancels and counts as a normal tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTabItem(
    group: GroupMapItem,
    selected: Boolean,
    serverFlow: StateFlow<List<ServersCache>>,
    hasSubscriptionUrl: Boolean,
    onClick: () -> Unit,
    onUpdateSubscription: () -> Unit,
    onPingGroup: () -> Unit,
) {
    val servers by serverFlow.collectAsStateWithLifecycle()
    // The default group (empty id) never showed a count.
    val count = if (group.id.isEmpty()) 0 else servers.size
    val colors = MaterialTheme.colorScheme
    val bringIntoView = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val marginPx = with(LocalDensity.current) { SELECTED_TAB_MARGIN.toPx() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val longPressLabel = stringResource(R.string.tab_menu_open)

    // Bring the tab in with some room around it so the pill never ends up flush against the bar's
    // rounded edge; at the first and last tab the scroll stops at the bar's own inner padding.
    LaunchedEffect(selected, size) {
        if (selected && size != IntSize.Zero) {
            bringIntoView.bringIntoView(
                Rect(-marginPx, 0f, size.width + marginPx, size.height.toFloat())
            )
        }
    }
    LaunchedEffect(menuOpen) {
        if (!menuOpen && fill.value > 0f) fill.animateTo(0f, tween(260))
    }

    // Ink follows the theme: light on the dark bar, graphite on the light one.
    val ink = MaterialTheme.colorScheme.onSurface
    val selectedBrush = Brush.verticalGradient(
        listOf(ink.copy(alpha = 0.16f), ink.copy(alpha = 0.06f))
    )

    Box(propagateMinConstraints = true) {
        // One selectable node: name, count and selected state are announced together.
        Row(
            modifier = Modifier
                .bringIntoViewRequester(bringIntoView)
                .onSizeChanged { size = it }
                .clip(TAB_SHAPE)
                .then(
                    if (selected) {
                        Modifier
                            .background(selectedBrush)
                            .border(1.dp, ink.copy(alpha = 0.16f), TAB_SHAPE)
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    val value = fill.value
                    if (value > 0f) {
                        // Grows from the start edge and brightens as it goes.
                        drawRect(
                            color = ink.copy(alpha = 0.10f + 0.30f * value),
                            size = Size(this.size.width * value, this.size.height),
                        )
                    }
                }
                .semantics {
                    this.selected = selected
                    role = Role.Tab
                    onClick { currentOnClick(); true }
                    onLongClick(label = longPressLabel) { menuOpen = true; true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            var fired = false
                            val job = scope.launch {
                                fill.snapTo(0f)
                                fill.animateTo(1f, tween(LONG_PRESS_FILL_MS, easing = LinearEasing))
                                fired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            }
                            val released = tryAwaitRelease()
                            if (!fired) {
                                job.cancel()
                                scope.launch { fill.animateTo(0f, tween(160)) }
                                if (released) currentOnClick()
                            }
                        }
                    )
                }
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.remarks,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (count > 0) {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 18.dp, minHeight = 16.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) colors.onSurface.copy(alpha = 0.92f)
                            else colors.onSurfaceVariant.copy(alpha = 0.28f)
                        )
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = if (selected) colors.surface else colors.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        RxDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            var index = 0
            if (hasSubscriptionUrl) {
                RxMenuItem(
                    index = index++,
                    text = stringResource(R.string.tab_menu_update_subscription),
                    iconRes = R.drawable.ic_refresh_24dp,
                    onClick = {
                        menuOpen = false
                        onUpdateSubscription()
                    },
                )
            }
            RxMenuItem(
                index = index,
                text = stringResource(R.string.tab_menu_ping_all),
                iconRes = R.drawable.ic_ping_24dp,
                onClick = {
                    menuOpen = false
                    onPingGroup()
                },
            )
        }
    }
}
