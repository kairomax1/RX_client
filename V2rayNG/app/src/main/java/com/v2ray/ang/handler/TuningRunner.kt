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
    enum class Phase { Idle, Searching, Done, Unsupported }

    /** How long the tunnel is given to come up, and to go down again. */
    private const val UP_TIMEOUT_MS = 20_000L
    private const val DOWN_TIMEOUT_MS = 8_000L
    private const val POLL_MS = 250L

    /** A short rest after each attempt, so the line is not measured while settling. */
    private const val SETTLE_MS = 400L

    /**
     * Measures every combination worth trying and leaves the fastest one applied.
     * [onPhase] is called as it moves along, so the screen can say what is happening.
     */
    suspend fun tune(context: Context, guid: String, onPhase: (Phase) -> Unit): Tuning.Choice =
        withContext(Dispatchers.IO) {
            val profile = MmkvManager.decodeServerConfig(guid)
            if (guid.isBlank() || !Tuning.worthTrying(profile)) {
                // Nothing to look for: the config is connected exactly as it is.
                Tuning.apply(Tuning.PLAIN)
                Tuning.remember(guid, Tuning.PLAIN)
                onPhase(Phase.Unsupported)
                return@withContext Tuning.PLAIN
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

            Tuning.apply(best)
            Tuning.remember(guid, best)
            AppLog.info(AppLog.Section.Connection, "Tuning: ${best.key} was the fastest on this line")
            onPhase(Phase.Done)
            best
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
