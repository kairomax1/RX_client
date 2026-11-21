package com.v2ray.ang.ui.fragmentvpn

import android.app.Application
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.FragmentVpn
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.rxGlassBrush
import com.v2ray.ang.ui.compose.rxScreenBrush
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FragmentVpnActivity : BaseComponentActivity() {

    private val viewModel: FragmentVpnViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        FragmentVpnScreen(viewModel = viewModel, onBack = { finish() })
    }
}

class FragmentVpnViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val config: FragmentVpn.Config = FragmentVpn.currentConfig(),
        val isRunning: Boolean = false,
        val isConnecting: Boolean = false,
        val isProbing: Boolean = false,
        val presetPings: Map<FragmentVpn.Preset, Long> = emptyMap(),
        val livePingMs: Long? = FragmentVpn.currentConfig().lastPingMs,
        val ipInfo: String? = null,
        val error: String? = null,
        val dirtyWhileRunning: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        refreshState()
        viewModelScope.launch {
            while (isActive) {
                val active = FragmentVpn.isActiveRunning(getApplication())
                _state.update { cur ->
                    if (cur.isConnecting) {
                        cur
                    } else if (cur.isRunning != active) {
                        cur.copy(
                            isRunning = active,
                            dirtyWhileRunning = if (!active) false else cur.dirtyWhileRunning,
                        )
                    } else cur
                }
                delay(700L)
            }
        }
    }

    fun refreshState() {
        val cfg = FragmentVpn.currentConfig()
        val active = FragmentVpn.isActiveRunning(getApplication())
        _state.update {
            it.copy(
                config = cfg,
                isRunning = active,
                livePingMs = cfg.lastPingMs ?: it.livePingMs,
            )
        }
    }

    fun selectPreset(preset: FragmentVpn.Preset) {
        FragmentVpn.savePreset(preset)
        _state.update {
            it.copy(
                config = FragmentVpn.currentConfig(),
                error = null,
                dirtyWhileRunning = it.isRunning,
            )
        }
    }

    fun updateCustom(packets: String, length: String, delay: String, maxSplit: String) {
        FragmentVpn.saveCustomParams(packets, length, delay, maxSplit)
        _state.update {
            it.copy(
                config = FragmentVpn.currentConfig(),
                dirtyWhileRunning = it.isRunning,
            )
        }
    }

    fun setDropQuic(enabled: Boolean) {
        FragmentVpn.saveDropQuic(enabled)
        _state.update {
            it.copy(
                config = FragmentVpn.currentConfig(),
                dirtyWhileRunning = it.isRunning,
            )
        }
    }

    fun setBypassIran(enabled: Boolean) {
        FragmentVpn.saveBypassIran(enabled)
        _state.update {
            it.copy(
                config = FragmentVpn.currentConfig(),
                dirtyWhileRunning = it.isRunning,
            )
        }
    }

    fun selectDoh(option: FragmentVpn.DohOption) {
        FragmentVpn.saveDoh(option)
        _state.update {
            it.copy(
                config = FragmentVpn.currentConfig(),
                dirtyWhileRunning = it.isRunning,
            )
        }
    }

    fun probePresets() {
        if (_state.value.isProbing || _state.value.isConnecting) return
        viewModelScope.launch {
            _state.update { it.copy(isProbing = true, error = null) }
            val results = withContext(Dispatchers.IO) {
                FragmentVpn.probePresets(getApplication())
            }
            val cfg = FragmentVpn.currentConfig()
            val bestPing = results.values.filter { it > 0L }.minOrNull()
            _state.update {
                it.copy(
                    isProbing = false,
                    config = cfg,
                    presetPings = results,
                    livePingMs = bestPing ?: -1L,
                    error = if (bestPing == null) getApplication<Application>().getString(R.string.fragment_vpn_probe_failed) else null,
                )
            }
        }
    }

    fun toggleConnection() {
        if (_state.value.isRunning || _state.value.isConnecting) {
            stopFragmentVpn()
        } else {
            startFragmentVpn()
        }
    }

    fun reconnectWithCurrentSettings() {
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = true, dirtyWhileRunning = false, error = null) }
            withContext(Dispatchers.IO) {
                LauncherManager.stopService(getApplication())
                delay(450L)
            }
            startFragmentVpn()
        }
    }

    fun startFragmentVpn() {
        if (_state.value.isProbing || _state.value.isConnecting) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isConnecting = true,
                    error = null,
                    dirtyWhileRunning = false,
                )
            }
            val app = getApplication<Application>()
            val cfg = FragmentVpn.currentConfig()

            val probeResults = if (cfg.preset == FragmentVpn.Preset.AUTO) {
                _state.update { it.copy(isProbing = true) }
                withContext(Dispatchers.IO) { FragmentVpn.probePresets(app) }
            } else {
                emptyMap()
            }
            _state.update { it.copy(isProbing = false, config = FragmentVpn.currentConfig()) }

            withContext(Dispatchers.IO) {
                if (ServiceRuntimeState.isRunning(app)) {
                    LauncherManager.stopService(app)
                    delay(450L)
                }
                val guid = FragmentVpn.ensureProfile(app)
                MmkvManager.setSelectServer(guid)
                LauncherManager.startService(app, guid = guid)
            }

            var up = false
            repeat(20) {
                delay(400L)
                if (FragmentVpn.isActiveRunning(app)) {
                    up = true
                    return@repeat
                }
            }
            if (!up) {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        isRunning = false,
                        error = app.getString(R.string.fragment_vpn_start_failed),
                    )
                }
                return@launch
            }

            val (measuredPing, ipText) = withContext(Dispatchers.IO) {
                val ping = measureLiveDelay()
                val exit = probeSocksIp(SettingsManager.getSocksPort())
                ping to exit
            }

            if (measuredPing > 0L) FragmentVpn.saveLastPing(measuredPing)
            AppLog.info(
                AppLog.Section.Connection,
                "Fragment VPN active (direct user IP, preset=${FragmentVpn.currentConfig().resolvedPreset.key}, ${measuredPing}ms)",
            )
            _state.update {
                it.copy(
                    isConnecting = false,
                    isRunning = true,
                    presetPings = if (probeResults.isNotEmpty()) probeResults else it.presetPings,
                    livePingMs = measuredPing.takeIf { p -> p > 0L } ?: it.livePingMs,
                    ipInfo = ipText ?: it.ipInfo,
                )
            }
        }
    }

    private fun measureLiveDelay(): Long {
        val urls = listOf(
            SettingsManager.getDelayTestUrl(),
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
        ).distinct()
        for (url in urls) {
            val ms = CoreServiceManager.measureDelayDirect(url)
            if (ms > 0L) return ms
        }
        return -1L
    }

    private fun probeSocksIp(socksPort: Int): String? {
        if (socksPort <= 0) return null
        val detailed = runCatching {
            HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = "http://ip-api.com/json?fields=query,country,isp",
                    timeout = 5000,
                    socksPort = socksPort,
                )
            )
        }.getOrNull()
        if (!detailed.isNullOrBlank()) {
            val ip = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            val country = Regex("\"country\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            val isp = Regex("\"isp\"\\s*:\\s*\"([^\"]+)\"").find(detailed)?.groupValues?.get(1).orEmpty()
            val formatted = listOf(ip, country, isp).filter { it.isNotBlank() }.joinToString(" · ")
            if (formatted.isNotBlank()) return formatted
        }
        return runCatching {
            HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = "https://api.ipify.org",
                    timeout = 5000,
                    socksPort = socksPort,
                )
            )?.trim()?.takeIf { it.length in 7..45 && it.none { c -> c.isWhitespace() } }
        }.getOrNull()
    }

    fun stopFragmentVpn() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LauncherManager.stopService(getApplication())
            }
            _state.update {
                it.copy(
                    isRunning = false,
                    isConnecting = false,
                    dirtyWhileRunning = false,
                )
            }
        }
    }
}

