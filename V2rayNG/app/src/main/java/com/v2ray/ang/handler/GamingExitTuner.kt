package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chooses the exit a game is played through.
 *
 * Gaming mode already sends a game out on whichever line the owner picked; what it cannot
 * know is *which* address on that line answers soonest. A VLESS config that hides behind a
 * content network reaches one of hundreds of edges, and anycast does not make them equal:
 * two addresses of the same config can differ by more than the whole ping of a game. So the
 * edges are measured, and the config is pointed at the one that answers first.
 *
 * A config that does not hide behind such a network is compared with the owner's other
 * configs by the ping the app already measures for each of them, and the fastest one wins
 * whatever country it leaves from — a game cares about the round trip, not the flag.
 *
 * Nothing here runs while the owner is playing: it is called when the mode is switched on
 * and once a day, and a switch only happens when the new exit is clearly sooner, so a
 * config does not flap between two addresses that measure the same.
 */
object GamingExitTuner {

    /** A new exit has to answer this much sooner before it replaces the current one. */
    private const val MARGIN_MS = 25L

    /** Anything slower than this is not a gaming exit, and is not measured further. */
    private const val TIMEOUT_MS = 1_500

    /**
     * Points the selected config at the fastest exit it can reach.
     *
     * @return the latency the exit now answers in, in milliseconds, or null when there was
     * nothing to do (gaming mode off, no config selected, nothing measurable).
     */
    suspend fun tune(
        context: Context,
        groupName: String,
        remarksSuffix: String,
    ): Long? = withContext(Dispatchers.IO) {
        if (!GamingMode.isEnabled()) return@withContext null
        // Switching the exit while a tunnel is carrying traffic would cut the game
        // the owner is in the middle of. The measurement waits for a moment when
        // nothing is connected; the daily pass and the next switch-on catch it.
        if (ServiceRuntimeState.isRunning(context)) return@withContext null
        fastestEdge(context, groupName, remarksSuffix) ?: fastestConfig()
    }

    /**
     * For a config that hides behind a content network: measure a sample of edges and, when
     * one answers clearly sooner than the address the config points at now, keep a copy of
     * the config pointing at it. The owner's own profile is never rewritten.
     */
    private suspend fun fastestEdge(
        context: Context,
        groupName: String,
        remarksSuffix: String,
    ): Long? {
        val guid = CloudflareAssistant.selectedGuid() ?: return null
        val config = CloudflareAssistant.selectedConfig() ?: return null
        if (!CloudflareAssistant.isCloudflareConfig(config)) return null
        val host = config.server?.trim().orEmpty()
        if (host.isEmpty()) return null

        val sni = config.sni?.takeIf { it.isNotBlank() } ?: config.host
        val now = CloudflareAssistant.measure(host, sni, TIMEOUT_MS)
        val best = CloudflareAssistant.scan(sni, timeoutMs = TIMEOUT_MS, concurrency = 16)
            .firstOrNull() ?: return null

        if (now != null && best.latencyMs + MARGIN_MS >= now) {
            LogUtil.i(
                AppConfig.TAG,
                "Gaming: the exit answers in ${now}ms; the fastest edge measured ${best.latencyMs}ms, " +
                    "which is not enough sooner to be worth the change"
            )
            return now
        }

        val report = CloudflareAssistant.buildCloudflareConfig(
            context = context,
            sourceGuid = guid,
            address = best.address,
            groupName = groupName,
            remarksSuffix = remarksSuffix,
        )
        LogUtil.i(
            AppConfig.TAG,
            "Gaming: exit moved to ${best.address} (handshake ${best.latencyMs}ms, was ${now ?: "no answer"})" +
                (report?.latencyMs?.let { ", real ping ${it}ms" } ?: "")
        )
        return report?.latencyMs ?: best.latencyMs
    }

    /**
     * For a config that does not hide behind a content network: the app already measures a
     * ping for every config it has been asked to test. The fastest of those wins, whichever
     * country it leaves from.
     */
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
