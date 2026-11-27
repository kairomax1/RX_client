package com.v2ray.ang.ui.ipscanner

import android.app.Activity
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.IpScanner
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.RxGlassButton
import com.v2ray.ang.ui.compose.RxGlassPanel

class IpScannerActivity : BaseComponentActivity() {

    private val viewModel: IpScannerViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshSource()
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

        LaunchedEffect(state.connectReadyGuid) {
            val guid = state.connectReadyGuid ?: return@LaunchedEffect
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_CONNECT_GUID, guid),
            )
            finish()
        }

        IpScannerScreen(
            state = state,
            isLoading = isLoading,
            onBack = { finish() },
            onSelectTarget = viewModel::selectTarget,
            onRangeChanged = viewModel::updateCustomRange,
            onPortModeChanged = viewModel::setPortEnabled,
            onPortChanged = viewModel::updatePort,
            onSniChanged = viewModel::updateSni,
            onScan = viewModel::scan,
            onSelectResult = viewModel::selectResult,
            onCheckSni = viewModel::checkSni,
            onMeasureSpeed = viewModel::measureSpeed,
            onUseIp = viewModel::useSelectedIp,
            onConnect = viewModel::connectToIp,
        )
    }

    companion object {
        const val EXTRA_CONNECT_GUID = "com.v2ray.ang.ipscanner.CONNECT_GUID"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpScannerScreen(
    state: IpScannerViewModel.UiState,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSelectTarget: (Boolean) -> Unit,
    onRangeChanged: (String) -> Unit,
    onPortModeChanged: (Boolean) -> Unit,
    onPortChanged: (String) -> Unit,
    onSniChanged: (String) -> Unit,
    onScan: () -> Unit,
    onSelectResult: (String) -> Unit,
    onCheckSni: () -> Unit,
    onMeasureSpeed: () -> Unit,
    onUseIp: () -> Unit,
    onConnect: (String) -> Unit,
) {
    val errorRes = when (state.error) {
        IpScannerViewModel.InputError.InvalidRange -> R.string.ipscan_error_range
        IpScannerViewModel.InputError.TooManyAddresses -> R.string.ipscan_error_too_many
        IpScannerViewModel.InputError.InvalidPort -> R.string.ipscan_error_port
        IpScannerViewModel.InputError.InvalidSni -> R.string.ipscan_error_sni
        IpScannerViewModel.InputError.NoSelection -> R.string.ipscan_error_no_selection
        IpScannerViewModel.InputError.NoConfig -> R.string.ipscan_no_config
        IpScannerViewModel.InputError.ScanFailed -> R.string.ipscan_error_scan_failed
        IpScannerViewModel.InputError.ApplyFailed -> R.string.ipscan_apply_failed
        IpScannerViewModel.InputError.NoWorkingConfig -> R.string.ipscan_connect_no_method
        null -> null
    }
    val selected = state.selectedResult

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = androidx.compose.ui.res.stringResource(R.string.ipscan_title),
                onBackClick = onBack,
                isLoading = isLoading,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "target-label") {
                PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_target_label))
            }
            item(key = "target-choices") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.cloudflarePool,
                        onClick = { onSelectTarget(true) },
                        enabled = !state.scanning && !state.applying && !state.connecting,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_cloudflare)) },
                    )
                    FilterChip(
                        selected = !state.cloudflarePool,
                        onClick = { onSelectTarget(false) },
                        enabled = !state.scanning && !state.applying && !state.connecting,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_custom)) },
                    )
                }
            }
            if (!state.cloudflarePool) {
                item(key = "custom-range") {
                    OutlinedTextField(
                        value = state.customRange,
                        onValueChange = onRangeChanged,
                        enabled = !state.scanning && !state.applying && !state.connecting,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_range)) },
                        supportingText = {
                            Text(androidx.compose.ui.res.stringResource(R.string.ipscan_range_hint))
                        },
                        isError = state.error == IpScannerViewModel.InputError.InvalidRange ||
                            state.error == IpScannerViewModel.InputError.TooManyAddresses,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "port-label") {
                PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_port_mode))
            }
            item(key = "port-choices") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.portEnabled,
                        onClick = { onPortModeChanged(true) },
                        enabled = !state.scanning && !state.applying && !state.connecting,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_test_port)) },
                    )
                    FilterChip(
                        selected = !state.portEnabled,
                        onClick = { onPortModeChanged(false) },
                        enabled = !state.scanning && !state.applying && !state.connecting,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_no_port)) },
                    )
                }
            }
            if (state.portEnabled) {
                item(key = "port-input") {
                    OutlinedTextField(
                        value = state.portText,
                        onValueChange = onPortChanged,
                        enabled = !state.scanning && !state.applying && !state.connecting,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_port)) },
                        isError = state.error == IpScannerViewModel.InputError.InvalidPort,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "sni-input") {
                OutlinedTextField(
                    value = state.sniText,
                    onValueChange = onSniChanged,
                    enabled = state.portEnabled && !state.scanning && !state.applying && !state.connecting,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_sni)) },
                    isError = state.error == IpScannerViewModel.InputError.InvalidSni,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            errorRes?.let { resId ->
                item(key = "input-error") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(resId),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item(key = "scan-action") {
                RxGlassButton(
                    onClick = onScan,
                    enabled = (!isLoading || state.scanning) && !state.applying && !state.connecting,
                    accent = !state.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            if (state.scanning) R.string.ipscan_stop else R.string.ipscan_scan,
                        ),
                    )
                }
            }
            if (state.scanning) {
                item(key = "scan-progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.progressTotal <= 0) 0f
                                else state.progressDone.toFloat() / state.progressTotal
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                R.string.ipscan_progress,
                                state.progressDone,
                                state.progressTotal,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "measurement-note") {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.ipscan_measurement_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "results-header") {
                PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_results))
            }
            item(key = "connect-note") {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.ipscan_connect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.connecting && state.connectionProgressTotal > 0) {
                item(key = "connect-progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = {
                                state.connectionProgressDone.toFloat() /
                                    state.connectionProgressTotal.coerceAtLeast(1)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                R.string.ipscan_connect_progress,
                                state.connectionProgressDone,
                                state.connectionProgressTotal,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.results.isEmpty() && state.hasScanned && !state.scanning) {
                item(key = "no-results") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.ipscan_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.results.isEmpty() && !state.scanning) {
                item(key = "empty-hint") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.ipscan_start_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.results, key = { it.address }) { result ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProbeResultCard(
                        result = result,
                        selected = result.address == state.selectedAddress,
                        onSelect = { onSelectResult(result.address) },
                        modifier = Modifier.weight(1f),
                    )
                    RxGlassButton(
                        onClick = { onConnect(result.address) },
                        enabled = state.canConnectToIp && !isLoading,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(
                                if (state.connecting) R.string.rx_connecting else R.string.rx_connect,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (selected != null) {
                item(key = "selected-actions") {
                    SelectedIpActions(
                        state = state,
                        result = selected,
                        onCheckSni = onCheckSni,
                        onMeasureSpeed = onMeasureSpeed,
                        onUseIp = onUseIp,
                    )
                }
            }
            item(key = "navigation-spacer") { NavigationBarsSpacer() }
        }
    }
}

@Composable
private fun ProbeResultCard(
    result: IpScanner.ProbeResult,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, outline),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(result.address, style = MaterialTheme.typography.titleMedium)
            val primaryMetric = when {
                result.port == null -> result.reachabilityMs?.let {
                    androidx.compose.ui.res.stringResource(R.string.ipscan_reachable_ms, it)
                } ?: androidx.compose.ui.res.stringResource(R.string.ipscan_not_reachable)
                result.tcpConnectMs != null -> androidx.compose.ui.res.stringResource(
                    R.string.ipscan_tcp_ms,
                    result.tcpConnectMs,
                )
                else -> androidx.compose.ui.res.stringResource(R.string.ipscan_not_reachable)
            }
            val tlsMetric = when (result.tlsSucceeded) {
                true -> result.tlsHandshakeMs?.let {
                    androidx.compose.ui.res.stringResource(R.string.ipscan_tls_ms, it)
                }
                false -> androidx.compose.ui.res.stringResource(R.string.ipscan_sni_failed, result.address)
                null -> null
            }
            Text(
                text = listOfNotNull(
                    primaryMetric,
                    tlsMetric,
                    result.downloadMbps?.let {
                        androidx.compose.ui.res.stringResource(R.string.ipscan_download_mbps, formatMbps(it))
                    },
                    result.uploadMbps?.let {
                        androidx.compose.ui.res.stringResource(R.string.ipscan_upload_mbps, formatMbps(it))
                    },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectedIpActions(
    state: IpScannerViewModel.UiState,
    result: IpScanner.ProbeResult,
    onCheckSni: () -> Unit,
    onMeasureSpeed: () -> Unit,
    onUseIp: () -> Unit,
) {
    PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_selected))
    RxGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(result.address, style = MaterialTheme.typography.titleMedium)
            state.sniOutcome?.takeIf { it.address == result.address }?.let { outcome ->
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        if (outcome.succeeded) R.string.ipscan_sni_ok else R.string.ipscan_sni_failed,
                        result.address,
                        outcome.handshakeMs ?: 0L,
                    ),
                    color = if (outcome.succeeded) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val download = result.downloadMbps?.let {
                androidx.compose.ui.res.stringResource(R.string.ipscan_download_mbps, formatMbps(it))
            } ?: androidx.compose.ui.res.stringResource(R.string.ipscan_speed_unavailable)
            val upload = result.uploadMbps?.let {
                androidx.compose.ui.res.stringResource(R.string.ipscan_upload_mbps, formatMbps(it))
            } ?: androidx.compose.ui.res.stringResource(R.string.ipscan_speed_unavailable)
            Text(
                text = "$download  ·  $upload",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RxGlassButton(
                    onClick = onCheckSni,
                    enabled = state.canCheckSni,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.ipscan_check_sni))
                }
                RxGlassButton(
                    onClick = onMeasureSpeed,
                    enabled = state.canMeasureSpeed,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            if (state.checkingSpeed) R.string.ipscan_measuring_speed
                            else R.string.ipscan_measure_speed,
                        ),
                    )
                }
            }
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.ipscan_speed_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RxGlassButton(
                onClick = onUseIp,
                enabled = state.canApplyToConfig && !state.applying && !state.connecting && !state.scanning &&
                    !state.checkingSni && !state.checkingSpeed,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        if (state.applying) R.string.ipscan_applying else R.string.ipscan_apply_ip,
                    ),
                )
            }
            Text(
                text = if (state.canApplyToConfig) {
                    androidx.compose.ui.res.stringResource(
                        R.string.ipscan_apply_hint,
                        state.sourceConfigName.orEmpty(),
                    )
                } else {
                    androidx.compose.ui.res.stringResource(R.string.ipscan_no_config)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.appliedConfigName != null) {
                Text(
                    text = state.appliedConfigPingMs?.let {
                        androidx.compose.ui.res.stringResource(R.string.ipscan_applied_ping, it)
                    } ?: if (state.appliedConfigPingFinished) {
                        androidx.compose.ui.res.stringResource(R.string.ipscan_applied_ping_unavailable)
                    } else {
                        androidx.compose.ui.res.stringResource(R.string.ipscan_applied, state.appliedConfigName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun formatMbps(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
