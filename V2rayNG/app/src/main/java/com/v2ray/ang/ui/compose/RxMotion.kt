package com.v2ray.ang.ui.compose

import android.app.ActivityManager
import androidx.compose.runtime.LongState
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.drawscope.scale
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
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
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Surface of everything that floats above the screen: menus, the quick card, the failure message. */
val RxFloatingDark = Color(0xFF1B1F27)

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
        val lowMemoryPhone = try {
            val manager = context.getSystemService(ActivityManager::class.java)
            manager?.isLowRamDevice == true || (manager?.memoryClass ?: Int.MAX_VALUE) <= 128
        } catch (e: Exception) {
            false
        }
        if (lowMemoryPhone) return@remember false
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

/** True while the active scheme is a dark one; decorative layers pick their ink from this. */
@Composable
fun rxIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Backdrop of the main screen. Pure black next to bright silver is hard on the eyes, so the dark
 * screen sits on a deep blue-grey; the light one on cool paper rather than plain white.
 */
@Composable
fun rxScreenBrush(): Brush {
    return if (rxIsDark()) {
        Brush.verticalGradient(listOf(Color(0xFF141821), Color(0xFF0F1218), Color(0xFF12161D)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF5F7FA), Color(0xFFE9EDF2), Color(0xFFF0F3F7)))
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
        // Enough to read as dust behind the list, few enough that a weak phone does not spend
        // its frame budget on decoration. The list and the bars always come first.
        count = min(44f, widthDp * heightDp / 9000f).toInt().coerceAtLeast(0)
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
 * The live wallpaper: dust drifting upward and twinkling. Drawn in the draw phase only, so it
 * never recomposes the screen above it. Silver dust on the dark theme, graphite dust on the light
 * one, so the screen is alive in both.
 */
@Composable
fun RxDustBackground(modifier: Modifier = Modifier) {
    val dark = rxIsDark()
    val animate = rememberAnimationsEnabled()
    val field = remember { DustField() }
    val frameNanos by rememberFrameClock(animate, DUST_FRAME_NANOS)
    val speck = if (dark) Color(0xFFD7E1F5) else Color(0xFF39465A)
    val speckGain = if (dark) 1f else 0.62f
    // Its own layer: a new wallpaper frame repaints the wallpaper alone, instead of making the
    // whole screen above it (list, bars, text) be recorded again on every frame.
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        field.ensure(size, density)
        field.step(frameNanos)
        val t = field.seconds
        for (i in 0 until field.count) {
            val twinkle = 0.65f + 0.35f * sin(t * 1.4f + field.phase[i])
            drawCircle(
                color = speck.copy(alpha = field.alpha[i] * twinkle * speckGain),
                radius = field.radius[i],
                center = Offset(field.x[i], field.y[i]),
            )
        }
    }
}

/**
 * Living fog: a few wide, soft banks drifting sideways at different speeds and breathing in and
 * out. White on the dark theme, slate on the light one. It is kept thin enough that text placed
 * on top stays readable. With animations off it is a still mist.
 */
@Composable
fun RxFogBackground(modifier: Modifier = Modifier, active: Boolean = true) {
    val dark = rxIsDark()
    val animate = rememberAnimationsEnabled()
    // [active] is false while the fog cannot be seen (a closed drawer): no frames are asked for
    // then, and the fog simply continues from where it was when it shows again.
    val frameNanos by rememberFrameClock(animate && active, FOG_FRAME_NANOS)
    val clock = remember { FloatArray(2) } // [0] seconds elapsed, [1] last frame in seconds
    val banks = remember(dark) { FogBanks() }
    val ink = if (dark) Color.White else Color(0xFF5B6B84)
    val strength = if (dark) 1f else 0.75f
    // Own layer for the same reason as the wallpaper: text lying on the fog is not redrawn with it.
    Canvas(modifier.graphicsLayer()) {
        val now = frameNanos / 1_000_000_000f
        if (clock[1] != 0f && now != 0f) clock[0] += (now - clock[1]).coerceIn(0f, 0.05f)
        clock[1] = now
        val t = clock[0]
        val w = size.width
        val h = size.height
        banks.ensure(w, ink)
        for (i in 0 until FOG_BANKS) {
            val seed = i * 1.618f
            // Each bank crosses the sheet at its own pace and wraps around well off screen.
            val span = w * 2.2f
            val travel = (t * (9f + 5f * i) * density + seed * w * 0.7f) % span
            val x = if (i % 2 == 0) travel - w * 0.6f else w * 1.6f - travel
            val y = h * (0.12f + 0.2f * i) + h * 0.05f * sin(t * 0.21f + seed * 2f)
            val breathe = 0.75f + 0.25f * sin(t * 0.33f + seed * 3f)
            val radius = w * (0.55f + 0.12f * (i % 3))
            val alpha = (0.115f + 0.03f * (i % 2)) * breathe * strength
            // Stretched sideways so a bank reads as a drifting sheet of mist, not a round spot.
            // The gradient is shared and only moved, scaled and faded per bank.
            withTransform({
                translate(x, y)
                scale(scaleX = 1.9f, scaleY = 0.8f, pivot = Offset.Zero)
            }) {
                drawCircle(brush = banks.brushes[i % 3]!!, radius = radius, center = Offset.Zero, alpha = alpha)
            }
        }
    }
}

