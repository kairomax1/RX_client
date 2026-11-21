package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import libv2ray.CoreCallbackHandler

/**
 * Server-less Direct Fragment VPN: routes all traffic directly through Xray `freedom`
 * outbound using the user's own internet IP, with TCP/TLS packet fragmentation and
 * encrypted DNS (DoH/DoT) so DPI cannot read SNI or poison DNS.
 */
object FragmentVpn {

    const val PROFILE_GUID = "rx-fragment-vpn"
    const val GROUP_ID = "rx_fragment_vpn"

    private const val PREF_PRESET = "pref_rx_frag_vpn_preset"
    private const val PREF_AUTO_WINNER = "pref_rx_frag_vpn_auto_winner"
    private const val PREF_PACKETS = "pref_rx_frag_vpn_packets"
    private const val PREF_LENGTH = "pref_rx_frag_vpn_length"
    private const val PREF_DELAY = "pref_rx_frag_vpn_delay"
    private const val PREF_MAXSPLIT = "pref_rx_frag_vpn_maxsplit"
    private const val PREF_DROP_QUIC = "pref_rx_frag_vpn_drop_quic"
    private const val PREF_BYPASS_IRAN = "pref_rx_frag_vpn_bypass_iran"
    private const val PREF_DOH = "pref_rx_frag_vpn_doh"
    private const val PREF_LAST_PING = "pref_rx_frag_vpn_last_ping"

    enum class Preset(
        val key: String,
        val packets: String,
        val length: String,
        val delay: String,
        val maxSplit: String,
    ) {
        AUTO("auto", "tlshello", "50-100", "1-3", "32"),
        BALANCED("balanced", "tlshello", "50-100", "1-3", "32"),
        STRICT_ISP("strict", "tlshello", "30-60", "1-3", "48"),
        LOW_SPLIT("fine", "1-3", "40-80", "1-3", "32"),
        CUSTOM("custom", "tlshello", "50-100", "1-3", "32");

        companion object {
            fun fromKey(key: String?): Preset = entries.firstOrNull { it.key == key } ?: AUTO
            val measurablePresets: List<Preset> = listOf(BALANCED, STRICT_ISP, LOW_SPLIT)
        }
    }

    enum class DohOption(val key: String, val url: String, val label: String) {
        CLOUDFLARE("cf", "https://1.1.1.1/dns-query", "Cloudflare (1.1.1.1)"),
        GOOGLE("google", "https://8.8.8.8/dns-query", "Google (8.8.8.8)"),
        QUAD9("quad9", "https://9.9.9.9/dns-query", "Quad9 (9.9.9.9)"),
        ADGUARD("adguard", "https://dns.adguard-dns.com/dns-query", "AdGuard DoH"),
        CLOUDFLARE_DOT("cf_host", "https://cloudflare-dns.com/dns-query", "Cloudflare DoH");

        companion object {
            fun fromKey(key: String?): DohOption = entries.firstOrNull { it.key == key } ?: CLOUDFLARE
        }
    }

    data class Config(
        val preset: Preset,
        val resolvedPreset: Preset,
        val packets: String,
        val length: String,
        val delay: String,
        val maxSplit: String,
        val dropQuic: Boolean,
        val bypassIran: Boolean,
        val doh: DohOption,
        val lastPingMs: Long?,
    )

    fun isFragmentProfile(guid: String?): Boolean = guid == PROFILE_GUID

    fun isActiveRunning(context: Context): Boolean =
        ServiceRuntimeState.isRunning(context) &&
            MmkvManager.getSelectServer() == PROFILE_GUID

