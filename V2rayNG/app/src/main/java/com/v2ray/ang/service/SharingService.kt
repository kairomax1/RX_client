package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.Sharing
import com.v2ray.ang.handler.SharingGateway
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.main.MainActivity
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps sharing alive while the app is closed.
 *
 * The gateway itself listens in this process, next to the core, so a console
 * keeps its connection when the screen is turned away. What runs here also
 * watches the clock the owner set, the network the phone is sharing, and the
 * devices that are waiting for an answer — none of which can wait for the
 * screen to be open.
 */
class SharingService : Service() {

    companion object {
        private const val ACTION_START = "com.v2ray.ang.action.SHARING_START"
        private const val ACTION_STOP = "com.v2ray.ang.action.SHARING_STOP"
        private const val ACTION_ALLOW = "com.v2ray.ang.action.SHARING_ALLOW"
        private const val ACTION_BLOCK = "com.v2ray.ang.action.SHARING_BLOCK"
        private const val ACTION_TIME = "com.v2ray.ang.action.SHARING_TIME"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_NAME = "name"

        private const val SERVICE_NOTIFICATION_ID = 23
        private const val ASK_NOTIFICATION_BASE = 4000
        private const val TICK_MILLIS = 1_000L

        /** What the screen calls to switch sharing on. */
        fun start(context: android.content.Context) {
            val intent = Intent(context, SharingService::class.java).setAction(ACTION_START)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    AppLog.error(AppLog.Section.Connection, "Sharing: could not start (${it.message})")
                }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, SharingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** The owner's answer, from the screen or from the notice itself. */
        fun allow(context: android.content.Context, ip: String, name: String) {
            command(context, ACTION_ALLOW) {
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_NAME, name)
            }
        }

        fun block(context: android.content.Context, ip: String) {
            command(context, ACTION_BLOCK) { putExtra(EXTRA_IP, ip) }
        }

        /**
         * The owner changed how long sharing may run. This does not reopen the
         * door, so a device that is using it keeps its connection.
         */
        fun restartClock(context: android.content.Context) {
            command(context, ACTION_TIME) {}
        }