/** The three fog gradients (one per bank size), rebuilt only when the width or the ink changes. */
private class FogBanks {
    val brushes = arrayOfNulls<Brush>(3)
    private var width = -1f
    private var ink = Color.Unspecified

    fun ensure(w: Float, color: Color) {
        if (w == width && color == ink) return
        width = w
        ink = color
        for (k in 0 until 3) {
            brushes[k] = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0.45f), Color.Transparent),
                center = Offset.Zero,
                radius = (w * (0.55f + 0.12f * k)).coerceAtLeast(1f),
            )
        }
    }
}

/**
 * Frame time for the decorative layers. It only ticks while [active] and while the screen is
 * actually on show: in the background, or with the display off, no frame is requested at all.
 */
@Composable
private fun rememberFrameClock(active: Boolean, minFrameNanos: Long = 0L): LongState {
    val state = remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (active) {
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Slow decoration does not need every frame of a 90 or 120 Hz display: frames
                // closer together than [minFrameNanos] are skipped, and a skipped frame draws nothing.
                while (true) withFrameNanos { if (it - state.longValue >= minFrameNanos) state.longValue = it }
            }
        }
    }
    return state
}

private const val FOG_BANKS = 3

// Decoration runs well below the display rate: dust at ~24 frames a second and fog at ~20.
// The list, the bars and the text are what the user actually reads, and on a weak phone every
// frame spent on a background is a frame taken from them.
private const val DUST_FRAME_NANOS = 42_000_000L // at most ~24 frames a second
private const val FOG_FRAME_NANOS = 50_000_000L // at most ~20: fog drifts far too slowly to show it

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

/**
 * A menu that opens by unfolding: the body tips down out of the button it hangs from and
 * settles, and each row then swings in from the side, one after the next. Everything else
 * about it is the app's own floating surface.
 */
@Composable
fun RxFanDropdownMenu(
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
        // The body is drawn below, so it can be folded while it opens.
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        RxUnfoldSurface(content = content)
    }
}

/** The surface of [RxFanDropdownMenu]: hinged on its top edge, and folded back while closed. */
@Composable
private fun RxUnfoldSurface(content: @Composable ColumnScope.() -> Unit) {
    val animate = rememberAnimationsEnabled()
    val open = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        open.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 520f))
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .graphicsLayer {
                val shown = open.value
                // Tipped back while closed, so it looks like a flap let down from the button.
                rotationX = -38f * (1f - shown)
                scaleX = 0.94f + 0.06f * shown
                scaleY = 0.82f + 0.18f * shown
                alpha = shown.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .shadow(14.dp, shape)
            .clip(shape)
            .background(rxFloatingColor())
            .border(rxFloatingBorder(), shape),
        content = content,
    )
}

/** A row of [RxFanDropdownMenu]: it swings in from the side, a little after the row above it. */
@Composable
fun RxFanMenuItem(
    index: Int,
    text: String,
    onClick: () -> Unit,
    iconRes: Int? = null,
    /** Red: for the entries that throw configs away. */
    danger: Boolean = false,
) {
    val animate = rememberAnimationsEnabled()
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        delay(70L + index * 42L)
        progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 400f))
    }
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val leadingIcon: (@Composable () -> Unit)? = if (iconRes != null) {
        {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    } else {
        null
    }
    DropdownMenuItem(
        text = { Text(text = text, style = MaterialTheme.typography.bodyMedium, color = tint) },
        onClick = onClick,
        leadingIcon = leadingIcon,
        modifier = Modifier.graphicsLayer {
            val shown = progress.value
            alpha = shown.coerceIn(0f, 1f)
            translationX = (1f - shown) * 24.dp.toPx()
            scaleX = 0.96f + 0.04f * shown
        },
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
