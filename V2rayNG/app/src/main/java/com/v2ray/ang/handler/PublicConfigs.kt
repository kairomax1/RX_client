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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
    private const val KEY_TAG = "rx_public_tag_"
    private const val REFRESH_MILLIS = 3 * 60 * 60 * 1000L
    private const val CONNECT_MILLIS = 2500
    private const val TESTING_AT_ONCE = 48

    /** The group a candidate is put in while it is being measured; never shown. */
    private const val PENDING_GROUP = "rx_public_pending"

    /** How many candidates carry a real download at the same time. */
    private const val VERIFY_AT_ONCE = 4

    /** How long one candidate has to carry a small download before it is refused. */
    private const val VERIFY_TIMEOUT_MILLIS = 18_000L
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

    data class Options(
        val kinds: Set<Kind>,
        val count: Int,
        val mux: Boolean,
        val fragment: Boolean,
        val game: Boolean = false,
        /**
         * Measure each config by carrying real bytes before it is kept. False only
         * where a tunnel cannot be raised (a test, a build machine); every screen
         * that talks to a person leaves it true, because a config that was not
         * made to carry something is not known to work.
         */
        val verify: Boolean = true,
    )

    data class Progress(
        val origin: Origin?,
        val found: Int,
        val tried: Int,
        val reachable: Int,
        /** Configs measured by carrying real bytes so far, and how many carried. */
        val verified: Int = 0,
        val carried: Int = 0,
    )

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
        // Collected from channels that test before publishing, which is what the
        // speed of a public group is really made of.
        Source("Kwinshadow/TelegramV2rayCollector", "main", "sublinks/mix.txt"),
        Source("Kwinshadow/TelegramV2rayCollector", "main", "sublinks/vless.txt"),
        Source("w1770946466/Auto_proxy", "main", "Long_term_subscription_num"),
        Source("w1770946466/Auto_proxy", "main", "Long_term_subscription_try"),
        Source("mahdibland/ShadowsocksAggregator", "master", "Eternity.txt"),
        Source("mahdibland/ShadowsocksAggregator", "master", "EternityAir.txt"),
        Source("itsyebekhe/PSG", "main", "lite/subscriptions/xray/mix"),
        Source("itsyebekhe/PSG", "main", "lite/subscriptions/xray/reality"),
        // Large lists, read only when the ones above were not enough.
        Source("itsyebekhe/PSG", "main", "lite/subscriptions/xray/cdn", small = false),
        Source("mahdibland/ShadowsocksAggregator", "master", "sub/sub_merge_base64.txt", small = false),
        Source("mahdibland/ShadowsocksAggregator", "master", "sub/airport_sub_merge.txt", small = false),
        Source("mahdibland/ShadowsocksAggregator", "master", "EternityBase", small = false),
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
        Source("Argh94/V2RayAutoConfig", "main", "configs/Canada.txt", small = false),
        Source("Argh94/V2RayAutoConfig", "main", "configs/Singapore.txt", small = false),
        Source("Argh94/V2RayAutoConfig", "main", "configs/Trojan.txt", small = false),
        Source("Surfboardv2ray/TGParse", "main", "splitted/trojan", small = false),
        Source("Surfboardv2ray/TGParse", "main", "splitted/vmess", small = false),
        Source("mahdibland/V2RayAggregator", "master", "sub/splitted/ss.txt", small = false),
        Source("mahdibland/V2RayAggregator", "master", "sub/splitted/trojan.txt", small = false),
        Source("mahdibland/V2RayAggregator", "master", "sub/splitted/vmess.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub13.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub14.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub15.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub16.txt", small = false),
        Source("SoliSpirit/v2ray-configs", "main", "Protocols/vless.txt", small = false),
        Source("Delta-Kronecker/V2ray-Config", "main", "config/protocols/vless.txt", small = false),
        Source("Delta-Kronecker/V2ray-Config", "main", "config/protocols/trojan.txt", small = false),
        Source("Delta-Kronecker/V2ray-Config", "main", "config/protocols/ss.txt", small = false),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/vmess.txt", small = false),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/port_8443.txt", small = false),
        Source("barry-far/V2ray-Config", "main", "Sub5.txt", small = false),
        Source("barry-far/V2ray-Config", "main", "Sub7.txt", small = false),
        Source("barry-far/V2ray-Config", "main", "Sub9.txt", small = false),
        Source("barry-far/V2ray-Config", "main", "Sub14.txt", small = false),
        Source("barry-far/V2ray-Config", "main", "Sub15.txt", small = false),
        Source("ShatakVPN/ConfigForge-V2Ray", "main", "configs/lt/all.txt", small = false),
        Source("ShatakVPN/ConfigForge-V2Ray", "main", "configs/kr/all.txt", small = false),
        Source("ShatakVPN/ConfigForge-V2Ray", "main", "configs/cz/all.txt", small = false),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/filtered/subs/vless.txt", small = false),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/filtered/subs/trojan.txt", small = false),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/filtered/subs/ss.txt", small = false),
        Source("Leon406/SubCrawler", "main", "sub/share/v2", small = false),
        Source("Leon406/SubCrawler", "main", "sub/share/ss", small = false),
        Source("sakha1370/OpenRay", "main", "output/kind/vless.txt", small = false),
        Source("shabane/kamaji", "master", "hub/trojan.txt", small = false),
        Source("shabane/kamaji", "master", "hub/vmess.txt", small = false),
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

    internal class Answer(val code: Int, val body: String?, val tag: String?)

    internal fun fetch(address: String, knownTag: String? = null, post: String? = null): Answer = try {
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
        // The lists changed, and the group is what is out of date: a dot on a button
        // that only clears when somebody walks to the screen is not a refresh, and
        // was why the public configs were the same ones weeks later.
        //
        // Only when nothing is connected. Filling the group means raising a tunnel
        // for every candidate, and doing that under a running VPN would take the
        // connection the owner is using away from them — the one thing a background
        // job must never do. With a tunnel up, the list is marked as waiting and the
        // group is renewed the next time the app is opened without one.
        if (ServiceRuntimeState.isRunning(context)) {
            AppLog.info(AppLog.Section.Other, "Public configs: the lists changed, but a tunnel is up; the group is renewed when it is not")
            return false
        }
        val options = savedOptions()
        val outcome = runCatching { collect(context, options, AtomicBoolean(false)) { } }.getOrNull()
        if (outcome != null && outcome.added > 0) {
            AppLog.info(
                AppLog.Section.Other,
                "Public configs: the group was renewed by itself, ${outcome.added} configs",
            )
            setFresh(false)
            runCatching { SettingsChangeManager.makeSetupGroupTab() }
            return true
        }
        return false
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

    /**
     * What the screen starts from. Mux and fragment are not the owner's to set
     * any more: they are found for each config when it connects, and a switch
     * here would only be overruled. The game sort follows gaming mode, which is
     * the one place the owner says whether games are what this phone is for.
     */
    fun savedOptions(): Options {
        val names = MmkvManager.decodeSettingsString(KEY_KINDS).orEmpty().split(',')
        val kinds = Kind.entries.filter { it.name in names }.toSet().ifEmpty { Kind.entries.toSet() }
        return Options(
            kinds = kinds,
            count = MmkvManager.decodeSettingsInt(KEY_COUNT, 50).coerceIn(10, MAX_COUNT),
            mux = false,
            fragment = false,
            game = GamingMode.isEnabled(),
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
     * Gets the lists, keeps the safe links of the chosen sorts, and puts into the
     * public group exactly [Options.count] configs that were each made to carry
     * real bytes on this connection, best first.
     *
     * The order of the work is the point. Three times the wanted number were
     * imported before, then a real test dropped the ones that did not work —
     * which meant a screen full of configs that had never carried anything, a
     * first connection that had to wait for the test to sort them out, and a
     * group that could hold more than was asked for. Now the count is worked
     * towards from the other end: candidates are measured one at a time and a
     * config only goes in when it carried a download and was quickest of what
     * has been measured. The group is emptied first and filled exactly once, so
     * a second collection replaces the first instead of adding to it.
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
        val wanted = options.count.coerceIn(1, MAX_COUNT)
        val seen = HashSet<String>()
        val reachable = ArrayList<Link>()
        var found = 0
        var tried = 0
        val pool = Executors.newFixedThreadPool(TESTING_AT_ONCE)

        // Enough candidates to measure: the ones that answer a connection are only
        // a start, and a download through each of them takes a few seconds.
        val candidatesWanted = maxOf(wanted * 3, wanted + 10)

        fun tryAll(lines: List<String>) {
            val links = select(lines.asSequence(), options.kinds, options.game, seen)
            found += links.size
            onProgress(Progress(origin, found, tried, reachable.size))
            for (batch in links.chunked(TESTING_AT_ONCE)) {
                if (cancelled.get() || reachable.size >= candidatesWanted || tried >= MAX_TRIED) break
                val answers = pool.invokeAll(batch.map { link -> Callable { reaches(link) } })
                batch.forEachIndexed { index, link -> if (runCatching { answers[index].get() }.getOrDefault(false)) reachable.add(link) }
                tried += batch.size
                onProgress(Progress(origin, found, tried, reachable.size))
            }
        }

        try {
            tryAll(if (origin == Origin.Bundled) context.assets.open(ASSET).bufferedReader().use { it.readLines() } else first.lines)
            // Not enough from the short lists: the large ones are read as well.
            if (reachable.size < candidatesWanted && !cancelled.get()) tryAll(download(context, small = false).lines)
        } finally {
            pool.shutdownNow()
        }
        // A server that answered nothing over TCP is no candidate; one that answered
        // is measured properly below, which is the only test that decides.
        val candidates = select(reachable.asSequence().map { it.raw }, options.kinds).take(candidatesWanted)
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: list ${origin.name.lowercase()}, $found safe links of the chosen sorts" +
                (if (options.game) " (game mode)" else "") + ", $tried tried, " +
                "${candidates.size} good enough to be measured properly",
        )
        if (candidates.isEmpty()) return Outcome(0, origin)

        MmkvManager.encodeSettings(KEY_KINDS, options.kinds.joinToString(",") { it.name })
        MmkvManager.encodeSettings(KEY_COUNT, wanted)

        // Measured before they are imported. Importing first and measuring second is
        // what left the group holding configs that had never carried a byte.
        val kept = runBlocking { verify(context, candidates, wanted, options.verify, cancelled) { verified, carried ->
            onProgress(Progress(origin, found, tried, reachable.size, verified, carried))
        } }
        if (kept.isEmpty()) {
            AppLog.error(AppLog.Section.Other, "Public configs: none of the ${candidates.size} measured configs carried anything on this line")
            return Outcome(0, origin)
        }

        // Emptied once, then filled once, so a second collection replaces the first
        // instead of adding to it.
        MmkvManager.clearServerList(GROUP_ID)
        val group = MmkvManager.decodeSubscription(GROUP_ID) ?: SubscriptionItem()
        group.remarks = context.getString(R.string.public_group_name)
        MmkvManager.encodeSubscription(GROUP_ID, group)
        val added = AngConfigManager.importBatchConfig(kept.joinToString("\n") { it.first.raw }, GROUP_ID, true).first
        setFresh(false)
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: ${kept.size} of the $wanted asked for, each made to carry real bytes here",
        )
        return Outcome(added, origin)
    }

    /**
     * Measures candidates by raising a tunnel for each and downloading through it,
     * and returns the quickest [wanted] that carried anything, with their speeds.
     *
     * A few run at once and the search stops as soon as [wanted] have carried: the
     * point is a group that works, not a league table.
     */
    private suspend fun verify(
        context: Context,
        candidates: List<Link>,
        wanted: Int,
        verify: Boolean,
        cancelled: AtomicBoolean,
        onProgress: (verified: Int, carried: Int) -> Unit,
    ): List<Pair<Link, Int>> = coroutineScope {
        if (!verify) return@coroutineScope candidates.take(wanted).map { it to 0 }
        val gate = Semaphore(VERIFY_AT_ONCE)
        val carried = java.util.Collections.synchronizedList(mutableListOf<Pair<Link, Int>>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        // Every measurement runs through one tunnel at a time: a core is a single
        // engine with one routing table, so two configs raised at once would be
        // measuring each other's traffic as well as their own.
        val oneAtATime = Mutex()
        candidates.map { link ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    if (cancelled.get() || carried.size >= wanted) return@withPermit
                    val speed = oneAtATime.withLock { speedOf(context, link) }
                    done.incrementAndGet()
                    if (speed > 0) {
                        carried.add(link to speed)
                        AppLog.info(
                            AppLog.Section.Other,
                            "Public configs: ${link.kind.name} ${link.host} carried ${speed / 1000} kbit/s here",
                        )
                    }
                    onProgress(done.get(), carried.size)
                }
            }
        }.awaitAll()
        carried.sortedByDescending { it.second }.take(wanted)
    }

    /**
     * The speed [link] carries on this line, in kbit/s, or 0 when it carried
     * nothing. The tunnel is raised privately for the measurement, so a running
     * connection is not disturbed and nothing half-tested is ever left in the
     * group the owner sees.
     */
    private suspend fun speedOf(context: Context, link: Link): Int {
        val guid = privateImport(context, link) ?: return 0
        return try {
            AutoConfigTest.probe(context, guid, VERIFY_TIMEOUT_MILLIS) ?: 0
        } finally {
            MmkvManager.removeServers(listOf(guid), PENDING_GROUP)
        }
    }

    /**
     * Puts [link] in a group of its own, so a tunnel can be raised for it without
     * touching either the public group or the owner's own configs. Returns its GUID.
     */
    private fun privateImport(context: Context, link: Link): String? = try {
        if (MmkvManager.decodeSubscription(PENDING_GROUP) == null) {
            MmkvManager.encodeSubscription(
                PENDING_GROUP,
                SubscriptionItem(remarks = PENDING_GROUP, url = "", enabled = false),
            )
        }
        MmkvManager.clearServerList(PENDING_GROUP)
        AngConfigManager.importBatchConfig(link.raw, PENDING_GROUP, true)
        MmkvManager.decodeServerList(PENDING_GROUP).firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** The flag of a two-letter country code, or a globe when there is none. */
    /**
     * Keeps at most [keep] configs in the public group, the quickest of those that
     * answered, and returns how many stay.
     *
     * A config that answered is kept even when it is not the quickest: an
     * alternative is what makes a group useful an hour later, when the winner has
     * been cut off. Only configs that answered nothing are dropped, and only the
     * surplus beyond [keep] is trimmed after that.
     */
    fun prune(keep: Int): Int {
        val all = MmkvManager.decodeServerList(GROUP_ID)
        if (all.isEmpty()) return 0
        val answered = all.filter { (MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L) > 0L }
        val silent = all.filterNot { it in answered }
        // The silent ones go first; then whichever answered are beyond the number asked for.
        val overflow = (answered.size - keep).coerceAtLeast(0)
        val drop = silent + answered.sortedByDescending { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L }.take(overflow)
        if (drop.isNotEmpty()) MmkvManager.removeServers(drop, GROUP_ID)
        val kept = MmkvManager.decodeServerList(GROUP_ID)
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: ${all.size} measured, ${answered.size} answered, ${kept.size} kept" +
                (if (drop.size > 0) " (${drop.size} dropped)" else ""),
        )
        if (kept.isNotEmpty()) runCatching { nameByCountry(kept) }
        return kept.size
    }

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
