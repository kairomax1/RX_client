package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Hidden remote-control bridge ("rx-control.json") shared between the owner
 * and the agent across app builds.
 *
 * Completely invisible in the UI: refreshes quietly in the background, caches
 * the last valid policy in MMKV for offline resilience, and exposes live
 * overrides for public configs, Mozilla VPN, routing/ad-blocking, DNS scanner,
 * core/geo updates, Aether, and feature switches without requiring an APK push.
 */
object RemoteControl {

    private const val PREF_JSON = "pref_rx_remote_control_json"
    private const val PREF_AT = "pref_rx_remote_control_at"
    private const val PREF_LAST_APPLIED_REV = "pref_rx_remote_control_rev"
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

    private val gson = Gson()

    @Volatile
    private var cached: Config? = null

    data class Features(
        // Background list syncs default to OFF: they download without the user asking and
        // were the source of "lag + freeze + downloads by itself" on weak phones. The UI
        // screens (public configs, Mozilla VPN, DNS scanner) do not read these flags.
        val publicConfigs: Boolean = false,
        val mozillaVpn: Boolean = false,
        val gamingMode: Boolean = true,
        val dnsScanner: Boolean = false,
        val aether: Boolean = true,
        val adBlock: Boolean = true,
        val autoUpdate: Boolean = true,
        val autoFailover: Boolean? = null,
    )

    data class PublicConfigsPolicy(
        val extraGithubSources: List<String> = emptyList(),
        val extraUrlSources: List<String> = emptyList(),
        val disabledSources: List<String> = emptyList(),
        val emergencyConfigs: List<String> = emptyList(),
        /** Public-config safety override; Cloudflare exits stay excluded unless policy explicitly opts in. */
        val allowCloudflare: Boolean = false,
        val maxDurationSec: Long? = null,
        val verifyTimeoutMs: Long? = null,
        val dailyRefreshHours: Long = 24L,
    )

    data class RoutingPolicy(
        val blockDomains: List<String> = emptyList(),
        val unblockDomains: List<String> = emptyList(),
        val proxyDomains: List<String> = emptyList(),
        val directDomains: List<String> = emptyList(),
        val blockUdp443: Boolean? = true,
        val adListUrl: String? = null,
    )

    data class MozillaEndpoint(
        val host: String = "",
        val port: Int = 443,
        val name: String = "",
    )

    data class MozillaPolicy(
        val serverListUrl: String? = null,
        val extraEndpoints: List<MozillaEndpoint> = emptyList(),
        val userAgent: String? = null,
        val hideQuota: Boolean = true,
    )

    data class DnsScannerPolicy(
        val extraSources: List<String> = emptyList(),
        val disabledSources: List<String> = emptyList(),
        val extraAppNames: List<String> = emptyList(),
    )

    data class CorePolicy(
        val geositeUrl: String? = null,
        val geoipUrl: String? = null,
        val geoipCnPrivateUrl: String? = null,
        val coreCheckUrl: String? = null,
        // Off by default: geoip/geosite are multi-MB downloads. The APK ships with the
        // databases in assets, so a silent refresh is never required for correctness.
        val silentGeoUpdate: Boolean = false,
        val minSupportedVersionCode: Int = 0,
    )

    data class Directives(
        val note: String = "",
        val commands: List<String> = emptyList(),
    )

    data class Config(
        val schemaVersion: Int = 1,
        val revision: Long = 0L,
        val updatedAt: String = "",
        val features: Features = Features(),
        val publicConfigs: PublicConfigsPolicy = PublicConfigsPolicy(),
        val routing: RoutingPolicy = RoutingPolicy(),
        val mozilla: MozillaPolicy = MozillaPolicy(),
        val dnsScanner: DnsScannerPolicy = DnsScannerPolicy(),
        val core: CorePolicy = CorePolicy(),
        val directives: Directives = Directives(),
    )

    /** Returns the currently active policy (memory -> MMKV -> compiled defaults). */
    fun current(): Config {
        cached?.let { return it }
        val raw = runCatching { MmkvManager.decodeSettingsString(PREF_JSON) }.getOrNull()
        val parsed = raw?.let(::parse) ?: Config()
        cached = parsed
        return parsed
    }

    /** Parses [raw] JSON safely without throwing. */
    fun parse(raw: String): Config? = runCatching {
        val cfg = gson.fromJson(raw, Config::class.java) ?: return@runCatching null
        if (cfg.schemaVersion <= 0) return@runCatching null
        Config(
            schemaVersion = cfg.schemaVersion,
            revision = cfg.revision,
            updatedAt = cfg.updatedAt.orEmpty(),
            features = cfg.features ?: Features(),
            publicConfigs = cfg.publicConfigs ?: PublicConfigsPolicy(),
            routing = cfg.routing ?: RoutingPolicy(),
            mozilla = cfg.mozilla ?: MozillaPolicy(),
            dnsScanner = cfg.dnsScanner ?: DnsScannerPolicy(),
            core = cfg.core ?: CorePolicy(),
            directives = cfg.directives ?: Directives(),
        )
    }.getOrNull()

