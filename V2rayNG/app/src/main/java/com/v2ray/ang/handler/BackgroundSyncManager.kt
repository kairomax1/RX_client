package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden background updater — zero UI footprint.
 *
 * Silently keeps every core subsystem updated from its upstream source:
 * 1. RemoteControl (`rx-control.json`)
 * 2. Mozilla VPN server list (`FirefoxVpnServers`)
 * 3. Core routing databases (`geoip-only-cn-private.dat`, `geoip.dat`, `geosite.dat`) + core check
 * 4. Public Configs (GitHub + Telegram channels + offline cache)
 * 5. DNS Scanner resolver lists (`XboxDns`)
 * 6. Ad-block / Site lists (`SiteLists`)
 */
object BackgroundSyncManager {

    private const val WORK_NAME = "rx_hidden_background_sync"
    private const val PREF_GEO_UPDATED_AT = "pref_rx_bg_geo_updated_at"
    private const val PREF_CORE_TAG = "pref_rx_bg_xray_core_tag"
    private const val PREF_LAST_SYNC_AT = "pref_rx_bg_last_sync_at"
    private const val GEO_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val MIN_SYNC_GAP_MS = 30 * 60 * 1000L

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Non-blocking kick on app startup so fresh policy/caches stay warm. */
    fun kickIfDue(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val last = runCatching { MmkvManager.decodeSettingsLong(PREF_LAST_SYNC_AT, 0L) }.getOrDefault(0L)
        if (now - last < MIN_SYNC_GAP_MS) return
        syncScope.launch {
            runCatching { syncAll(appContext, force = false) }
        }
    }

    /** Runs all silent background updates independently; failures in one never block others. */
    suspend fun syncAll(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!inFlight.compareAndSet(false, true)) return@withContext
        try {
            val appContext = context.applicationContext
            if (!PublicConfigs.isNetworkAvailable(appContext)) return@withContext

            MmkvManager.encodeSettings(PREF_LAST_SYNC_AT, System.currentTimeMillis())

            // 1. RemoteControl first so subsequent steps get any updated URLs/flags.
            runCatching { RemoteControl.refreshIfDue(force = force) }

            // 2. Mozilla VPN server list.
            if (RemoteControl.isFeatureEnabled { it.mozillaVpn }) {
                runCatching { FirefoxVpnServers.refreshDailyIfDue() }
            }

            // 3. Core geo databases & core version metadata.
            runCatching { refreshCoreAssetsIfDue(appContext, force = force) }
            runCatching { checkXrayCoreMetadata() }

            // 4. Public Configs (GitHub + Telegram sources).
            if (RemoteControl.isFeatureEnabled { it.publicConfigs }) {
                runCatching { PublicConfigs.refreshSourcesDaily(appContext, force = force) }
                runCatching { PublicConfigs.refreshIfDue(appContext) }
            }

            // 5. DNS Scanner resolver lists.
            if (RemoteControl.isFeatureEnabled { it.dnsScanner }) {
                runCatching { XboxDns.refreshDailyIfDue(appContext, force = force) }
            }

            // 6. Ad-block list (with YouTube & essential domains protected).
            if (RemoteControl.isFeatureEnabled { it.adBlock }) {
                runCatching { SiteLists.refreshIfDue() }
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun refreshCoreAssetsIfDue(context: Context, force: Boolean) {
        val policy = RemoteControl.current().core
        if (!policy.silentGeoUpdate) return

        val now = System.currentTimeMillis()
        val last = runCatching { MmkvManager.decodeSettingsLong(PREF_GEO_UPDATED_AT, 0L) }.getOrDefault(0L)
        if (!force && last > 0L && now - last < GEO_INTERVAL_MS) return

        val assetDir = File(Utils.userAssetPath(context)).apply { mkdirs() }
        val cnUrl = policy.geoipCnPrivateUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: AppConfig.GEOIP_ONLY_CN_PRIVATE_URL
        val geoipUrl = policy.geoipUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
        val geositeUrl = policy.geositeUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

        var updatedCount = 0
        if (downloadAssetSafely(cnUrl, File(assetDir, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT), minBytes = 50_000L)) {
            updatedCount++
        }
        if (downloadAssetSafely(geoipUrl, File(assetDir, AppConfig.GEOIP_DAT), minBytes = 500_000L)) {
            updatedCount++
        }
        if (downloadAssetSafely(geositeUrl, File(assetDir, AppConfig.GEOSITE_DAT), minBytes = 500_000L)) {
            updatedCount++
        }

        if (updatedCount > 0) {
            MmkvManager.encodeSettings(PREF_GEO_UPDATED_AT, now)
            AppLog.info(AppLog.Section.Core, "BackgroundSync: refreshed $updatedCount geo/core asset(s)")
        }
    }

    private fun downloadAssetSafely(url: String, target: File, minBytes: Long): Boolean {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "RX-VPN")
            }
            try {
                if (conn.responseCode !in 200..299) return false
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() < minBytes) {
                tmp.delete()
                return false
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun checkXrayCoreMetadata() {
        val checkUrl = RemoteControl.current().core.coreCheckUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val body = runCatching {
            HttpUtil.getUrlContent(UrlContentRequest(url = checkUrl, timeout = 5_000))
        }.getOrNull() ?: return
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.trim() ?: return
        val prev = runCatching { MmkvManager.decodeSettingsString(PREF_CORE_TAG) }.getOrNull()
        if (tag != prev) {
            MmkvManager.encodeSettings(PREF_CORE_TAG, tag)
            AppLog.debug(AppLog.Section.Core, "BackgroundSync: upstream Xray core latest=$tag")
        }
    }
}

class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { BackgroundSyncManager.syncAll(applicationContext, force = false) }
        return Result.success()
    }
}
