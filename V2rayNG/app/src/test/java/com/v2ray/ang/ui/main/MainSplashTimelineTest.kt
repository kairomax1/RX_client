package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainSplashTimelineTest {

    private val delta = 0.0001f
    private val eased = 0.01f

    @Test
    fun progressIsClampedOutsideItsWindow() {
        assertEquals(0f, MainSplashTimeline.progress(0f, 100f, 200f), delta)
        assertEquals(0f, MainSplashTimeline.progress(100f, 100f, 200f), delta)
        assertEquals(0.5f, MainSplashTimeline.progress(150f, 100f, 200f), delta)
        assertEquals(1f, MainSplashTimeline.progress(200f, 100f, 200f), delta)
        assertEquals(1f, MainSplashTimeline.progress(500f, 100f, 200f), delta)
    }

    @Test
    fun emptyWindowIsDoneImmediately() {
        assertEquals(0f, MainSplashTimeline.progress(50f, 100f, 100f), delta)
        assertEquals(1f, MainSplashTimeline.progress(100f, 100f, 100f), delta)
        assertEquals(1f, MainSplashTimeline.progress(150f, 200f, 100f), delta)
    }

    @Test
    fun cubicBezierHitsItsEndsAndStaysMonotonic() {
        assertEquals(0f, MainSplashTimeline.cubicBezier(0f, 0.2f, 0.9f, 0.2f, 1f), delta)
        assertEquals(1f, MainSplashTimeline.cubicBezier(1f, 0.2f, 0.9f, 0.2f, 1f), delta)
        // A straight line through the control points is the identity.
        assertEquals(0.3f, MainSplashTimeline.cubicBezier(0.3f, 0.25f, 0.25f, 0.75f, 0.75f), eased)
        // ease-in-out is symmetric around its midpoint.
        assertEquals(0.5f, MainSplashTimeline.easeInOut(0.5f), eased)
        var previous = 0f
        for (step in 1..20) {
            val value = MainSplashTimeline.easeLanding(step / 20f)
            assertTrue(value >= previous - eased, "curve went backwards at step $step")
            previous = value
        }
        // The landing curve is front-loaded: most of the way there after a quarter of the time.
        assertTrue(MainSplashTimeline.easeLanding(0.25f) > 0.7f)
    }

    @Test
    fun markSpinsInOutOfABlur() {
        val hidden = MainSplashTimeline.logoProgress(MainSplashTimeline.LOGO_START_MS)
        assertEquals(0f, hidden, delta)
        assertEquals(0.4f, MainSplashTimeline.logoScale(hidden), delta)
        assertEquals(-12f, MainSplashTimeline.logoRotation(hidden), delta)
        assertEquals(14f, MainSplashTimeline.logoBlurDp(hidden), delta)

        val landed = MainSplashTimeline.logoProgress(MainSplashTimeline.LOGO_END_MS)
        assertEquals(1f, landed, delta)
        assertEquals(1f, MainSplashTimeline.logoScale(landed), delta)
        assertEquals(0f, MainSplashTimeline.logoRotation(landed), delta)
        assertEquals(0f, MainSplashTimeline.logoBlurDp(landed), delta)
    }

    @Test
    fun slashShootsOutThenFades() {
        assertEquals(0f, MainSplashTimeline.slashAlpha(0f), delta)
        assertEquals(0f, MainSplashTimeline.slashLengthDp(0f), delta)
        assertEquals(1f, MainSplashTimeline.slashAlpha(0.3f), delta)
        assertEquals(260f, MainSplashTimeline.slashLengthDp(0.6f), 0.01f)
        assertEquals(1f, MainSplashTimeline.slashAlpha(0.6f), delta)
        assertEquals(300f, MainSplashTimeline.slashLengthDp(1f), 0.01f)
        assertEquals(0f, MainSplashTimeline.slashAlpha(1f), delta)

        val start = MainSplashTimeline.SLASH_A_START_MS
        assertEquals(0f, MainSplashTimeline.slashProgress(start, start), delta)
        assertEquals(
            1f,
            MainSplashTimeline.slashProgress(start + MainSplashTimeline.SLASH_LENGTH_MS, start),
            delta,
        )
        assertTrue(MainSplashTimeline.SLASH_B_START_MS > MainSplashTimeline.SLASH_A_START_MS)
    }

    @Test
    fun ringsLoopAndAreStaggered() {
        val period = MainSplashTimeline.RING_PERIOD_MS
        val step = MainSplashTimeline.RING_STEP_MS

        // The first ring is alive from the first frame; later rings wait for their turn.
        assertEquals(0f, MainSplashTimeline.ringProgress(0f, 0), delta)
        assertTrue(MainSplashTimeline.ringProgress(step / 2f, 0) > 0f)
        assertEquals(0f, MainSplashTimeline.ringProgress(step / 2f, 1), delta)
        assertEquals(0f, MainSplashTimeline.ringProgress(step, 2), delta)

        // Each ring repeats with the same period.
        assertEquals(
            MainSplashTimeline.ringProgress(500f, 0),
            MainSplashTimeline.ringProgress(500f + period, 0),
            eased,
        )
        assertEquals(
            MainSplashTimeline.ringProgress(step + 500f, 1),
            MainSplashTimeline.ringProgress(500f, 0),
            eased,
        )

        assertEquals(0.7f, MainSplashTimeline.ringScale(0f), delta)
        assertEquals(2.4f, MainSplashTimeline.ringScale(1f), delta)
        assertEquals(0.7f, MainSplashTimeline.ringAlpha(0f), delta)
        assertEquals(0f, MainSplashTimeline.ringAlpha(1f), delta)
    }

    @Test
    fun nameTrackingTightensAsItLands() {
        val wide = MainSplashTimeline.brandLetterSpacingEm(0f)
        val settled = MainSplashTimeline.brandLetterSpacingEm(1f)
        assertEquals(0.8f, wide, delta)
        assertEquals(0.38f, settled, delta)
        assertTrue(
            MainSplashTimeline.brandLetterSpacingEm(0.5f) in settled..wide,
            "tracking must tighten monotonically",
        )
    }

    @Test
    fun taglineFollowsTheNameAndSettlesBeforeTheExit() {
        assertEquals(0f, MainSplashTimeline.taglineProgress(0f), delta)
        assertEquals(
            0f, MainSplashTimeline.taglineProgress(MainSplashTimeline.TAGLINE_START_MS), delta
        )
        assertTrue(MainSplashTimeline.TAGLINE_START_MS > MainSplashTimeline.BRAND_START_MS)
        assertEquals(
            1f, MainSplashTimeline.taglineProgress(MainSplashTimeline.TAGLINE_END_MS), delta
        )
        assertTrue(MainSplashTimeline.TAGLINE_END_MS < MainSplashTimeline.OUT_START_MS)
    }

    @Test
    fun loadingLineIsFullBeforeTheExit() {
        assertEquals(0f, MainSplashTimeline.barProgress(MainSplashTimeline.BAR_START_MS), delta)
        assertEquals(1f, MainSplashTimeline.barProgress(MainSplashTimeline.BAR_END_MS), delta)
        assertTrue(MainSplashTimeline.BAR_END_MS <= MainSplashTimeline.OUT_START_MS)
    }

    @Test
    fun overlayIsUntouchedUntilItLeaves() {
        val start = MainSplashTimeline.OUT_START_MS
        assertEquals(1f, MainSplashTimeline.overlayAlpha(0f), delta)
        assertEquals(1f, MainSplashTimeline.overlayAlpha(start), delta)
        assertEquals(1f, MainSplashTimeline.overlayScale(start), delta)
        assertEquals(0f, MainSplashTimeline.overlayBlurDp(start), delta)

        val end = MainSplashTimeline.TOTAL_MS
        assertEquals(0f, MainSplashTimeline.overlayAlpha(end), delta)
        assertEquals(1.15f, MainSplashTimeline.overlayScale(end), delta)
        assertEquals(8f, MainSplashTimeline.overlayBlurDp(end), delta)
        assertEquals(0f, MainSplashTimeline.overlayAlpha(end * 2f), delta)
    }

    @Test
    fun everyWindowEndsBeforeTheExit() {
        listOf(
            MainSplashTimeline.LOGO_END_MS,
            MainSplashTimeline.SLASH_A_START_MS + MainSplashTimeline.SLASH_LENGTH_MS,
            MainSplashTimeline.SLASH_B_START_MS + MainSplashTimeline.SLASH_LENGTH_MS,
            MainSplashTimeline.SWEEP_END_MS,
            MainSplashTimeline.BRAND_END_MS,
            MainSplashTimeline.TAGLINE_END_MS,
            MainSplashTimeline.BAR_END_MS,
        ).forEach { assertTrue(it <= MainSplashTimeline.OUT_START_MS, "window ends at $it") }
        assertTrue(MainSplashTimeline.OUT_START_MS < MainSplashTimeline.OUT_END_MS)
    }

    @Test
    fun homeScreenScalesInFromJustBelowFullSize() {
        assertEquals(MainSplashTimeline.REVEAL_START_SCALE, MainSplashTimeline.revealScale(0f), delta)
        assertEquals(1f, MainSplashTimeline.revealScale(1f), delta)
        assertEquals(1f, MainSplashTimeline.revealScale(4f), delta)
    }

    @Test
    fun leavingAndFinishFlagsFollowTheExitWindow() {
        assertFalse(MainSplashTimeline.isLeaving(MainSplashTimeline.OUT_START_MS - 1f))
        assertTrue(MainSplashTimeline.isLeaving(MainSplashTimeline.OUT_START_MS))
        assertFalse(MainSplashTimeline.isFinished(MainSplashTimeline.TOTAL_MS - 1f))
        assertTrue(MainSplashTimeline.isFinished(MainSplashTimeline.TOTAL_MS))
    }
}