    /** Candidate URLs for `rx-control.json` across active branch, master, and jsDelivr mirror. */
    internal fun controlUrls(): List<String> {
        val urls = LinkedHashSet<String>()
        val branch = runCatching { BuildConfig.BUILD_BRANCH.trim() }.getOrDefault("master")
        if (branch.isNotEmpty() && branch != "master") {
            urls += "https://raw.githubusercontent.com/kairomax1/RX_client/$branch/rx-control.json"
            urls += "https://cdn.jsdelivr.net/gh/kairomax1/RX_client@$branch/rx-control.json"
        }
        urls += "https://raw.githubusercontent.com/kairomax1/RX_client/master/rx-control.json"
        urls += "https://cdn.jsdelivr.net/gh/kairomax1/RX_client@master/rx-control.json"
        return urls.toList()
    }

    /** Silently fetches & applies `rx-control.json` if due (or when [force] is true). */
    suspend fun refreshIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastAt = runCatching { MmkvManager.decodeSettingsLong(PREF_AT, 0L) }.getOrDefault(0L)
        if (!force && lastAt > 0L && now - lastAt < REFRESH_INTERVAL_MS) {
            return@withContext false
        }

        val token = runCatching { MmkvManager.decodeSettingsString(AppConfig.PREF_GITHUB_TOKEN).orEmpty().trim() }.getOrDefault("")
        val headers: String? = if (token.isNotEmpty()) gson.toJson(mapOf("Authorization" to "Bearer $token")) else null
        val httpPort = runCatching { SettingsManager.getHttpPort() }.getOrDefault(0)
        val proxyUser = runCatching { SettingsManager.getSocksUsername() }.getOrNull()
        val proxyPass = runCatching { SettingsManager.getSocksPassword() }.getOrNull()

        for (url in controlUrls()) {
            var body = runCatching {
                HttpUtil.getUrlContent(
                    UrlContentRequest(url = url, timeout = 5_000, requestHeaders = headers)
                )
            }.getOrNull()

            if (body.isNullOrBlank() && httpPort > 0) {
                body = runCatching {
                    HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = url,
                            timeout = 5_000,
                            httpPort = httpPort,
                            proxyUsername = proxyUser,
                            proxyPassword = proxyPass,
                            requestHeaders = headers,
                        )
                    )
                }.getOrNull()
            }

            if (body.isNullOrBlank()) continue
            val parsed = parse(body) ?: continue

            cached = parsed
            runCatching {
                MmkvManager.encodeSettings(PREF_JSON, body)
                MmkvManager.encodeSettings(PREF_AT, now)
            }
            applyDirectives(parsed)
            return@withContext true
        }
        false
    }

    private fun applyDirectives(config: Config) {
        val prevRev = runCatching { MmkvManager.decodeSettingsLong(PREF_LAST_APPLIED_REV, -1L) }.getOrDefault(-1L)
        if (config.revision <= prevRev) return
        runCatching { MmkvManager.encodeSettings(PREF_LAST_APPLIED_REV, config.revision) }

        config.directives.commands.forEach { rawCmd ->
            val cmd = rawCmd.trim()
            if (cmd.isEmpty()) return@forEach
            when {
                cmd.startsWith("set:") -> {
                    val kv = cmd.removePrefix("set:").split('=', limit = 2)
                    if (kv.size == 2) {
                        val key = kv[0].trim()
                        val value = kv[1].trim()
                        runCatching {
                            when {
                                value.equals("true", ignoreCase = true) -> MmkvManager.encodeSettings(key, true)
                                value.equals("false", ignoreCase = true) -> MmkvManager.encodeSettings(key, false)
                                else -> MmkvManager.encodeSettings(key, value)
                            }
                        }
                    }
                }
            }
        }

        if (config.directives.note.isNotBlank()) {
            runCatching {
                AppLog.info(AppLog.Section.Core, "RemoteControl rev=${config.revision}: ${config.directives.note}")
            }
        } else {
            runCatching {
                AppLog.debug(AppLog.Section.Core, "RemoteControl synced rev=${config.revision}")
            }
        }
    }

    /** Normalizes any domain list entries from policy (`domain:x`, `full:x`, `x`). */
    fun normalizedDomainKeys(entries: List<String>): Set<String> =
        entries.mapNotNull { raw ->
            val trimmed = raw.trim().lowercase(Locale.ROOT)
            if (trimmed.isEmpty()) return@mapNotNull null
            val bare = trimmed.removePrefix("domain:").removePrefix("full:").removePrefix("+.").removePrefix(".")
            if (bare.isEmpty() || !bare.contains('.')) null else bare
        }.toSet()

    fun unblockedDomainKeys(): Set<String> = normalizedDomainKeys(current().routing.unblockDomains)

    fun isFeatureEnabled(selector: (Features) -> Boolean): Boolean =
        runCatching { selector(current().features) }.getOrDefault(true)

    /** Only the public-config selector consumes this explicit exception; default is always deny. */
    fun allowCloudflarePublicConfigs(): Boolean =
        runCatching { current().publicConfigs.allowCloudflare }.getOrDefault(false)

    fun mozillaUserAgentOr(defaultUa: String): String =
        current().mozilla.userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: defaultUa

}
