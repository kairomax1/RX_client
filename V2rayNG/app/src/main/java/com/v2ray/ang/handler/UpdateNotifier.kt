package com.v2ray.ang.handler

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.v2ray.ang.service.UpdateWatchWorker
import java.util.concurrent.TimeUnit
import android.app.DownloadManager
import android.app.NotificationChannel
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    // Short on purpose: a new release should be noticed quickly. It only keeps rapid reopening from
    // using up GitHub's hourly request allowance.
    private const val OPEN_CHECK_INTERVAL_MS = 3 * 60 * 1000L
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
    suspend fun checkOnAppOpen(context: Context) = checkNow(context)

    /**
     * Asks GitHub whether a newer release exists and notifies when it does. Called when the app
     * opens, when the tunnel comes up (the moment GitHub is surely reachable) and by the
     * background check that runs every 15 minutes.
     */
    suspend fun checkNow(context: Context) = withContext(Dispatchers.IO) {
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
            LogUtil.w(AppConfig.TAG, "Update check failed", e)
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

    fun cachedApk(context: Context, version: String): File = UpdateDownloader.targetFile(context, version)

    /** See [UpdateDownloader]; kept here so callers have one place for everything about updates. */
    suspend fun downloadApk(
        context: Context,
        version: String,
        url: String,
        size: Long = 0L,
        sha256: String? = null,
        onProgress: (UpdateDownloader.Progress) -> Unit = {},
    ): File? = UpdateDownloader.download(context, version, url, size, sha256, onProgress)

    /**
     * Hands a downloaded update to the user. The app deliberately does not hold the "install
     * apps" permission, so it cannot open the installer itself: the file is put into the phone's
     * Download folder and the downloads list is opened; a tap on the file there installs it.
     * Returns false when that is not possible (Android 9 and older, or no downloads app).
     */
    fun handOver(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // An earlier copy of the same version would make the system rename this one "(1)".
            runCatching {
                resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(apk.name))
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out, 64 * 1024) } }
                ?: return false
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            try {
                context.startActivity(
                    Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // No downloads screen on this phone; the file is in the folder all the same.
                LogUtil.w(AppConfig.TAG, "Downloads screen could not be opened", e)
            }
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Update could not be put into Downloads", e)
            false
        }
    }

    /** At most once a day per version; tapping opens the update screen with the download button. */
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

    private const val WATCH_WORK_NAME = "rx_update_watch"

    /**
     * Keeps looking for a new release while the app is closed: every 15 minutes, the shortest
     * period Android allows, and only while a network is connected.
     */
    fun scheduleWatch(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<UpdateWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WATCH_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Update watch could not be scheduled", e)
        }
    }
}
