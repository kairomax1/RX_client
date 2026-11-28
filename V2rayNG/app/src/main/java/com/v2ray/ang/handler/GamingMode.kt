package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Gaming mode: everything through the config, or games on the phone's own line.
 *
 * The default — "IP ایران" — is one rule and nothing else: every name and every bare address
 * leaves through the config, so every site, app and game sees the config's exit address and no
 * other. Nothing is exempt, because an exemption is a second exit and a second exit is what an
 * address check notices. The other choice, [EXIT_OWN], keeps games on the phone's own line for
 * the lowest ping and sends everything filtered or sanctioned through the config.
 *
 * One thing is not a mode and is always on: adverts and trackers are blocked, in front of either
 * set of rules. An advert that is never fetched costs nobody anything.
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
    private const val GAME_ASSET = "rx_game_domains.txt"

    /** Games that are filtered, sanctioned or shut inside the country (assets/…). */
    private const val GAME_BLOCKED_ASSET = "rx_game_blocked_domains.txt"

    /** Marks the rule that carries the games which must leave from outside. */
    private const val BLOCKED_GAMES_RULE = "Gaming: blocked games leave from outside"

    /** Marks the rule that carries every service which can only be answered from outside. */
    private const val TUNNELLED_RULE = "Gaming: filtered and restricted sites"
    const val PREF_EXTRA_DOMAINS = "pref_rx_dns_mode_extra"

    /** Where game traffic leaves from, chosen by the owner when the mode is switched on. */
    const val PREF_GAME_EXIT = "pref_rx_gaming_exit"

    /** Games use the phone's own line: the lowest ping, and the game sees the owner's address. */
    const val EXIT_OWN = "own"

    /** Games use the config's exit address: for games that are filtered, or region locked. */
    const val EXIT_CONFIG = "config"

    /** Where traffic leaves from right now; [EXIT_OWN] — the Iranian line — by default. */
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

    /**
     * Resolvers that answer from inside the country, used for everything that
     * leaves on the phone's own line.
     *
     * Not the two that every phone knows: from here they do not answer at all,
     * and a name that is not answered is a site that never opens and a game
     * that never finds its server. These two are public resolvers of this
     * country, reachable from almost every network in it.
     */
    val DIRECT_RESOLVERS = listOf("178.22.122.100", "185.51.200.2")

    /** True for resolvers that answer from inside the country and must not be used in strict tunnel mode. */
    fun isDirectResolver(dns: String): Boolean = DIRECT_RESOLVERS.any { it.equals(dns.trim(), ignoreCase = true) }

    fun isEnabled(): Boolean =
        RemoteControl.isFeatureEnabled { it.gamingMode } &&
            MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        AppLog.info(
            AppLog.Section.GamingMode,
            when {
                !enabled -> "Gaming mode switched off"
                // Said out loud, because the whole promise of the mode is this one
                // sentence and the owner has to be able to check it in her own log.
                chosenExit(MmkvManager.decodeSettingsString(PREF_GAME_EXIT)) == EXIT_CONFIG ->
                    "Gaming mode switched on: every name, address and game through the config, nothing direct"
                else -> "Gaming mode switched on: games on the phone's own line"
            },
        )
        MmkvManager.encodeSettings(PREF_ENABLED, enabled)
        // Switching the mode off also forgets where games were leaving from, so an
        // answer given once is never carried silently into another session. With
        // nothing stored the mode answers to the config, which is the one answer
        // that opens everything.
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
     * With [EXIT_OWN] — "IP ایران", the default — everything that works from here keeps the
     * owner's own Iranian address, and only what cannot work from here is tunnelled: the
     * filtered, the sanctioned, the blocked games and the AI sites. With [EXIT_CONFIG] the whole
     * connection goes through the config instead: every name, every address, every port, nothing
     * left outside, all of it on the config's one address.
     */
    fun rulesets(
        extra: List<String> = emptyList(),
        bundled: List<String> = emptyList(),
        // The same default as `chosenExit`, so a caller that does not say which
        // way round it wants gets the mode as it is actually configured rather
        // than a different one. Two defaults disagreeing is how "IP ایران" ended
        // up reporting one thing and routing another.
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
            // Two rules, and neither of them is a way out.
            //
            // The first drops the quarrels of a QUIC handshake. Chrome apps — YouTube, the Play
            // Store, Maps — and every game that speaks QUIC start on UDP 443. A tunnel carries
            // TCP and its own UDP; a UDP 443 that is neither of those leaves the phone and is
            // answered, or not, by the network here, and what the app sees is a site that half
            // loads. Dropping it makes the app fall back to TCP at once, inside the tunnel — the
            // same thing every browser does when QUIC is unavailable — so the site opens.
            //
            // The second is the whole connection: every name, every bare address, every port,
            // through the config. There is deliberately nothing after it that sends anything
            // out directly: a "local network" exception or a games exception would be a second
            // exit, and a second exit is exactly what an address or sanction check notices. A
            // blocked game opens because its traffic is tunnelled like everything else, not
            // because it is exempt.
            return mutableListOf(
                RulesetItem(
                    remarks = "Gaming: QUIC on UDP 443 dropped so it falls back into the tunnel",
                    outboundTag = AppConfig.TAG_BLOCKED,
                    network = "udp",
                    port = "443",
                ),
                RulesetItem(
                    remarks = "Gaming: everything through the config",
                    outboundTag = AppConfig.TAG_PROXY,
                    port = "0-65535",
                ),
            )
        }

        // ------------------------------------------------------------------
        // The Iranian line: the owner's own address stays on everything that
        // works from here, and only what cannot work from here goes through the
        // config. Order is the whole of the promise, so it is written out.
        // ------------------------------------------------------------------
        val rules = mutableListOf<RulesetItem>()

        // 1. Adverts and trackers. Never wanted, never fetched.
        //    (Added in front by `ordered`, so it is deliberately not repeated here.)

        // 2. QUIC on UDP 443: blocked on many Iranian networks and often breaks
        //    YouTube/Google. Drop it so clients fall back to TCP through the tunnel.
        rules += RulesetItem(
            remarks = "Gaming: QUIC on UDP 443 dropped so it falls back to TCP",
            outboundTag = AppConfig.TAG_BLOCKED,
            network = "udp",
            port = "443",
        )

        // 3. A name that is filtered, sanctioned or region-locked is answered
        //    through the config before anything else is looked at. This is the
        //    rule that opens YouTube, Instagram, Telegram, Proton, the AI sites
        //    and every blocked game — by name, inside the tunnel, whatever the
        //    phone's own resolver would have said about it.
        rules += RulesetItem(
            remarks = TUNNELLED_RULE,
            outboundTag = AppConfig.TAG_PROXY,
            domain = tunnelled,
        )

        // 3. A game that is shut here, by the name its own launcher uses.
        blockedGameDomains.takeIf { it.isNotEmpty() }?.let { names ->
            rules += RulesetItem(
                remarks = BLOCKED_GAMES_RULE,
                outboundTag = AppConfig.TAG_PROXY,
                domain = names,
            )
        }

        // 4. Telegram and every game server that is reached by a bare address,
        //    so no site name is ever seen for them. An address cannot be matched
        //    by name, which is why this rule exists at all.
        rules += RulesetItem(
            remarks = "Gaming: Telegram addresses",
            outboundTag = AppConfig.TAG_PROXY,
            ip = TELEGRAM_NETWORKS,
        )

        // 5. The phone's own network — the router, the hotspot, another device on
        //    it — stays on the phone's own line, because a tunnel cannot carry it
        //    and there is nothing to leak by leaving it there.
        rules += localNetwork

        // 6. Everything else: the owner's own address, on the owner's own line.
        //    One rule for every remaining name and every remaining address, so
        //    nothing can fall through to a second exit that was never chosen.
        rules += RulesetItem(
            remarks = "Gaming: everything else on the phone's own line",
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
    private val adDomains: List<String>
        get() {
            if (!RemoteControl.isFeatureEnabled { it.adBlock }) return emptyList()
            val extra = RemoteControl.current().routing.blockDomains
                .mapNotNull { normalizeDomain(it)?.let { d -> "domain:$d" } }
            return (bundledAdDomains + SiteLists.adDomains() + extra)
                .filterNot { SiteLists.isProtectedOrUnblocked(it) }
                .distinct()
        }

    private val bundledAdDomains: List<String> by lazy { readList(AD_ASSET) }

    /**
     * Sign-in, store, matchmaking and content hosts of the big games. A game's own servers are
     * usually reached by address rather than by name, so these rules carry the parts that can be
     * named and the owner's choice carries the rest.
     */
    private val gameDomains: List<String> by lazy { readList(GAME_ASSET) }

    /**
     * Games that do not answer from inside the country: filtered, sanctioned, or shut to an
     * address from here. They are played from outside even when the owner chose the phone's own
     * line for everything else, because a game that never connects has no ping to protect.
     */
    private val blockedGameDomains: List<String> by lazy { readList(GAME_BLOCKED_ASSET) }

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
     * Adverts are put in front of either set, because they are not a mode: an advert is never
     * wanted, whether the owner is gaming or not.
     */
    fun effectiveRulesets(): MutableList<RulesetItem>? {
        val base: List<RulesetItem> = if (isEnabled()) {
            rulesets(extraDomains(), bundledSites, gameExit())
        } else {
            MmkvManager.decodeRoutingRulesets().orEmpty()
        }
        return if (isEnabled()) ordered(base) else withAdverts(base)
    }

    /**
     * The rules in the one order that keeps every connection on one exit.
     *
     * Adverts are blocked first, because they are never wanted. Then the services
     * that can only be answered from outside — a blocked name has to reach the
     * tunnel before anything could send it out directly — then the games whose
     * line the owner chose, and everything else in the mode's own order.
     *
     * Kept separate from the lists themselves so the order can be read and tested.
     */
    internal fun ordered(base: List<RulesetItem>): MutableList<RulesetItem> {
        val rules = mutableListOf<RulesetItem>()
        if (adDomains.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "Adverts and trackers",
                outboundTag = AppConfig.TAG_BLOCKED,
                domain = adDomains,
            )
        }
        val remoteRouting = RemoteControl.current().routing
        val remoteProxy = remoteRouting.proxyDomains.mapNotNull { normalizeDomain(it)?.let { d -> "domain:$d" } }
        if (remoteProxy.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "RemoteControl: proxy domains",
                outboundTag = AppConfig.TAG_PROXY,
                domain = remoteProxy,
            )
        }
        val remoteDirect = remoteRouting.directDomains.mapNotNull { normalizeDomain(it)?.let { d -> "domain:$d" } }
        if (remoteDirect.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "RemoteControl: direct domains",
                outboundTag = AppConfig.TAG_DIRECT,
                domain = remoteDirect,
            )
        }
        // The services that can only be answered from outside come first: a
        // blocked name must reach the tunnel before any rule that could send it
        // out directly. Then the games whose line the owner chose, then the rest.
        val tunnelled = base.firstOrNull { it.remarks == TUNNELLED_RULE }
        val blockedGames = base.firstOrNull { it.remarks == BLOCKED_GAMES_RULE }
        val games = base.firstOrNull { it.remarks?.startsWith("Gaming: games") == true }
        tunnelled?.let { rules += it }
        blockedGames?.let { rules += it }
        games?.let { rules += it }
        rules += base.filterNot { it === blockedGames || it === games || it === tunnelled }
        return rules
    }

    /**
     * The services that can only be answered from outside, as one rule. The same names as
     * in the Gaming mode, so a service is never opened there and closed outside it. It stands
     * in front of the owner's own rules, because a rule that sends a name out directly before
     * this one is reached is the whole reason a blocked site stays closed.
     */
    internal val tunnelledServices: List<String> by lazy {
        ((FILTERED + SANCTIONED + VPN_SERVICES + WHOLE_ENDINGS).map { "domain:$it" }).distinct()
    }

    /** The rule that sends those services through the config. */
    internal fun tunnelledServicesRule(): RulesetItem = RulesetItem(
        remarks = TUNNELLED_RULE,
        outboundTag = AppConfig.TAG_PROXY,
        domain = tunnelledServices,
    )

    /**
     * The owner's own rules, in the one order that keeps a blocked site open: adverts are
     * blocked in front, the services that can only be answered from outside go through the
     * config before anything could send them out directly, and what the remote policy names
     * follows in its own order.
     *
     * Pure, so the order can be read and tested without a store behind it.
     */
    internal fun aroundOwnerRules(
        base: List<RulesetItem>,
        adverts: List<String>,
        remoteProxy: List<String>,
        remoteDirect: List<String>,
    ): MutableList<RulesetItem> {
        val rules = mutableListOf<RulesetItem>()
        if (adverts.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "Adverts and trackers",
                outboundTag = AppConfig.TAG_BLOCKED,
                domain = adverts,
            )
        }
        // QUIC/HTTP3 over UDP 443 is often broken/blocked and makes YouTube fail to load.
        // Drop it so clients fall back to TCP through the tunnel.
        rules += RulesetItem(
            remarks = "QUIC on UDP 443 dropped so it falls back to TCP",
            outboundTag = AppConfig.TAG_BLOCKED,
            network = "udp",
            port = "443",
        )
        // YouTube and the rest must never depend on which preset the owner chose, on a rule
        // of theirs that sends a whole TLD out directly, or on a geo list being downloaded:
        // the names are written here, in front.
        rules += tunnelledServicesRule()
        if (remoteProxy.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "RemoteControl: proxy domains",
                outboundTag = AppConfig.TAG_PROXY,
                domain = remoteProxy,
            )
        }
        if (remoteDirect.isNotEmpty()) {
            rules += RulesetItem(
                remarks = "RemoteControl: direct domains",
                outboundTag = AppConfig.TAG_DIRECT,
                domain = remoteDirect,
            )
        }
        rules += base
        return rules
    }

    /** The owner's own rules, wrapped as above with what is in force right now. */
    private fun withAdverts(base: List<RulesetItem>): MutableList<RulesetItem> {
        val remoteRouting = RemoteControl.current().routing
        return aroundOwnerRules(
            base = base,
            adverts = adDomains,
            remoteProxy = remoteRouting.proxyDomains.mapNotNull { normalizeDomain(it)?.let { d -> "domain:$d" } },
            remoteDirect = remoteRouting.directDomains.mapNotNull { normalizeDomain(it)?.let { d -> "domain:$d" } },
        )
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
