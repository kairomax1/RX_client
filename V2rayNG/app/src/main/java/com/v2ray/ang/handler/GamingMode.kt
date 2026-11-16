package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Gaming mode: the tunnel carries only what needs it, and games go where the owner says.
 *
 * Everything a game talks to leaves by the exit the owner chose: the phone's own line (lowest
 * ping, the game sees the owner's own address) or the config's exit address (for a game that is
 * filtered, or that refuses an Iranian address). Sites that are filtered or sanctioned always go
 * through the config; everything else leaves the phone directly, with the line's own speed.
 *
 * Two things are not a mode and are always on: adverts and trackers are blocked, and Iranian
 * sites go out on the phone's own line. An Iranian bank that sees a foreign address refuses the
 * connection outright, and an advert that is never fetched costs nobody anything.
 *
 * It is not a DNS server address that turns a config into something a console can type in: an
 * app cannot listen on port 53. Other devices use the phone's proxy address instead, see
 * [shareAddresses].
 *
 * The mode replaces the routing rules only while it is on; the owner's own rules stay stored and
 * come back untouched when it is switched off. Lists are plain `domain:` entries on purpose: they
 * do not depend on which geosite file happens to be installed.
 */
object GamingMode {

    const val PREF_ENABLED = "pref_rx_dns_mode"
    private const val SITES_ASSET = "rx_dns_mode_sites.txt"
    private const val AD_ASSET = "rx_ad_domains.txt"
    private const val IRAN_ASSET = "rx_iran_domains.txt"
    private const val GAME_ASSET = "rx_game_domains.txt"
    const val PREF_EXTRA_DOMAINS = "pref_rx_dns_mode_extra"

    /** Where game traffic leaves from, chosen by the owner when the mode is switched on. */
    const val PREF_GAME_EXIT = "pref_rx_gaming_exit"

    /** Games use the phone's own line: the lowest ping, and the game sees the owner's address. */
    const val EXIT_OWN = "own"

    /** Games use the config's exit address: for games that are filtered, or region locked. */
    const val EXIT_CONFIG = "config"

    /** Where games leave from right now; [EXIT_OWN] until the owner chooses otherwise. */
    fun gameExit(): String = chosenExit(MmkvManager.decodeSettingsString(PREF_GAME_EXIT))

    /**
     * The exit a stored answer means. A missing answer, and the empty text written when the mode
     * is switched off, both fall back to [EXIT_OWN]; anything else is kept as stored.
     */
    internal fun chosenExit(stored: String?): String = stored.takeUnless { it.isNullOrBlank() } ?: EXIT_OWN

    fun setGameExit(exit: String) {
        AppLog.info(
            AppLog.Section.GamingMode,
            if (exit == EXIT_CONFIG) "Gaming: games leave through the config's exit address"
            else "Gaming: games leave on the phone's own line"
        )
        MmkvManager.encodeSettings(PREF_GAME_EXIT, exit)
    }

    /** Resolvers reachable directly from Iran, used for everything that is not tunnelled. */
    val DIRECT_RESOLVERS = listOf("8.8.8.8", "1.1.1.1")

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        AppLog.info(AppLog.Section.GamingMode, if (enabled) "Gaming mode switched on" else "Gaming mode switched off")
        MmkvManager.encodeSettings(PREF_ENABLED, enabled)
        // Switching the mode off also forgets where games were leaving from: the
        // question is asked again the next time it is switched on, and no answer
        // is carried over into another session.
        if (!enabled) MmkvManager.encodeSettings(PREF_GAME_EXIT, "")
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

