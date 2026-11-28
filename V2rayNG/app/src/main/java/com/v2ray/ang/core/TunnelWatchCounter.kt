package com.v2ray.ang.core

/**
 * Consecutive health-check state for the core-owned public-config watcher.
 * An in-place reload is deliberately not a reset: if the same server remains
 * silent, the next result must still be able to trigger a move to its sibling.
 */
internal class TunnelWatchCounter(
    private val reloadAfter: Int,
    private val moveAfter: Int,
) {
    enum class Decision { WAIT, CARRIES, RELOAD, MOVE }

    var misses: Int = 0
        private set

    init {
        require(reloadAfter > 0)
        require(moveAfter > reloadAfter)
    }

    fun observe(carries: Boolean, reachableToAsk: Boolean): Decision {
        if (carries) {
            misses = 0
            return Decision.CARRIES
        }
        // A question the phone could not ask is not evidence against the config.
        if (!reachableToAsk) return Decision.WAIT

        misses++
        return when {
            misses == reloadAfter -> Decision.RELOAD
            misses >= moveAfter -> Decision.MOVE
            else -> Decision.WAIT
        }
    }

    /** A one-server group keeps retrying after reload without waiting for a new round. */
    fun retainOneMiss() {
        misses = 1
    }
}
