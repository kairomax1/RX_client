package com.v2ray.ang.ui.main

import kotlin.math.abs

/**
 * Frame timing of the startup brand animation drawn by [MainSplashOverlay].
 *
 * The sequence lives here instead of inside the composable so it stays free of Compose and Android
 * types and can be asserted by a plain JVM test. Every function is pure: callers pass the elapsed
 * time of the animation, the overlay only renders the returned values.
 *
 * Order of events: echo rings pulse from the first frame, the mark spins in out of a blur, two
 * light slashes cut across it, a sheen sweeps the tile, the name and tagline rise, and when the
 * loading line is full the intro zooms out while the home screen scales in underneath.
 */
internal object MainSplashTimeline {

    const val LOGO_START_MS = 300f
    const val LOGO_END_MS = 1500f
    const val SLASH_A_START_MS = 1000f
    const val SLASH_B_START_MS = 1150f
    const val SLASH_LENGTH_MS = 700f
    const val SWEEP_START_MS = 1350f
    const val SWEEP_END_MS = 2450f
    const val BRAND_START_MS = 1500f
    const val BRAND_END_MS = 2500f
    const val TAGLINE_START_MS = 1900f
    const val TAGLINE_END_MS = 2900f
    const val BAR_START_MS = 300f
    const val BAR_END_MS = 2900f
    const val RING_PERIOD_MS = 2000f
    const val RING_STEP_MS = 600f
    const val RING_COUNT = 3

    /** The intro starts leaving here: it fades, grows and blurs for [OUT_END_MS] - [OUT_START_MS]. */
    const val OUT_START_MS = 3300f
    const val OUT_END_MS = 4100f

    /** Whole overlay, including its exit. Nothing is drawn by the overlay after it. */
    const val TOTAL_MS = OUT_END_MS

    /** Entrance of the home screen, counted from [OUT_START_MS]. */
    const val REVEAL_DELAY_MS = 150
    const val REVEAL_MS = 900
    const val REVEAL_START_SCALE = 0.96f

    /** Linear 0..1 progress of the window [startMs]..[endMs], clamped at both ends. */
    fun progress(elapsedMs: Float, startMs: Float, endMs: Float): Float {
        if (endMs <= startMs) return if (elapsedMs >= endMs) 1f else 0f
        return ((elapsedMs - startMs) / (endMs - startMs)).coerceIn(0f, 1f)
    }

    /**
     * CSS `cubic-bezier(x1, y1, x2, y2)` evaluated at [p]. The curve's x is monotonic for control
     * points inside 0..1, so a bisection on the curve parameter finds it.
     */
    fun cubicBezier(p: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val x = p.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        var t = x
        repeat(24) {
            val current = bezier(t, x1, x2)
            if (abs(current - x) < 0.0005f) return bezier(t, y1, y2)
            if (current < x) low = t else high = t
            t = (low + high) / 2f
        }
        return bezier(t, y1, y2)
    }

    private fun bezier(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }

    /** Fast start, long soft landing: the curve the mark arrives on. */
    fun easeLanding(p: Float): Float = cubicBezier(p, 0.2f, 0.9f, 0.2f, 1f)

    /** Standard `ease`. */
    fun ease(p: Float): Float = cubicBezier(p, 0.25f, 0.1f, 0.25f, 1f)

    /** Standard `ease-out`. */
    fun easeOut(p: Float): Float = cubicBezier(p, 0f, 0f, 0.58f, 1f)

    /** Standard `ease-in-out`. */
    fun easeInOut(p: Float): Float = cubicBezier(p, 0.42f, 0f, 0.58f, 1f)

    /** Eased 0..1 arrival of the mark; drives its opacity, scale, rotation and blur together. */
    fun logoProgress(elapsedMs: Float): Float =
        easeLanding(progress(elapsedMs, LOGO_START_MS, LOGO_END_MS))

    /** The mark grows from 40 % to full size. */
    fun logoScale(logoProgress: Float): Float = 0.4f + 0.6f * logoProgress.coerceIn(0f, 1f)

    /** The mark untwists from -12 degrees. */
    fun logoRotation(logoProgress: Float): Float = -12f * (1f - logoProgress.coerceIn(0f, 1f))

    /** Blur radius of the mark in dp, 14 down to 0. */
    fun logoBlurDp(logoProgress: Float): Float = 14f * (1f - logoProgress.coerceIn(0f, 1f))

