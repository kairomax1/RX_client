package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chooses the exit a game is played through.
 *
 * Gaming mode routes games through the selected exit. When gaming mode is enabled, this helper
 * compares saved configs using their already-measured real ping and selects a clearly faster one.
 * It does not scan third-party edge ranges or rewrite a config endpoint.
 *
 * Nothing here runs while the owner is playing: it is called when the mode is switched on
 * and once a day, and a switch only happens when the new exit is clearly sooner, so a
 * config does not flap between two addresses that measure the same.
 */
object GamingExitTuner {

    /** A new exit has to answer this much sooner before it replaces the current one. */
    private const val MARGIN_MS = 25L

    /**
     * Points the selected config at the fastest exit it can reach.
     *
     * @return the latency the exit now answers in, in milliseconds, or null when there was
     * nothing to do (gaming mode off, no config selected, nothing measurable).
     */
    suspend fun tune(context: Context): Long? = withContext(Dispatchers.IO) {
        if (!GamingMode.isEnabled()) return@withContext null
        // Switching the exit while a tunnel is carrying traffic would cut the game
        // the owner is in the middle of. The measurement waits for a moment when
        // nothing is connected; the daily pass and the next switch-on catch it.
        if (ServiceRuntimeState.isRunning(context)) return@withContext null
        fastestConfig()
    }

    /** Picks the quickest saved config with a positive, already-measured ping. */
    private fun fastestConfig(): Long? {
        val selected = MmkvManager.getSelectServer() ?: return null
        val measured = MmkvManager.decodeServerAffiliationInfo(selected)?.testDelayMillis ?: 0L

        var winner = selected
        var best = measured
        for (subscription in MmkvManager.decodeSubscriptions()) {
            for (guid in MmkvManager.decodeServerList(subscription.guid)) {
                if (guid == selected) continue
                val ping = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                if (ping <= 0L) continue
                if (best <= 0L || ping + MARGIN_MS < best) {
                    winner = guid
                    best = ping
                }
            }
        }

        if (winner == selected) {
            if (best > 0L) {
                LogUtil.i(AppConfig.TAG, "Gaming: the selected config is the fastest measured one (${best}ms)")
            }
            return best.takeIf { it > 0L }
        }

        val name = MmkvManager.decodeServerConfig(winner)?.remarks.orEmpty()
        MmkvManager.setSelectServer(winner)
        LogUtil.i(
            AppConfig.TAG,
            "Gaming: exit moved to the fastest measured config, $name (${best}ms, was ${measured.takeIf { it > 0L } ?: "unmeasured"})"
        )
        return best
    }
}
