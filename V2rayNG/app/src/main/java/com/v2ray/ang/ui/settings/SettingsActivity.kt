package com.v2ray.ang.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.painterResource
import com.v2ray.ang.enums.Language
import com.v2ray.ang.ui.compose.SettingsSection
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.ui.sharing.SharingActivity
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.DailyMaintenance
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.Sharing
import com.v2ray.ang.handler.SharingGateway
import com.v2ray.ang.handler.Tuning
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.launch

class SettingsActivity : BaseComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshSystemVpnSettingsAvailability()
            }
        }
    }

    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            reportSystemVpnSettingsFailure(error)
        } catch (error: SecurityException) {
            reportSystemVpnSettingsFailure(error)
        }
    }

    private fun reportSystemVpnSettingsFailure(error: RuntimeException) {
        LogUtil.e(AppConfig.TAG, "Cannot open system VPN settings", error)
        toastError(R.string.toast_system_vpn_settings_unavailable)
    }

    @Composable
    override fun ScreenContent() {
        SettingsScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onSystemVpnSettingsClicked = ::openSystemVpnSettings
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onSystemVpnSettingsClicked: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val systemVpnSettingsAvailable by viewModel.systemVpnSettingsAvailable.collectAsStateWithLifecycle()
    val bestTuningState by viewModel.bestTuningState.collectAsStateWithLifecycle()
    var uiSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    // Kept in storage so the switch stays where the user left it, also after a restart.
    var vpnSettingsExpanded by rememberMmkvBool(AppConfig.PREF_SHOW_DNS_SETTINGS, false)
    var coreSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var observatorySettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var modeSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var dailyMaintenanceExpanded by rememberSaveable { mutableStateOf(true) }

    val context = LocalContext.current
    var dailyMaintenance by rememberMmkvBool(AppConfig.PREF_DAILY_MAINTENANCE, true)
    var autoFailover by rememberMmkvBool(AppConfig.PREF_AUTO_FAILOVER, true)
    var localDns by rememberMmkvBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
    var fakeDns by rememberMmkvBool(AppConfig.PREF_FAKE_DNS_ENABLED, false)
    var appendHttpProxy by rememberMmkvBool(AppConfig.PREF_APPEND_HTTP_PROXY, false)
    var vpnDns by rememberMmkvString(AppConfig.PREF_VPN_DNS, "")
    var vpnBypassLan by rememberMmkvString(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN)
    var vpnInterfaceAddress by rememberMmkvString(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")
    var vpnMtu by rememberMmkvString(AppConfig.PREF_VPN_MTU, "")


    var observatoryLeastPingInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
    var observatoryLeastLoadInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
    var observatoryLeastLoadMethod by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
    var observatoryLeastLoadSampling by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING, AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING)
    var observatoryLeastLoadTimeout by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)

    var mode by rememberMmkvString(AppConfig.PREF_MODE, VPN)
    var enableRootMode by rememberMmkvBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)
    var lanSharing by rememberMmkvBool(AppConfig.PREF_ROOT_LAN_SHARING, false)

    var hevTunLogLevel by rememberMmkvString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, AppConfig.DEFAULT_HEV_TUNNEL_LOGLEVEL)
    var hevTunRwTimeout by rememberMmkvString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, "")
    var useHevTun by rememberMmkvBool(AppConfig.PREF_USE_HEV_TUNNEL, true)

    var enableLocalProxy by rememberMmkvBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
    var socksPort by rememberMmkvString(AppConfig.PREF_SOCKS_PORT, "")
    var dynamicSocksPort by rememberMmkvBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
    var socksUsername by rememberMmkvString(AppConfig.PREF_SOCKS_USERNAME, "")
    var socksPassword by rememberMmkvString(AppConfig.PREF_SOCKS_PASSWORD, "")
    var socksEnableUdp by rememberMmkvBool(AppConfig.PREF_SOCKS_ENABLE_UDP, AppConfig.DEFAULT_SOCKS_ENABLE_UDP)
    var proxySharing by rememberMmkvBool(AppConfig.PREF_PROXY_SHARING, false)

    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, true)
    var confirmRemove by rememberMmkvBool(AppConfig.PREF_CONFIRM_REMOVE, true)
    var language by remember {
        mutableStateOf(
            MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, Language.PERSIAN.code) ?: Language.PERSIAN.code
        )
    }
    var uiModeNight by rememberMmkvString(AppConfig.PREF_UI_MODE_NIGHT, ThemeManager.DEFAULT_UI_MODE)
    var dynamicColor by rememberMmkvBool(AppConfig.PREF_DYNAMIC_COLOR, false)

    var ipv6Enabled by rememberMmkvBool(AppConfig.PREF_IPV6_ENABLED, false)
    var preferIpv6 by rememberMmkvBool(AppConfig.PREF_PREFER_IPV6, false)
    var sniffingEnabled by rememberMmkvBool(AppConfig.PREF_SNIFFING_ENABLED, true)
    var routeOnlyEnabled by rememberMmkvBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
    var remoteDns by rememberMmkvString(AppConfig.PREF_REMOTE_DNS, "")
    var domesticDns by rememberMmkvString(AppConfig.PREF_DOMESTIC_DNS, "")
    var dnsHosts by rememberMmkvString(AppConfig.PREF_DNS_HOSTS, "")
    var coreLogLevel by rememberMmkvString(AppConfig.PREF_LOGLEVEL, "warning")
    var outboundResolveMethod by rememberMmkvString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD)

    var isBooted by rememberMmkvBool(AppConfig.PREF_IS_BOOTED, false)
    var delayTestUrl by rememberMmkvString(AppConfig.PREF_DELAY_TEST_URL, "")
    var realPingConcurrency by rememberMmkvString(AppConfig.PREF_REAL_PING_CONCURRENCY, "16")
    var ipApiUrl by rememberMmkvString(AppConfig.PREF_IP_API_URL, "")

    var fragmentAllowed by rememberMmkvBool(AppConfig.PREF_RX_FRAGMENT_ENABLED, true)
    var muxAllowed by rememberMmkvBool(AppConfig.PREF_RX_MUX_ENABLED, true)
    var fragmentPackets by rememberMmkvString(AppConfig.PREF_FRAGMENT_PACKETS, "tlshello")
    var fragmentLength by rememberMmkvString(AppConfig.PREF_FRAGMENT_LENGTH, "50-100")
    var fragmentInterval by rememberMmkvString(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20")
    var fragmentMaxSplit by rememberMmkvString(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10")
    var muxConcurrency by rememberMmkvString(AppConfig.PREF_MUX_CONCURRENCY, "8")
    var muxXudpConcurrency by rememberMmkvString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY)
    var muxXudpQuic by rememberMmkvString(AppConfig.PREF_MUX_XUDP_QUIC, "reject")

    val reloadFragmentMuxFields: () -> Unit = {
        fragmentPackets = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_PACKETS, "tlshello") ?: "tlshello"
        fragmentLength = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_LENGTH, "50-100") ?: "50-100"
        fragmentInterval = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20") ?: "10-20"
        fragmentMaxSplit = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10") ?: "10"
        muxConcurrency = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8") ?: "8"
        muxXudpConcurrency = MmkvManager.decodeSettingsString(
            AppConfig.PREF_MUX_XUDP_CONCURRENCY,
            AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY,
        ) ?: AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY
        muxXudpQuic = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_QUIC, "reject") ?: "reject"
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.applyBestTuning(context, fragmentAllowed, muxAllowed, reloadFragmentMuxFields)
    }

    val startBestTuning: () -> Unit = {
        val needsVpnPerm = muxAllowed && mode == VPN && runCatching { VpnService.prepare(context) }.getOrNull() != null
        val permIntent = if (needsVpnPerm) runCatching { VpnService.prepare(context) }.getOrNull() else null
        if (permIntent != null) {
            vpnPermissionLauncher.launch(permIntent)
        } else {
            viewModel.applyBestTuning(context, fragmentAllowed, muxAllowed, reloadFragmentMuxFields)
        }
    }

    val fragmentPacketsOptions = listOf("tlshello", "1-3", "1-5", "1-2")
    val muxQuicEntries = listOf("reject", "allow", "skip")
    val muxQuicValues = listOf("reject", "allow", "skip")

    val isVpn = mode == VPN
    val hevTunEnabled = isVpn && useHevTun
    val localProxyForced = hevTunEnabled
    val effectiveLocalProxy = enableLocalProxy || localProxyForced

    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    LaunchedEffect(dynamicColorSupported) {
        if (!dynamicColorSupported && dynamicColor) {
            dynamicColor = false
            ThemeManager.setDynamicColorEnabled(false)
        }
    }

    // Persian, English, or whatever the phone uses; the other translations stay reachable
    // through the phone's own language.
    val languageEntries = listOf(
        stringResource(R.string.onboarding_language_persian),
        stringResource(R.string.onboarding_language_english),
        stringResource(R.string.onboarding_language_system),
    )
    val languageValues = listOf(Language.PERSIAN.code, Language.ENGLISH.code, Language.AUTO.code)
    val uiModeNightEntries = stringArrayResource(R.array.ui_mode_night).toList()
    val uiModeNightValues = stringArrayResource(R.array.ui_mode_night_value).toList()
    val bypassLanEntries = stringArrayResource(R.array.vpn_bypass_lan).toList()
    val bypassLanValues = stringArrayResource(R.array.vpn_bypass_lan_value).toList()
    val interfaceAddrEntries = stringArrayResource(R.array.vpn_interface_address).toList()
    val interfaceAddrValues = stringArrayResource(R.array.vpn_interface_address_value).toList()
    val hevLogEntries = stringArrayResource(R.array.hev_tunnel_loglevel).toList()
    val hevLogValues = stringArrayResource(R.array.hev_tunnel_loglevel).toList()
    val coreLogLevelEntries = stringArrayResource(R.array.core_loglevel).toList()
    val coreLogLevelValues = stringArrayResource(R.array.core_loglevel).toList()
    val outboundResolveEntries = stringArrayResource(R.array.outbound_domain_resolve_method).toList()
    val outboundResolveValues = stringArrayResource(R.array.outbound_domain_resolve_method_value).toList()
    val observatoryLeastLoadMethodEntries = stringArrayResource(R.array.observatory_least_load_method).toList()
    val observatoryLeastLoadMethodValues = stringArrayResource(R.array.observatory_least_load_method).toList()
    val modeEntries = stringArrayResource(R.array.mode_entries).toList()
    val modeValues = stringArrayResource(R.array.mode_value).toList()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_settings),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
        ) {
            // Only the settings an ordinary user reaches for, one card per topic, each row with a
            // sentence saying what it does. The technical ones (ports, tunnel internals,
            // observatory, root, test addresses) are hidden, not reset: their values keep applying.
            Spacer(modifier = Modifier.height(6.dp))
            SettingsSection(
                title = stringResource(R.string.settings_group_general),
                description = stringResource(R.string.settings_group_general_desc),
                icon = painterResource(R.drawable.ic_settings_24dp),
                order = 0
            ) {
                SettingsListItem(
                    title = stringResource(R.string.title_language),
                    hint = stringResource(R.string.settings_language_hint),
                    entries = languageEntries,
                    values = languageValues,
                    selectedValue = language,
                    onSelected = {
                        language = it
                        AppLocaleManager.setApplicationLanguage(it)
                    }
                )
                SettingsListItem(
                    title = stringResource(R.string.settings_theme_title),
                    hint = stringResource(R.string.settings_theme_hint),
                    entries = uiModeNightEntries,
                    values = uiModeNightValues,
                    selectedValue = uiModeNight,
                    onSelected = {
                        uiModeNight = it
                        ThemeManager.setThemeMode(it)
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_is_booted),
                    summary = stringResource(R.string.summary_pref_is_booted),
                    checked = isBooted,
                    onCheckedChange = { isBooted = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.settings_mode_title),
                    hint = stringResource(R.string.settings_mode_hint),
                    entries = modeEntries,
                    values = modeValues,
                    selectedValue = mode,
                    onSelected = { mode = it }
                )
                if (systemVpnSettingsAvailable) {
                    SettingsMenuItem(
                        title = stringResource(R.string.title_system_vpn_settings),
                        subtitle = stringResource(R.string.summary_system_vpn_settings),
                        onClick = onSystemVpnSettingsClicked
                    )
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_group_fragment_mux),
                description = stringResource(R.string.settings_group_fragment_mux_desc),
                icon = painterResource(R.drawable.ic_routing_24dp),
                order = 1
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_fragment_enabled),
                    summary = stringResource(R.string.summary_pref_fragment_enabled),
                    checked = fragmentAllowed,
                    onCheckedChange = { enabled ->
                        fragmentAllowed = enabled
                        MmkvManager.encodeSettings(AppConfig.PREF_RX_FRAGMENT_ENABLED, enabled)
                        Tuning.onUserSettingsChanged()
                    }
                )
                AnimatedVisibility(visible = fragmentAllowed) {
                    Column {
                        SettingsListItem(
                            title = stringResource(R.string.title_pref_fragment_packets),
                            entries = fragmentPacketsOptions,
                            values = fragmentPacketsOptions,
                            selectedValue = fragmentPackets,
                            onSelected = {
                                fragmentPackets = it
                                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, it)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.title_pref_fragment_length),
                            value = fragmentLength,
                            onValueChanged = {
                                val v = it.trim().ifEmpty { "50-100" }
                                fragmentLength = v
                                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, v)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.title_pref_fragment_interval),
                            value = fragmentInterval,
                            onValueChanged = {
                                val v = it.trim().ifEmpty { "10-20" }
                                fragmentInterval = v
                                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, v)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.title_pref_fragment_maxsplit),
                            value = fragmentMaxSplit,
                            keyboardNumber = true,
                            onValueChanged = {
                                val v = it.trim().ifEmpty { "10" }
                                fragmentMaxSplit = v
                                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, v)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                    }
                }

                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_mux_enabled),
                    summary = stringResource(R.string.summary_pref_mux_enabled),
                    checked = muxAllowed,
                    onCheckedChange = { enabled ->
                        muxAllowed = enabled
                        MmkvManager.encodeSettings(AppConfig.PREF_RX_MUX_ENABLED, enabled)
                        Tuning.onUserSettingsChanged()
                    }
                )
                AnimatedVisibility(visible = muxAllowed) {
                    Column {
                        SettingsEditItem(
                            title = stringResource(R.string.title_pref_mux_concurrency),
                            value = muxConcurrency,
                            keyboardNumber = true,
                            onValueChanged = {
                                val v = it.trim().toIntOrNull()?.takeIf { n -> n > 0 }?.toString() ?: "8"
                                muxConcurrency = v
                                MmkvManager.encodeSettings(AppConfig.PREF_MUX_CONCURRENCY, v)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.title_pref_mux_xudp_concurrency),
                            value = muxXudpConcurrency,
                            keyboardNumber = true,
                            onValueChanged = {
                                val v = it.trim().toIntOrNull()?.takeIf { n -> n > 0 }?.toString()
                                    ?: AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY
                                muxXudpConcurrency = v
                                MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_CONCURRENCY, v)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_pref_mux_xudp_quic),
                            entries = muxQuicEntries,
                            values = muxQuicValues,
                            selectedValue = muxXudpQuic,
                            onSelected = {
                                muxXudpQuic = it
                                MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_QUIC, it)
                                Tuning.onUserSettingsChanged()
                            }
                        )
                    }
                }

                AnimatedVisibility(visible = fragmentAllowed || muxAllowed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bestTuningState.statusText.ifEmpty {
                                    stringResource(R.string.settings_best_tuning_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (bestTuningState.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Button(
                            onClick = startBestTuning,
                            enabled = !bestTuningState.isRunning,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (bestTuningState.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = stringResource(R.string.settings_apply_best_tuning),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_group_connection),
                description = stringResource(R.string.settings_group_connection_desc),
                icon = painterResource(R.drawable.ic_routing_24dp),
                order = 2
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_auto_failover),
                    summary = stringResource(R.string.summary_pref_auto_failover),
                    checked = autoFailover,
                    onCheckedChange = { autoFailover = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_ipv6_enabled),
                    summary = stringResource(R.string.summary_pref_ipv6_enabled),
                    checked = ipv6Enabled,
                    onCheckedChange = { ipv6Enabled = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.settings_bypass_lan_title),
                    hint = stringResource(R.string.settings_bypass_lan_hint),
                    entries = bypassLanEntries,
                    values = bypassLanValues,
                    selectedValue = vpnBypassLan,
                    enabled = isVpn,
                    onSelected = { vpnBypassLan = it }
                )
                // DNS is for people who know they need it; it stays folded until asked for.
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_dns_show_title),
                    summary = stringResource(R.string.settings_dns_show_hint),
                    checked = vpnSettingsExpanded,
                    onCheckedChange = { vpnSettingsExpanded = it }
                )
                AnimatedVisibility(visible = vpnSettingsExpanded) {
                    Column {
                        SettingsEditItem(
                            title = stringResource(R.string.settings_remote_dns_title),
                            hint = stringResource(R.string.settings_remote_dns_hint),
                            value = remoteDns,
                            onValueChanged = { remoteDns = it }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.settings_domestic_dns_title),
                            hint = stringResource(R.string.settings_domestic_dns_hint),
                            value = domesticDns,
                            onValueChanged = { domesticDns = it }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.settings_vpn_dns_title),
                            hint = stringResource(R.string.settings_vpn_dns_hint),
                            value = vpnDns,
                            enabled = isVpn && !localDns,
                            onValueChanged = { vpnDns = it }
                        )
                    }
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_group_list),
                description = stringResource(R.string.settings_group_list_desc),
                icon = painterResource(R.drawable.ic_grid_24dp),
                order = 3
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_speed_enabled),
                    summary = stringResource(R.string.summary_pref_speed_enabled),
                    checked = speedEnabled,
                    onCheckedChange = { speedEnabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_confirm_remove),
                    summary = stringResource(R.string.summary_pref_confirm_remove),
                    checked = confirmRemove,
                    onCheckedChange = { confirmRemove = it }
                )
            }

            SettingsSection(
                title = stringResource(R.string.title_pref_daily_maintenance),
                description = stringResource(R.string.settings_group_maintenance_desc),
                icon = painterResource(R.drawable.ic_refresh_24dp),
                order = 4
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_maintenance_switch_title),
                    summary = stringResource(R.string.summary_pref_daily_maintenance),
                    checked = dailyMaintenance,
                    onCheckedChange = { enabled ->
                        dailyMaintenance = enabled
                        if (enabled) {
                            DailyMaintenance.schedule(context)
                        } else {
                            DailyMaintenance.cancel(context)
                        }
                    }
                )
            }

            SettingsSection(
                title = stringResource(R.string.sharing_title),
                description = stringResource(R.string.sharing_settings_desc),
                icon = painterResource(R.drawable.ic_share_24dp),
                order = 5
            ) {
                SettingsMenuItem(
                    title = stringResource(R.string.sharing_open),
                    subtitle = sharingSummary(),
                    onClick = { context.startActivity(Intent(context, SharingActivity::class.java)) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            NavigationBarsSpacer()
        }
    }
}

/**
 * One line saying what sharing is doing at this moment, so the settings can be
 * read without opening the screen: whether the door is open, and on which
 * address the other device has to be pointed.
 */
@Composable
private fun sharingSummary(): String {
    val on = Sharing.isEnabled() && Sharing.stoppedReason().isEmpty()
    if (!on) return stringResource(R.string.sharing_state_off)
    val address = remember { SharingGateway.network().first.firstOrNull() }
    val port = remember { Sharing.port() }
    return if (address == null) stringResource(R.string.sharing_no_network)
    else stringResource(R.string.sharing_at, address, port.toString())
}
