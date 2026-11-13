package com.v2ray.ang.ui.main

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.rxLetterR
import com.v2ray.ang.ui.compose.rxLetterX
import com.v2ray.ang.util.LogUtil
import kotlin.math.max
import kotlin.math.min

// The intro is a fixed brand plate, black with brushed silver, so it does not read the color
// scheme: it looks the same over the dark, light and dynamic themes it opens into.
private val IntroBackground = Color(0xFF000000)
private val IntroMuted = Color(0xFF8B919C)
private val IntroRing = Color(0xFFDCE6FA)
private val IntroGlow = Color(0xFFBECDEB)
private val IntroTileTop = Color(0xFF17181C)
private val IntroTileBottom = Color(0xFF060607)
private val IntroLine = Color(0x1AFFFFFF)
private val IntroBarStart = Color(0xFF6C7280)

private val MetalStops = arrayOf(
    0f to Color(0xFFFFFFFF),
    0.38f to Color(0xFFAEB4C0),
    0.52f to Color(0xFFF6F8FB),
    1f to Color(0xFF7D838F),
)

private const val SLASH_A_DEGREES = -24f
private const val SLASH_B_DEGREES = 28f
private const val BRAND_FONT_SP = 30f

/**
 * Startup brand animation: echo rings pulse while the RX mark spins in out of a blur, two light
 * slashes cut across it, a sheen sweeps the tile, the name and tagline rise and a loading line
 * fills; then the plate zooms out and fades while [MainScreen] scales in underneath.
 *
 * Presentation only: it owns no durable state and performs no repository, settings, or native
 * work. The animation is skipped when the system animation scale is off, and a tap jumps straight
 * to the exit. [onLeaving] fires exactly once when the exit begins (the caller starts revealing the
 * home screen) and [onFinished] exactly once when the overlay can be removed.
 */
@Composable
fun MainSplashOverlay(
    onLeaving: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motionDisabled = remember(context) { animationsDisabled(context) }
    val currentOnLeaving by rememberUpdatedState(onLeaving)
    val currentOnFinished by rememberUpdatedState(onFinished)

    // Negative until the first frame, so nothing is painted when the animation is skipped.
    var elapsedMs by remember { mutableFloatStateOf(-1f) }
    // Time added by a tap, so skipping plays the exit instead of cutting to the home screen.
    var skippedMs by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    val leave = {
        if (!leaving) {
            leaving = true
            currentOnLeaving()
        }
    }
    val finish = {
        leave()
        if (!finished) {
            finished = true
            currentOnFinished()
        }
    }

    LaunchedEffect(Unit) {
        if (motionDisabled) {
            finish()
            return@LaunchedEffect
        }
        val startNs = withFrameNanos { it }
        while (true) {
            val frameNs = withFrameNanos { it }
            val elapsed = (frameNs - startNs) / 1_000_000f + skippedMs
            elapsedMs = min(elapsed, MainSplashTimeline.TOTAL_MS)
            if (MainSplashTimeline.isLeaving(elapsed)) leave()
            if (MainSplashTimeline.isFinished(elapsed)) break
        }
        finish()
    }

    // Nothing at all when the user disabled motion; otherwise paint from t=0 so the main screen
    // never flashes through the one frame before the first clock tick.
    if (motionDisabled) return
    val elapsed = elapsedMs.coerceAtLeast(0f)

    val logo = MainSplashTimeline.logoProgress(elapsed)
    val logoScale = MainSplashTimeline.logoScale(logo)
    val logoRotation = MainSplashTimeline.logoRotation(logo)
    val logoBlur = MainSplashTimeline.logoBlurDp(logo)
    val slashA = MainSplashTimeline.slashProgress(elapsed, MainSplashTimeline.SLASH_A_START_MS)
    val slashB = MainSplashTimeline.slashProgress(elapsed, MainSplashTimeline.SLASH_B_START_MS)
    val sweep = MainSplashTimeline.sweepProgress(elapsed)
    val brand = MainSplashTimeline.brandProgress(elapsed)
    val brandSpacingEm = MainSplashTimeline.brandLetterSpacingEm(brand)
    val tagline = MainSplashTimeline.taglineProgress(elapsed)
    val bar = MainSplashTimeline.barProgress(elapsed)
    val overlayAlpha = MainSplashTimeline.overlayAlpha(elapsed)
    val overlayScale = MainSplashTimeline.overlayScale(elapsed)
    val overlayBlur = MainSplashTimeline.overlayBlurDp(elapsed)

    val density = LocalDensity.current
    val risePx = with(density) { 14.dp.toPx() }
    // Tracking adds a gap after the last letter too. The name is Latin, so that gap is always on
    // the physical right; the same amount on the physical left keeps the word optically centred
    // in right-to-left locales as well.
    val brandBalance = with(density) { (BRAND_FONT_SP * brandSpacingEm).sp.toDp() }
    val barFromRight = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = overlayAlpha
                scaleX = overlayScale
                scaleY = overlayScale
            }
            .blur(overlayBlur.dp)
            .background(IntroBackground)
            .pointerInput(Unit) {
                detectTapGestures {
                    val now = max(elapsedMs, 0f)
                    if (!MainSplashTimeline.isLeaving(now)) {
                        skippedMs += MainSplashTimeline.OUT_START_MS - now
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                // Rings and glow overflow the 168 dp box on purpose; a Canvas does not clip.
                Canvas(Modifier.fillMaxSize()) {
                    for (index in 0 until MainSplashTimeline.RING_COUNT) {
                        drawRing(MainSplashTimeline.ringProgress(elapsed, index))
                    }
                    drawGlow(logo)
                }
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .graphicsLayer {
                            alpha = logo
                            scaleX = logoScale
                            scaleY = logoScale
                            rotationZ = logoRotation
                        }
                        .blur(logoBlur.dp, BlurredEdgeTreatment.Unbounded)
                        .clip(RoundedCornerShape(30.dp))
                ) {
                    // The real logo artwork carries its own tile and rim.
                    Image(
                        painter = painterResource(R.drawable.ic_brand_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawSheen(sweep)
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawSlash(slashA, SLASH_A_DEGREES)
                    drawSlash(slashB, SLASH_B_DEGREES)
                }
            }

            Spacer(Modifier.height(34.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    brush = Brush.linearGradient(*MetalStops),
                    fontSize = BRAND_FONT_SP.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = brandSpacingEm.em,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .absolutePadding(left = brandBalance)
                    .graphicsLayer {
                        alpha = brand
                        translationY = (1f - brand) * risePx
                    },
            )

            Spacer(Modifier.height(10.dp))

            // No tracking here: the tagline is joined script in several locales and spacing its
            // letters apart would break the words.
            Text(
                text = stringResource(R.string.splash_tagline),
                color = IntroMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = tagline
                    translationY = (1f - tagline) * risePx
                },
            )
        }

        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 60.dp)
                .width(140.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
        ) {
            drawRect(Color.White.copy(alpha = 0.1f))
            drawLoadingLine(bar, barFromRight)
        }
    }
}

