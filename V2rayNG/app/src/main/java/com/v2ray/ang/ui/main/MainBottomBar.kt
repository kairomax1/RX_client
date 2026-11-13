package com.v2ray.ang.ui.main

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.v2ray.ang.ui.compose.rxIsDark
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxGlassPanel
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.ui.compose.rxGlassBrush
import com.v2ray.ang.util.LogUtil
import kotlin.math.tan

private val ConnectedDot = Color(0xFFBFE6FF)
private val ConnectGlow = Color(0xFFC8DCFF)
private val FailureTop = Color(0xFFFF6B6B)
private val FailureBottom = Color(0xFFC62828)
private val DOCK_BUTTON_HEIGHT = 64.dp
private val CONNECT_SHAPE = RoundedCornerShape(26.dp)
private val SIDE_SHAPE = RoundedCornerShape(22.dp)

/**
 * The dock: status line on top, then (always left to right) the grid button, the ping button and
 * the connect button.
 *
 * The connect button is the only place connection state is shown: moving stripes while the
 * connection is being verified, a glow once traffic really flows, red and a shake when it failed.
 */
@Composable
fun MainBottomBar(
    statusText: String,
    phase: ConnectPhase,
    failureTick: Int,
    isTesting: Boolean,
    pingMillis: Long?,
    exitIp: String?,
    exitCountry: String?,
    canConnect: Boolean,
    needsAppSelection: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenQuickSheet: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val connected = phase == ConnectPhase.Connected
    RxGlassPanel(
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
                    if (connected) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(rxLiveColor().copy(alpha = 0.22f))
                        )
                    }
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (connected) rxLiveColor() else colors.outline)
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                if (connected) {
                    ExitAddressText(ip = exitIp, country = exitCountry)
                }
            }
            Spacer(Modifier.height(12.dp))
            // The design pins this row left to right in every locale.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DockSideButton(
                        onClick = onOpenQuickSheet,
                        contentDescription = stringResource(R.string.quick_sheet_open),
                        modifier = Modifier.size(DOCK_BUTTON_HEIGHT),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_grid_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    PingButton(
                        enabled = connected && !isTesting,
                        isTesting = isTesting && connected,
                        pingMillis = pingMillis.takeIf { connected },
                        onClick = { onAction(MainAction.TestCurrentServer) },
                    )
                    ConnectButton(
                        phase = phase,
                        failureTick = failureTick,
                        enabled = canConnect || phase != ConnectPhase.Idle,
                        onClick = { onAction(MainAction.ToggleService) },
                        modifier = Modifier.weight(1f).height(DOCK_BUTTON_HEIGHT),
                    )
                }
            }
            if (phase == ConnectPhase.Idle && !canConnect) {
                Text(
                    text = stringResource(
                        if (needsAppSelection) R.string.rx_select_apps_hint else R.string.rx_connect_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DockSideButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(SIDE_SHAPE)
            .background(rxGlassBrush())
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.65f), SIDE_SHAPE)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                    if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f)
        ) {
            content()
        }
    }
}

/**
 * The exit address beside the status: the IP is typed out calmly, each character fading in, then
 * after a one second pause the country follows the same way. It is typed again only when a test
 * reports a different address, so a fixed address simply stays.
 */
@Composable
private fun ExitAddressText(ip: String?, country: String?) {
    val animate = rememberAnimationsEnabled()
    val locale = LocalConfiguration.current.locales[0]
    val ipText = ip?.trim().orEmpty()
    val countryText = remember(country, locale) {
        MainExitAddress.countryLabel(country) { code -> Locale("", code).getDisplayCountry(locale) }
    }
    if (ipText.isEmpty() && countryText.isEmpty()) return
    val ipUnits = remember(ipText) { MainExitAddress.typingUnits(ipText) }
    val countryUnits = remember(countryText) { MainExitAddress.typingUnits(countryText) }
    val ipProgress = remember { Animatable(0f) }
    val countryProgress = remember { Animatable(0f) }
    LaunchedEffect(ipText, countryText, animate) {
        if (!animate) {
            ipProgress.snapTo(ipUnits.size.toFloat())
            countryProgress.snapTo(countryUnits.size.toFloat())
            return@LaunchedEffect
        }
        ipProgress.snapTo(0f)
        countryProgress.snapTo(0f)
        ipProgress.animateTo(
            ipUnits.size.toFloat(),
            tween(MainExitAddress.typingMillis(ipUnits.size), easing = LinearEasing),
        )
        if (countryUnits.isNotEmpty()) {
            delay(MainExitAddress.COUNTRY_DELAY_MS)
            countryProgress.animateTo(
                countryUnits.size.toFloat(),
                tween(MainExitAddress.typingMillis(countryUnits.size), easing = LinearEasing),
            )
        }
    }
    // Handed on as a State and read only while drawing: the blink does not recompose the line.
    val caret = rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    val ink = rxLiveColor()
    val typingIp = ipProgress.value < ipUnits.size
    val typingCountry = !typingIp && countryUnits.isNotEmpty() && countryProgress.value < countryUnits.size
    // Addresses are Latin: keep the pair left to right inside a right-to-left status line.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = typedText(ipUnits, ipProgress.value, ink),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (animate && typingIp) TypingCaret(ink, caret)
            if (countryProgress.value > 0f || typingCountry) {
                Text(
                    text = typedText(countryUnits, countryProgress.value, ink),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
                if (animate && typingCountry) TypingCaret(ink, caret)
            }
        }
    }
}

