package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    /**
     * The most the slider offers, and the hard ceiling on what is kept.
     *
     * It used to be 150, and the log shows what that did: the screen said "asked
     * for 2220" while the collection stopped at 150 candidates and kept 50 — the
     * number the owner set was never the number that ran. 2220 is not a number
     * anybody chooses by accident, so it is honoured here.
     */
    const val MAX_COUNT = 2500
    private const val KEY_KINDS = "rx_public_kinds"
    private const val KEY_COUNT = "rx_public_count"
    private const val KEY_FRESH = "rx_public_fresh"
    private const val KEY_CHECKED = "rx_public_checked"
    private const val KEY_REBUILT = "rx_public_rebuilt"
    private const val KEY_DAILY_SOURCES = "rx_public_daily_sources"
    private const val KEY_SELECTED_SOURCES_MIGRATED = "rx_public_selected_sources_v1"
    private const val KEY_TAG = "rx_public_tag_"
    /** How often the lists are looked at. Fifteen minutes, as the owner asked. */
    private const val REFRESH_MILLIS = 15 * 60 * 1000L
    /** How often all public-config source lists are refreshed from scratch. */
    private const val DAILY_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * How old the configs in the group may get before they are rebuilt even
     * though no list changed. A list that has not changed still ages: its
     * configs die one by one, and a group that was filled a day ago is a group
     * of dead addresses.
     */
    private const val REBUILD_MILLIS = 6 * 60 * 60 * 1000L
    private const val CONNECT_MILLIS = 1200
    // Network probes do not need dozens of threads competing with the UI and VPN core.
    private const val TESTING_AT_ONCE = 16

    /** Where the presence of a line is checked, and how long that is waited for. */
    private val LINE_PROBE_ENDPOINTS = listOf(
        "8.8.8.8" to 443,
        "8.8.4.4" to 443,
        "9.9.9.9" to 443,
    )
    private const val LINE_PROBE_MILLIS = 2200

    /** Fast download budget so fetching lists never stalls before testing begins. */
    private const val DOWNLOAD_THREADS = 12
    private const val DOWNLOAD_BUDGET_MILLIS = 12_000L
    // Background daily refresh can afford more time to reach the larger source catalog.
    private const val DAILY_DOWNLOAD_BUDGET_MILLIS = 90_000L
    private const val FETCH_CONNECT_MILLIS = 2500
    private const val FETCH_READ_MILLIS = 4500

    /** Pipelined chunk bounds for streaming TCP pre-check into parallel Xray verification. */
    private const val CHUNK_MIN = 40
    private const val CHUNK_MAX = 80
    private const val FALLBACK_MEASURE = 20

    /** How many consecutive probe failures trigger an active line check. */
    private const val STREAK_LINE_CHECK = 12
    private const val LINE_RECHECK_MIN_GAP_MILLIS = 3000L

    /**
     * The longest one collection runs before it hands over what it has.
     */
    private const val MAX_DURATION_MILLIS = 8 * 60 * 1000L

    /**
     * The group a candidate is put in while it is being measured. It is a real
     * group only because a config has to belong to one to be imported; it is not
     * a tab and must never be drawn as one, so [isInternalGroup] answers for it
     * wherever the list of tabs is built.
     */
    const val PENDING_GROUP = "rx_public_pending"

    /** True for a group the app keeps for itself: measured in, never shown. */
    fun isInternalGroup(guid: String?): Boolean =
        guid == PENDING_GROUP || guid == GROUP_ID_SWEEP

    /** A second scratch group, so a measurement of a public config and a sweep cannot collide. */
    private const val GROUP_ID_SWEEP = "rx_public_sweep"

    /**
     * Removes what a measurement left behind, if it was interrupted before it
     * could. Called when the screen opens, so an old scratch group cannot come
     * back into view after a crash.
     */
    fun tidyUp() {
        runCatching { MmkvManager.removeServerViaSubid(PENDING_GROUP) }
        runCatching { MmkvManager.removeServerViaSubid(GROUP_ID_SWEEP) }
    }

    /**
     * How many candidates are probed through Xray in parallel. Keeping this bounded leaves
     * CPU and memory available to the UI and to any active connection. Byte-transfer
     * confirmation still runs only on candidates that answered on the user's line.
     */
    private const val VERIFY_AT_ONCE = 4

    /** How long a bare TCP connection to a candidate's address is waited for. */
    private const val QUICK_CONNECT_MILLIS = 1200

    /**
     * How long one candidate has to carry real bytes in the confirmation probe.
     */
    private const val VERIFY_TIMEOUT_MILLIS = 5_000L
    private const val MAX_TRIED = 4000
    private const val MAX_BYTES = 3_000_000

    private val pendingGroupLock = Any()
    private val groupWriteLock = Any()
    private val dailySourcesInFlight = AtomicBoolean(false)

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
        /** How many were asked for; the work stops at this, not past it. */
        val wanted: Int = 0,
        /** How many candidates a tunnel was opened over so far, and how many are in the tab. */
        val verified: Int = 0,
        val carried: Int = 0,
        /** Which round of looking at the lists this is; 0 before the first. */
        val round: Int = 0,
        /** The pool size the round is working from, so a stuck search says so. */
        val pool: Int = 0,
    )

    data class Outcome(val added: Int, val origin: Origin, val disconnected: Boolean = false)

    /**
     * One list of share links in a GitHub repository, or a direct URL / public Telegram
     * channel preview (`https://t.me/s/<channel>`). The [small] ones are short or already
     * tested by their makers and are always read; the rest are large and are read only when
     * the small ones did not give enough servers that answer.
     */
    internal data class Source(
        val repository: String,
        val branch: String,
        val path: String,
        val small: Boolean = true,
        val directUrl: String? = null,
    ) {
        val address get() = directUrl ?: "https://raw.githubusercontent.com/$repository/$branch/$path"
        val mirror get() = directUrl ?: "https://cdn.jsdelivr.net/gh/$repository@$branch/$path"
        val isTelegram get() = directUrl?.contains("t.me/s/") == true
    }

    // Only the public feeds and channels explicitly selected by the owner.
    internal val SOURCES = listOf(
        Source("Alirewa/V2ray-Configs", "main", "sub1.txt"),
        Source("Alirewa/V2ray-Configs", "main", "sub2.txt"),
        Source("Alirewa/V2ray-Configs", "main", "sub3.txt"),
        Source("barry-far/V2ray-Config", "main", "All_Configs_Sub.txt"),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/super-sub.txt"),
        Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/all_sub.txt"),
        Source("morpheusadam/v2ray-config", "main", "subs/bundles/best.txt"),
        Source("morpheusadam/v2ray-config", "main", "subs/bundles/iran.txt"),
        Source("morpheusadam/v2ray-config", "main", "subs/bundles/hysteria2.txt"),
        Source("morpheusadam/v2ray-config", "main", "subs/bundles/lite.txt"),
        Source("RezFlare/Vault", "main", "data/sub/all_configs.txt"),
        Source("mrvcoder/V2rayCollector", "main", "vless_iran.txt"),
        Source("mfuu/FreeProxies", "master", "sub"),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/top100.txt"),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/port_443.txt"),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/vless.txt"),
        Source("hamedcode/port-based-v2ray-configs", "main", "sub/vmess.txt"),
        Source("ByeWhiteLists/ByeWhiteLists2", "main", "ByeWhiteLists2.txt"),
        Source("yitong2333/proxy-minging", "main", "v2ray.txt"),
        Source("wuqb2i4f/xray-config-toolkit", "main", "output/base64/mix-uri"),
        Source("acymz/AutoVPN", "main", "data/V2.txt"),
        Source("CidVpn/cid-vpn-config", "main", "general.txt"),

        Source("t.me", "s", "V2rayNGn", directUrl = "https://t.me/s/V2rayNGn"),
        Source("t.me", "s", "free4allVPN", directUrl = "https://t.me/s/free4allVPN"),
        Source("t.me", "s", "PrivateVPNs", directUrl = "https://t.me/s/PrivateVPNs"),
        Source("t.me", "s", "MsV2ray", directUrl = "https://t.me/s/MsV2ray"),
        Source("t.me", "s", "ShadowSocks_s", directUrl = "https://t.me/s/ShadowSocks_s"),
        Source("t.me", "s", "VmessProtocol", directUrl = "https://t.me/s/VmessProtocol"),
        Source("t.me", "s", "DirectVPN", directUrl = "https://t.me/s/DirectVPN"),
        Source("t.me", "s", "ServerNett", directUrl = "https://t.me/s/ServerNett"),
        Source("t.me", "s", "VlessConfig", directUrl = "https://t.me/s/VlessConfig"),
        Source("t.me", "s", "NIM_VPN_ir", directUrl = "https://t.me/s/NIM_VPN_ir"),
        Source("t.me", "s", "FreeIranT", directUrl = "https://t.me/s/FreeIranT"),
        Source("t.me", "s", "ShadowProxy66", directUrl = "https://t.me/s/ShadowProxy66"),
        Source("t.me", "s", "v2rayNG_VPNN", directUrl = "https://t.me/s/v2rayNG_VPNN"),
        Source("t.me", "s", "vmess_vless_v2rayng", directUrl = "https://t.me/s/vmess_vless_v2rayng"),
        Source("t.me", "s", "V2RayTz", directUrl = "https://t.me/s/V2RayTz"),
        Source("t.me", "s", "CloudCityy", directUrl = "https://t.me/s/CloudCityy"),
        Source("t.me", "s", "IRN_VPN", directUrl = "https://t.me/s/IRN_VPN"),
        Source("t.me", "s", "Awlix_ir", directUrl = "https://t.me/s/Awlix_ir"),
        Source("t.me", "s", "Configforvpn01", directUrl = "https://t.me/s/Configforvpn01"),
        Source("t.me", "s", "FOX_VPN66", directUrl = "https://t.me/s/FOX_VPN66"),
        Source("t.me", "s", "DailyV2RY", directUrl = "https://t.me/s/DailyV2RY"),
        Source("t.me", "s", "lightning6", directUrl = "https://t.me/s/lightning6"),
        Source("t.me", "s", "SafeNet_Server", directUrl = "https://t.me/s/SafeNet_Server"),
        Source("t.me", "s", "VPNCUSTOMIZE", directUrl = "https://t.me/s/VPNCUSTOMIZE"),
        Source("t.me", "s", "v2rayngvpn", directUrl = "https://t.me/s/v2rayngvpn"),
        Source("t.me", "s", "DigiV2ray", directUrl = "https://t.me/s/DigiV2ray"),
        Source("t.me", "s", "FreeV2rays", directUrl = "https://t.me/s/FreeV2rays"),
        Source("t.me", "s", "FreeNet1500", directUrl = "https://t.me/s/FreeNet1500"),
        Source("t.me", "s", "OutlineVpnOfficial", directUrl = "https://t.me/s/OutlineVpnOfficial"),
        Source("t.me", "s", "v2ray_outlineir", directUrl = "https://t.me/s/v2ray_outlineir"),
        Source("t.me", "s", "v2ray_for_free", directUrl = "https://t.me/s/v2ray_for_free"),
        Source("t.me", "s", "V2Ray_FreedomIran", directUrl = "https://t.me/s/V2Ray_FreedomIran"),
        Source("t.me", "s", "v2rayn_server", directUrl = "https://t.me/s/v2rayn_server"),
        Source("t.me", "s", "VpnProSec", directUrl = "https://t.me/s/VpnProSec"),
        Source("t.me", "s", "WebShecan", directUrl = "https://t.me/s/WebShecan"),
        Source("t.me", "s", "XsV2ray", directUrl = "https://t.me/s/XsV2ray"),
        Source("t.me", "s", "yaney_01", directUrl = "https://t.me/s/yaney_01"),
        Source("t.me", "s", "zen_cloud", directUrl = "https://t.me/s/zen_cloud"),
    )

    private fun effectiveSources(small: Boolean): List<Source> {
        val base = SOURCES.filter { it.small == small }.distinctBy { it.address }
        if (!small) return base
        // Interleave Tier 1/2 GitHub sources with shuffled Telegram channels so t.me/s/
        // channels always run in the first concurrent batch instead of waiting behind 100+ repos.
        val gh = base.filterNot { it.isTelegram }
        val tg = base.filter { it.isTelegram }.shuffled()
        val interleaved = ArrayList<Source>(base.size)
        val maxSize = maxOf(gh.size, tg.size)
        for (i in 0 until maxSize) {
            if (i < gh.size) interleaved.add(gh[i])
            if (i < tg.size) interleaved.add(tg[i])
        }
        return interleaved
    }

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
     * Scores a parsed link by anti-censorship resilience and transport quality so
     * REALITY, XHTTP, Hysteria2 (+Salamander), WireGuard, gRPC, and SS-2022 are
     * tested and chosen ahead of overloaded plain WS/legacy configs.
     */
    internal fun qualityScore(link: Link): Int {
        val raw = link.raw.lowercase()
        return when (link.kind) {
            Kind.Vless -> when {
                "security=reality" in raw -> {
                    100 + (if ("type=xhttp" in raw || "type=grpc" in raw || "flow=xtls-rprx-vision" in raw) 5 else 0)
                }
                "type=xhttp" in raw -> 95
                "type=grpc" in raw -> 80
                link.direct -> 68
                else -> 64
            }
            Kind.Hysteria -> if ("obfs=salamander" in raw) 96 else 90
            Kind.WireGuard -> if ("reserved=" in raw || "fm=" in raw || "finalmask=" in raw) 90 else 86
            Kind.Trojan -> if (link.direct || "type=grpc" in raw) 76 else 60
            Kind.Shadowsocks -> if ("2022-blake3-" in raw) 74 else 45
            Kind.Vmess -> if (link.direct) 52 else 35
        }
    }

    /**
     * The safe links of the wanted [kinds] in [lines], each server once (and none that is in
     * [seen] already), ranked by anti-censorship strength within each sort and taken in turn
     * so that no sort crowds the others out.
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
            .mapValues { (_, list) -> list.sortedByDescending { qualityScore(it) } }
        val longest = byKind.values.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { index -> byKind.values.mapNotNull { it.getOrNull(index) } }
    }

    private val LINK_REGEX = Regex(
        """(?:vless|vmess|trojan|ss|hy2|hysteria2|hysteria|wireguard)://[^\s"'<>`\\]+""",
        RegexOption.IGNORE_CASE,
    )

    /** A list may come as plain lines, one Base64 block, or an HTML/Telegram channel page. */
    internal fun toLines(content: String): List<String> {
        val text = if ("://" in content) {
            content
        } else {
            decodeBase64(content.filterNot { it.isWhitespace() })?.let { String(it) }.orEmpty()
        }
        if (text.isBlank()) return emptyList()
        val looksLikeHtml = "<html" in text.lowercase() ||
            "tgme_widget_message" in text ||
            "<br" in text.lowercase() ||
            "&amp;" in text
        if (!looksLikeHtml) {
            return text.lines().map { it.trim() }.filter { "://" in it }
        }
        val unescaped = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(?:div|p|code|pre|li|tr|td|a)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        return LINK_REGEX.findAll(unescaped)
            .map { it.value.trim().trimEnd(',', '.', ';', ')') }
            .filter { "://" in it }
            .distinct()
            .toList()
    }

    // ---------- Getting the list ----------

    private fun cacheDir(context: Context) = File(context.filesDir, "rx-public-selected-v1").apply { mkdirs() }

    private fun cacheFile(context: Context, source: Source) =
        File(cacheDir(context), source.address.hashCode().toUInt().toString(16) + ".txt")

    private fun verifiedCacheFile(context: Context) = File(cacheDir(context), "verified_cache.txt")

    private fun recordVerifiedLink(context: Context, raw: String) {
        runCatching {
            val file = verifiedCacheFile(context)
            val existing = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            if (raw !in existing) {
                existing.add(0, raw)
                if (existing.size > 600) existing.subList(600, existing.size).clear()
                file.writeText(existing.joinToString("\n"))
            }
        }
    }

    private fun loadOfflinePool(context: Context): Pair<List<String>, Boolean> {
        val verified = runCatching {
            val f = verifiedCacheFile(context)
            if (f.exists()) f.readLines().filter { "://" in it } else emptyList()
        }.getOrDefault(emptyList())
        val cachedSources = SOURCES.asSequence().map { cacheFile(context, it) }
            .filter { it.isFile }
            .flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()).asSequence() }
            .toList()
        val saved = (verified + cachedSources).distinct()
        return saved to saved.isNotEmpty()
    }

    internal class Answer(val code: Int, val body: String?, val tag: String?)

    internal fun fetch(
        address: String,
        knownTag: String? = null,
        post: String? = null,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 12000,
    ): Answer = try {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
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
     * mirror, gives its kept copy. Stops quickly when [cancelled] is set, skips raw.githubusercontent.com
     * for the rest of the pass once it proves blocked, and caps wall time so testing starts fast.
     */
    private fun download(
        context: Context,
        small: Boolean,
        cancelled: AtomicBoolean? = null,
        minLinksDesired: Int = Int.MAX_VALUE,
        ignoreTags: Boolean = false,
        budgetMillis: Long = DOWNLOAD_BUDGET_MILLIS,
    ): Listing {
        if (cancelled?.get() == true) return Listing(emptyList(), 0, false)
        val sources = effectiveSources(small)
        val pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS)
        val results = ConcurrentLinkedQueue<Triple<List<String>, Boolean, Boolean>>()
        val finishedSources = java.util.Collections.synchronizedSet(HashSet<String>())
        val completedTasks = AtomicInteger(0)
        val answeredCount = AtomicInteger(0)
        val telegramAnswered = AtomicInteger(0)
        val totalLinks = AtomicInteger(0)
        val hasTelegramSources = sources.any { it.isTelegram }
        val rawGithubFailures = AtomicInteger(0)
        val rawGithubSuccesses = AtomicInteger(0)
        val startedAt = System.currentTimeMillis()
        try {
            sources.forEach { source ->
                pool.submit {
                    if (cancelled?.get() == true) {
                        completedTasks.incrementAndGet()
                        return@submit
                    }
                    val file = cacheFile(context, source)
                    val tagKey = KEY_TAG + file.name
                    val kept = if (file.exists()) runCatching { file.readLines() }.getOrNull() else null
                    val skipRaw = !source.isTelegram && source.directUrl == null &&
                        rawGithubSuccesses.get() == 0 && rawGithubFailures.get() >= 3
                    val answer = if (skipRaw) {
                        Answer(0, null, null)
                    } else {
                        fetch(
                            source.address,
                            if (ignoreTags || source.isTelegram) null else MmkvManager.decodeSettingsString(tagKey).takeIf { kept != null },
                            connectTimeoutMs = FETCH_CONNECT_MILLIS,
                            readTimeoutMs = FETCH_READ_MILLIS,
                        ).also {
                            if (!source.isTelegram && source.directUrl == null) {
                                if (it.code == 0) rawGithubFailures.incrementAndGet()
                                else rawGithubSuccesses.incrementAndGet()
                            }
                        }
                    }
                    if (cancelled?.get() == true) {
                        completedTasks.incrementAndGet()
                        return@submit
                    }
                    val body = when {
                        answer.body != null -> answer.body
                        answer.code == 304 -> null
                        source.mirror == source.address -> null
                        else -> fetch(
                            source.mirror,
                            connectTimeoutMs = FETCH_CONNECT_MILLIS,
                            readTimeoutMs = FETCH_READ_MILLIS,
                        ).body
                    }
                    val lines = body?.let { toLines(it) }
                    val item = when {
                        lines != null && lines.isNotEmpty() -> {
                            runCatching { file.writeText(lines.joinToString("\n")) }
                            MmkvManager.encodeSettings(tagKey, answer.tag.takeIf { answer.body != null })
                            Triple(lines, true, lines != kept)
                        }
                        else -> Triple(kept.orEmpty(), answer.code == 304, false)
                    }
                    finishedSources.add(source.address)
                    results.add(item)
                    if (item.second) {
                        answeredCount.incrementAndGet()
                        if (source.isTelegram) telegramAnswered.incrementAndGet()
                    }
                    totalLinks.addAndGet(item.first.size)
                    completedTasks.incrementAndGet()
                }
            }
            val deadline = startedAt + budgetMillis
            while (completedTasks.get() < sources.size && System.currentTimeMillis() < deadline) {
                if (cancelled?.get() == true) break
                val elapsed = System.currentTimeMillis() - startedAt
                val enoughTelegram = !hasTelegramSources || telegramAnswered.get() >= 6 || elapsed >= 8_000L
                if (minLinksDesired < Int.MAX_VALUE && elapsed >= 2500L &&
                    answeredCount.get() >= 28 && enoughTelegram && totalLinks.get() >= minLinksDesired
                ) {
                    break
                }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val list = results.toMutableList()
        if (cancelled?.get() != true && finishedSources.size < sources.size) {
            sources.filterNot { it.address in finishedSources }.forEach { source ->
                val file = cacheFile(context, source)
                if (file.exists()) {
                    val kept = runCatching { file.readLines() }.getOrNull().orEmpty()
                    if (kept.isNotEmpty()) list.add(Triple(kept, false, false))
                }
            }
        }
        // Put newly changed and freshly answered sources first so stale cache entries
        // never burn through the test queue before fresh configs are tried.
        val ordered = list.sortedWith(
            compareByDescending<Triple<List<String>, Boolean, Boolean>> { it.third }
                .thenByDescending { it.second }
        )
        val answered = ordered.count { it.second }
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: ${if (small) "short" else "large"} lists, $answered of ${sources.size} sources answered " +
                "(${ordered.count { it.third }} changed), ${ordered.sumOf { it.first.size }} links",
        )
        return Listing(ordered.flatMap { it.first }, answered, ordered.any { it.third })
    }

    /**
     * Checks whether the configs currently sitting in [GROUP_ID] are dead (unreachable),
     * so a 15-minute refresh can update the sources and replace dead configs without
     * waiting for [REBUILD_MILLIS].
     */
    private fun areGroupConfigsDead(): Boolean {
        val guids = MmkvManager.decodeServerList(GROUP_ID)
        if (guids.isEmpty()) return false
        val sample = guids.take(10)
        var anyAlive = false
        for (guid in sample) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val host = profile.server.orEmpty()
            val port = profile.serverPort?.toIntOrNull() ?: continue
            if (host.isBlank() || port <= 0) continue
            val isUdp = profile.configType == com.v2ray.ang.enums.EConfigType.WIREGUARD ||
                profile.configType == com.v2ray.ang.enums.EConfigType.HYSTERIA2
            val reachable = if (isUdp) {
                runCatching { !isCloudflare(InetAddress.getByName(host).address) }.getOrDefault(false)
            } else {
                runCatching {
                    val addr = InetAddress.getByName(host)
                    if (isCloudflare(addr.address)) false
                    else Socket().use { it.connect(InetSocketAddress(addr, port), CONNECT_MILLIS); true }
                }.getOrDefault(false)
            }
            if (reachable) {
                anyAlive = true
                break
            }
        }
        return !anyAlive
    }

    /** Remove the previous public group once it is safe to replace, without touching user groups. */
    private fun migrateSelectedSources(context: Context): Boolean = synchronized(groupWriteLock) {
        if (MmkvManager.decodeSettingsBool(KEY_SELECTED_SOURCES_MIGRATED, false)) return@synchronized true
        // Do not remove the selected profile while it is carrying an active connection.
        if (ServiceRuntimeState.isRunning(context)) return@synchronized false
        MmkvManager.clearServerList(GROUP_ID)
        // The old directory belongs only to the retired public-source catalog.
        val retiredCache = File(context.filesDir, "rx-public")
        if (retiredCache.exists() && !retiredCache.deleteRecursively()) return@synchronized false
        MmkvManager.encodeSettings(KEY_CHECKED, 0L)
        MmkvManager.encodeSettings(KEY_REBUILT, 0L)
        MmkvManager.encodeSettings(KEY_DAILY_SOURCES, 0L)
        MmkvManager.encodeSettings(KEY_SELECTED_SOURCES_MIGRATED, true)
        setFresh(true)
        notifyGroupUpdated()
        true
    }

    /**
     * Refreshes all Public Configs source lists (both short and large) once per day so the
     * source caches on disk stay up to date even when the user hasn't opened the screen.
     */
    fun refreshSourcesDaily(context: Context, force: Boolean = false): Boolean {
        if (!isNetworkAvailable(context) || !dailySourcesInFlight.compareAndSet(false, true)) return false
        try {
            val now = System.currentTimeMillis()
            if (!force && now - MmkvManager.decodeSettingsLong(KEY_DAILY_SOURCES, 0L) < DAILY_MILLIS) return false
            val smallListing = download(context, small = true, ignoreTags = true, budgetMillis = DAILY_DOWNLOAD_BUDGET_MILLIS)
            val largeListing = download(context, small = false, ignoreTags = true, budgetMillis = DAILY_DOWNLOAD_BUDGET_MILLIS)
            if (smallListing.answered + largeListing.answered > 0) {
                MmkvManager.encodeSettings(KEY_DAILY_SOURCES, now)
            }
            if (smallListing.changed || largeListing.changed) {
                setFresh(true)
            }
            AppLog.info(
                AppLog.Section.Other,
                "Public configs: daily source update finished (${smallListing.answered + largeListing.answered} sources answered)",
            )
            return smallListing.changed || largeListing.changed
        } finally {
            dailySourcesInFlight.set(false)
        }
    }

    /**
     * Looks at the short lists when the last look is older than fifteen minutes, when
     * the current public configs are dead, or when the daily 24h update is due.
     * True when the group was renewed.
     */
    fun refreshIfDue(context: Context): Boolean {
        val migrated = migrateSelectedSources(context)
        val now = System.currentTimeMillis()
        val lastRebuilt = MmkvManager.decodeSettingsLong(KEY_REBUILT, 0L)
        val dailyDue = !hasGroup() || (now - lastRebuilt >= DAILY_MILLIS)
        if (!dailyDue && now - MmkvManager.decodeSettingsLong(KEY_CHECKED, 0L) < REFRESH_MILLIS) return false

        // The lists are only looked at again after fifteen minutes, so the marker
        // is written before the work starts — otherwise two calls at once would
        // both download. It is put back when the work cannot finish, below, so a
        // check that was blocked is not counted as one that was done.
        MmkvManager.encodeSettings(KEY_CHECKED, now)
        if (dailyDue) {
            runCatching { refreshSourcesDaily(context) }
        }
        val dead = areGroupConfigsDead()
        val listing = download(context, small = true, ignoreTags = dead || dailyDue)
        if (dead) {
            // Also refresh large lists when existing configs are dead so fresh sources are available.
            download(context, small = false, ignoreTags = true)
        }
        val old = now - lastRebuilt >= REBUILD_MILLIS
        if (listing.changed || dead || dailyDue) setFresh(true)
        // Downloading can continue during a connection, but removing its old profile cannot.
        if (!migrated) return false
        val pending = MmkvManager.decodeSettingsBool(KEY_FRESH, false)
        if (!dailyDue && !listing.changed && !old && !dead && !pending) return false
        if (ServiceRuntimeState.isRunning(context)) {
            MmkvManager.encodeSettings(KEY_CHECKED, now - REFRESH_MILLIS)
            AppLog.info(AppLog.Section.Other, "Public configs: a tunnel is up; the group is renewed at the next idle check")
            return false
        }
        if (!hasLine(context)) {
            MmkvManager.encodeSettings(KEY_CHECKED, now - REFRESH_MILLIS)
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
            MmkvManager.encodeSettings(KEY_REBUILT, now)
            MmkvManager.encodeSettings(KEY_CHECKED, now)
            notifyGroupUpdated()
            return true
        }
        return false
    }

    /** Check for new posts at Android's minimum periodic interval; a full refresh runs daily. */
    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<PublicConfigsWorker>(15, TimeUnit.MINUTES)
            // Give the first screen time to settle before the initial source scan.
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "rx_public_configs_daily", ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    /** Looks at the lists every fifteen minutes, also while the app is closed. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<PublicConfigsWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("rx_public_configs", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** True when the public group holds configs, so there is something to renew. */
    fun hasGroup(): Boolean = MmkvManager.decodeServerList(GROUP_ID).isNotEmpty()

    /** Emits a new timestamp every time [GROUP_ID] rows change so the main table updates immediately. */
    val groupUpdated: StateFlow<Long> by lazy { groupUpdatedState }
    private val groupUpdatedState = MutableStateFlow(0L)

    private fun notifyGroupUpdated() {
        groupUpdatedState.value = System.currentTimeMillis()
        runCatching { SettingsChangeManager.makeSetupGroupTab() }
    }

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
            // The saved figure is taken as saved. It used to be pulled up to at
            // least 10, so a number below ten could not be kept.
            count = acceptedCount(MmkvManager.decodeSettingsInt(KEY_COUNT, 20)),
            mux = false,
            fragment = false,
            game = GamingMode.isEnabled(),
        )
    }

    /**
     * The number actually worked towards, from what the owner set.
     *
     * One place, so the figure on the screen, the figure in the log and the figure
     * the search aims at cannot disagree — which they did: the slider offered a
     * number, the collection clamped it to 150, and neither said so.
     */
    fun acceptedCount(count: Int): Int = count.coerceIn(1, MAX_COUNT)

    // ---------- Collecting ----------

    /** True when the device currently reports an active network with internet capability. */
    internal fun isNetworkAvailable(context: Context?): Boolean {
        if (context == null) return true
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching true
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    /** True when the server is not behind Cloudflare (unless allowed remotely) and, for TCP sorts, accepts a connection. */
    private fun reaches(link: Link): Boolean = try {
        val address = InetAddress.getByName(link.host)
        val allowCf = RemoteControl.allowCloudflarePublicConfigs()
        when {
            !allowCf && isCloudflare(address.address) -> false
            // UDP protocols (WireGuard, Hysteria2) cannot be checked with a TCP connect;
            // once DNS resolves outside Cloudflare, let Xray probe them directly.
            link.kind.udp -> true
            else -> {
                Socket().use {
                    it.connect(InetSocketAddress(address, link.port), CONNECT_MILLIS)
                    true
                }
            }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * True when this phone really has a line to the internet right now.
     *
     * Checks ConnectivityManager first for instant offline detection, then races
     * multiple external endpoints so a single blocked IP does not look offline
     * while an offline router still returns false quickly.
     */
    internal fun hasLine(context: Context? = null): Boolean {
        if (!isNetworkAvailable(context)) return false
        val pool = Executors.newFixedThreadPool(LINE_PROBE_ENDPOINTS.size)
        val connected = AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(LINE_PROBE_ENDPOINTS.size)
        return try {
            LINE_PROBE_ENDPOINTS.forEach { (host, port) ->
                pool.submit {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), LINE_PROBE_MILLIS)
                            if (connected.compareAndSet(false, true)) {
                                while (latch.count > 0) latch.countDown()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(LINE_PROBE_MILLIS + 300L, TimeUnit.MILLISECONDS)
            connected.get()
        } catch (_: Exception) {
            false
        } finally {
            pool.shutdownNow()
        }
    }

    private val lineCheckLock = Any()

    private fun checkLineOrAbort(
        context: Context,
        cancelled: AtomicBoolean,
        disconnected: AtomicBoolean,
        lastCheckAt: AtomicLong,
    ): Boolean {
        if (disconnected.get()) return false
        val now = System.currentTimeMillis()
        if (now - lastCheckAt.get() < LINE_RECHECK_MIN_GAP_MILLIS) return true
        synchronized(lineCheckLock) {
            if (disconnected.get()) return false
            val recheckNow = System.currentTimeMillis()
            if (recheckNow - lastCheckAt.get() < LINE_RECHECK_MIN_GAP_MILLIS) return true
            lastCheckAt.set(recheckNow)
            if (!hasLine(context)) {
                disconnected.set(true)
                cancelled.set(true)
                AppLog.error(AppLog.Section.Other, "Public configs: internet disconnected; stopping immediately")
                return false
            }
            return true
        }
    }

    /**
     * Fills the public group with the number of configs its owner asked for, each
     * one tested on the user's real internet connection before it is kept.
     *
     * Uses pipelined chunks: parallel TCP pre-filtering feeds bounded parallel
     * Xray round-trip checks (`measureOutboundDelay`), and only configs that answer
     * through Xray undergo real-byte download confirmation. Stops immediately if
     * the user's internet disconnects.
     */
    fun collect(
        context: Context,
        options: Options,
        cancelled: AtomicBoolean,
        disconnected: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit,
    ): Outcome {
        onProgress(Progress(null, 0, 0, 0))
        if (!migrateSelectedSources(context)) return Outcome(0, Origin.Saved)
        val wanted = acceptedCount(options.count)
        MmkvManager.encodeSettings(KEY_KINDS, options.kinds.joinToString(",") { it.name })
        MmkvManager.encodeSettings(KEY_COUNT, wanted)

        // Emptied when the first config proves itself (or offline pool imports), not before,
        // so this collection replaces the last one instead of piling onto it — and only once
        // there is something to replace it with.
        val previous = MmkvManager.decodeServerList(GROUP_ID)
        var replaced = false
        fun clearGroupOnce() {
            if (!replaced) {
                MmkvManager.clearServerList(GROUP_ID)
                replaced = true
            }
        }

        if (!hasLine(context)) {
            disconnected.set(true)
            val (offlineLines, _) = loadOfflinePool(context)
            val offlineLinks = select(offlineLines.asSequence(), options.kinds, options.game).take(wanted)
            val originOffline = Origin.Saved
            if (offlineLinks.isEmpty()) {
                AppLog.error(
                    AppLog.Section.Other,
                    "Public configs: offline and no matching configs in the selected-source cache",
                )
                return Outcome(0, originOffline, disconnected = true)
            }
            var offlineImported = 0
            synchronized(groupWriteLock) {
                clearGroupOnce()
                offlineImported = importIntoGroup(context, offlineLinks.map { it.raw })
            }
            if (offlineImported > 0) {
                runCatching { SettingsChangeManager.makeSetupGroupTab() }
                AppLog.info(
                    AppLog.Section.Other,
                    "Public configs: offline mode loaded $offlineImported configs from ${originOffline.name.lowercase()} pool",
                )
            }
            return Outcome(offlineImported, originOffline, disconnected = true)
        }

        val first = download(
            context = context,
            small = true,
            cancelled = cancelled,
            minLinksDesired = maxOf(wanted * 20, 800),
        )
        if (disconnected.get() || (cancelled.get() && !hasLine(context))) {
            disconnected.set(true)
            return Outcome(0, Origin.Saved, disconnected = true)
        }
        if (cancelled.get()) {
            return Outcome(0, Origin.Saved, disconnected = false)
        }

        var origin = when {
            first.answered > 0 -> Origin.Fresh
            else -> Origin.Saved
        }
        if (origin == Origin.Fresh) MmkvManager.encodeSettings(KEY_CHECKED, System.currentTimeMillis())

        val kept = mutableListOf<Link>()
        val keptRaws = HashSet<String>()
        val seen = HashSet<String>()
        var found = 0
        var tried = 0
        var measured = 0
        val verifiedCounter = AtomicInteger(0)
        // How many configs are really in the tab.
        var carried = 0
        var rounds = 0
        var poolSize = 0
        val lastLineCheckAt = AtomicLong(System.currentTimeMillis())

        fun report() {
            onProgress(Progress(origin, found, tried, kept.size, wanted, measured, carried, rounds, poolSize))
        }

        val queue = ArrayDeque(select(first.lines.asSequence(), options.kinds, options.game, seen))
        poolSize = queue.size
        var triedLarge = false
        val chunkSize = (wanted * 4).coerceIn(CHUNK_MIN, CHUNK_MAX)
        val remoteMaxSec = RemoteControl.current().publicConfigs.maxDurationSec
        val durationMillis = if (remoteMaxSec != null && remoteMaxSec > 0L) remoteMaxSec * 1000L else MAX_DURATION_MILLIS
        val deadline = System.currentTimeMillis() + durationMillis
        var imported = 0
        var lastGroupNoticeNanos = 0L

        while (!cancelled.get() && !disconnected.get() && carried < wanted && System.currentTimeMillis() < deadline) {
            if (!isNetworkAvailable(context)) {
                disconnected.set(true)
                cancelled.set(true)
                break
            }
            if (queue.isEmpty()) {
                if (!triedLarge) {
                    triedLarge = true
                    val large = download(
                        context = context,
                        small = false,
                        cancelled = cancelled,
                        minLinksDesired = maxOf((wanted - carried) * 20, 1000),
                    )
                    if (large.answered > 0) origin = Origin.Fresh
                    val extra = select(large.lines.asSequence(), options.kinds, options.game, seen)
                    queue.addAll(extra)
                    poolSize += extra.size
                }
                if (queue.isEmpty()) break
            }

            rounds++
            val batch = ArrayList<Link>(chunkSize)
            while (batch.size < chunkSize && queue.isNotEmpty()) {
                batch.add(queue.removeFirst())
            }
            if (batch.isEmpty()) break
            found += batch.size
            report()

            val reachable = quickCheck(batch, options, cancelled) { count -> tried += count; report() }
            if (cancelled.get() || disconnected.get()) break

            if (reachable.isEmpty() && !checkLineOrAbort(context, cancelled, disconnected, lastLineCheckAt)) {
                break
            }

            // When the cheap TCP check answers nothing on a line that blocks raw
            // connects, measure a slice directly through Xray instead of dropping all.
            val candidates = reachable.ifEmpty { batch.take(FALLBACK_MEASURE) }
            if (candidates.isEmpty()) continue

            val carriedNow = runBlocking {
                verify(
                    context = context,
                    candidates = candidates,
                    wanted = (wanted - carried).coerceAtLeast(1),
                    verify = options.verify,
                    cancelled = cancelled,
                    disconnected = disconnected,
                    deadline = deadline,
                    verifiedCounter = verifiedCounter,
                    lastLineCheckAt = lastLineCheckAt,
                    onCarried = { link, profile, pingMs, speed ->
                        val isNew = synchronized(kept) { keptRaws.add(link.raw).also { if (it) kept += link } }
                        if (isNew) {
                            synchronized(groupWriteLock) {
                                // The first proven config is the moment the old group
                                // is out of date. Until then nothing is touched.
                                clearGroupOnce()
                                val added = importIntoGroup(context, profile, link.raw, pingMs, speed)
                                imported += added
                                if (added > 0) carried += added
                                if (added > 0) {
                                    recordVerifiedLink(context, link.raw)
                                    // Rebuilding every tab for every imported profile causes repeated
                                    // work on large lists. Show the first result immediately and
                                    // coalesce subsequent updates; completion always sends a final one.
                                    val now = System.nanoTime()
                                    if (lastGroupNoticeNanos == 0L || now - lastGroupNoticeNanos >= 1_000_000_000L) {
                                        lastGroupNoticeNanos = now
                                        notifyGroupUpdated()
                                    }
                                }
                            }
                            report()
                        }
                    },
                ) { verified, _ ->
                    measured = verified
                    report()
                }
            }
            val fresh = carriedNow.filter { it.first.raw in keptRaws }.map { it.first }
            report()
            if (fresh.isEmpty() && kept.isEmpty()) {
                AppLog.info(
                    AppLog.Section.Other,
                    "Public configs: chunk $rounds measured ${candidates.size} and none carried",
                )
            }
        }

        if (kept.isEmpty()) {
            if (previous.isNotEmpty()) {
                AppLog.info(
                    AppLog.Section.Other,
                    "Public configs: nothing carried this time; the ${previous.size} already in the tab were left as they were",
                )
            }
            if (disconnected.get()) {
                AppLog.error(AppLog.Section.Other, "Public configs: stopped because the internet disconnected")
                return Outcome(0, origin, disconnected = true)
            }
            if (cancelled.get()) {
                AppLog.info(AppLog.Section.Other, "Public configs: stopped on request; nothing had carried yet")
                return Outcome(0, origin, disconnected = false)
            }
            AppLog.error(
                AppLog.Section.Other,
                "Public configs: none of the $measured measured carried anything on this line",
            )
            return Outcome(0, origin, disconnected = false)
        }

        val group = MmkvManager.decodeSubscription(GROUP_ID) ?: SubscriptionItem()
        group.remarks = context.getString(R.string.public_group_name)
        MmkvManager.encodeSubscription(GROUP_ID, group)
        runCatching { AutoConfigTest.sortGroup(GROUP_ID) }
        setFresh(false)
        MmkvManager.encodeSettings(KEY_REBUILT, System.currentTimeMillis())
        if (imported > 0) {
            notifyGroupUpdated()
        }
        AppLog.info(
            AppLog.Section.Other,
            "Public configs: $imported of the $wanted asked for after $rounds chunk(s). " +
                "The pool had $poolSize; $tried were checked, $measured were tested via Xray on this line" +
                (if (disconnected.get()) " (stopped when internet disconnected)" else ""),
        )
        return Outcome(imported, origin, disconnected = disconnected.get())
    }

    /**
     * Imports raw links into the public group, keeping whatever is already there.
     */
    private fun importIntoGroup(context: Context, raws: List<String>): Int {
        if (raws.isEmpty()) return 0
        val group = MmkvManager.decodeSubscription(GROUP_ID) ?: SubscriptionItem()
        group.remarks = context.getString(R.string.public_group_name)
        MmkvManager.encodeSubscription(GROUP_ID, group)
        return runCatching {
            AngConfigManager.importBatchConfig(raws.joinToString("\n"), GROUP_ID, true).first
        }.getOrDefault(0)
    }

    /**
     * Puts one measured config into the tab, recording its measured ping and speed
     * so the list is immediately ranked best-first.
     */
    private fun importIntoGroup(
        context: Context,
        profile: ProfileItem?,
        raw: String,
        pingMs: Long = 0L,
        speedKbps: Int = 0,
    ): Int {
        val group = MmkvManager.decodeSubscription(GROUP_ID) ?: SubscriptionItem()
        group.remarks = context.getString(R.string.public_group_name)
        MmkvManager.encodeSubscription(GROUP_ID, group)
        val before = MmkvManager.decodeServerList(GROUP_ID).size
        val measured = profile?.let { proved ->
            runCatching {
                val guid = Utils.getUuid()
                proved.subscriptionId = GROUP_ID
                MmkvManager.saveServerProfiles(
                    profiles = mapOf(guid to proved),
                    rawConfigs = mapOf(guid to raw),
                    subscriptionId = GROUP_ID,
                    append = true,
                )
                if (pingMs > 0L) {
                    MmkvManager.encodeServerTestDelayMillis(guid, pingMs)
                    AutoConfigTest.record(guid, pingMs, speedKbps.takeIf { it > 0 })
                }
                guid
            }.getOrNull()
        }
        if (measured != null) {
            return (MmkvManager.decodeServerList(GROUP_ID).size - before).coerceAtLeast(0)
        }
        return importIntoGroup(context, listOf(raw))
    }

    /** Fast parallel TCP pre-check; rejects unreachable hosts before starting Xray. */
    private fun quickCheck(
        links: List<Link>,
        options: Options,
        cancelled: AtomicBoolean,
        onCount: (tried: Int) -> Unit,
    ): List<Link> {
        val reachable = java.util.Collections.synchronizedList(mutableListOf<Link>())
        val pool = Executors.newFixedThreadPool(TESTING_AT_ONCE)
        try {
            for (batch in links.chunked(TESTING_AT_ONCE)) {
                if (cancelled.get() || reachable.size >= links.size) break
                val answers = pool.invokeAll(
                    batch.map { link ->
                        Callable {
                            if (cancelled.get()) false else reaches(link)
                        }
                    },
                )
                var done = 0
                batch.forEachIndexed { index, link ->
                    val answered = runCatching { answers[index].get() }.getOrDefault(false)
                    if (answered) reachable.add(link)
                    done++
                }
                onCount(done)
            }
        } finally {
            pool.shutdownNow()
        }
        return reachable.toList()
    }

    /**
     * Tests candidates in parallel through Xray on the user's real internet (`measureOutboundDelay`),
     * skips failed configs immediately, and confirms real byte transfer on those that answer.
     */
    private suspend fun verify(
        context: Context,
        candidates: List<Link>,
        wanted: Int,
        verify: Boolean,
        cancelled: AtomicBoolean,
        disconnected: AtomicBoolean = AtomicBoolean(false),
        deadline: Long = Long.MAX_VALUE,
        verifiedCounter: AtomicInteger = AtomicInteger(0),
        lastLineCheckAt: AtomicLong = AtomicLong(System.currentTimeMillis()),
        onCarried: (Link, ProfileItem?, Long, Int) -> Unit = { _, _, _, _ -> },
        onProgress: (verified: Int, carried: Int) -> Unit,
    ): List<Pair<Link, Int>> {
        if (!verify) {
            return candidates.take(wanted).map { it to 0 }
        }
        val gate = Semaphore(VERIFY_AT_ONCE)
        val carried = java.util.Collections.synchronizedList(mutableListOf<Pair<Link, Int>>())
        val done = verifiedCounter
        val consecutiveFailures = AtomicInteger(0)
        val oneAtATime = Mutex()
        val delayTestUrl = SettingsManager.getDelayTestUrl()
        val verifyTimeoutMs = RemoteControl.current().publicConfigs.verifyTimeoutMs
            ?.takeIf { it > 0L } ?: VERIFY_TIMEOUT_MILLIS
        val active = AtomicInteger(candidates.size)
        val verifyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        candidates.forEach { link ->
            verifyScope.async {
                try {
                    if (cancelled.get() || disconnected.get() || carried.size >= wanted) return@async
                    gate.withPermit {
                        if (cancelled.get() || disconnected.get() || carried.size >= wanted ||
                            System.currentTimeMillis() >= deadline
                        ) {
                            return@withPermit
                        }
                        val guid = privateImportSingle(link)
                        if (guid == null) {
                            done.incrementAndGet()
                            onProgress(done.get(), carried.size)
                            return@withPermit
                        }
                        try {
                            val built = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                            if (!built.status) {
                                done.incrementAndGet()
                                onProgress(done.get(), carried.size)
                                return@withPermit
                            }
                            var pingMs = CoreNativeManager.measureOutboundDelay(built.content, delayTestUrl)
                            if (pingMs <= 0L && link.kind.udp && !cancelled.get() && !disconnected.get()) {
                                delay(200)
                                pingMs = CoreNativeManager.measureOutboundDelay(built.content, delayTestUrl)
                            }
                            if (pingMs <= 0L) {
                                done.incrementAndGet()
                                if (consecutiveFailures.incrementAndGet() >= STREAK_LINE_CHECK) {
                                    checkLineOrAbort(context, cancelled, disconnected, lastLineCheckAt)
                                }
                                onProgress(done.get(), carried.size)
                                return@withPermit
                            }
                            if (cancelled.get() || disconnected.get() || carried.size >= wanted ||
                                System.currentTimeMillis() >= deadline
                            ) {
                                return@withPermit
                            }
                            val speed = oneAtATime.withLock {
                                if (cancelled.get() || disconnected.get() || carried.size >= wanted ||
                                    System.currentTimeMillis() >= deadline
                                ) {
                                    0
                                } else {
                                    // probeFast measures real payload transfer and falls back to HTTP 204 reachability.
                                    // Never admit a proxy that fails both real transfer and 204 reachability.
                                    AutoConfigTest.probeFast(context, guid, verifyTimeoutMs) ?: 0
                                }
                            }
                            done.incrementAndGet()
                            if (speed > 0) {
                                consecutiveFailures.set(0)
                                carried.add(link to speed)
                                AppLog.info(
                                    AppLog.Section.Other,
                                    "Public configs: ${link.kind.name} ${link.host} carried $speed kbit/s (${pingMs}ms) here",
                                )
                                val proved = MmkvManager.decodeServerConfig(guid)
                                onCarried(link, proved, pingMs, speed)
                            } else if (consecutiveFailures.incrementAndGet() >= STREAK_LINE_CHECK) {
                                checkLineOrAbort(context, cancelled, disconnected, lastLineCheckAt)
                            }
                            onProgress(done.get(), carried.size)
                        } finally {
                            synchronized(pendingGroupLock) {
                                runCatching { MmkvManager.removeServers(listOf(guid), PENDING_GROUP) }
                            }
                        }
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        while (active.get() > 0 && !cancelled.get() && !disconnected.get() &&
            carried.size < wanted && System.currentTimeMillis() < deadline
        ) {
            delay(40)
        }
        return carried.sortedByDescending { it.second }.take(wanted)
    }

    /**
     * Puts one [link] into [PENDING_GROUP] with a unique GUID without disturbing
     * other parallel workers, returning that GUID or null if import failed.
     */
    private fun privateImportSingle(link: Link): String? = synchronized(pendingGroupLock) {
        try {
            if (MmkvManager.decodeSubscription(PENDING_GROUP) == null) {
                MmkvManager.encodeSubscription(
                    PENDING_GROUP,
                    SubscriptionItem(remarks = PENDING_GROUP, url = "", enabled = false),
                )
            }
            val before = MmkvManager.decodeServerList(PENDING_GROUP).toHashSet()
            val added = AngConfigManager.importBatchConfig(link.raw, PENDING_GROUP, true).first
            if (added <= 0) return null
            MmkvManager.decodeServerList(PENDING_GROUP).firstOrNull { it !in before }
        } catch (e: Exception) {
            null
        }
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
        val answeredIds = answered.toHashSet()
        val silent = all.filterNot { it in answeredIds }
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

/** Fifteen-minute and daily source check; see [PublicConfigs.schedule]. */
class PublicConfigsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { PublicConfigs.refreshSourcesDaily(applicationContext) }
        runCatching { PublicConfigs.refreshIfDue(applicationContext) }
        return Result.success()
    }
}