    fun currentConfig(): Config {
        val selected = Preset.fromKey(MmkvManager.decodeSettingsString(PREF_PRESET))
        val winner = Preset.fromKey(MmkvManager.decodeSettingsString(PREF_AUTO_WINNER))
            .takeIf { it in Preset.measurablePresets } ?: Preset.BALANCED
        val resolved = if (selected == Preset.AUTO) winner else selected
        val packets = if (selected == Preset.CUSTOM) {
            MmkvManager.decodeSettingsString(PREF_PACKETS)?.trim().takeUnless { it.isNullOrEmpty() } ?: "tlshello"
        } else {
            resolved.packets
        }
        val length = if (selected == Preset.CUSTOM) {
            MmkvManager.decodeSettingsString(PREF_LENGTH)?.trim().takeUnless { it.isNullOrEmpty() } ?: "50-100"
        } else {
            resolved.length
        }
        val delay = if (selected == Preset.CUSTOM) {
            MmkvManager.decodeSettingsString(PREF_DELAY)?.trim().takeUnless { it.isNullOrEmpty() } ?: "1-3"
        } else {
            resolved.delay
        }
        val maxSplit = if (selected == Preset.CUSTOM) {
            MmkvManager.decodeSettingsString(PREF_MAXSPLIT)?.trim().takeUnless { it.isNullOrEmpty() } ?: "32"
        } else {
            resolved.maxSplit
        }
        val dropQuic = MmkvManager.decodeSettingsBool(PREF_DROP_QUIC, true)
        val bypassIran = MmkvManager.decodeSettingsBool(PREF_BYPASS_IRAN, true)
        val doh = DohOption.fromKey(MmkvManager.decodeSettingsString(PREF_DOH))
        val lastPing = MmkvManager.decodeSettingsString(PREF_LAST_PING)?.toLongOrNull()
        return Config(
            preset = selected,
            resolvedPreset = resolved,
            packets = packets,
            length = length,
            delay = delay,
            maxSplit = maxSplit,
            dropQuic = dropQuic,
            bypassIran = bypassIran,
            doh = doh,
            lastPingMs = lastPing,
        )
    }

    fun savePreset(preset: Preset) {
        MmkvManager.encodeSettings(PREF_PRESET, preset.key)
    }

    fun saveCustomParams(packets: String, length: String, delay: String, maxSplit: String) {
        MmkvManager.encodeSettings(PREF_PACKETS, packets.trim().ifEmpty { "tlshello" })
        MmkvManager.encodeSettings(PREF_LENGTH, length.trim().ifEmpty { "50-100" })
        MmkvManager.encodeSettings(PREF_DELAY, delay.trim().ifEmpty { "1-3" })
        MmkvManager.encodeSettings(PREF_MAXSPLIT, maxSplit.trim().ifEmpty { "32" })
    }