    /** Eased 0..1 life of a slash that starts at [startMs]. */
    fun slashProgress(elapsedMs: Float, startMs: Float): Float =
        easeOut(progress(elapsedMs, startMs, startMs + SLASH_LENGTH_MS))

    /** Length of a slash in dp: shoots to 260 in the first 60 %, then drifts to 300. */
    fun slashLengthDp(slashProgress: Float): Float {
        val p = slashProgress.coerceIn(0f, 1f)
        return if (p <= 0.6f) 260f * (p / 0.6f) else 260f + 40f * ((p - 0.6f) / 0.4f)
    }

    /** Opacity of a slash: solid while it shoots out, gone by the end, never drawn before it starts. */
    fun slashAlpha(slashProgress: Float): Float {
        val p = slashProgress.coerceIn(0f, 1f)
        return when {
            p <= 0f -> 0f
            p <= 0.6f -> 1f
            else -> 1f - (p - 0.6f) / 0.4f
        }
    }

    /** 0..1 travel of the sheen across the tile. */
    fun sweepProgress(elapsedMs: Float): Float =
        easeInOut(progress(elapsedMs, SWEEP_START_MS, SWEEP_END_MS))

    /** Eased 0..1 arrival of the app name. */
    fun brandProgress(elapsedMs: Float): Float =
        ease(progress(elapsedMs, BRAND_START_MS, BRAND_END_MS))

    /** Eased 0..1 arrival of the tagline; it follows the name. */
    fun taglineProgress(elapsedMs: Float): Float =
        ease(progress(elapsedMs, TAGLINE_START_MS, TAGLINE_END_MS))

    /** Tracking of the app name in em: wide and airy as it fades in, settling at 0.38. */
    fun brandLetterSpacingEm(brandProgress: Float): Float =
        0.8f - 0.42f * brandProgress.coerceIn(0f, 1f)

    /** Fill of the loading line. */
    fun barProgress(elapsedMs: Float): Float =
        cubicBezier(progress(elapsedMs, BAR_START_MS, BAR_END_MS), 0.5f, 0f, 0.2f, 1f)

    /**
     * 0..1 life of echo ring [index]. Rings repeat every [RING_PERIOD_MS], each one
     * [RING_STEP_MS] behind the previous, and a ring does not exist before its first start.
     */
    fun ringProgress(elapsedMs: Float, index: Int): Float {
        val local = elapsedMs - index * RING_STEP_MS
        if (local < 0f) return 0f
        return easeOut((local % RING_PERIOD_MS) / RING_PERIOD_MS)
    }

    /** A ring grows from 70 % to 240 % of its box. */
    fun ringScale(ringProgress: Float): Float = 0.7f + 1.7f * ringProgress.coerceIn(0f, 1f)

    /** A ring fades from 70 % to nothing. */
    fun ringAlpha(ringProgress: Float): Float = 0.7f * (1f - ringProgress.coerceIn(0f, 1f))

    /** Eased 0..1 exit of the overlay. */
    fun outProgress(elapsedMs: Float): Float =
        ease(progress(elapsedMs, OUT_START_MS, OUT_END_MS))

    /** Opacity of the whole overlay; stays 1 until the exit. */
    fun overlayAlpha(elapsedMs: Float): Float = 1f - outProgress(elapsedMs)

    /** The overlay grows to 115 % while it leaves. */
    fun overlayScale(elapsedMs: Float): Float = 1f + 0.15f * outProgress(elapsedMs)

    /** Blur radius of the overlay in dp while it leaves, 0 up to 8. */
    fun overlayBlurDp(elapsedMs: Float): Float = 8f * outProgress(elapsedMs)

    /** Scale of the home screen for a 0..1 [reveal]. */
    fun revealScale(reveal: Float): Float =
        REVEAL_START_SCALE + (1f - REVEAL_START_SCALE) * reveal.coerceIn(0f, 1f)

    /** True once the exit has begun, i.e. the home screen should start coming in. */
    fun isLeaving(elapsedMs: Float): Boolean = elapsedMs >= OUT_START_MS

    /** True once the overlay has nothing left to show. */
    fun isFinished(elapsedMs: Float): Boolean = elapsedMs >= TOTAL_MS
}
