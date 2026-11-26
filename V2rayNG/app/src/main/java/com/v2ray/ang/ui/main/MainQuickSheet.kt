package com.v2ray.ang.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.ui.compose.rxFloatingBorder
import com.v2ray.ang.ui.compose.rxFloatingColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SHEET_SHAPE = RoundedCornerShape(28.dp)
private val TILE_SHAPE = RoundedCornerShape(20.dp)
private val TILE_HEIGHT = 104.dp
private val TILE_GAP = 12.dp

/**
 * The card opened by the grid button: four equal tiles, two by two. Not a full-screen page: it
 * floats above the dock with margins on every side, over a dimmed screen, and grows out of the
 * corner where the grid button sits. The tiles follow one after another.
 */
@Composable
fun MainQuickSheet(
    onDismiss: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val animate = rememberAnimationsEnabled()
    val scope = rememberCoroutineScope()
    val open = remember { Animatable(if (animate) 0f else 1f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (animate) {
            open.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    // Play the card back into the button before it leaves the composition.
    val close: (() -> Unit) -> Unit = { then ->
        if (!closing) {
            closing = true
            scope.launch {
                if (animate) open.animateTo(0f, tween(170, easing = FastOutSlowInEasing))
                then()
            }
        }
    }
    BackHandler { close(onDismiss) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = open.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.action_cancel),
            ) { close(onDismiss) },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // Clears the dock, so the card reads as coming out of the grid button.
                .padding(start = 22.dp, end = 22.dp, bottom = 132.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val value = open.value
                    scaleX = 0.55f + 0.45f * value
                    scaleY = 0.55f + 0.45f * value
                    // The grid button is at the physical bottom left in every locale.
                    transformOrigin = TransformOrigin(0.08f, 1f)
                }
                .shadow(28.dp, SHEET_SHAPE)
                .clip(SHEET_SHAPE)
                .background(rxFloatingColor())
                .border(rxFloatingBorder(), SHEET_SHAPE)
                // Taps on the card's own padding must not close it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            quickSheetItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    rowItems.forEachIndexed { columnIndex, item ->
                        QuickTile(
                            item = item,
                            order = rowIndex * 2 + columnIndex,
                            animate = animate,
                            onClick = { close { onNavigate(item) } },
                            modifier = Modifier.weight(1f).height(TILE_HEIGHT),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    item: MainDestination,
    order: Int,
    animate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val appear = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        delay(70L + order * 55L)
        appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
    }
    Column(
        modifier = modifier
            .graphicsLayer {
                val value = appear.value
                alpha = value.coerceIn(0f, 1f)
                translationY = (1f - value) * 18.dp.toPx()
                scaleX = 0.9f + 0.1f * value
                scaleY = 0.9f + 0.1f * value
            }
            .clip(TILE_SHAPE)
            .background(
                Brush.verticalGradient(
                    listOf(colors.onSurface.copy(alpha = 0.09f), colors.onSurface.copy(alpha = 0.03f))
                )
            )
            .border(1.dp, colors.onSurface.copy(alpha = 0.10f), TILE_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(item.labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
