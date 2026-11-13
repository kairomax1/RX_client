package com.v2ray.ang.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.CloudflareAssistant
import com.v2ray.ang.handler.DailyMaintenance
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The daily maintenance pass.
 *
 * 1. Engine: the Xray core travels inside the APK, so a new build *is* the engine update. When one
 *    exists it is downloaded in the background and an install notification is posted; the actual
 *    install stays a single tap the user owns, because Android never lets an app install silently.
 * 2. Configs: subscriptions that are marked for automatic updates are refreshed.
 * 3. Local: orphaned profiles are removed and the cached group tabs are rebuilt so the next launch
 *    starts from consistent data. This part needs no network at all.
 *
 * Every phase is independent and swallows its own failures: a flaky network must not turn into a
 * retry storm, so the worker always reports success.
 */
class DailyMaintenanceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!DailyMaintenance.isEnabled()) {
            LogUtil.i(AppConfig.TAG, "Daily maintenance is disabled, skipping")
            return@withContext Result.success()
        }

        LogUtil.i(AppConfig.TAG, "Daily maintenance started")

        runCatching { updateEngineIfAvailable() }
            .onFailure { LogUtil.w(AppConfig.TAG, "Daily maintenance: engine update failed", it) }

        runCatching { refreshSubscriptions() }
            .onFailure { LogUtil.w(AppConfig.TAG, "Daily maintenance: subscription refresh failed", it) }

        runCatching { repairCloudflareConfig() }
            .onFailure { LogUtil.w(AppConfig.TAG, "Daily maintenance: Cloudflare repair failed", it) }

        runCatching { tidyLocalData() }
            .onFailure { LogUtil.w(AppConfig.TAG, "Daily maintenance: local tidy-up failed", it) }

        LogUtil.i(AppConfig.TAG, "Daily maintenance finished")
        Result.success()
    }

    // -------------------------------------------------------------------------
    // 1. Engine / app update
    // -------------------------------------------------------------------------

    private suspend fun updateEngineIfAvailable() {
        if (!isOnline()) return

        // Official releases only; pre-releases and drafts never trigger the notice.
        val result = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
        val url = result.downloadUrl
        val version = result.latestVersion
        if (!result.hasUpdate || url.isNullOrEmpty() || version.isNullOrEmpty()) {
            UpdateNotifier.onCheckResult(applicationContext, result)
            return
        }

        // Nothing is downloaded here: the user is told the size first and decides on the update screen.
        UpdateNotifier.onCheckResult(applicationContext, result)
    }

    // -------------------------------------------------------------------------
    // 2. Configs
    // -------------------------------------------------------------------------

    private fun refreshSubscriptions() {
        if (!isOnline()) return

        val subIds = MmkvManager.decodeSubscriptions()
            .filter { it.subscription.autoUpdate && it.subscription.url.isNotEmpty() }
            .map { it.guid }
        if (subIds.isEmpty()) return

        // Same path the per-subscription periodic worker uses, so the update, the notification and
        // the "last updated" bookkeeping stay in one place.
        subIds.forEach { SubscriptionUpdater.updateLastUpdatedAndReschedule(applicationContext, it) }
        MessageHelper.sendMsg2SubscriptionService(
            applicationContext,
            SubscriptionUpdateMessage(AppConfig.MSG_SUB_UPDATE_START, true, subIds)
        )
        LogUtil.i(AppConfig.TAG, "Daily maintenance: refreshing ${subIds.size} subscription(s)")
    }

    // -------------------------------------------------------------------------
    // 3. Local, no network needed
    // -------------------------------------------------------------------------

    /** Repairs the selected Cloudflare config when it stopped answering; no-op otherwise. */
    private suspend fun repairCloudflareConfig() {
        if (!CloudflareAssistant.isAutoEnabled()) return
        val report = CloudflareAssistant.autoRepair(
            context = applicationContext,
            groupName = applicationContext.getString(R.string.cloudflare_group_name),
            remarksSuffix = applicationContext.getString(R.string.cloudflare_copy_suffix),
        )
        if (!report.isCloudflare) return
        // A repair leaves the user's own profile alone and writes a Cloudflare copy, so the tab has
        // to be rebuilt for the new entry to show up.
        if (report.newConfigGuid != null) SettingsChangeManager.makeSetupGroupTab()
        LogUtil.i(
            AppConfig.TAG,
            "Daily maintenance: Cloudflare latency=${report.latencyMs} " +
                    "unblocked=${report.unblocked} address=${report.switchedAddress} " +
                    "copy=${report.newConfigName}"
        )
    }

    private fun tidyLocalData() {
        MmkvManager.removeOrphanedServerProfiles()
        // Rebuild the cached group tabs so the home screen opens on consistent data.
        com.v2ray.ang.handler.SettingsChangeManager.makeSetupGroupTab()
    }

    private fun isOnline(): Boolean {
        val manager = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

}
