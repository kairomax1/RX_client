package com.v2ray.ang.ui.compose

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Surface of everything that floats above the screen: menus, the quick card, the failure message. */
val RxFloatingDark = Color(0xFF15161A)

@Composable
fun rxFloatingColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.background.luminance() < 0.5f) RxFloatingDark else colors.surfaceContainerHigh
}

@Composable
fun rxFloatingBorder(): BorderStroke {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.12f) else colors.outlineVariant)
}

/**
 * False when the system "Remove animations" switch is on. Every decorative motion in the app
 * checks this, so a user who asked the platform for no motion gets a still screen.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        } catch (e: Exception) {
            true
        }
    }
}

/**
 * Backdrop of the main screen. Pure black next to bright silver is hard on the eyes, so the
 * screen sits on a deep blue-grey that still reads as dark.
 */
@Composable
fun rxScreenBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return if (colors.background.luminance() < 0.5f) {
        Brush.verticalGradient(listOf(Color(0xFF12151C), Color(0xFF0D0F14), Color(0xFF101319)))
    } else {
        Brush.verticalGradient(listOf(colors.surfaceContainerLow, colors.background))
    }
}

/** The particles of [RxDustBackground]; plain arrays so a frame allocates nothing. */
private class DustField {
    var count = 0
    var width = 0f
    var height = 0f
    var x = FloatArray(0)
    var y = FloatArray(0)
    var radius = FloatArray(0)
    var speed = FloatArray(0)
    var alpha = FloatArray(0)
    var phase = FloatArray(0)
    var lastNanos = 0L
    var seconds = 0f

    fun ensure(size: Size, density: Float) {
        if (size.width == width && size.height == height) return
        width = size.width
        height = size.height
        val widthDp = width / density
        val heightDp = height / density
        // Denser and a little larger than the web mock, where a phone screen got about two dozen
        // specks that vanished behind the list.
        count = min(110f, widthDp * heightDp / 3600f).toInt().coerceAtLeast(0)
        x = FloatArray(count) { Random.nextFloat() * width }
        y = FloatArray(count) { Random.nextFloat() * height }
        radius = FloatArray(count) { (0.5f + Random.nextFloat() * 1.5f) * density }
        speed = FloatArray(count) { (5f + Random.nextFloat() * 20f) * density }
        alpha = FloatArray(count) { 0.22f + Random.nextFloat() * 0.5f }
        phase = FloatArray(count) { Random.nextFloat() * 6.283f }
    }

    fun step(nowNanos: Long) {
        if (lastNanos == 0L || nowNanos == 0L) {
            lastNanos = nowNanos
            return
        }
        // Clamp so a paused screen does not make every particle jump when it resumes.
        val delta = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNanos = nowNanos
        seconds += delta
        for (i in 0 until count) {
            y[i] -= speed[i] * delta
            if (y[i] < -5f) {
                y[i] = height + 5f
                x[i] = Random.nextFloat() * width
            }
        }
    }
}

/**
 * The live wallpaper: silver dust drifting upward and twinkling, over two soft lights that wander
 * slowly. Drawn in the draw phase only, so it never recomposes the screen above it. Skipped on
 * light themes, where it would not show.
 */
@Composable
fun RxDustBackground(modifier: Modifier = Modifier) {
    if (MaterialTheme.colorScheme.background.luminance() >= 0.5f) return
    val animate = rememberAnimationsEnabled()
    val field = remember { DustField() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { frameNanos = it }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        field.ensure(size, density)
        field.step(frameNanos)
        val t = field.seconds
        // Two wide, faint lights on slow, different orbits.
        val first = Offset(
            size.width * (0.5f + 0.32f * sin(t * 0.11f)),
            size.height * (0.36f + 0.10f * cos(t * 0.09f)),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0x2E8A9CC8), Color.Transparent),
                center = first,
                radius = size.height * 0.55f,
            )
        )
        val second = Offset(
            size.width * (0.5f - 0.36f * sin(t * 0.07f + 1.3f)),
            size.height * (0.78f + 0.08f * sin(t * 0.13f)),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0x1FB8C4DC), Color.Transparent),
                center = second,
                radius = size.height * 0.42f,
            )
        )
        for (i in 0 until field.count) {
            val twinkle = 0.65f + 0.35f * sin(t * 1.4f + field.phase[i])
            drawCircle(
                color = Color(0xFFD7E1F5).copy(alpha = field.alpha[i] * twinkle),
                radius = field.radius[i],
                center = Offset(field.x[i], field.y[i]),
            )
        }
    }
}

/** Dropdown with the app's floating surface: own background, thin border, round corners, shadow. */
@Composable
fun RxDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        containerColor = rxFloatingColor(),
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        border = rxFloatingBorder(),
        content = content,
    )
}

/** A menu row that slides and fades in a little after the one above it. */
@Composable
fun RxMenuItem(
    index: Int,
    text: String,
    onClick: () -> Unit,
    iconRes: Int? = null,
) {
    val animate = rememberAnimationsEnabled()
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        delay(index * 38L)
        progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    val leadingIcon: (@Composable () -> Unit)? = if (iconRes != null) {
        { Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp)) }
    } else {
        null
    }
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = leadingIcon,
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 10.dp.toPx()
        },
    )
}
