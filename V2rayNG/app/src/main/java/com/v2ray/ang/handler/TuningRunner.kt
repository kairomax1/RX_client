package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.core.LauncherManager
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Runs the search for the fastest mux and fragment settings, once per config.
 *
 * Every combination has to be tried for real: the tunnel is raised with it, a
 * request is sent through it and timed, and the tunnel is taken down again. That
 * is slower than guessing, but guessing is what "it depends on your line" means
 * nobody can do. The whole pass happens before the connection the user asked for,
 * and only for a config that has never been measured on this phone.
 */
object TuningRunner {

    /** What the screen says while it happens. */
    enum class Phase { Idle, Searching, Done, Unsupported, Failed }

    data class Outcome(val choice: Tuning.Choice, val bypassed: Boolean)

    /** How long the tunnel is given to come up, and to go down again. */
    private const val UP_TIMEOUT_MS = 20_000L
    private const val DOWN_TIMEOUT_MS = 8_000L
    private const val POLL_MS = 250L

    /** A short rest after each attempt, so the line is not measured while settling. */
    private const val SETTLE_MS = 400L

    /**
     * Measures every combination worth trying and leaves the fastest one applied.
     * If the config failed (`-1ms`) and neither plain nor fragment/mux carries
     * traffic, the tunnel is stopped and [Outcome.bypassed] is `false`.
     * [onPhase] is called as it moves along, so the screen can say what is happening.
     */
    suspend fun tune(
        context: Context,
        guid: String,
        mode: Tuning.Mode = Tuning.currentMode(),
        onPhase: (Phase) -> Unit,
    ): Outcome =
        withContext(Dispatchers.IO) {
            val profile = MmkvManager.decodeServerConfig(guid)
            val storedDelay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            val allowMux = mode.mux
            val allowFragment = mode.fragment
            if (guid.isBlank() || !Tuning.worthTrying(profile, allowMux = allowMux, allowFragment = allowFragment)) {
                if (storedDelay < 0L && guid.isNotBlank()) {
                    onPhase(Phase.Searching)
                    Tuning.apply(Tuning.PLAIN)
                    val millis = withChoice(context) { Tuning.measure() }
                    if (millis != null) {
                        Tuning.remember(guid, Tuning.PLAIN, mode)
                        MmkvManager.encodeServerTestDelayMillis(guid, millis)
                        onPhase(Phase.Done)
                        return@withContext Outcome(Tuning.PLAIN, bypassed = true)
                    }
                    Tuning.forget(guid)
                    Tuning.markUnbypassable(guid)
                    LauncherManager.stopService(context)
                    onPhase(Phase.Failed)
                    return@withContext Outcome(Tuning.PLAIN, bypassed = false)
                }
                // Nothing to look for: the config is connected exactly as it is.
                Tuning.apply(Tuning.PLAIN)
                Tuning.remember(guid, Tuning.PLAIN, mode)
                onPhase(Phase.Unsupported)
                return@withContext Outcome(Tuning.PLAIN, bypassed = true)
            }

            onPhase(Phase.Searching)
            AppLog.info(AppLog.Section.Connection, "Tuning ($mode): looking for the fastest settings")
            var best = Tuning.PLAIN
            // The answer from last time leads the search and starts as the one to
            // beat: a line that has not changed costs two attempts instead of five,
            // and one that has changed is still found because the rest is measured.
            val known = Tuning.bestForCurrentMode(guid)?.takeIf { !Tuning.stale(guid) }
            if (known != null) best = known
            var bestMillis = Long.MAX_VALUE

            val custom = Tuning.userCustomChoice(
                allowMux = allowMux && Tuning.supportsMux(profile),
                allowFragment = allowFragment && Tuning.supportsFragment(profile),
            )
            val basePlan = Tuning.plan(profile, known, allowMux = allowMux, allowFragment = allowFragment)
            val candidates = if ((custom.mux || custom.fragment) && basePlan.none {
                    it.mux == custom.mux && it.fragment == custom.fragment &&
                        it.packets == custom.packets && it.length == custom.length &&
                        it.interval == custom.interval && it.maxSplit == custom.maxSplit &&
                        it.concurrency == custom.concurrency
                }
            ) {
                basePlan + custom
            } else {
                basePlan
            }

            for (choice in candidates) {
                coroutineContext.ensureActive()
                Tuning.apply(choice)
                val millis = withChoice(context) { Tuning.measure() }
                AppLog.info(
                    AppLog.Section.Connection,
                    "Tuning: ${choice.key} " + (millis?.let { "answered in $it ms" } ?: "carried nothing")
                )
                // Only a setting that actually carried something can win, so a
                // combination the line refuses is never chosen however it looks.
                if (millis != null && millis < bestMillis) {
                    bestMillis = millis
                    best = choice
                }
            }

            if (bestMillis == Long.MAX_VALUE) {
                // On very limited/slow internet probes may time out; apply a conservative
                // best-guess instead of giving up, so fragment/mux still take effect.
                val fallback = Tuning.networkPreset(
                    isCellular = false,
                    probeLatencyMs = null,
                    mode = mode,
                )
                val fallbackSanitized = Tuning.sanitizeForCurrentMode(fallback, mode)
                val chosen = if (fallbackSanitized != Tuning.PLAIN) fallbackSanitized
                else candidates.firstOrNull { it != Tuning.PLAIN } ?: Tuning.PLAIN
                Tuning.apply(chosen)
                if (chosen != Tuning.PLAIN) {
                    Tuning.remember(guid, chosen, mode)
                } else {
                    Tuning.forget(guid)
                    Tuning.markUnbypassable(guid)
                }
                MmkvManager.encodeServerTestDelayMillis(guid, -1L)
                AppLog.info(
                    AppLog.Section.Connection,
                    "Tuning: probes timed out on slow network; applying fallback ${chosen.key}",
                )
                onPhase(if (chosen != Tuning.PLAIN) Phase.Done else Phase.Failed)
                return@withContext Outcome(chosen, bypassed = chosen != Tuning.PLAIN)
            }

            Tuning.apply(best)
            Tuning.remember(guid, best, mode)
            if (storedDelay < 0L) {
                MmkvManager.encodeServerTestDelayMillis(guid, bestMillis)
            }
            AppLog.info(AppLog.Section.Connection, "Tuning: ${best.key} was the fastest on this line")
            onPhase(Phase.Done)
            Outcome(best, bypassed = true)
        }

    /** Raises the tunnel with the settings already applied, measures, and takes it down. */
    private suspend fun withChoice(context: Context, measure: suspend () -> Long?): Long? {
        coroutineContext.ensureActive()
        if (!LauncherManager.startService(context, quiet = true)) return null
        if (!waitFor(context, wanted = true, UP_TIMEOUT_MS)) {
            LauncherManager.stopService(context)
            waitFor(context, wanted = false, DOWN_TIMEOUT_MS)
            return null
        }
        delay(SETTLE_MS)
        val millis = measure()
        coroutineContext.ensureActive()
        LauncherManager.stopService(context)
        waitFor(context, wanted = false, DOWN_TIMEOUT_MS)
        delay(SETTLE_MS)
        return millis
    }

    /** Waits until the tunnel is in the state [wanted]; false when it never got there. */
    private suspend fun waitFor(context: Context, wanted: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ServiceRuntimeState.isRunning(context) == wanted) return true
            delay(POLL_MS)
        }
        return ServiceRuntimeState.isRunning(context) == wanted
    }
}