    fun saveDropQuic(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_DROP_QUIC, enabled)
    }

    fun saveBypassIran(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_BYPASS_IRAN, enabled)
    }

    fun saveDoh(option: DohOption) {
        MmkvManager.encodeSettings(PREF_DOH, option.key)
    }

    fun saveLastPing(pingMs: Long?) {
        MmkvManager.encodeSettings(PREF_LAST_PING, pingMs?.toString())
    }

    /** Ensures the internal hidden profile exists for CoreServiceManager / LauncherManager. */
    fun ensureProfile(context: Context): String {
        if (MmkvManager.decodeSubscription(GROUP_ID) == null) {
            MmkvManager.encodeSubscription(
                GROUP_ID,
                SubscriptionItem(
                    remarks = context.getString(R.string.fragment_vpn_title),
                    url = "",
                    enabled = false,
                ),
            )
        }
        val profile = MmkvManager.decodeServerConfig(PROFILE_GUID)
            ?: ProfileItem.create(EConfigType.CUSTOM)
        profile.subscriptionId = GROUP_ID
        profile.remarks = context.getString(R.string.fragment_vpn_title)
        profile.server = "1.1.1.1"
        profile.serverPort = "443"
        MmkvManager.encodeServerConfig(PROFILE_GUID, profile)
        MmkvManager.encodeServerRaw(PROFILE_GUID, buildXrayConfigJson(context, forSpeedtest = false))
        return PROFILE_GUID
    }

    fun buildXrayConfigResult(
        context: Context,
        presetOverride: Preset? = null,
        forSpeedtest: Boolean = false,
    ): ConfigResult {
        val json = buildXrayConfigJson(context, presetOverride = presetOverride, forSpeedtest = forSpeedtest)
        return ConfigResult(status = true, guid = PROFILE_GUID, content = json)
    }

    /**
     * Tests the measurable Fragment presets on the user's current network using Xray's full
     * encrypted DoH DNS + routing + freedom fragment pipeline (when VPN is stopped), and
     * remembers the fastest working one for [Preset.AUTO].
     */
    fun probePresets(context: Context): Map<Preset, Long> {
        val delayTestUrls = listOf(
            SettingsManager.getDelayTestUrl(),
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
        ).distinct()
        val results = linkedMapOf<Preset, Long>()
        for (preset in Preset.measurablePresets) {
            // Even when VPN is active, CoreVpnService disallows selfPackageName so a temporary
            // CoreController on a random free port probes directly with full DoH + DNS routing.
            val ping = measurePresetWithFullDns(context, preset, delayTestUrls)
            results[preset] = ping
        }
        val winner = results.entries
            .filter { it.value > 0L }
            .minByOrNull { it.value }
        if (winner != null) {
            MmkvManager.encodeSettings(PREF_AUTO_WINNER, winner.key.key)
            saveLastPing(winner.value)
            AppLog.info(
                AppLog.Section.Connection,
                "Fragment VPN: best preset is ${winner.key.key} (${winner.value}ms)",
            )
        } else {
            saveLastPing(-1L)
            AppLog.error(AppLog.Section.Connection, "Fragment VPN: no preset answered on this network")
        }
        return results
    }

    private fun measurePresetWithFullDns(
        context: Context,
        preset: Preset,
        urls: List<String>,
    ): Long {
        val freePort = Utils.findRandomFreePort()
        val json = buildXrayConfigJson(
            context = context,
            presetOverride = preset,
            forSpeedtest = false,
            socksPortOverride = freePort,
            forceDisableTun = true,
        )
        val controller = CoreNativeManager.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(l: Long, s: String?): Long = 0L
        })
        return try {
            CoreNativeManager.initCoreEnv(context)
            controller.startLoop(json, 0)
            if (!controller.isRunning) return -1L
            for (url in urls) {
                val ping = runCatching { controller.measureDelay(url) }.getOrDefault(-1L)
                if (ping > 0L) return ping
            }
            -1L
        } catch (_: Throwable) {
            -1L
        } finally {
            runCatching {
                if (controller.isRunning) controller.stopLoop()
            }
        }
    }

    fun buildXrayConfigJson(
        context: Context,
        presetOverride: Preset? = null,
        forSpeedtest: Boolean = false,
        socksPortOverride: Int? = null,
        forceDisableTun: Boolean = false,
    ): String {
        val cfg = currentConfig()
        val activePreset = presetOverride ?: cfg.resolvedPreset
        val packets = if (presetOverride == null && cfg.preset == Preset.CUSTOM) cfg.packets else activePreset.packets
        val length = if (presetOverride == null && cfg.preset == Preset.CUSTOM) cfg.length else activePreset.length
        val delay = if (presetOverride == null && cfg.preset == Preset.CUSTOM) cfg.delay else activePreset.delay
        val maxSplit = if (presetOverride == null && cfg.preset == Preset.CUSTOM) cfg.maxSplit else activePreset.maxSplit
        val ipv6Enabled = runCatching { MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true }.getOrDefault(false)
        val domainStrategy = if (ipv6Enabled) "UseIP" else "UseIPv4"
        val logLevel = runCatching { MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) }.getOrNull() ?: "warning"
        val needTun = !forSpeedtest && !forceDisableTun && SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()

        val inbounds = arrayListOf<V2rayConfig.InboundBean>()
        if (!forSpeedtest) {
            val sniffingDest = arrayListOf("http", "tls")
            if (!cfg.dropQuic) sniffingDest.add("quic")
            val sniffing = V2rayConfig.InboundBean.SniffingBean(
                enabled = true,
                destOverride = sniffingDest,
                routeOnly = false,
            )
            inbounds.add(
                V2rayConfig.InboundBean(
                    tag = "socks",
                    port = socksPortOverride ?: SettingsManager.getSocksPort(),
                    protocol = "socks",
                    listen = AppConfig.LOOPBACK,
                    settings = V2rayConfig.InboundBean.InSettingsBean(
                        auth = "noauth",
                        udp = true,
                        userLevel = AppConfig.DEFAULT_LEVEL,
                    ),
                    sniffing = sniffing,
                )
            )
            if (needTun) {
                val tunTemplate = runCatching {
                    val raw = Utils.readTextFromAssets(context, "v2ray_config_with_tun.json")
                    JsonUtil.fromJson(raw, V2rayConfig::class.java)?.inbounds?.firstOrNull { it.tag == "tun" }
                }.getOrNull()
                if (tunTemplate != null) {
                    tunTemplate.settings?.mtu = SettingsManager.getVpnMtu()
                    tunTemplate.sniffing = sniffing
                    inbounds.add(tunTemplate)
                }
            }
        }

        val proxyOutbound = V2rayConfig.OutboundBean(
            tag = AppConfig.TAG_PROXY,
            protocol = AppConfig.PROTOCOL_FREEDOM,
            settings = null,
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                network = null,
                sockopt = V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(
                    TcpNoDelay = true,
                    tcpKeepAliveIdle = 30,
                    domainStrategy = domainStrategy,
                    happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                        prioritizeIPv6 = ipv6Enabled,
                        maxConcurrentTry = 4,
                        tryDelayMs = 250,
                        interleave = 2,
                    ),
                ),
                finalmask = V2rayConfig.OutboundBean.StreamSettingsBean.FinalMaskBean(
                    tcp = listOf(
                        V2rayConfig.OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "fragment",
                            settings = V2rayConfig.OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                packets = packets,
                                length = length,
                                delay = delay,
                                maxSplit = maxSplit,
                            ),
                        )
                    ),
                ),
            ),
            mux = null,
        )

        val directOutbound = V2rayConfig.OutboundBean(
            tag = AppConfig.TAG_DIRECT,
            protocol = AppConfig.PROTOCOL_FREEDOM,
            settings = null,
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                network = null,
                sockopt = V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(
                    TcpNoDelay = true,
                    tcpKeepAliveIdle = 30,
                    domainStrategy = domainStrategy,
                ),
            ),
            mux = null,
        )

        val blockOutbound = V2rayConfig.OutboundBean(
            tag = AppConfig.TAG_BLOCKED,
            protocol = "blackhole",
            settings = null,
            mux = null,
        )

        val dnsOutbound = V2rayConfig.OutboundBean(
            tag = "dns-out",
            protocol = "dns",
            mux = null,
        )

        val outbounds = arrayListOf(proxyOutbound, directOutbound, blockOutbound)
        if (!forSpeedtest) outbounds.add(dnsOutbound)

        val rules = arrayListOf<V2rayConfig.RoutingBean.RulesBean>()
        if (!forSpeedtest) {
            val dnsInboundTags = if (needTun) listOf("socks", "tun") else listOf("socks")
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    inboundTag = dnsInboundTags,
                    port = "53",
                    outboundTag = "dns-out",
                )
            )
            if (cfg.bypassIran) {
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = listOf(AppConfig.TAG_DOMESTIC_DNS),
                        outboundTag = AppConfig.TAG_DIRECT,
                    )
                )
            }
        }
        rules.add(
            V2rayConfig.RoutingBean.RulesBean(
                inboundTag = listOf(AppConfig.TAG_DNS),
                outboundTag = AppConfig.TAG_PROXY,
            )
        )
        if (cfg.dropQuic) {
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    network = "udp",
                    port = "443",
                    outboundTag = AppConfig.TAG_BLOCKED,
                )
            )
        }
        if (!forSpeedtest) {
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    ip = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7"),
                    outboundTag = AppConfig.TAG_DIRECT,
                )
            )
            if (cfg.bypassIran) {
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        domain = listOf("domain:ir"),
                        outboundTag = AppConfig.TAG_DIRECT,
                    )
                )
            }
        }
        rules.add(
            V2rayConfig.RoutingBean.RulesBean(
                network = "tcp,udp",
                outboundTag = AppConfig.TAG_PROXY,
            )
        )

        val dnsServers = arrayListOf<Any>()
        if (!forSpeedtest && cfg.bypassIran) {
            val domesticDns = SettingsManager.getDomesticDnsServers(context).firstOrNull()
                ?: GamingMode.DIRECT_RESOLVERS.first()
            dnsServers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = domesticDns,
                    domains = listOf("domain:ir"),
                    skipFallback = true,
                    tag = AppConfig.TAG_DOMESTIC_DNS,
                )
            )
        }
        // Only encrypted DoH (https://) resolvers supported by Xray-core (`app/dns/nameserver.go`).
        // Leave `tag` unset so every remote DoH server inherits `dns.tag` (AppConfig.TAG_DNS),
        // matches the TAG_DNS -> TAG_PROXY routing rule, and shares one PolicyID so
        // `enableParallelQuery = true` races all resolvers simultaneously instead of sequentially.
        val dohList = listOf(
            cfg.doh.url,
            "https://94.140.14.14/dns-query",
            "https://94.140.15.15/dns-query",
            "https://208.67.222.222/dns-query",
            "https://149.112.112.112/dns-query",
            "https://185.222.222.222/dns-query",
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
            "https://8.8.8.8/dns-query",
            "https://9.9.9.9/dns-query",
            "https://dns.adguard-dns.com/dns-query",
            "https://doh.opendns.com/dns-query",
            "https://dns.quad9.net/dns-query",
            "https://dns.google/dns-query",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.sb/dns-query",
        ).distinct()
        dohList.forEach { url ->
            dnsServers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = url,
                    timeoutMs = 2500L,
                )
            )
        }

        val hosts = mapOf<String, Any>(
            "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.cloudflare.com" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
            "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
            "doh.opendns.com" to listOf("208.67.222.222", "208.67.220.220"),
            "dns.sb" to listOf("185.222.222.222", "45.11.45.11"),
        )

        val statsEnabled = !forSpeedtest && !forceDisableTun &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED, true) == true
        val policy = if (statsEnabled) {
            V2rayConfig.PolicyBean(
                levels = mapOf(
                    "8" to V2rayConfig.PolicyBean.LevelBean(
                        handshake = 8,
                        connIdle = 300,
                        uplinkOnly = 1,
                        downlinkOnly = 1,
                    )
                ),
                system = mapOf(
                    "statsOutboundUplink" to true,
                    "statsOutboundDownlink" to true,
                ),
            )
        } else null

        val v2rayConfig = V2rayConfig(
            remarks = context.getString(R.string.fragment_vpn_title),
            stats = if (statsEnabled) emptyMap<String, Any>() else null,
            log = V2rayConfig.LogBean(loglevel = logLevel),
            policy = policy,
            inbounds = inbounds,
            outbounds = outbounds,
            dns = V2rayConfig.DnsBean(
                servers = dnsServers,
                hosts = hosts,
                queryStrategy = if (ipv6Enabled) "UseIP" else "UseIPv4",
                enableParallelQuery = true,
                tag = AppConfig.TAG_DNS,
            ),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "IPIfNonMatch",
                rules = rules,
            ),
        )
        return JsonUtil.toJsonPretty(v2rayConfig) ?: JsonUtil.toJson(v2rayConfig)
    }
}
