package com.v2ray.ang.util

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.v2ray.ang.AppConfig

/**
 * Opens a link in the app that owns it instead of a browser tab.
 *
 * A plain web link to Telegram goes to the browser first; when the installed Telegram is not the
 * verified handler (an unofficial build, or the link is blocked on the network) the tab opens and
 * closes without ever reaching the channel. Asking Telegram directly avoids that round trip.
 */
object ExternalLinks {

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    private val telegramWeb = Regex("^https?://(?:www\\.)?(?:t|telegram)\\.me/([^?#]+?)/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)
    private val telegramName = Regex("^[A-Za-z][A-Za-z0-9_]{3,}$")
    private val youtubeWeb = Regex("^https?://(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/.*$", RegexOption.IGNORE_CASE)

    /**
     * The `tg:` form of a Telegram web link, or null when [url] is not one this can translate.
     * Handles public names (`t.me/name`), a post in one (`t.me/name/12`) and invites (`t.me/+hash`).
     */
    fun telegramDeepLink(url: String): String? {
        val path = telegramWeb.find(url.trim())?.groupValues?.get(1) ?: return null
        if (path.startsWith("+")) {
            val hash = path.substring(1)
            return if (hash.isNotEmpty() && '/' !in hash) "tg://join?invite=$hash" else null
        }
        if (path.startsWith("joinchat/")) {
            val hash = path.removePrefix("joinchat/")
            return if (hash.isNotEmpty() && '/' !in hash) "tg://join?invite=$hash" else null
        }
        val parts = path.split('/')
        val name = parts[0]
        if (!telegramName.matches(name)) return null
        return when (parts.size) {
            1 -> "tg://resolve?domain=$name"
            2 -> parts[1].toIntOrNull()?.let { "tg://resolve?domain=$name&post=$it" }
            else -> null
        }
    }

    fun isYoutubeLink(url: String): Boolean = youtubeWeb.matches(url.trim())

    /** Opens [url] in its own app when there is one, otherwise the way any link is opened. */
    fun open(context: Context, url: String) {
        val deepLink = telegramDeepLink(url)
        if (deepLink != null && tryStart(context, Intent(Intent.ACTION_VIEW, deepLink.toUri()))) return
        if (isYoutubeLink(url) &&
            tryStart(context, Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(YOUTUBE_PACKAGE))
        ) return
        Utils.openUri(context, url)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        // No app for it on this device: fall back to the web link.
        LogUtil.i(AppConfig.TAG, "No app handles ${intent.data?.scheme} links, using the browser")
        false
    }
}