private val ActiveGreen = Color(0xFF58C99A)
private val WarnAmber = Color(0xFFE0A84A)
private val DangerRed = Color(0xFFE56B6F)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FragmentVpnScreen(viewModel: FragmentVpnViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.startFragmentVpn()
        }
    }

    val onToggleClick: () -> Unit = {
        if (state.isRunning || state.isConnecting) {
            viewModel.stopFragmentVpn()
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(context)
            if (intent == null) viewModel.startFragmentVpn()
            else vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.startFragmentVpn()
        }
    }

    var customPackets by remember(state.config.packets) { mutableStateOf(state.config.packets) }
    var customLength by remember(state.config.length) { mutableStateOf(state.config.length) }
    var customDelay by remember(state.config.delay) { mutableStateOf(state.config.delay) }
    var customMaxSplit by remember(state.config.maxSplit) { mutableStateOf(state.config.maxSplit) }

    Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
        RxFogBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = stringResource(R.string.log_back),
                        tint = colors.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.fragment_vpn_title),
                    style = TextStyle(fontFamily = RxPromptFont, fontSize = 24.sp),
                    color = colors.onSurface,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Explainer & Status Hero Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(rxGlassBrush())
                        .border(1.dp, colors.outline.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when {
                                state.isRunning -> ActiveGreen
                                state.isConnecting || state.isProbing -> WarnAmber
                                else -> colors.onSurface.copy(alpha = 0.35f)
                            }
                            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when {
                                    state.isProbing -> stringResource(R.string.fragment_vpn_status_probing)
                                    state.isConnecting -> stringResource(R.string.fragment_vpn_status_connecting)
                                    state.isRunning -> stringResource(R.string.fragment_vpn_status_connected)
                                    else -> stringResource(R.string.fragment_vpn_status_idle)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                        }
                        state.livePingMs?.takeIf { it > 0L }?.let { ping ->
                            Text(
                                text = "${ping}ms",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreen,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.fragment_vpn_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface.copy(alpha = 0.78f),
                    )

                    Text(
                        text = stringResource(
                            R.string.fragment_vpn_active_params,
                            presetLabel(state.config.resolvedPreset),
                            state.config.packets,
                            state.config.length,
                            state.config.delay,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                    )

                    state.ipInfo?.let { ip ->
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface.copy(alpha = 0.74f),
                        )
                    }

                    state.error?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RxPromptButton(
                            text = if (state.isRunning || state.isConnecting) {
                                stringResource(R.string.fragment_vpn_disconnect)
                            } else {
                                stringResource(R.string.fragment_vpn_connect)
                            },
                            tone = if (state.isRunning || state.isConnecting) RxPromptTone.MistRed else RxPromptTone.DarkGreen,
                            onClick = onToggleClick,
                            modifier = Modifier.weight(1f),
                        )
                        RxPromptButton(
                            text = if (state.isProbing) {
                                stringResource(R.string.fragment_vpn_probing_short)
                            } else {
                                stringResource(R.string.fragment_vpn_probe_best)
                            },
                            tone = RxPromptTone.Ghost,
                            onClick = { if (!state.isProbing) viewModel.probePresets() },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (state.isProbing || state.isConnecting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = if (state.isProbing) {
                                    stringResource(R.string.fragment_vpn_probing_hint)
                                } else {
                                    stringResource(R.string.fragment_vpn_connecting_hint)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurface.copy(alpha = 0.75f),
                            )
                        }
                    }

                    if (state.dirtyWhileRunning) {
                        RxPromptButton(
                            text = stringResource(R.string.fragment_vpn_apply_reconnect),
                            tone = RxPromptTone.Silver,
                            onClick = { viewModel.reconnectWithCurrentSettings() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Preset Selector Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(rxGlassBrush())
                        .border(1.dp, colors.outline.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.fragment_vpn_preset_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FragmentVpn.Preset.entries.forEach { preset ->
                            val selected = state.config.preset == preset
                            val ping = state.presetPings[preset]
                            val suffix = when {
                                ping == null -> ""
                                ping > 0L -> " (${ping}ms)"
                                else -> " (×)"
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (selected) colors.primary.copy(alpha = 0.22f)
                                        else colors.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) colors.primary else colors.outline.copy(alpha = 0.25f),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable { viewModel.selectPreset(preset) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = presetLabel(preset) + suffix,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) colors.primary else colors.onSurface,
                                )
                            }
                        }
                    }

                    if (state.config.preset == FragmentVpn.Preset.CUSTOM) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = customPackets,
                                    onValueChange = {
                                        customPackets = it
                                        viewModel.updateCustom(it, customLength, customDelay, customMaxSplit)
                                    },
                                    label = { Text(stringResource(R.string.fragment_vpn_custom_packets)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = customMaxSplit,
                                    onValueChange = {
                                        customMaxSplit = it
                                        viewModel.updateCustom(customPackets, customLength, customDelay, it)
                                    },
                                    label = { Text(stringResource(R.string.fragment_vpn_custom_maxsplit)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = customLength,
                                    onValueChange = {
                                        customLength = it
                                        viewModel.updateCustom(customPackets, it, customDelay, customMaxSplit)
                                    },
                                    label = { Text(stringResource(R.string.fragment_vpn_custom_length)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = customDelay,
                                    onValueChange = {
                                        customDelay = it
                                        viewModel.updateCustom(customPackets, customLength, it, customMaxSplit)
                                    },
                                    label = { Text(stringResource(R.string.fragment_vpn_custom_delay)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                // DoH & Routing Options Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(rxGlassBrush())
                        .border(1.dp, colors.outline.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.fragment_vpn_doh_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FragmentVpn.DohOption.entries.forEach { option ->
                            val selected = state.config.doh == option
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (selected) colors.primary.copy(alpha = 0.22f)
                                        else colors.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) colors.primary else colors.outline.copy(alpha = 0.25f),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable { viewModel.selectDoh(option) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) colors.primary else colors.onSurface,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.fragment_vpn_drop_quic_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.fragment_vpn_drop_quic_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface.copy(alpha = 0.72f),
                            )
                        }
                        Switch(
                            checked = state.config.dropQuic,
                            onCheckedChange = { viewModel.setDropQuic(it) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.fragment_vpn_bypass_iran_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.fragment_vpn_bypass_iran_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface.copy(alpha = 0.72f),
                            )
                        }
                        Switch(
                            checked = state.config.bypassIran,
                            onCheckedChange = { viewModel.setBypassIran(it) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun presetLabel(preset: FragmentVpn.Preset): String = when (preset) {
    FragmentVpn.Preset.AUTO -> stringResource(R.string.fragment_vpn_preset_auto)
    FragmentVpn.Preset.BALANCED -> stringResource(R.string.fragment_vpn_preset_balanced)
    FragmentVpn.Preset.STRICT_ISP -> stringResource(R.string.fragment_vpn_preset_strict)
    FragmentVpn.Preset.LOW_SPLIT -> stringResource(R.string.fragment_vpn_preset_fine)
    FragmentVpn.Preset.CUSTOM -> stringResource(R.string.fragment_vpn_preset_custom)
}
