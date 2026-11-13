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

    private const val PAYLOAD_BYTES = 600_000
    private const val MAX_DOWNLOAD_MS = 6_000L
    private val PAYLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=$PAYLOAD_BYTES",
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

    private fun save(guid: String, value: Stored) {
        storage.encode(guid, "${value.pingMs},${value.speedKbps ?: ""}")
    }

    /** Download speed of one config in kbit/s, or null when nothing could be measured. */
    private suspend fun measureSpeed(context: Context, guid: String): Int? = withContext(Dispatchers.IO) {
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
            delay(250) // let the local door open
            download(port)
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

    private fun download(port: Int): Int? {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(MAX_DOWNLOAD_MS + 5_000L, TimeUnit.MILLISECONDS)
            .build()
        try {
            for (url in PAYLOAD_URLS) {
                val speed = runCatching { downloadOnce(client, url) }.getOrNull()
                if (speed != null) return speed
            }
            return null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun downloadOnce(client: OkHttpClient, url: String): Int? {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val input = response.body?.byteStream() ?: return null
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            // The clock starts at the first byte so the handshake does not count as slowness.
            val started = System.nanoTime()
            while (total < PAYLOAD_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if ((System.nanoTime() - started) / 1_000_000 > MAX_DOWNLOAD_MS) break
            }
            val millis = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            if (total < 20_000) return null
            return (total * 8 / millis).toInt().coerceAtLeast(1) // bits per millisecond = kbit/s
        }
    }

    private object QuietCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
