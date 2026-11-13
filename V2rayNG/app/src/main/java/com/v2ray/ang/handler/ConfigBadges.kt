package com.v2ray.ang.handler

/** The mark a config can carry in its group after the automatic test. */
enum class ConfigBadge { Recommended, LowestPing, Fastest }

/**
 * Decides which configs of one group carry a badge.
 *
 * Pure on purpose: the rules are easy to get subtly wrong and are covered by JVM tests.
 */
object ConfigBadges {

    /** One config's numbers. [pingMs] <= 0 means unreachable; [speedKbps] null means not measured. */
    data class Score(val guid: String, val pingMs: Long, val speedKbps: Int? = null)

    /**
     * At most one badge per config and one config per badge.
     *
     * A config that wins both ping and speed is the recommended one, and the two single titles go
     * to the runners-up. When ping and speed have different winners each keeps its own title, and
     * the recommended one is the best all-rounder among the rest (best combined ping and speed
     * place). Unreachable configs never get a badge.
     */
    fun assign(scores: Collection<Score>): Map<String, ConfigBadge> {
        val reachable = scores.filter { it.pingMs > 0 }
        if (reachable.isEmpty()) return emptyMap()

        val byPing = reachable.sortedWith(compareBy<Score> { it.pingMs }.thenByDescending { it.speedKbps ?: 0 })
        val bySpeed = reachable.filter { (it.speedKbps ?: 0) > 0 }
            .sortedWith(compareByDescending<Score> { it.speedKbps ?: 0 }.thenBy { it.pingMs })

        val result = LinkedHashMap<String, ConfigBadge>()
        val lowest = byPing.first()
        val fastest = bySpeed.firstOrNull()

        if (fastest == null) {
            // No speed could be measured at all: only the ping title means anything.
            result[lowest.guid] = ConfigBadge.LowestPing
            return result
        }

        if (lowest.guid == fastest.guid) {
            result[lowest.guid] = ConfigBadge.Recommended
            byPing.firstOrNull { it.guid !in result }?.let { result[it.guid] = ConfigBadge.LowestPing }
            bySpeed.firstOrNull { it.guid !in result }?.let { result[it.guid] = ConfigBadge.Fastest }
            return result
        }

        result[lowest.guid] = ConfigBadge.LowestPing
        result[fastest.guid] = ConfigBadge.Fastest
        val pingPlace = byPing.withIndex().associate { it.value.guid to it.index }
        val speedPlace = bySpeed.withIndex().associate { it.value.guid to it.index }
        bySpeed.filter { it.guid !in result }
            .minWithOrNull(
                compareBy<Score> { pingPlace.getValue(it.guid) + speedPlace.getValue(it.guid) }
                    .thenBy { it.pingMs }
            )
            ?.let { result[it.guid] = ConfigBadge.Recommended }
        return result
    }

    /** The reachable configs worth a speed measurement: the [limit] lowest pings. */
    fun speedCandidates(pings: Map<String, Long>, limit: Int = 5): List<String> =
        pings.entries.filter { it.value > 0 }.sortedBy { it.value }.take(limit).map { it.key }
}
