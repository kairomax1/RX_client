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
    suspend fun tune(context: Context, guid: String, onPhase: (Phase) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            val profile = MmkvManager.decodeServerConfig(guid)
            val storedDelay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            if (guid.isBlank() || !Tuning.worthTrying(profile)) {
                if (storedDelay < 0L && guid.isNotBlank()) {
                    onPhase(Phase.Searching)
                    Tuning.apply(Tuning.PLAIN)
                    val millis = withChoice(context) { Tuning.measure() }
                    if (millis != null) {
                        Tuning.remember(guid, Tuning.PLAIN)
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
                Tuning.remember(guid, Tuning.PLAIN)
                onPhase(Phase.Unsupported)
                return@withContext Outcome(Tuning.PLAIN, bypassed = true)
            }

            onPhase(Phase.Searching)
            AppLog.info(AppLog.Section.Connection, "Tuning: looking for the fastest settings")
            var best = Tuning.PLAIN
            // The answer from last time leads the search and starts as the one to
            // beat: a line that has not changed costs two attempts instead of five,
            // and one that has changed is still found because the rest is measured.
            val known = Tuning.best(guid)?.takeIf { !Tuning.stale(guid) }
            if (known != null) best = known
            var bestMillis = Long.MAX_VALUE

            for (choice in Tuning.plan(profile, known)) {
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
                Tuning.forget(guid)
                Tuning.markUnbypassable(guid)
                MmkvManager.encodeServerTestDelayMillis(guid, -1L)
                Tuning.apply(Tuning.PLAIN)
                LauncherManager.stopService(context)
                AppLog.error(
                    AppLog.Section.Connection,
                    "Tuning: could not bypass config with fragment or mux; stopping",
                )
                onPhase(Phase.Failed)
                return@withContext Outcome(Tuning.PLAIN, bypassed = false)
            }

            Tuning.apply(best)
            Tuning.remember(guid, best)
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