/** The pieces typed so far at full strength, and the one arriving now fading in. */
private fun typedText(units: List<String>, progress: Float, ink: Color): AnnotatedString = buildAnnotatedString {
    val done = progress.toInt().coerceIn(0, units.size)
    withStyle(SpanStyle(color = ink)) {
        for (i in 0 until done) append(units[i])
    }
    if (done < units.size) {
        withStyle(SpanStyle(color = ink.copy(alpha = (progress - done).coerceIn(0f, 1f)))) {
            append(units[done])
        }
    }
}

@Composable
private fun TypingCaret(ink: Color, alpha: State<Float>) {
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(2.dp)
            .height(15.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(ink)
    )
}

/** Tone of everything that says "live": the status dot and the typed address. */
@Composable
private fun rxLiveColor(): Color = if (rxIsDark()) ConnectedDot else Color(0xFF0E62B8)

/** Shows the latency of the live connection; a tap measures it again. */
@Composable
private fun PingButton(
    enabled: Boolean,
    isTesting: Boolean,
    pingMillis: Long?,
    onClick: () -> Unit,
) {
    DockSideButton(
        onClick = onClick,
        enabled = enabled,
        contentDescription = stringResource(R.string.connection_test_pending),
        modifier = Modifier.width(72.dp).height(DOCK_BUTTON_HEIGHT),
    ) {
        val label = when {
            isTesting -> "…"
            pingMillis == null -> ""
            pingMillis < 0 -> "—"
            else -> pingMillis.toString()
        }
        // The number pops in when a new measurement arrives.
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.7f))
            },
            label = "ping",
        ) { value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    value == "…" -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    value.isEmpty() -> Icon(
                        painter = painterResource(R.drawable.ic_ping_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )

                    else -> {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectButton(
    phase: ConnectPhase,
    failureTick: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val context = LocalContext.current
    val failed = phase == ConnectPhase.Failed
    val connecting = phase == ConnectPhase.Connecting
    val connected = phase == ConnectPhase.Connected

    // Shake and a distinctive double-then-long buzz, replayed for every failed attempt.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failureTick) {
        if (failureTick == 0) return@LaunchedEffect
        vibrateFailure(context)
        if (!animate) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 520
                -14f at 60
                12f at 130
                -10f at 200
                8f at 270
                -5f at 350
                3f at 430
                0f at 520
            }
        )
    }

    val infinite = rememberInfiniteTransition(label = "connect")
    val stripeShift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart),
        label = "stripes",
    )
    val sheen by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Restart),
        label = "sheen",
    )
    val glow by animateFloatAsState(if (connected) 1f else 0f, tween(600), label = "glow")
    val red by animateFloatAsState(if (failed) 1f else 0f, tween(260), label = "red")

    val top = lerpColor(colors.primary, FailureTop, red)
    val bottom = lerpColor(colors.secondary, FailureBottom, red)
    val contentColor = if (enabled) lerpColor(colors.onPrimary, Color.White, red) else colors.onSurfaceVariant
    val background = if (enabled) Brush.linearGradient(listOf(top, bottom)) else rxGlassBrush()

    Box(
        modifier = modifier
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .shadow(
                elevation = (26f * glow).dp,
                shape = CONNECT_SHAPE,
                ambientColor = ConnectGlow,
                spotColor = ConnectGlow,
            )
            .clip(CONNECT_SHAPE)
            .background(background)
            .drawWithContent {
                if (enabled && animate) {
                    if (connecting) {
                        // 110 degree bands, 14 dp wide, sliding one period every 0.6 s.
                        val band = 14.dp.toPx()
                        val period = band * 2f
                        val slant = size.height / tan(Math.toRadians(70.0)).toFloat()
                        var x = -period - slant + stripeShift * period
                        while (x < size.width + slant) {
                            drawLine(
                                color = Color.Black.copy(alpha = 0.10f),
                                start = Offset(x + slant, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = band * 0.94f,
                            )
                            x += period
                        }
                    } else if (!failed) {
                        // A band of light gliding across, most of the cycle off screen.
                        val travel = size.width * 2.6f
                        val centre = -size.width * 0.8f + travel * sheen
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.34f),
                                    Color.Transparent,
                                ),
                                start = Offset(centre - size.width * 0.22f, 0f),
                                end = Offset(centre + size.width * 0.22f, size.height),
                            )
                        )
                    }
                }
                drawContent()
            }
            .border(
                1.dp,
                if (enabled) Color.White.copy(alpha = 0.35f) else colors.outlineVariant.copy(alpha = 0.65f),
                CONNECT_SHAPE,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val label = stringResource(
            when (phase) {
                ConnectPhase.Connecting -> R.string.rx_connecting
                ConnectPhase.Connected -> R.string.connection_connected
                ConnectPhase.Failed, ConnectPhase.Idle -> R.string.rx_connect
            }
        )
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "connectLabel",
        ) { text ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_power_24dp),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = text,
                    color = contentColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color =
    androidx.compose.ui.graphics.lerp(from, to, fraction.coerceIn(0f, 1f))

/** Two short taps and a long one: unlike any other vibration in the app. Needs no user-visible text. */
private fun vibrateFailure(context: Context) {
    try {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 55, 70, 55, 70, 170)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Main: failure vibration unavailable", e)
    }
}