        private fun command(
            context: android.content.Context,
            action: String,
            fill: Intent.() -> Unit
        ) {
            val intent = Intent(context, SharingService::class.java).setAction(action).apply(fill)
            runCatching { context.startService(intent) }
        }
    }

    @Volatile
    private var watching = false

    /** Devices that asked and have not been answered yet, from either process. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification(getString(R.string.sharing_notice_on)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish(null)
                return START_NOT_STICKY
            }
            ACTION_ALLOW -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                if (ip.isNotEmpty()) {
                    Sharing.allow(ip, intent.getStringExtra(EXTRA_NAME).orEmpty())
                    pending.remove(ip)
                    cancelAsk(ip)
                    AppLog.info(AppLog.Section.Connection, "Sharing: $ip was allowed by the owner")
                }
            }
            ACTION_BLOCK -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                if (ip.isNotEmpty()) {
                    Sharing.block(ip)
                    pending.remove(ip)
                    cancelAsk(ip)
                    AppLog.info(AppLog.Section.Connection, "Sharing: $ip was refused by the owner")
                }
            }
            ACTION_TIME -> {
                Sharing.setUntil(
                    Sharing.minutes().let { minutes ->
                        if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
                    }
                )
                AppLog.info(AppLog.Section.Connection, "Sharing: the time was set again")
            }
            else -> startSharing()
        }
        return START_STICKY
    }

    private fun startSharing() {
        val minutes = Sharing.minutes()
        Sharing.setUntil(
            if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        )
        Sharing.setStoppedReason(null)

        if (!SharingGateway.isRunning()) {
            // The gateway says a device is waiting; the watcher is the one that
            // posts the notice, so it is posted once and from one place.
            val opened = SharingGateway.start(Sharing.port()) { ip, _ -> pending.add(ip) }
            if (opened == 0) {
                Sharing.setEnabled(false)
                Sharing.setStoppedReason("port")
                Sharing.publish(Sharing.Live(running = false, port = 0))
                stopSelf()
                return
            }
            Sharing.setPort(opened)
        }
        if (!watching) {
            watching = true
            thread(name = "rx-sharing-watch", isDaemon = true) { watch() }
        }
        AppLog.info(
            AppLog.Section.Connection,
            "Sharing: on, other devices use ${addressOf()} on port ${Sharing.port()}" +
                if (minutes > 0) " for $minutes minutes" else ""
        )
    }

    /** The clock, the network, and what every device is doing right now. */
    private fun watch() {
        val notified = HashSet<String>()
        while (watching) {
            val until = Sharing.until()
            if (until > 0 && System.currentTimeMillis() > until) {
                AppLog.info(AppLog.Section.Connection, "Sharing: the time the owner set is over")
                Sharing.setStoppedReason("expired")
                finish("expired")
                return
            }
            val (addresses, _) = SharingGateway.network()
            if (addresses.isEmpty()) {
                AppLog.info(AppLog.Section.Connection, "Sharing: the phone has no network to share")
                Sharing.setStoppedReason("network")
                finish("network")
                return
            }
            val waiting = SharingGateway.asking().keys
            for (ip in waiting) pending.add(ip)
            for (ip in pending.toList()) {
                if (!waiting.contains(ip)) {
                    // It gave up or was answered while the notice was on its way.
                    pending.remove(ip)
                    cancelAsk(ip)
                } else if (notified.add(ip)) {
                    ask(ip)
                }
            }

            val live = Sharing.Live(
                running = true,
                port = Sharing.port(),
                until = until,
                devices = SharingGateway.snapshot(),
            )
            Sharing.publish(live)
            refreshNotice(live, addresses)
            try {
                Thread.sleep(TICK_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun finish(reason: String?) {
        watching = false
        SharingGateway.stop()
        Sharing.setEnabled(false)
        Sharing.clearLive()
        for (ip in pending.toList()) cancelAsk(ip)
        pending.clear()
        if (reason != null) Sharing.setStoppedReason(reason)
        AppLog.info(AppLog.Section.Connection, "Sharing: off")
        runCatching { NotificationHelper.stopForeground(this) }
        stopSelf()
    }

    override fun onDestroy() {
        watching = false
        SharingGateway.stop()
        Sharing.clearLive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    // ── What the owner is told ────────────────────────────────────

    private fun addressOf(): String = SharingGateway.network().first.firstOrNull().orEmpty()

    private fun serviceNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, NotificationChannelType.SHARING.channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.sharing_notice_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setColor(0xFF9AA4B2.toInt())
            .build()
    }

    private fun refreshNotice(live: Sharing.Live, addresses: List<String>) {
        val address = addresses.firstOrNull().orEmpty()
        val working = live.devices.count { it.active > 0 }
        val text = if (address.isEmpty()) {
            getString(R.string.sharing_notice_on)
        } else {
            getString(R.string.sharing_notice_body, address, live.port, working)
        }
        // Without the notice permission the door still works; only the notice is lost.
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(SERVICE_NOTIFICATION_ID, serviceNotification(text))
        }
    }

    /** Asks the owner about a device, whether or not the app is open. */
    private fun ask(ip: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val allowIntent = Intent(this, SharingService::class.java)
            .setAction(ACTION_ALLOW)
            .putExtra(EXTRA_IP, ip)
        val blockIntent = Intent(this, SharingService::class.java)
            .setAction(ACTION_BLOCK)
            .putExtra(EXTRA_IP, ip)
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)

        val notification = NotificationCompat.Builder(this, NotificationChannelType.SHARING_ASK.channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.sharing_ask_title))
            .setContentText(getString(R.string.sharing_ask_body, ip))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                getString(R.string.sharing_block),
                PendingIntent.getService(this, ip.hashCode(), blockIntent, flags)
            )
            .addAction(
                R.drawable.ic_action_done,
                getString(R.string.sharing_allow),
                PendingIntent.getService(this, ip.hashCode() + 1, allowIntent, flags)
            )
            .build()
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(ASK_NOTIFICATION_BASE + (ip.hashCode() and 0x3FF), notification)
        }
        AppLog.info(AppLog.Section.Connection, "Sharing: $ip is waiting for the owner's word")
    }

    private fun cancelAsk(ip: String) {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(ASK_NOTIFICATION_BASE + (ip.hashCode() and 0x3FF))
        }
    }

    private fun ensureChannels() {
        NotificationHelper.ensureNotificationChannel(
            context = this,
            channelId = NotificationChannelType.SHARING.channelId,
            channelNameRes = NotificationChannelType.SHARING.channelNameRes,
            importance = NotificationManager.IMPORTANCE_LOW,
        )
        NotificationHelper.ensureNotificationChannel(
            context = this,
            channelId = NotificationChannelType.SHARING_ASK.channelId,
            channelNameRes = NotificationChannelType.SHARING_ASK.channelNameRes,
            importance = NotificationManager.IMPORTANCE_HIGH,
        )
    }
}
