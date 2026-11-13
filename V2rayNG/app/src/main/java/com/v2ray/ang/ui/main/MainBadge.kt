package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.ConfigBadge
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled

/**
 * The title a config earned in the automatic test. It pops in once and then a soft light keeps
 * crossing it. The light is read only while drawing, so it costs no recomposition.
 */
@Composable
internal fun ConfigBadgeChip(badge: ConfigBadge, modifier: Modifier = Modifier) {
    val (label, top, bottom) = when (badge) {
        ConfigBadge.Recommended -> Triple(R.string.badge_recommended, Color(0xFFE9C46A), Color(0xFFB8862D))
        ConfigBadge.LowestPing -> Triple(R.string.badge_lowest_ping, Color(0xFF6FD3A6), Color(0xFF2E8F68))
        ConfigBadge.Fastest -> Triple(R.string.badge_fastest, Color(0xFF7CB8F2), Color(0xFF3A78C2))
    }
    val animate = rememberAnimationsEnabled()
    val pop = remember(badge) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(badge) {
        if (pop.value < 1f) pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
    }
    val sheen: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "badge").animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(tween(2600, delayMillis = 1400, easing = LinearEasing), RepeatMode.Restart),
            label = "badgeSheen",
        )
    } else null
    val shape = RoundedCornerShape(9.dp)
    Text(
        text = stringResource(label),
        color = Color(0xFF14171C),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .graphicsLayer {
                val value = pop.value
                scaleX = 0.6f + 0.4f * value
                scaleY = 0.6f + 0.4f * value
                alpha = value.coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(top, bottom)))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .drawWithContent {
                drawContent()
                val position = sheen?.value ?: return@drawWithContent
                val x = size.width * position
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                        start = Offset(x - size.height * 1.2f, 0f),
                        end = Offset(x + size.height * 1.2f, size.height),
                    )
                )
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