    /**
     * The routing rules of the mode, in matching order.
     *
     * With [EXIT_OWN] the phone's own line carries everything that does not need the tunnel,
     * which is what keeps a game's ping at the line's own figure: only filtered and sanctioned
     * sites are tunnelled. With [EXIT_CONFIG] the whole connection goes through the config and
     * only what an Iranian site needs is left outside, so a game sees the config's exit address.
     */
    fun rulesets(
        extra: List<String> = emptyList(),
        bundled: List<String> = emptyList(),
        gameExit: String = EXIT_OWN,
    ): MutableList<RulesetItem> {
        val tunnelled = ((FILTERED + SANCTIONED + VPN_SERVICES + WHOLE_ENDINGS + extra).map { "domain:$it" } + bundled)
            .distinct()
        val localNetwork = RulesetItem(
            remarks = "Gaming: local network",
            outboundTag = AppConfig.TAG_DIRECT,
            ip = listOf(AppConfig.GEOIP_PRIVATE),
        )
        // A rule with no names in it matches nothing, so it is left out rather than added empty.
        val games = gameDomains.takeIf { it.isNotEmpty() }?.let { names ->
            RulesetItem(
                remarks = if (gameExit == EXIT_CONFIG) {
                    "Gaming: games through the config's exit address"
                } else {
                    "Gaming: games on the phone's own line"
                },
                outboundTag = if (gameExit == EXIT_CONFIG) AppConfig.TAG_PROXY else AppConfig.TAG_DIRECT,
                domain = names,
            )
        }

        if (gameExit == EXIT_CONFIG) {
            val rules = mutableListOf(localNetwork)
            games?.let { rules += it }
            // A game's own servers are reached by address, not by name, so with this choice
            // every address that is not Iranian or local goes through the config too: that is
            // what gives the game the config's exit address.
            rules += RulesetItem(
                remarks = "Gaming: everything else through the config",
                outboundTag = AppConfig.TAG_PROXY,
                port = "0-65535",
            )
            return rules
        }

        val rules = mutableListOf<RulesetItem>()
        // Games first, so a game host that also looks like a filtered one is still left on the
        // phone's own line: that is the choice the owner made for them.
        games?.let { rules += it }
        // After the games on purpose: inside Iran the names of filtered sites are answered with
        // a private address (10.10.34.x). Matching the name before the local-network rule keeps
        // such a connection in the tunnel instead of sending it out directly.
        rules += RulesetItem(
            remarks = "Gaming: filtered and restricted sites",
            outboundTag = AppConfig.TAG_PROXY,
            domain = tunnelled,
        )
        // Telegram talks to bare addresses, so no site name is ever seen for it.
        rules += RulesetItem(
            remarks = "Gaming: Telegram addresses",
            outboundTag = AppConfig.TAG_PROXY,
            ip = TELEGRAM_NETWORKS,
        )
        rules += localNetwork
        // Named explicitly so that their names are also resolved outside the tunnel.
        rules += RulesetItem(
            remarks = "Gaming: every other site",
            outboundTag = AppConfig.TAG_DIRECT,
            domain = listOf("regexp:.+"),
        )
        rules += RulesetItem(
            remarks = "Gaming: everything else",
            outboundTag = AppConfig.TAG_DIRECT,
            port = "0-65535",
        )
        return rules
    }

    /**
     * The big site list shipped with the app (assets/rx_dns_mode_sites.txt): several thousand
     * names of filtered and restricted services and game publishers. Empty if it cannot be read;
     * the short lists in this file then still cover the best known services.
     */
    private val bundledSites: List<String> by lazy { readList(SITES_ASSET) }

    /**
     * Names adverts, ad measurement and trackers are fetched from: the ones that
     * travel with the app, and the ones fetched for the day (see [SiteLists]).
     * The second half is read every time rather than remembered for the life of
     * the process, so a list renewed while the tunnel is up is used as it is.
     */
    private val adDomains: List<String> get() = bundledAdDomains + SiteLists.adDomains()

    private val bundledAdDomains: List<String> by lazy { readList(AD_ASSET) }

    /** Iranian sites: banks, shops, government, universities, news, everyday services. */
    private val iranDomains: List<String> by lazy { readList(IRAN_ASSET) }

    /** The core's own list of Iranian names, alongside the ones bundled with the app. */
    private val IRAN_GEOSITE = listOf("geosite:category-ir")

    /** The core's own list of Iranian addresses. */
    private val IRAN_GEOIP = listOf("geoip:ir")

    /**
     * Sign-in, store, matchmaking and content hosts of the big games. A game's own servers are
     * usually reached by address rather than by name, so these rules carry the parts that can be
     * named and the owner's choice carries the rest.
     */
    private val gameDomains: List<String> by lazy { readList(GAME_ASSET) }

    private fun readList(asset: String): List<String> = try {
        AngApplication.application.assets.open(asset).bufferedReader().use { parseSiteList(it.readText()) }
    } catch (e: Throwable) {
        emptyList()
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

    /**
     * The rules the core must use right now: the mode's when it is on, the user's otherwise.
     *
     * Adverts and Iranian sites are added in front of either set, because they are not a mode:
     * an advert is never wanted, and an Iranian site must not arrive from a foreign address
     * whether the owner is gaming or not.
     */
    fun effectiveRulesets(): MutableList<RulesetItem>? {
        val base = if (isEnabled()) {
            rulesets(extraDomains(), bundledSites, gameExit())
        } else {
            MmkvManager.decodeRoutingRulesets() ?: return null
        }
        val rules = mutableListOf<RulesetItem>()
        if (adDomains.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "Adverts and trackers",
                outboundTag = AppConfig.TAG_BLOCKED,
                domain = adDomains,
            )
        }
        if (iranDomains.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "Iranian sites on the phone's own line",
                outboundTag = AppConfig.TAG_DIRECT,
                // The names bundled with the app, then the core's own list of
                // Iranian names, which is the wider of the two; and the Iranian
                // addresses as well, because a site of this country that keeps
                // its name abroad still lives on an address at home.
                domain = iranDomains + IRAN_GEOSITE,
                ip = IRAN_GEOIP,
            )
        }
        rules += base
        return rules
    }

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
