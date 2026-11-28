package com.v2ray.ang.handler

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one route's transient failures from immediately sending every new
 * tunnel back to the same dead endpoint. A success clears that endpoint's
 * streak; two consecutive failures quarantine it briefly so a verified spare
 * can be used without making a single dropped request cause route flapping.
 */
internal class FirefoxEndpointFailures(
    private val failureThreshold: Int = 2,
    private val quarantineMillis: Long = 60_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class State(
        val consecutiveFailures: Int = 0,
        val quarantinedUntilMillis: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, State>()

    init {
        require(failureThreshold > 0)
        require(quarantineMillis >= 0L)
    }

    /** Returns true only when this failure newly quarantines the endpoint. */
    fun recordFailure(endpoint: FirefoxVpnRelay.Endpoint): Boolean {
        val key = key(endpoint)
        val now = nowMillis()
        var newlyQuarantined = false
        states.compute(key) { _, previous ->
            if (previous != null && previous.quarantinedUntilMillis > now) {
                previous
            } else {
                val streak = (previous?.consecutiveFailures ?: 0) + 1
                if (streak >= failureThreshold) {
                    newlyQuarantined = true
                    State(quarantinedUntilMillis = now + quarantineMillis)
                } else {
                    State(consecutiveFailures = streak)
                }
            }
        }
        return newlyQuarantined
    }

    /** An authenticated tunnel succeeding proves this endpoint is healthy again. */
    fun recordSuccess(endpoint: FirefoxVpnRelay.Endpoint) {
        states.remove(key(endpoint))
    }

    fun isQuarantined(endpoint: FirefoxVpnRelay.Endpoint): Boolean {
        val state = states[key(endpoint)] ?: return false
        if (state.quarantinedUntilMillis <= nowMillis()) {
            states.remove(key(endpoint), state)
            return false
        }
        return true
    }

    fun reset() {
        states.clear()
    }

    private fun key(endpoint: FirefoxVpnRelay.Endpoint): String =
        "${endpoint.host.lowercase()}:${endpoint.port}"
}
