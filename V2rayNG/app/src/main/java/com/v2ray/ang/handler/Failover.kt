package com.v2ray.ang.handler

/**
 * Moving to another config when the one in use stops carrying traffic.
 *
 * A config that answered yesterday is silent today, and the owner is left
 * switching by hand while nothing loads. This is the part that does it without
 * being asked: the next config of the same group is taken, the tunnel is raised
 * again, and nothing is said unless there is nothing left to take.
 *
 * It is deliberately short of breath. Three moves in a row is a round, and
 * when none of them carried anything the owner is told the way they always
 * were — a phone that keeps raising tunnels in the dark would only hide the
 * fact that the connection itself is gone. A config that stays up for a minute
 * ends the round, so the next failure starts from a full list again.
 */
object Failover {

    /** How many configs one round may go through. */
    private const val MAX_MOVES = 3

    /** How long a connection has to carry traffic before the round is forgotten. */
    const val SETTLE_MILLIS = 60_000L

    /** Configs already tried in this round. */
    private val tried = LinkedHashSet<String>()

    private var moves = 0

    /** Called when the owner connects or disconnects by hand: the slate is clean. */
    @Synchronized
    fun reset() {
        tried.clear()
        moves = 0
    }

    /** True while the round is not over yet. */
    @Synchronized
    fun canMove(): Boolean = moves < MAX_MOVES

    /**
     * The config to try next: one that is not the one that just failed and has
     * not been tried in this round. Those whose latency is known come first,
     * quickest first, because a measured number is better a guess than the
     * order the list happens to be in; the rest keep their order.
     */
    @Synchronized
    fun next(current: String?, guids: List<String>, pingOf: (String) -> Long): String? {
        if (!canMove()) return null
        val pool = guids.filter { guid -> guid.isNotBlank() && guid != current && guid !in tried }
        if (pool.isEmpty()) return null
        val measured = pool.filter { pingOf(it) > 0L }.sortedBy { pingOf(it) }
        return measured.firstOrNull() ?: pool.firstOrNull()
    }

    /** Remembers that [guid] is being tried now. */
    @Synchronized
    fun moved(guid: String) {
        tried.add(guid)
        moves++
    }
}
