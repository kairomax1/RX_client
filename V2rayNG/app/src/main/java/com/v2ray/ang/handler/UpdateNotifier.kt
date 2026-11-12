package com.v2ray.ang.handler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything around "a newer official release exists": remembering it (red dot on the menu
 * button), telling the user (notification) and getting the APK to the system installer.
 *
 * Only published, non-pre-release GitHub releases count. Android never installs silently, so the
 * last step is always the system's own confirmation screen.
 */
object UpdateNotifier {

    const val EXTRA_AUTO_INSTALL = "auto_install"

    private const val PREF_PENDING_VERSION = "pref_pending_update_version"
    private const val PREF_LAST_CHECK_MS = "pref_update_last_check_ms"
    private const val PREF_LAST_NOTIFIED = "pref_update_last_notified"
    private const val CHANNEL_ID = "app_update_channel"
    private const val NOTIFICATION_ID = 22
    private const val OPEN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val RENOTIFY_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val _pending = MutableStateFlow(readPending())
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private fun readPending(): Boolean = try {
        val version = MmkvManager.decodeSettingsString(PREF_PENDING_VERSION)
        !version.isNullOrEmpty() &&
                UpdateCheckerManager.compareVersions(version, BuildConfig.VERSION_NAME) > 0
    } catch (e: Exception) {
        false
    }

    fun markPending(version: String) {
        MmkvManager.encodeSettings(PREF_PENDING_VERSION, version)
        _pending.value = true
    }

    fun clearPending() {
        MmkvManager.encodeSettings(PREF_PENDING_VERSION, "")
        _pending.value = false
    }

    /**
     * Check made when the app opens. Rate limited so opening the app repeatedly does not hit the
     * GitHub API every time. Never throws: without network the old state simply stays.
     */
    suspend fun checkOnAppOpen(context: Context) = withContext(Dispatchers.IO) {
        // A build that is already the newest must not keep an old dot after it was updated.
        _pending.value = readPending()
        val now = System.currentTimeMillis()
        val last = MmkvManager.decodeSettingsLong(PREF_LAST_CHECK_MS, 0L)
        if (now - last in 0 until OPEN_CHECK_INTERVAL_MS) return@withContext
        try {
            val result = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
            MmkvManager.encodeSettings(PREF_LAST_CHECK_MS, now)
            onCheckResult(context, result)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Update check on app open failed", e)
        }
    }

    /** Stores the outcome of any official-release check and notifies when it found something. */
    fun onCheckResult(context: Context, result: CheckUpdateResult) {
        val version = result.latestVersion
        if (!result.hasUpdate || version.isNullOrEmpty()) {
            clearPending()
            return
        }
        if (result.isPreRelease) return
        markPending(version)
        notifyUpdate(context, version)
    }

    fun cachedApk(context: Context, version: String): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        return File(dir, "RX-VPN-$version.apk")
    }

    /**
     * Downloads the release APK, directly first and through the local proxy when that fails (GitHub
     * is often only reachable while connected). A finished file from an earlier pass is reused.
     */
    suspend fun downloadApk(context: Context, version: String, url: String): File? =
        withContext(Dispatchers.IO) {
            val target = cachedApk(context, version)
            if (target.length() > 0) return@withContext target
            val partial = File(target.parentFile, target.name + ".part")
            partial.delete()
            var ok = HttpUtil.downloadToFile(UrlContentRequest(url = url, timeout = 60_000), partial)
            if (!ok) {
                partial.delete()
                ok = HttpUtil.downloadToFile(
                    UrlContentRequest(
                        url = url,
                        timeout = 60_000,
                        httpPort = SettingsManager.getHttpPort(),
                        proxyUsername = SettingsManager.getSocksUsername(),
                        proxyPassword = SettingsManager.getSocksPassword(),
                    ),
                    partial
                )
            }
            if (!ok || partial.length() <= 0 || !partial.renameTo(target)) {
                partial.delete()
                return@withContext null
            }
            // Older builds accumulate in the cache; keep only the one being offered.
            target.parentFile?.listFiles()?.filter { it != target }?.forEach { it.delete() }
            target
        }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".cache", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** At most once a day per version; tapping opens the update screen, which downloads and installs. */
    fun notifyUpdate(context: Context, version: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val stamp = MmkvManager.decodeSettingsString(PREF_LAST_NOTIFIED).orEmpty()
        val lastVersion = stamp.substringBefore('|', "")
        val lastTime = stamp.substringAfter('|', "0").toLongOrNull() ?: 0L
        if (!force && lastVersion == version && now - lastTime in 0 until RENOTIFY_INTERVAL_MS) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            LogUtil.i(AppConfig.TAG, "Update notice: notifications are off, only the dot is shown")
            return
        }
        ensureChannel(context)

        val intent = Intent(context, CheckUpdateActivity::class.java)
            .putExtra(EXTRA_AUTO_INSTALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.update_notice_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            MmkvManager.encodeSettings(PREF_LAST_NOTIFIED, "$version|$now")
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Update notice: could not post the notification", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_ID) != null) return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notice_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
