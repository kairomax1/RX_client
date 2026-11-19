package com.v2ray.ang.handler

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The quiet test that runs by itself after configs are added: the test service pings every new
 * config, then this object downloads a small payload through the five with the lowest ping. The
 * numbers decide the badges and the order of the list.
 *
 * A config that is not connected has no tunnel to measure through, so each speed measurement
 * starts a private core for that one config with a local HTTP door, downloads a small payload
 * through it and shuts it down again. The running VPN, if any, is not touched.
 */
object AutoConfigTest {

    /** Kept apart from the settings on purpose: these are measurements and never go into a backup. */
    const val STORAGE_ID = "RX_AUTO_TEST"
    private val storage by lazy { MMKV.mmkvWithID(STORAGE_ID, MMKV.MULTI_PROCESS_MODE) }

    // What decides a config is whether bytes come through, not how many. A small
    // download is enough to tell a working tunnel from a dead one, and asking for
    // a large one only added waiting to every candidate on a slow but working
    // line: at 4 kbit/s — which is what one of hers really carried — a sixth of a
    // megabyte is two minutes, so the measurement gave up on a config that was
    // in fact carrying. The floor and the window are both smaller now, and the
    // window is shorter than the deadline the caller gives the whole probe.
    private const val PAYLOAD_BYTES = 300_000
    private const val FAST_PAYLOAD_BYTES = 65_536
    private const val MAX_DOWNLOAD_MS = 3_500L
    private const val FAST_MAX_DOWNLOAD_MS = 2_000L
    private val PAYLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=$PAYLOAD_BYTES",
        "https://cachefly.cachefly.net/1mb.test",
    )
    private val FAST_PAYLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=$FAST_PAYLOAD_BYTES",
        "https://cachefly.cachefly.net/1mb.test",
    )

    private data class Stored(val pingMs: Long = 0L, val speedKbps: Int? = null)

    /**
     * Second half of the automatic test. The pings were just measured by the test service (the
     * same one behind "test all"); this stores them and measures the speed of the best few.
     */
    suspend fun finish(context: Context, guids: List<String>) {
        if (guids.isEmpty()) return
        val pings = guids.associateWith { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L }
        pings.forEach { (guid, ping) -> save(guid, Stored(pingMs = ping)) }
        for (guid in ConfigBadges.speedCandidates(pings)) {
            coroutineContext.ensureActive()
            val speed = measureSpeed(context, guid)
            save(guid, Stored(pingMs = pings[guid] ?: 0L, speedKbps = speed))
        }
    }

    /** Puts the configs of [groupId] in badge order and returns whether anything moved. */
    fun sortGroup(groupId: String): Boolean {
        val list = MmkvManager.decodeServerList(groupId)
        if (list.size < 2) return false
        val badges = ConfigBadges.assign(scores(list))
        val sorted = ConfigBadges.order(list, badges) { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L }
        if (sorted == list) return false
        MmkvManager.encodeServerList(ArrayList(sorted), groupId)
        return true
    }

    /** Stored numbers of the given configs, for the badge rules. */
    fun scores(guids: Collection<String>): List<ConfigBadges.Score> = guids.mapNotNull { guid ->
        // Stored as "ping,speed" (speed empty when not measured).
        val parts = storage.decodeString(guid)?.split(',') ?: return@mapNotNull null
        val ping = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        ConfigBadges.Score(guid, ping, parts.getOrNull(1)?.toIntOrNull())
    }

    fun record(guid: String, pingMs: Long, speedKbps: Int?) {
        save(guid, Stored(pingMs = pingMs, speedKbps = speedKbps))
    }

    private fun save(guid: String, value: Stored) {
        storage.encode(guid, "${value.pingMs},${value.speedKbps ?: ""}")
    }

    /**
     * Carries real bytes through this config right now, and says how quickly.
     *
     * A ping is only a handshake: a UDP sort — WireGuard above all — answers a
     * handshake and then moves no data at all when its MTU is wrong or its
     * carrier's NAT entry is gone, which is exactly the state that was reported
     * as "it pings and does not work". Only a download can tell the difference,
     * so this is what decides whether a config is offered to anybody.
     *
     * @return kbit/s measured, or null when nothing came through in [timeoutMillis].
     */
    suspend fun probe(context: Context, guid: String, timeoutMillis: Long = 14_000L): Int? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) { measureSpeed(context, guid) }?.takeIf { it > 0 }
        }

    /**
     * Fast real-byte transfer check for bulk candidate verification after a
     * proxy has already passed an Xray round-trip probe.
     */
    suspend fun probeFast(context: Context, guid: String, timeoutMillis: Long = 5_000L): Int? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) { measureSpeed(context, guid, fast = true) }?.takeIf { it > 0 }
        }

    /** Download speed of one config in kbit/s, or null when nothing could be measured. */
    private suspend fun measureSpeed(context: Context, guid: String, fast: Boolean = false): Int? = withContext(Dispatchers.IO) {
        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrNull() ?: return@withContext null
        val config = probeConfig(context, guid, port) ?: return@withContext null
        val controller = try {
            CoreNativeManager.initCoreEnv(context)
            CoreNativeManager.newCoreController(QuietCallback)
        } catch (e: Throwable) {
            LogUtil.e(AppConfig.TAG, "Speed probe: no core controller", e)
            return@withContext null
        }
        try {
            controller.startLoop(config, 0)
            if (!controller.isRunning) return@withContext null
            delay(if (fast) 100L else 250L) // let the local door open
            download(port, fast)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "Speed probe failed: ${e.message}")
            null
        } finally {
            runCatching { controller.stopLoop() }
        }
    }

    /** The config's own outbound with nothing else but one local HTTP inbound on [port]. */
    private fun probeConfig(context: Context, guid: String, port: Int): String? {
        val built = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!built.status) return null
        val json = JsonUtil.parseString(built.content) ?: return null
        val inbound = JsonObject().apply {
            addProperty("tag", "rx-probe")
            addProperty("listen", "127.0.0.1")
            addProperty("port", port)
            addProperty("protocol", "http")
        }
        json.add("inbounds", JsonArray().apply { add(inbound) })
        return json.toString()
    }

    private fun download(port: Int, fast: Boolean = false): Int? {
        val payloadBytes = if (fast) FAST_PAYLOAD_BYTES else PAYLOAD_BYTES
        val maxDownloadMs = if (fast) FAST_MAX_DOWNLOAD_MS else MAX_DOWNLOAD_MS
        val urls = if (fast) FAST_PAYLOAD_URLS else PAYLOAD_URLS
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            // Two seconds to the local door, and a read that only has to be
            // alive rather than fast: a slow line sends a few bytes at a time and
            // is still a working config. The whole call is bounded so that a
            // candidate can never hold the round for longer than its share.
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(if (fast) 2500L else 4000L, TimeUnit.MILLISECONDS)
            .callTimeout(maxDownloadMs + (if (fast) 1_200L else 1_500L), TimeUnit.MILLISECONDS)
            .build()
        try {
            for (url in urls) {
                val attempt = try {
                    downloadOnce(client, url, payloadBytes, maxDownloadMs)
                } catch (_: Throwable) {
                    // When fast-probing, a socket/timeout failure means the tunnel
                    // itself stalled; move on immediately instead of waiting a second timeout.
                    if (fast) return null
                    null
                }
                if (attempt != null) return attempt
            }
            return null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun downloadOnce(client: OkHttpClient, url: String, payloadBytes: Int, maxDownloadMs: Long): Int? {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val input = response.body?.byteStream() ?: return null
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            // The clock starts at the first byte so the handshake does not count as slowness.
            val started = System.nanoTime()
            while (total < payloadBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if ((System.nanoTime() - started) / 1_000_000 > maxDownloadMs) break
            }
            val millis = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            // 4 kB is a fifth of a second at 4 kbit/s: enough to prove the tunnel
            // moves bytes, small enough that a slow line is not called dead.
            if (total < 4_000) return null
            return (total * 8 / millis).toInt().coerceAtLeast(1) // bits per millisecond = kbit/s
        }
    }

    private object QuietCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