/**
 * Accessibility: the system "Remove animations" developer/accessibility switch sets the animator
 * duration scale to zero. Reading it keeps the startup free of motion for those users instead of
 * playing a full-screen animation they asked the platform to suppress.
 */
private fun animationsDisabled(context: Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
} catch (e: Exception) {
    LogUtil.w(AppConfig.TAG, "Could not read the animator duration scale", e)
    false
}

private fun DrawScope.drawRing(progress: Float) {
    val alpha = MainSplashTimeline.ringAlpha(progress)
    if (alpha <= 0f) return
    drawCircle(
        color = IntroRing.copy(alpha = 0.5f * alpha),
        radius = size.minDimension / 2f * MainSplashTimeline.ringScale(progress),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun DrawScope.drawGlow(logoProgress: Float) {
    if (logoProgress <= 0f) return
    val radius = size.minDimension * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(IntroGlow.copy(alpha = 0.30f * logoProgress), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
    )
}

/** A slanted band of light, 40 % of the tile wide, travelling from off the left to off the right. */
private fun DrawScope.drawSheen(progress: Float) {
    if (progress <= 0f || progress >= 1f) return
    val band = size.width * 0.4f
    val left = size.width * (-0.6f + 1.9f * progress)
    val slant = size.height * 0.36f
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
            start = Offset(left, 0f),
            end = Offset(left + band, slant),
        )
    )
}

private fun DrawScope.drawSlash(progress: Float, degrees: Float) {
    val alpha = MainSplashTimeline.slashAlpha(progress)
    if (alpha <= 0f) return
    val half = MainSplashTimeline.slashLengthDp(progress).dp.toPx() / 2f
    if (half <= 0f) return
    rotate(degrees, center) {
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.White, Color.Transparent),
                startX = center.x - half,
                endX = center.x + half,
            ),
            start = Offset(center.x - half, center.y),
            end = Offset(center.x + half, center.y),
            strokeWidth = 2.dp.toPx(),
            alpha = 0.9f * alpha,
        )
    }
}

/** Fills from the reading-direction start, with the bright end leading. */
private fun DrawScope.drawLoadingLine(progress: Float, fromRight: Boolean) {
    if (progress <= 0f) return
    val filled = size.width * progress.coerceIn(0f, 1f)
    val left = if (fromRight) size.width - filled else 0f
    val colors = if (fromRight) listOf(Color.White, IntroBarStart) else listOf(IntroBarStart, Color.White)
    drawRect(
        brush = Brush.horizontalGradient(colors, startX = left, endX = left + filled),
        topLeft = Offset(left, 0f),
        size = Size(filled, size.height),
    )
}
