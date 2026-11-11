package com.v2ray.ang.core

/**
 * Decisions of the automatic failover, kept free of Android and core types so they can be tested
 * on the JVM. [CoreServiceManager] owns the loop that asks these questions; nothing here measures,
 * stores or restarts anything.
 *
 * The rule the whole feature rests on: the selected config is only replaced by one that has just
 * answered. When nothing answers, the phone is most likely offline, so the selection stays as it is
 * and the search is retried later with a growing pause.
 */
object FailoverPolicy {

    /** A config of the same group together with the delay of its last test. */
    data class Candidate(val guid: String, val lastDelayMs: Long)

    /** Pause between two probes of the running tunnel while the screen is on. */
    const val PROBE_INTERVAL_MS = 30_000L

    /** Pause while the screen is off, so an idle phone is not woken twice a minute. */
    const val PROBE_INTERVAL_SCREEN_OFF_MS = 120_000L

    /** Failed probes in a row before the tunnel counts as down; one lost request is not an outage. */
    const val FAILURES_BEFORE_SWITCH = 3

    /** Upper bound of configs measured in one search, each one is a full round trip. */
    const val MAX_CANDIDATES = 24

    /** Longest pause between two searches that found nothing. */
    const val MAX_BACKOFF_MS = 600_000L

    /**
     * Pause before the next probe. Every search that found no working config doubles it, up to
     * [MAX_BACKOFF_MS], which keeps an offline phone from testing the whole group in a loop.
     */
    fun nextProbeDelayMs(screenOn: Boolean, emptySearches: Int): Long {
        val base = if (screenOn) PROBE_INTERVAL_MS else PROBE_INTERVAL_SCREEN_OFF_MS
        if (emptySearches <= 0) return base
        // 2^5 already exceeds the cap for both bases, a larger shift could only overflow.
        val factor = 1L shl emptySearches.coerceAtMost(5)
        return (base * factor).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** New count of failed probes in a row. */
    fun nextFailureCount(previous: Int, probeSucceeded: Boolean): Int =
        if (probeSucceeded) 0 else previous + 1

    fun shouldSearch(failuresInRow: Int): Boolean = failuresInRow >= FAILURES_BEFORE_SWITCH

    /**
     * Configs worth measuring, most promising first: the ones whose last test succeeded (fastest
     * first), then the never tested ones, then the ones that failed last time. The running config
     * is left out, it is the one that just stopped answering.
     */
    fun orderCandidates(
        currentGuid: String,
        group: List<Candidate>,
        limit: Int = MAX_CANDIDATES,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return group
            .asSequence()
            .filter { it.guid.isNotBlank() && it.guid != currentGuid }
            .distinctBy { it.guid }
            .sortedWith(compareBy<Candidate> { rank(it.lastDelayMs) }.thenBy { it.lastDelayMs })
            .map { it.guid }
            .take(limit)
            .toList()
    }

    /** The fastest config that answered, or null when none did. */
    fun pickBest(results: Map<String, Long>): String? =
        results.entries
            .filter { it.value > 0L }
            .minByOrNull { it.value }
            ?.key

    private fun rank(lastDelayMs: Long): Int = when {
        lastDelayMs > 0L -> 0
        lastDelayMs == 0L -> 1
        else -> 2
    }
}
