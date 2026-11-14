package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
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
 * Where the list comes from, best first: the sources on the internet; the copies kept from
 * the last successful download; the pack shipped inside the app (for a phone with no way out).
 *
 * What is refused: a link that turns certificate checking off, VLESS and Trojan without TLS,
 * Shadowsocks with a cipher that does not authenticate, VMess with the legacy handshake, plain
 * SOCKS/HTTP proxies, anything the core cannot run, and servers behind Cloudflare. The owner of a public server still sees which sites are visited; no filter
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
    private const val KEY_GAME = "rx_public_game"
    private const val KEY_TAG = "rx_public_tag_"
    private const val REFRESH_MILLIS = 3 * 60 * 60 * 1000L
    private const val CONNECT_MILLIS = 2500
    private const val TESTING_AT_ONCE = 48
    private const val MAX_TRIED = 4000
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

    /** [direct]: reached straight over TCP or UDP, not through a web transport made for CDNs. */
    data class Link(val raw: String, val kind: Kind, val host: String, val port: Int, val direct: Boolean = true)

    data class Options(val kinds: Set<Kind>, val count: Int, val mux: Boolean, val fragment: Boolean, val game: Boolean = false)

    data class Progress(val origin: Origin?, val found: Int, val tried: Int, val reachable: Int)

    data class Outcome(val added: Int, val origin: Origin)

    /**
     * One list of share links in a GitHub repository. The [small] ones are short or already
     * tested by their makers and are always read; the rest are large and are read only when
     * the small ones did not give enough servers that answer.
     */
    internal data class Source(val repository: String, val branch: String, val path: String, val small: Boolean = true) {
        val address get() = "https://raw.githubusercontent.com/$repository/$branch/$path"
        val mirror get() = "https://cdn.jsdelivr.net/gh/$repository@$branch/$path"
    }

    internal val SOURCES = listOf(
        // Tested by their makers, some of them from inside Iran.
        Source("0xRadikal/Free-v2ray-Configs", "main", "verified/configs.txt"),
        Source("0xRadikal/Free-v2ray-Configs", "main", "top100.txt"),
        Source("nimazolfaghari66-sketch/iran-vpn-radar", "main", "subscriptions/iran-priority/all"),
        Source("nimazolfaghari66-sketch/iran-vpn-radar", "main", "subscriptions/candidates/all"),
        Source("sakha1370/OpenRay", "main", "output_iran/iran_top100_checked.txt"),
        Source("ermaozi/get_subscribe", "main", "subscribe/reachable/v2ray.txt"),
        Source("shabane/kamaji", "master", "hub/tested/merged.txt"),
        Source("shabane/kamaji", "master", "hub/best.txt"),
        Source("4n0nymou3/multi-proxy-config-fetcher", "main", "configs/proxy_configs_tested.txt"),
        Source("F0rc3Run/F0rc3Run", "main", "Best-Results/proxies.txt"),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/super-sub.txt"),
        // Hysteria and WireGuard, the sorts that carry UDP natively.
        Source("0xRadikal/Free-v2ray-Configs", "main", "protocols/hysteria2.txt"),
        Source("sakha1370/OpenRay", "main", "output/kind/hysteria2.txt"),
        Source("Argh94/V2RayAutoConfig", "main", "configs/Hysteria2.txt"),
        Source("Argh94/V2RayAutoConfig", "main", "configs/WireGuard.txt"),
        // Short collected lists.
        Source("ShatakVPN/ConfigForge-V2Ray", "main", "configs/all.txt"),
        Source("peasoft/NoMoreWalls", "master", "list_raw.txt"),
        Source("lagzian/SS-Collector", "main", "configs/subscription-all.txt"),
        Source("itsyebekhe/PSG", "main", "subscriptions/xray/mix"),
        Source("roosterkid/openproxylist", "main", "V2RAY_RAW.txt"),
        Source("Leon406/SubCrawler", "main", "sub/share/a11"),
        Source("miladtahanian/V2RayCFGDumper", "main", "sub.txt"),
        Source("liketolivefree/kobabi", "main", "sub.txt"),
        Source("V2RayRoot/V2RayConfig", "main", "Config/vless.txt"),
        Source("snakem982/proxypool", "main", "source/v2ray-2.txt"),
        Source("igareck/vpn-configs-for-russia", "main", "BLACK_VLESS_RUS.txt"),
        Source("Delta-Kronecker/V2ray-Config", "main", "config/new.txt"),
        Source("MhdiTaheri/V2rayCollector_Py", "main", "sub/Mix/mix.txt"),
        Source("barry-far/V2ray-Config", "main", "Sub1.txt"),
        Source("barry-far/V2ray-Config", "main", "Sub2.txt"),
        // Large lists, read only when the ones above were not enough.
        Source("sakha1370/OpenRay", "main", "output/site_access/all_sites.txt", small = false),
        Source("shabane/kamaji", "master", "hub/self/tested/merged.txt", small = false),
        Source("Delta-Kronecker/V2ray-Config", "main", "config/all_configs.txt", small = false),
        Source("mahdibland/V2RayAggregator", "master", "sub/sub_merge.txt", small = false),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/all_sub.txt", small = false),
        Source("Barabama/FreeNodes", "main", "nodes/merged.txt", small = false),
        Source("0xRadikal/Free-v2ray-Configs", "main", "protocols/vless.txt", small = false),
        Source("0xRadikal/Free-v2ray-Configs", "main", "protocols/trojan.txt", small = false),
        Source("0xRadikal/Free-v2ray-Configs", "main", "protocols/shadowsocks.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub10.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub11.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub12.txt", small = false),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/trojan.txt", small = false),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/ss.txt", small = false),
        Source("mheidari98/.proxy", "main", "trojan", small = false),
        Source("Argh94/V2RayAutoConfig", "main", "configs/Germany.txt", small = false),
        Source("Argh94/V2RayAutoConfig", "main", "configs/Netherlands.txt", small = false),
        Source("Argh94/V2RayAutoConfig", "main", "configs/France.txt", small = false),
    )

    /** Cloudflare's published IPv4 ranges, as address and prefix length. */
    private val CLOUDFLARE_V4 = listOf(
        "173.245.48.0" to 20, "103.21.244.0" to 22, "103.22.200.0" to 22, "103.31.4.0" to 22, "141.101.64.0" to 18,
        "108.162.192.0" to 18, "190.93.240.0" to 20, "188.114.96.0" to 20, "197.234.240.0" to 22, "198.41.128.0" to 17,
        "162.158.0.0" to 15, "104.16.0.0" to 13, "104.24.0.0" to 14, "172.64.0.0" to 13, "131.0.72.0" to 22,
    ).map { (address, bits) -> address.split('.').fold(0) { sum, part -> (sum shl 8) or part.toInt() } to bits }

    /** First 32 bits of Cloudflare's IPv6 ranges; all of them are /32. */
    private val CLOUDFLARE_V6 = setOf(0x2400cb00, 0x26064700, 0x2803f800, 0x2405b500, 0x24058400, 0x2a0698c0, 0x2c0ff248)

    /** True when [address] (4 or 16 raw bytes) belongs to Cloudflare, so the server sits behind it. */
    internal fun isCloudflare(address: ByteArray): Boolean {
        if (address.size < 4) return false
        val head = address.take(4).fold(0) { sum, byte -> (sum shl 8) or (byte.toInt() and 0xFF) }
        if (address.size != 4) return head in CLOUDFLARE_V6
        return CLOUDFLARE_V4.any { (network, bits) -> (head xor network) ushr (32 - bits) == 0 }
    }

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
        val direct = kind.udp || params["type"].orEmpty() in setOf("", "tcp", "raw")
        return when (kind) {
            Kind.Vless -> if (security == "tls" || security == "reality") Link(text, kind, host, port, direct) else null
            Kind.Trojan -> if (security == "none") null else Link(text, kind, host, port, direct)
            else -> Link(text, kind, host, port, direct)
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
        return Link(text, Kind.Vmess, host, port, field("net") in setOf("", "tcp", "raw"))
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
     * The safe links of the wanted [kinds] in [lines], each server once (and none that is in
     * [seen] already), the sorts taken in turn so that no sort crowds the others out.
     */
    internal fun select(
        lines: Sequence<String>,
        kinds: Set<Kind>,
        game: Boolean = false,
        seen: MutableSet<String> = HashSet(),
    ): List<Link> {
        // Games need UDP and a short path: web transports made for CDNs are left out.
        val byKind = lines.mapNotNull { parse(it) }
            .filter { it.kind in kinds && (!game || it.direct) && seen.add("${it.kind}|${it.host.lowercase()}|${it.port}") }
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

    private fun cacheDir(context: Context) = File(context.filesDir, "rx-public").apply { mkdirs() }

    private fun cacheFile(context: Context, source: Source) =
        File(cacheDir(context), source.address.hashCode().toUInt().toString(16) + ".txt")

    private class Answer(val code: Int, val body: String?, val tag: String?)

    private fun fetch(address: String, knownTag: String? = null, post: String? = null): Answer = try {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        if (knownTag != null) connection.setRequestProperty("If-None-Match", knownTag)
        if (post != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(post.toByteArray()) }
        }
        try {
            val code = connection.responseCode
            val body = if (code != 200) null else connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (out.size() < MAX_BYTES) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.toString("UTF-8")
            }
            Answer(code, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Answer(0, null, null)
    }

    private class Listing(val lines: List<String>, val answered: Int, val changed: Boolean)

    /**
     * The lines of the wanted sources. A source that did not change since the last time costs
     * one short request (its ETag is kept); one that cannot be reached, on GitHub or on the
     * mirror, gives its kept copy.
     */
    private fun download(context: Context, small: Boolean): Listing {
        val sources = SOURCES.filter { it.small == small }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = sources.map { source ->
                Callable {
                    val file = cacheFile(context, source)
                    val tagKey = KEY_TAG + file.name
                    val kept = if (file.exists()) runCatching { file.readLines() }.getOrNull() else null
                    val answer = fetch(source.address, MmkvManager.decodeSettingsString(tagKey).takeIf { kept != null })
                    val body = answer.body ?: if (answer.code == 304) null else fetch(source.mirror).body
                    val lines = body?.let { toLines(it) }
                    when {
                        lines != null && lines.isNotEmpty() -> {
                            runCatching { file.writeText(lines.joinToString("\n")) }
                            MmkvManager.encodeSettings(tagKey, answer.tag.takeIf { answer.body != null })
                            Triple(lines, true, lines != kept)
                        }
                        else -> Triple(kept.orEmpty(), answer.code == 304, false)
                    }
                }
            }
            val results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS)
                .map { runCatching { it.get() }.getOrDefault(Triple(emptyList(), false, false)) }
            val answered = results.count { it.second }
            AppLog.info(
                AppLog.Section.Other,
                "Public configs: ${if (small) "short" else "large"} lists, $answered of ${sources.size} sources answered " +
                    "(${results.count { it.third }} changed), ${results.sumOf { it.first.size }} links",
            )
            return Listing(results.flatMap { it.first }, answered, results.any { it.third })
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Looks at the short lists when the last look is older than three hours. True when one of
     * them changed, which the main screen shows as a dot on the download button and uses to
     * renew the group the next time the app is open and not connected.
     */
    fun refreshIfDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - MmkvManager.decodeSettingsLong(KEY_CHECKED, 0L) < REFRESH_MILLIS) return false
        MmkvManager.encodeSettings(KEY_CHECKED, now)
        if (!download(context, small = true).changed) return false
        setFresh(true)
        return true
    }

    /** Looks at the lists every three hours, also while the app is closed. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<PublicConfigsWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("rx_public_configs", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** True when the public group holds configs, so there is something to renew. */
    fun hasGroup(): Boolean = MmkvManager.decodeServerList(GROUP_ID).isNotEmpty()

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
            game = MmkvManager.decodeSettingsBool(KEY_GAME, false),
        )
    }

    // ---------- Collecting ----------

    /** True when the server is not behind Cloudflare and, for TCP sorts, accepts a connection. */
    private fun reaches(link: Link): Boolean = try {
        val address = InetAddress.getByName(link.host)
        when {
            isCloudflare(address.address) -> false
            link.kind.udp -> true
            else -> {
                Socket().use { it.connect(InetSocketAddress(address, link.port), CONNECT_MILLIS) }
                true
            }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Gets the lists, keeps the safe links of the chosen sorts, tries each server from this
     * connection and puts those that can be reached into the public group, replacing what was
     * there. Three times the wanted number go in, because the real test that follows (a
     * request through each config) drops many more; [prune] then keeps the best.
     *
     * Servers that speak UDP (Hysteria, WireGuard) cannot be tried with a plain connection;
     * only their address is looked at, and the real test decides.
     */
    fun collect(context: Context, options: Options, cancelled: AtomicBoolean, onProgress: (Progress) -> Unit): Outcome {
        onProgress(Progress(null, 0, 0, 0))
        val first = download(context, small = true)
        val origin = when {
            first.answered > 0 -> Origin.Fresh
            first.lines.size >= 50 -> Origin.Saved
            else -> Origin.Bundled
        }
        if (origin == Origin.Fresh) MmkvManager.encodeSettings(KEY_CHECKED, System.currentTimeMillis())
        val seen = HashSet<String>()
        val wanted = options.count * 3
        val reachable = ArrayList<Link>()
        var found = 0
        var tried = 0
        val pool = Executors.newFixedThreadPool(TESTING_AT_ONCE)

        fun tryAll(lines: List<String>) {
            val links = select(lines.asSequence(), options.kinds, options.game, seen)
            found += links.size
            onProgress(Progress(origin, found, tried, reachable.size))
            for (batch in links.chunked(TESTING_AT_ONCE)) {
                if (cancelled.get() || reachable.size >= wanted || tried >= MAX_TRIED) break
                val answers = pool.invokeAll(batch.map { link -> Callable { reaches(link) } })
                batch.forEachIndexed { index, link -> if (runCatching { answers[index].get() }.getOrDefault(false)) reachable.add(link) }
                tried += batch.size
                onProgress(Progress(origin, found, tried, reachable.size))
            }
        }

        try {
            tryAll(if (origin == Origin.Bundled) context.assets.open(ASSET).bufferedReader().use { it.readLines() } else first.lines)
            // Not enough from the short lists: the large ones are read as well.
            if (reachable.size < wanted && !cancelled.get()) tryAll(download(context, small = false).lines)
        } finally {
            pool.shutdownNow()
        }
        val chosen = select(reachable.asSequence().map { it.raw }, options.kinds).take(wanted)
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: list ${origin.name.lowercase()}, $found safe links of the chosen sorts" +
                (if (options.game) " (game mode)" else "") + ", $tried tried, ${chosen.size} go to the real test",
        )
        if (chosen.isEmpty()) return Outcome(0, origin)

        MmkvManager.encodeSettings(KEY_KINDS, options.kinds.joinToString(",") { it.name })
        MmkvManager.encodeSettings(KEY_COUNT, options.count)
        MmkvManager.encodeSettings(KEY_GAME, options.game)
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
     * After the real test: drops every config of the group that did not answer, keeps the
     * [keep] quickest and names them by country. Returns how many stay.
     */
    fun prune(keep: Int): Int {
        val all = MmkvManager.decodeServerList(GROUP_ID)
        val best = all.mapNotNull { guid ->
            (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L).takeIf { it > 0 }?.let { guid to it }
        }.sortedBy { it.second }.take(keep).map { it.first }
        MmkvManager.removeServers(all.filter { it !in best }, GROUP_ID)
        AppLog.info(AppLog.Section.Other, "Public configs: ${all.size} tested through the core, ${best.size} answered and stay")
        runCatching { nameByCountry(best) }
        return best.size
    }

    /** The flag of a two-letter country code, or a globe when there is none. */
    internal fun flag(country: String): String =
        if (country.length != 2 || country.any { it !in 'A'..'Z' }) "\uD83C\uDF10"
        else country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /**
     * Replaces the names the lists gave (mostly channel adverts) with country, sort and a
     * number. The country comes from one question to a public IP directory per hundred servers.
     */
    private fun nameByCountry(guids: List<String>) {
        val profiles = guids.mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
        val pool = Executors.newFixedThreadPool(16)
        val addresses = try {
            pool.invokeAll(profiles.map { (_, profile) ->
                Callable { runCatching { InetAddress.getByName(profile.server.orEmpty()).hostAddress }.getOrNull() }
            }).map { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
        val countries = HashMap<String, String>()
        addresses.filterNotNull().distinct().chunked(100).forEach { chunk ->
            val body = fetch("http://ip-api.com/batch?fields=countryCode,query", post = chunk.joinToString(",", "[", "]") { "\"$it\"" }).body
                ?: return@forEach
            runCatching {
                JsonParser.parseString(body).asJsonArray.forEach { item ->
                    val entry = item.asJsonObject
                    countries[entry.get("query").asString] = entry.get("countryCode")?.asString.orEmpty()
                }
            }
        }
        profiles.forEachIndexed { index, (guid, profile) ->
            val country = countries[addresses[index]].orEmpty()
            profile.remarks = "${flag(country)} ${country.ifEmpty { "??" }} \u00B7 ${profile.configType.name} \u00B7 ${index + 1}"
            MmkvManager.encodeServerConfig(guid, profile)
        }
    }

    /**
     * The config to move to when [current], one of the public group, stopped answering: the
     * next one in the group's order (best first), or null at the end of the list.
     */
    fun nextAfter(current: String): String? {
        val list = MmkvManager.decodeServerList(GROUP_ID)
        val index = list.indexOf(current)
        return if (index < 0) null else list.getOrNull(index + 1)
    }

    /** This connection's public address and provider, asked directly; empty parts when unknown. */
    fun whoAmI(): Pair<String, String> {
        val detailed = fetch("http://ip-api.com/json?fields=query,isp").body
        if (detailed != null) {
            val address = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            val provider = Regex("\"isp\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            if (address.isNotEmpty()) return address to provider
        }
        val plain = fetch("https://api.ipify.org").body ?: fetch("https://ifconfig.me/ip").body
        return plain?.trim()?.takeIf { it.length in 7..45 && it.none { char -> char.isWhitespace() } }.orEmpty() to ""
    }
}

/** The three-hourly look at the lists; see [PublicConfigs.schedule]. */
class PublicConfigsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { PublicConfigs.refreshIfDue(applicationContext) }
        return Result.success()
    }
}
