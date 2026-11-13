package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * "DNS mode": the tunnel carries only what needs it.
 *
 * Sites that are filtered or that refuse Iranian addresses go through the selected config; every
 * other connection (games, local sites, downloads) leaves the phone directly, with the line's own
 * speed and ping. Names of tunnelled sites are resolved through the tunnel as well, so a poisoned
 * answer from the provider's resolver cannot break them.
 *
 * It is not a DNS server address that turns a config into something a console can type in: an
 * app cannot listen on port 53. Other devices use the phone's proxy address instead, see
 * [shareAddresses].
 *
 * The mode replaces the routing rules only while it is on; the user's own rules stay stored and
 * come back untouched when it is switched off. Lists are plain `domain:` entries on purpose: they
 * do not depend on which geosite file happens to be installed.
 */
object DnsMode {

    const val PREF_ENABLED = "pref_rx_dns_mode"
    private const val SITES_ASSET = "rx_dns_mode_sites.txt"
    const val PREF_EXTRA_DOMAINS = "pref_rx_dns_mode_extra"

    /** Resolvers reachable directly from Iran, used for everything that is not tunnelled. */
    val DIRECT_RESOLVERS = listOf("8.8.8.8", "1.1.1.1")

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_ENABLED, enabled)
    }

    fun extraDomains(): List<String> =
        MmkvManager.decodeSettingsString(PREF_EXTRA_DOMAINS).orEmpty().split(',').filter { it.isNotBlank() }

    /** Adds what the user typed; returns false when it is not a usable site name. */
    fun addExtraDomain(input: String): Boolean {
        val domain = normalizeDomain(input) ?: return false
        val all = (extraDomains() + domain).distinct()
        MmkvManager.encodeSettings(PREF_EXTRA_DOMAINS, all.joinToString(","))
        return true
    }

    fun clearExtraDomains() {
        MmkvManager.encodeSettings(PREF_EXTRA_DOMAINS, "")
    }

    /**
     * "https://www.Example.com/page" -> "example.com". Null for anything that is not a host name
     * (spaces, no dot, an IP address, odd characters).
     */
    fun normalizeDomain(input: String): String? {
        var text = input.trim().lowercase()
        text = text.substringAfter("://")
        text = text.substringBefore('/').substringBefore('?').substringBefore('#')
        text = text.substringAfterLast('@').substringBefore(':')
        text = text.removePrefix("*.").removePrefix("www.").trim('.')
        if (text.length !in 4..253 || !text.contains('.')) return null
        val labels = text.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        if (!labels.all { label -> label.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } }) return null
        if (labels.last().all { it.isDigit() }) return null
        return text
    }

    /** The routing rules of the mode, in matching order. */
    fun rulesets(
        extra: List<String> = emptyList(),
        bundled: List<String> = emptyList(),
    ): MutableList<RulesetItem> {
        val tunnelled = ((FILTERED + SANCTIONED + VPN_SERVICES + WHOLE_ENDINGS + extra).map { "domain:$it" } + bundled)
            .distinct()
        return mutableListOf(
            // First on purpose: inside Iran the names of filtered sites are answered with a
            // private address (10.10.34.x). Matching the name before the local-network rule
            // keeps such a connection in the tunnel instead of sending it out directly.
            RulesetItem(
                remarks = "DNS mode: filtered and restricted sites",
                outboundTag = AppConfig.TAG_PROXY,
                domain = tunnelled,
            ),
            // Telegram talks to bare addresses, so no site name is ever seen for it.
            RulesetItem(
                remarks = "DNS mode: Telegram addresses",
                outboundTag = AppConfig.TAG_PROXY,
                ip = TELEGRAM_NETWORKS,
            ),
            RulesetItem(
                remarks = "DNS mode: local network",
                outboundTag = AppConfig.TAG_DIRECT,
                ip = listOf(AppConfig.GEOIP_PRIVATE),
            ),
            // Named explicitly so that their names are also resolved outside the tunnel.
            RulesetItem(
                remarks = "DNS mode: every other site",
                outboundTag = AppConfig.TAG_DIRECT,
                domain = listOf("regexp:.+"),
            ),
            RulesetItem(
                remarks = "DNS mode: everything else",
                outboundTag = AppConfig.TAG_DIRECT,
                port = "0-65535",
            ),
        )
    }

    /**
     * The big site list shipped with the app (assets/rx_dns_mode_sites.txt): several thousand
     * names of filtered and restricted services and game publishers. Empty if it cannot be read;
     * the short lists in this file then still cover the best known services.
     */
    private val bundledSites: List<String> by lazy {
        try {
            AngApplication.application.assets.open(SITES_ASSET).bufferedReader().use { parseSiteList(it.readText()) }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Keeps the "domain:name" and "full:name" lines of a site list file; drops everything else. */
    internal fun parseSiteList(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { line ->
            val name = line.substringAfter(':', "")
            (line.startsWith("domain:") || line.startsWith("full:")) &&
                name.isNotEmpty() && name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.' }
        }
        .toList()

    /** The rules the core must use right now: the mode's when it is on, the user's otherwise. */
    fun effectiveRulesets(): MutableList<RulesetItem>? =
        if (isEnabled()) rulesets(extraDomains(), bundledSites) else MmkvManager.decodeRoutingRulesets()

    /**
     * Addresses of this phone on its Wi-Fi or hotspot network. Another device on the same network
     * reaches the tunnel by using one of them, with the app's proxy port, as its proxy server.
     */
    fun shareAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.name.startsWith("tun") }
            .sortedBy { iface -> HOTSPOT_PREFIXES.indexOfFirst { iface.name.startsWith(it) }.let { if (it < 0) 99 else it } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "rndis", "wlan")

    /** Services that are filtered in Iran. */
    internal val FILTERED = listOf(
        // Google, whole: YouTube, the Play Store and the Google apps load their parts from many
        // shared Google names, so tunnelling only "youtube.com" leaves them half working.
        "youtube.com", "youtu.be", "yt.be", "ytimg.com", "googlevideo.com", "youtube-nocookie.com", "ggpht.com",
        "youtubekids.com", "withyoutube.com", "youtubeeducation.com",
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "withgoogle.com", "gvt1.com",
        "gvt2.com", "gvt3.com", "1e100.net", "g.co", "goo.gl", "android.com", "appspot.com", "blogger.com",
        "recaptcha.net", "gmail.com", "googlemail.com", "chrome.com", "chromium.org", "googletagmanager.com",
        "google-analytics.com", "googlesyndication.com", "googleadservices.com", "doubleclick.net",
        "googlesource.com", "googlecode.com", "web.dev", "page.link", "app.goo.gl", "firebaseapp.com",
        // Meta
        "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "fb.com", "fbsbx.com", "facebook.net",
        "messenger.com", "whatsapp.com", "whatsapp.net", "wa.me", "threads.net", "threads.com",
        // Telegram
        "telegram.org", "t.me", "telegram.me", "telegra.ph", "telesco.pe", "tdesktop.com", "telegram-cdn.org",
        "cdn-telegram.org", "tg.dev",
        // X
        "twitter.com", "x.com", "t.co", "twimg.com",
        // Other social and messaging
        "tiktok.com", "tiktokv.com", "tiktokcdn.com", "byteoversea.com", "ibytedtos.com", "muscdn.com",
        "discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media",
        "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com",
        "pinterest.com", "pinimg.com", "snapchat.com", "sc-cdn.net", "snap.com", "tumblr.com",
        "signal.org", "whispersystems.org", "viber.com", "line.me", "linkedin.com", "licdn.com",
        "medium.com", "quora.com", "soundcloud.com", "sndcdn.com", "vimeo.com", "vimeocdn.com",
        "twitch.tv", "ttvnw.net", "jtvnw.net", "dailymotion.com",
        // Knowledge and news
        "wikipedia.org", "wikimedia.org", "bbc.com", "bbc.co.uk", "bbci.co.uk", "voanews.com", "radiofarda.com",
        "iranintl.com", "manototv.com", "dw.com", "archive.org", "blogspot.com", "wordpress.com", "wp.com",
        // Tools
        "protonmail.com", "proton.me", "duckduckgo.com", "torproject.org", "psiphon.ca", "github.io",
    )

    /** Services that refuse connections from Iranian addresses. */
    internal val SANCTIONED = listOf(
        // AI
        "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "anthropic.com", "claude.ai",
        "gemini.google.com", "aistudio.google.com", "generativelanguage.googleapis.com", "bard.google.com",
        "notebooklm.google.com", "copilot.microsoft.com", "perplexity.ai", "x.ai", "grok.com", "midjourney.com",
        "huggingface.co", "hf.co", "cursor.com", "cursor.sh",
        "sora.com", "claudeusercontent.com", "deepmind.com", "poe.com", "elevenlabs.io", "runwayml.com",
        "suno.com", "stability.ai", "replicate.com", "openrouter.ai", "groq.com", "mistral.ai", "cohere.com",
        "together.ai", "character.ai", "meta.ai", "githubcopilot.com", "ideogram.ai", "leonardo.ai", "pika.art",
        "lovable.dev", "bolt.new", "v0.dev", "replit.com", "windsurf.com", "codeium.com", "manus.im",
        "genspark.ai", "krea.ai", "luma.ai", "lumalabs.ai", "heygen.com", "gamma.app", "you.com", "phind.com",
        "civitai.com", "kaggle.com", "colab.research.google.com", "wandb.ai", "langchain.com", "pinecone.io",
        // Google developer and paid services
        "developers.google.com", "developer.android.com", "source.android.com", "firebase.google.com",
        "firebaseio.com", "cloud.google.com", "console.cloud.google.com", "dl.google.com", "golang.org", "go.dev",
        "tensorflow.org", "flutter.dev", "dart.dev", "pub.dev", "kotlinlang.org", "maven.google.com",
        "analytics.google.com", "ads.google.com", "adsense.com", "admob.com", "domains.google", "store.google.com",
        // Development
        "docker.com", "docker.io", "gitlab.com", "bitbucket.org", "atlassian.com", "atlassian.net", "jetbrains.com",
        "npmjs.com", "pypi.org", "pythonhosted.org", "nvidia.com", "amd.com", "intel.com", "oracle.com",
        "vmware.com", "mongodb.com", "mongodb.org", "redis.io", "elastic.co", "hashicorp.com", "terraform.io",
        "vercel.com", "vercel.app", "netlify.com", "netlify.app", "heroku.com", "herokuapp.com", "digitalocean.com",
        "unity.com", "unity3d.com", "unrealengine.com", "stackoverflow.com", "sstatic.net", "notion.so",
        "notion.com", "figma.com", "canva.com", "slack.com", "slack-edge.com", "zoom.us", "trello.com",
        "adobe.com", "autodesk.com", "apple.com/developer", "developer.apple.com",
        // Media and stores
        "spotify.com", "scdn.co", "spotifycdn.com", "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com",
        "disneyplus.com", "primevideo.com", "hulu.com", "hbomax.com", "max.com", "coursera.org", "udemy.com",
        "udacity.com", "edx.org", "khanacademy.org", "paypal.com", "paypalobjects.com", "stripe.com",
        "wise.com", "binance.com", "coinbase.com", "kraken.com", "tradingview.com",
        // Game stores and launchers (the games themselves stay direct)
        "epicgames.com", "epicgames.dev", "unrealengine.com", "ea.com", "origin.com", "battle.net", "blizzard.com",
        "riotgames.com", "playvalorant.com", "leagueoflegends.com", "ubisoft.com", "ubi.com", "rockstargames.com",
        "playstation.com", "sonyentertainmentnetwork.com", "xbox.com", "xboxlive.com", "nintendo.com",
        "supercell.com", "activision.com", "callofduty.com",
    ).filter { !it.contains('/') }

    /** VPN and anti-filter services; their sites and sign-in servers are filtered. */
    internal val VPN_SERVICES = listOf(
        "tunnelbear.com", "windscribe.com", "expressvpn.com", "surfshark.com", "mullvad.net", "hotspotshield.com",
        "cyberghostvpn.com", "privateinternetaccess.com", "ipvanish.com", "hide.me", "vyprvpn.com", "purevpn.com",
        "getlantern.org", "lantern.io", "betternet.co", "privadovpn.com", "atlasvpn.com", "protonvpn.com",
        "nordvpn.com", "torguard.net", "strongvpn.com", "zenmate.com", "hola.org", "urban-vpn.com", "turbovpn.com",
        "ultrasurf.us", "freegate.com", "outline.org", "getoutline.org", "v2ray.com", "xtls.github.io",
    )

    /** Whole name endings that belong to one company (labs.google, deepmind.google, ...). */
    internal val WHOLE_ENDINGS = listOf("google", "goog", "youtube")

    internal val TELEGRAM_NETWORKS = listOf(
        "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22", "91.108.16.0/22", "91.108.20.0/22",
        "91.108.56.0/22", "91.105.192.0/23", "149.154.160.0/20", "185.76.151.0/24", "95.161.64.0/20",
        "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48", "2001:b28:f23c::/48", "2a0a:f280::/32",
    )
}
