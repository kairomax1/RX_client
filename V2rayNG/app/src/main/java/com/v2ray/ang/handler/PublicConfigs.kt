package com.v2ray.ang.handler

import android.content.Context
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Free public configs: collected from open lists, filtered for safety, tried on the user's own
 * connection and put in a group of their own.
 *
 * Where the list comes from, best first: the sources on the internet; the copy kept from the
 * last successful download; the pack shipped inside the app (for a phone with no way out).
 *
 * What is refused: a link that turns certificate checking off, VLESS and Trojan without TLS,
 * Shadowsocks with a cipher that does not authenticate, VMess with the legacy handshake, plain
 * SOCKS/HTTP proxies, anything the core cannot run, and Cloudflare Workers (their exit is
 * Cloudflare's). The owner of a public server still sees which sites are visited; no filter
 * changes that.
 */
object PublicConfigs {

    const val GROUP_ID = "rx_public_configs"
    const val MAX_COUNT = 150
    private const val ASSET = "rx_public_configs.txt"
    private const val KEY_KINDS = "rx_public_kinds"
    private const val KEY_COUNT = "rx_public_count"
    private const val KEY_FRESH = "rx_public_fresh"
    private const val KEY_CHECKED = "rx_public_checked"
    private const val REFRESH_MILLIS = 3 * 60 * 60 * 1000L
    private const val CONNECT_MILLIS = 2500
    private const val TESTING_AT_ONCE = 48
    private const val MAX_TRIED = 2500
    private const val MAX_BYTES = 3_000_000

    enum class Kind(val schemes: List<String>, val udp: Boolean) {
        Vless(listOf("vless"), false),
        Vmess(listOf("vmess"), false),
        Trojan(listOf("trojan"), false),
        Shadowsocks(listOf("ss"), false),
        Hysteria(listOf("hysteria2", "hy2", "hysteria"), true),
        WireGuard(listOf("wireguard"), true),
    }

    enum class Origin { Fresh, Saved, Bundled }

    data class Link(val raw: String, val kind: Kind, val host: String, val port: Int)

    data class Options(val kinds: Set<Kind>, val count: Int, val mux: Boolean, val fragment: Boolean)

    data class Progress(val origin: Origin?, val found: Int, val tried: Int, val reachable: Int)

    data class Outcome(val added: Int, val origin: Origin)

    /** Repository, branch and path of each list; all are plain lists of share links. */
    private val SOURCES = listOf(
        Triple("0xRadikal/Free-v2ray-Configs", "main", "verified/configs.txt"),
        Triple("nimazolfaghari66-sketch/iran-vpn-radar", "main", "subscriptions/iran-priority/vless"),
        Triple("nimazolfaghari66-sketch/iran-vpn-radar", "main", "subscriptions/iran-priority/trojan"),
        Triple("nimazolfaghari66-sketch/iran-vpn-radar", "main", "subscriptions/iran-priority/shadowsocks"),
        Triple("nimazolfaghari66-sketch/iran-vpn-radar", "main", "subscriptions/iran-priority/vmess"),
        Triple("sakha1370/OpenRay", "main", "output_iran/iran_top100_checked.txt"),
        Triple("sakha1370/OpenRay", "main", "output/kind/hysteria2.txt"),
        Triple("0xRadikal/Free-v2ray-Configs", "main", "protocols/hysteria2.txt"),
        Triple("ShatakVPN/ConfigForge-V2Ray", "main", "configs/all.txt"),
        Triple("barry-far/V2ray-Config", "main", "Sub1.txt"),
        Triple("barry-far/V2ray-Config", "main", "Sub2.txt"),
        Triple("Argh94/V2RayAutoConfig", "main", "configs/Hysteria2.txt"),
        Triple("Argh94/V2RayAutoConfig", "main", "configs/WireGuard.txt"),
    )

    private val SAFE_CIPHERS = setOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305")

    // ---------- Reading links ----------

    /** Standard and URL-safe Base64, padding optional; null when [text] is not Base64. */
    internal fun decodeBase64(text: String): ByteArray? {
        val clean = text.trim().trimEnd('=')
        if (clean.isEmpty()) return null
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var at = 0
        for (char in clean) {
            val value = when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[at++] = (buffer shr bits).toByte()
            }
        }
        return out.copyOf(at)
    }

    private fun splitHostPort(text: String): Pair<String, Int>? {
        val clean = text.trim().trimEnd('/')
        val host: String
        val portText: String
        if (clean.startsWith("[")) {
            host = clean.substringAfter('[').substringBefore(']')
            portText = clean.substringAfter("]:", "")
        } else {
            host = clean.substringBeforeLast(':', "")
            portText = clean.substringAfterLast(':')
        }
        // Hysteria may list several ports ("443,5000-6000"); the first one is the one tried.
        val port = portText.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535 || isUnwanted(host)) return null
        return host to port
    }

    /** Hosts that are no public server, and Cloudflare Workers, whose exit is Cloudflare's own. */
    private fun isUnwanted(host: String): Boolean {
        val name = host.lowercase()
        return name == "localhost" || name.startsWith("127.") || name.startsWith("10.") || name.startsWith("192.168.") ||
            name.startsWith("0.") || name.endsWith(".workers.dev") || name.endsWith(".pages.dev")
    }

    private fun isOn(value: String?): Boolean = value == "1" || value == "true"

    /** The link in [line], or null when it is none, is of an unknown sort, or is refused as unsafe. */
    internal fun parse(line: String): Link? = try {
        val text = line.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        val kind = Kind.entries.firstOrNull { scheme in it.schemes }
        val body = text.substringAfter("://").substringBefore('#')
        when (kind) {
            null -> null
            Kind.Vmess -> parseVmess(text, body)
            Kind.Shadowsocks -> parseShadowsocks(text, body)
            else -> parseUrl(text, kind, body)
        }
    } catch (e: Exception) {
        null
    }

    private fun parseUrl(text: String, kind: Kind, body: String): Link? {
        val address = body.substringBefore('?')
        if ('@' !in address) return null
        val (host, port) = splitHostPort(address.substringAfterLast('@')) ?: return null
        val params = body.substringAfter('?', "").split('&').associate {
            it.substringBefore('=').lowercase() to it.substringAfter('=', "").lowercase()
        }
        if (isOn(params["allowinsecure"]) || isOn(params["insecure"])) return null
        if (listOf(params["sni"], params["host"]).any { it != null && isUnwanted(it) }) return null
        val security = params["security"].orEmpty()
        return when (kind) {
            Kind.Vless -> if (security == "tls" || security == "reality") Link(text, kind, host, port) else null
            Kind.Trojan -> if (security == "none") null else Link(text, kind, host, port)
            else -> Link(text, kind, host, port)
        }
    }

    private fun parseVmess(text: String, body: String): Link? {
        val json = JsonParser.parseString(String(decodeBase64(body) ?: return null)).asJsonObject
        fun field(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val (host, port) = splitHostPort(field("add") + ":" + field("port")) ?: return null
        // A non-zero alterId means the legacy handshake, which can be told apart and replayed.
        if (field("aid").ifEmpty { "0" } != "0") return null
        if (isOn(field("allowInsecure").lowercase()) || isOn(field("insecure").lowercase())) return null
        if (field("tls") != "tls" && field("scy").lowercase() in setOf("none", "zero")) return null
        if (listOf(field("sni"), field("host")).any { it.isNotEmpty() && isUnwanted(it) }) return null
        return Link(text, Kind.Vmess, host, port)
    }

    private fun parseShadowsocks(text: String, body: String): Link? {
        val main = body.substringBefore('?')
        val plain = if ('@' in main) {
            val user = main.substringBeforeLast('@')
            val decoded = decodeBase64(user)?.let { String(it) }?.takeIf { ':' in it } ?: user
            decoded + "@" + main.substringAfterLast('@')
        } else {
            String(decodeBase64(main) ?: return null)
        }
        val cipher = plain.substringBefore(':').lowercase()
        if (cipher !in SAFE_CIPHERS && !cipher.startsWith("2022-blake3-")) return null
        val (host, port) = splitHostPort(plain.substringAfterLast('@')) ?: return null
        return Link(text, Kind.Shadowsocks, host, port)
    }

    /**
     * The safe links of the wanted [kinds] in [lines], each server once, the sorts taken in
     * turn so that no sort crowds the others out of the front of the list.
     */
    internal fun select(lines: Sequence<String>, kinds: Set<Kind>): List<Link> {
        val seen = HashSet<String>()
        val byKind = lines.mapNotNull { parse(it) }
            .filter { it.kind in kinds && seen.add("${it.kind}|${it.host.lowercase()}|${it.port}") }
            .groupBy { it.kind }
        val longest = byKind.values.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { index -> byKind.values.mapNotNull { it.getOrNull(index) } }
    }

    /** A list may come as plain lines or as one Base64 block of them. */
    internal fun toLines(content: String): List<String> {
        val text = if ("://" in content) content else decodeBase64(content.filterNot { it.isWhitespace() })?.let { String(it) }.orEmpty()
        return text.lines().map { it.trim() }.filter { "://" in it }
    }

    // ---------- Getting the list ----------

    private fun cacheFile(context: Context) = File(context.filesDir, "rx-public-configs.txt")

    private fun fetch(address: String): String? = try {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (out.size() < MAX_BYTES) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Every source's lines, GitHub first and a mirror of it second. Empty when nothing came. */
    private fun download(): List<String> {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = SOURCES.map { (repository, branch, path) ->
                Callable {
                    val content = fetch("https://raw.githubusercontent.com/$repository/$branch/$path")
                        ?: fetch("https://cdn.jsdelivr.net/gh/$repository@$branch/$path")
                    content?.let { toLines(it) }.orEmpty()
                }
            }
            val results = pool.invokeAll(tasks, 40, TimeUnit.SECONDS).map { runCatching { it.get() }.getOrDefault(emptyList()) }
            AppLog.info(AppLog.Section.Other, "Public configs: ${results.count { it.isNotEmpty() }} of ${SOURCES.size} sources answered, ${results.sumOf { it.size }} links")
            return results.flatten()
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Downloads the lists when the kept copy is older than three hours, and keeps them. True
     * when the kept copy changed, which the main screen shows as a dot on the download button.
     */
    fun refreshIfDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - MmkvManager.decodeSettingsLong(KEY_CHECKED, 0L) < REFRESH_MILLIS) return false
        MmkvManager.encodeSettings(KEY_CHECKED, now)
        val lines = download()
        if (lines.size < 50) return false
        val file = cacheFile(context)
        val text = lines.joinToString("\n")
        if (file.exists() && file.readText() == text) return false
        file.writeText(text)
        setFresh(true)
        return true
    }

    /** True while lists newer than the configs in the group are waiting. */
    val fresh: StateFlow<Boolean> by lazy { freshState }
    private val freshState by lazy { MutableStateFlow(MmkvManager.decodeSettingsBool(KEY_FRESH, false)) }

    private fun setFresh(value: Boolean) {
        MmkvManager.encodeSettings(KEY_FRESH, value)
        freshState.value = value
    }

    fun savedOptions(): Options {
        val names = MmkvManager.decodeSettingsString(KEY_KINDS).orEmpty().split(',')
        val kinds = Kind.entries.filter { it.name in names }.toSet().ifEmpty { Kind.entries.toSet() }
        return Options(
            kinds = kinds,
            count = MmkvManager.decodeSettingsInt(KEY_COUNT, 50).coerceIn(10, MAX_COUNT),
            mux = MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false),
            fragment = MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false),
        )
    }

    // ---------- Collecting ----------

    private fun reaches(link: Link): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(link.host, link.port), CONNECT_MILLIS) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Gets the lists, keeps the safe links of the chosen sorts, tries each server from this
     * connection and puts those that can be reached into the public group, replacing what was
     * there. Three times the wanted number go in, because the real test that follows (a
     * request through each config) drops many more; [prune] then keeps the best.
     *
     * Servers that speak UDP (Hysteria, WireGuard) cannot be tried with a plain connection and
     * go to the real test untried.
     */
    fun collect(context: Context, options: Options, cancelled: AtomicBoolean, onProgress: (Progress) -> Unit): Outcome {
        onProgress(Progress(null, 0, 0, 0))
        var origin = Origin.Fresh
        var lines = download()
        if (lines.size >= 50) {
            runCatching { cacheFile(context).writeText(lines.joinToString("\n")) }
            MmkvManager.encodeSettings(KEY_CHECKED, System.currentTimeMillis())
        } else {
            val saved = runCatching { cacheFile(context).readLines() }.getOrDefault(emptyList())
            if (saved.size >= 50) {
                origin = Origin.Saved
                lines = saved
            } else {
                origin = Origin.Bundled
                lines = context.assets.open(ASSET).bufferedReader().use { it.readLines() }
            }
        }
        val links = select(lines.asSequence(), options.kinds)
        val wanted = options.count * 3
        val untried = links.filter { it.kind.udp }.take(options.count)
        val reachable = ArrayList<Link>()
        var tried = 0
        onProgress(Progress(origin, links.size, 0, 0))
        val pool = Executors.newFixedThreadPool(TESTING_AT_ONCE)
        try {
            for (batch in links.filter { !it.kind.udp }.take(MAX_TRIED).chunked(TESTING_AT_ONCE)) {
                if (cancelled.get() || reachable.size + untried.size >= wanted) break
                val answers = pool.invokeAll(batch.map { link -> Callable { reaches(link) } })
                batch.forEachIndexed { index, link -> if (runCatching { answers[index].get() }.getOrDefault(false)) reachable.add(link) }
                tried += batch.size
                onProgress(Progress(origin, links.size, tried, reachable.size))
            }
        } finally {
            pool.shutdownNow()
        }
        val chosen = select((reachable + untried).asSequence().map { it.raw }, options.kinds).take(wanted)
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: list ${origin.name.lowercase()}, ${links.size} safe links of the chosen sorts, $tried tried, " +
                "${reachable.size} reachable, ${untried.size} UDP untried, ${chosen.size} go to the real test",
        )
        if (chosen.isEmpty()) return Outcome(0, origin)

        MmkvManager.encodeSettings(KEY_KINDS, options.kinds.joinToString(",") { it.name })
        MmkvManager.encodeSettings(KEY_COUNT, options.count)
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, options.mux)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, options.fragment)
        MmkvManager.removeServerViaSubid(GROUP_ID)
        val group = MmkvManager.decodeSubscription(GROUP_ID) ?: SubscriptionItem()
        group.remarks = context.getString(R.string.public_group_name)
        MmkvManager.encodeSubscription(GROUP_ID, group)
        val added = AngConfigManager.importBatchConfig(chosen.joinToString("\n") { it.raw }, GROUP_ID, true).first
        setFresh(false)
        return Outcome(added, origin)
    }

    /**
     * After the real test: drops every config of the group that did not answer and keeps the
     * [keep] quickest. Returns how many stay.
     */
    fun prune(keep: Int): Int {
        val all = MmkvManager.decodeServerList(GROUP_ID)
        val best = all.mapNotNull { guid ->
            (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L).takeIf { it > 0 }?.let { guid to it }
        }.sortedBy { it.second }.take(keep).map { it.first }.toSet()
        MmkvManager.removeServers(all.filter { it !in best }, GROUP_ID)
        AppLog.info(AppLog.Section.Other, "Public configs: ${all.size} tested through the core, ${best.size} answered and stay")
        return best.size
    }

    /** This connection's public address and provider, asked directly; empty parts when unknown. */
    fun whoAmI(): Pair<String, String> {
        val detailed = fetch("http://ip-api.com/json?fields=query,isp")
        if (detailed != null) {
            val address = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            val provider = Regex("\"isp\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            if (address.isNotEmpty()) return address to provider
        }
        val plain = fetch("https://api.ipify.org") ?: fetch("https://ifconfig.me/ip")
        return plain?.trim()?.takeIf { it.length in 7..45 && it.none { char -> char.isWhitespace() } }.orEmpty() to ""
    }
}
