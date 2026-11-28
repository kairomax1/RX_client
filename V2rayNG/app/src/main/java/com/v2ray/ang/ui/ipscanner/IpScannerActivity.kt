package com.v2ray.ang.ui.ipscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.IpScanInput
import com.v2ray.ang.handler.IpScanner
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.RxGlassButton
import com.v2ray.ang.ui.compose.RxGlassPanel

class IpScannerActivity : BaseComponentActivity() {

    private val viewModel: IpScannerViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

        IpScannerScreen(
            state = state,
            isLoading = isLoading,
            onBack = { finish() },
            onRefreshNetworkInfo = viewModel::refreshNetworkInfo,
            onTargetSourceChanged = viewModel::updateTargetSource,
            onAddressesChanged = viewModel::updateAddresses,
            onBuiltInTargetToggle = viewModel::toggleBuiltInTarget,
            onRemoteUrlChanged = viewModel::updateRemoteUrl,
            onLoadRemoteList = viewModel::loadRemoteTargetList,
            onCountChanged = viewModel::updateCount,
            onModeChanged = viewModel::updateMode,
            onPresetPortToggle = viewModel::togglePresetPort,
            onCustomPortsChanged = viewModel::updateCustomPorts,
            onScan = viewModel::scan,
            onCopy = ::copyIp,
        )
    }

    private fun copyIp(address: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("IP", address))
        Toast.makeText(this, R.string.ipscan_copied, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpScannerScreen(
    state: IpScannerViewModel.UiState,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefreshNetworkInfo: () -> Unit,
    onTargetSourceChanged: (IpScanner.TargetSource) -> Unit,
    onAddressesChanged: (String) -> Unit,
    onBuiltInTargetToggle: (String) -> Unit,
    onRemoteUrlChanged: (String) -> Unit,
    onLoadRemoteList: () -> Unit,
    onCountChanged: (String) -> Unit,
    onModeChanged: (IpScanner.Mode) -> Unit,
    onPresetPortToggle: (Int) -> Unit,
    onCustomPortsChanged: (String) -> Unit,
    onScan: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val errorRes = when (state.error) {
        IpScanInput.ScanError.InvalidTargets -> R.string.ipscan_error_targets
        IpScanInput.ScanError.InvalidCount -> R.string.ipscan_error_count
        IpScanInput.ScanError.CountOutOfRange -> R.string.ipscan_error_count_range
        IpScanInput.ScanError.NotEnoughAddresses -> R.string.ipscan_error_not_enough
        IpScanInput.ScanError.InvalidPort -> R.string.ipscan_error_port
        IpScanInput.ScanError.TooManyPorts -> R.string.ipscan_error_too_many_ports
        IpScanInput.ScanError.NoPorts -> R.string.ipscan_error_no_ports
        IpScanInput.ScanError.TooManyProbes -> R.string.ipscan_error_too_many_probes
        IpScanInput.ScanError.ScanFailed -> R.string.ipscan_error_scan_failed
        IpScanInput.ScanError.NoBuiltInTargets -> R.string.ipscan_error_no_builtin
        IpScanInput.ScanError.RemoteListNotLoaded -> R.string.ipscan_error_remote_not_loaded
        null -> null
    }
    val remoteListErrorRes = when (state.remoteListError) {
        IpScanner.RemoteListError.INVALID_URL -> R.string.ipscan_remote_error_url
        IpScanner.RemoteListError.DOWNLOAD_FAILED -> R.string.ipscan_remote_error_download
        IpScanner.RemoteListError.TOO_LARGE -> R.string.ipscan_remote_error_too_large
        IpScanner.RemoteListError.INVALID_CONTENT -> R.string.ipscan_remote_error_content
        null -> null
    }
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
            item(key = "network-info") {
                NetworkInfoCard(
                    publicIp = state.publicIp,
                    operator = state.operator,
                    loading = state.loadingNetworkInfo,
                    onRefresh = onRefreshNetworkInfo,
                )
            }
            item(key = "count") {
                OutlinedTextField(
                    value = state.countText,
                    onValueChange = onCountChanged,
                    enabled = !state.scanning,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_count)) },
                    supportingText = {
                        Text(androidx.compose.ui.res.stringResource(R.string.ipscan_count_hint))
                    },
                    isError = state.error == IpScanInput.ScanError.InvalidCount ||
                        state.error == IpScanInput.ScanError.CountOutOfRange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "target-source-header") {
                PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_target_source))
            }
            item(key = "target-source-options") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TargetSourceChip(IpScanner.TargetSource.MANUAL, state.targetSource, !state.scanning, onTargetSourceChanged)
                    TargetSourceChip(IpScanner.TargetSource.BUILT_IN, state.targetSource, !state.scanning, onTargetSourceChanged)
                    TargetSourceChip(IpScanner.TargetSource.REMOTE, state.targetSource, !state.scanning, onTargetSourceChanged)
                }
            }
            when (state.targetSource) {
                IpScanner.TargetSource.MANUAL -> item(key = "manual-targets") {
                    OutlinedTextField(
                        value = state.addressText,
                        onValueChange = onAddressesChanged,
                        enabled = !state.scanning,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_targets)) },
                        supportingText = {
                            Text(androidx.compose.ui.res.stringResource(R.string.ipscan_targets_hint))
                        },
                        isError = state.error == IpScanInput.ScanError.InvalidTargets ||
                            state.error == IpScanInput.ScanError.NotEnoughAddresses,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IpScanner.TargetSource.BUILT_IN -> {
                    item(key = "builtin-header") {
                        PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_builtin_targets))
                    }
                    item(key = "builtin-hint") {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.ipscan_builtin_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = "builtin-options") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IpScanner.BUILT_IN_TARGETS.forEach { targetSet ->
                                FilterChip(
                                    selected = targetSet.id in state.selectedBuiltInTargetIds,
                                    onClick = { onBuiltInTargetToggle(targetSet.id) },
                                    enabled = !state.scanning,
                                    label = {
                                        Text(androidx.compose.ui.res.stringResource(builtInLabel(targetSet.id)))
                                    },
                                )
                            }
                        }
                    }
                }
                IpScanner.TargetSource.REMOTE -> {
                    item(key = "remote-url") {
                        OutlinedTextField(
                            value = state.remoteUrlText,
                            onValueChange = onRemoteUrlChanged,
                            enabled = !state.scanning && !state.loadingRemoteList,
                            label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_remote_url)) },
                            supportingText = {
                                Text(androidx.compose.ui.res.stringResource(R.string.ipscan_remote_hint))
                            },
                            isError = state.remoteListError == IpScanner.RemoteListError.INVALID_URL,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "remote-load") {
                        RxGlassButton(
                            onClick = onLoadRemoteList,
                            enabled = !state.scanning && !state.loadingRemoteList,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    if (state.loadingRemoteList) R.string.ipscan_remote_loading
                                    else R.string.ipscan_remote_load,
                                ),
                            )
                        }
                    }
                    if (remoteListErrorRes != null) {
                        item(key = "remote-error") {
                            Text(
                                text = androidx.compose.ui.res.stringResource(remoteListErrorRes),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (state.remoteTargetsText.isNotBlank()) {
                        item(key = "remote-loaded") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.ipscan_remote_loaded),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = state.remoteTargetsText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 5,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "mode-header") {
                PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_mode))
            }
            item(key = "mode-options") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModeChip(IpScanner.Mode.TCP, state.mode, !state.scanning, onModeChanged)
                    ModeChip(IpScanner.Mode.UDP, state.mode, !state.scanning, onModeChanged)
                    ModeChip(IpScanner.Mode.NONE, state.mode, !state.scanning, onModeChanged)
                }
            }
            if (state.mode != IpScanner.Mode.NONE) {
                item(key = "preset-ports-header") {
                    PreferenceGroupHeader(title = androidx.compose.ui.res.stringResource(R.string.ipscan_ports))
                }
                item(key = "preset-ports") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IpScanner.PRESET_PORTS.forEach { port ->
                            FilterChip(
                                selected = port in state.selectedPorts,
                                onClick = { onPresetPortToggle(port) },
                                enabled = !state.scanning,
                                label = {
                                    Text(androidx.compose.ui.res.stringResource(R.string.ipscan_port_chip, port))
                                },
                            )
                        }
                    }
                }
                item(key = "custom-ports") {
                    OutlinedTextField(
                        value = state.customPortsText,
                        onValueChange = onCustomPortsChanged,
                        enabled = !state.scanning,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.ipscan_custom_ports)) },
                        supportingText = {
                            Text(androidx.compose.ui.res.stringResource(R.string.ipscan_ports_hint))
                        },
                        isError = state.error == IpScanInput.ScanError.InvalidPort ||
                            state.error == IpScanInput.ScanError.TooManyPorts ||
                            state.error == IpScanInput.ScanError.NoPorts,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            errorRes?.let { resource ->
                item(key = "error") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(resource),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item(key = "scan-action") {
                RxGlassButton(
                    onClick = onScan,
                    enabled = !state.loadingRemoteList && (!isLoading || state.scanning),
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
                item(key = "progress") {
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
            item(key = "network-note") {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.ipscan_network_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "results-header") {
                PreferenceGroupHeader(
                    title = androidx.compose.ui.res.stringResource(
                        R.string.ipscan_results_count,
                        state.results.size,
                    ),
                )
            }
            if (state.results.isEmpty() && state.hasScanned && !state.scanning) {
                item(key = "empty-results") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.ipscan_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.results.isEmpty() && !state.scanning) {
                item(key = "start-hint") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.ipscan_start_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.results, key = { it.address }) { result ->
                AddressResultCard(result = result, onCopy = onCopy)
            }
            item(key = "navigation-spacer") { NavigationBarsSpacer() }
        }
    }
}

@Composable
private fun NetworkInfoCard(
    publicIp: String?,
    operator: String,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    RxGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.ipscan_network_info),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.ipscan_public_ip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.75f),
                )
                Text(
                    text = when {
                        loading -> androidx.compose.ui.res.stringResource(R.string.ipscan_public_ip_loading)
                        publicIp != null -> publicIp
                        else -> androidx.compose.ui.res.stringResource(R.string.ipscan_public_ip_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.25f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.ipscan_operator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.75f),
                )
                Text(
                    text = if (operator == com.v2ray.ang.handler.NetworkOperator.UNKNOWN) {
                        androidx.compose.ui.res.stringResource(R.string.ipscan_operator_unknown)
                    } else {
                        operator
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.25f),
                )
            }
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text(androidx.compose.ui.res.stringResource(R.string.ipscan_refresh))
            }
        }
    }
}

@Composable
private fun TargetSourceChip(
    source: IpScanner.TargetSource,
    selected: IpScanner.TargetSource,
    enabled: Boolean,
    onSelect: (IpScanner.TargetSource) -> Unit,
) {
    val label = when (source) {
        IpScanner.TargetSource.MANUAL -> R.string.ipscan_source_manual
        IpScanner.TargetSource.BUILT_IN -> R.string.ipscan_source_builtin
        IpScanner.TargetSource.REMOTE -> R.string.ipscan_source_remote
    }
    FilterChip(
        selected = source == selected,
        onClick = { onSelect(source) },
        enabled = enabled,
        label = { Text(androidx.compose.ui.res.stringResource(label)) },
    )
}

private fun builtInLabel(id: String): Int = when (id) {
    "google-dns" -> R.string.ipscan_builtin_google
    "quad9-dns" -> R.string.ipscan_builtin_quad9
    "opendns" -> R.string.ipscan_builtin_opendns
    "adguard-dns" -> R.string.ipscan_builtin_adguard
    else -> R.string.ipscan_builtin_targets
}

@Composable
private fun ModeChip(
    mode: IpScanner.Mode,
    selected: IpScanner.Mode,
    enabled: Boolean,
    onSelect: (IpScanner.Mode) -> Unit,
) {
    val label = when (mode) {
        IpScanner.Mode.TCP -> R.string.ipscan_tcp
        IpScanner.Mode.UDP -> R.string.ipscan_udp
        IpScanner.Mode.NONE -> R.string.ipscan_none
    }
    FilterChip(
        selected = mode == selected,
        onClick = { onSelect(mode) },
        enabled = enabled,
        label = { Text(androidx.compose.ui.res.stringResource(label)) },
    )
}

@Composable
private fun AddressResultCard(
    result: IpScanner.AddressResult,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = result.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCopy(result.address) }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.ipscan_copy))
                }
            }
            result.probes.forEach { probe -> ProbeLine(probe) }
        }
    }
}

@Composable
private fun ProbeLine(probe: IpScanner.ProbeResult) {
    val label = when (probe.status) {
        IpScanner.Status.OPEN -> R.string.ipscan_status_open
        IpScanner.Status.RESPONSE -> R.string.ipscan_status_udp_response
        IpScanner.Status.REACHABLE -> R.string.ipscan_status_reachable
        IpScanner.Status.CLOSED -> R.string.ipscan_status_closed
        IpScanner.Status.NO_RESPONSE -> when (probe.mode) {
            IpScanner.Mode.UDP -> R.string.ipscan_status_udp_unknown
            IpScanner.Mode.NONE -> R.string.ipscan_status_no_reply
            IpScanner.Mode.TCP -> R.string.ipscan_status_no_reply
        }
        IpScanner.Status.ERROR -> R.string.ipscan_status_error
    }
    val modeLabel = when (probe.mode) {
        IpScanner.Mode.TCP -> R.string.ipscan_tcp
        IpScanner.Mode.UDP -> R.string.ipscan_udp
        IpScanner.Mode.NONE -> R.string.ipscan_none
    }
    val modeText = androidx.compose.ui.res.stringResource(modeLabel)
    val portText = probe.port?.let { androidx.compose.ui.res.stringResource(R.string.ipscan_port_number, it) }
    val statusText = androidx.compose.ui.res.stringResource(label)
    val latencyText = probe.latencyMs?.let { androidx.compose.ui.res.stringResource(R.string.ipscan_latency, it) }
    val details = listOfNotNull(modeText, portText, statusText, latencyText).joinToString(" · ")
    Text(
        text = details,
        style = MaterialTheme.typography.bodySmall,
        color = if (probe.status in setOf(
                IpScanner.Status.OPEN,
                IpScanner.Status.RESPONSE,
                IpScanner.Status.REACHABLE,
                IpScanner.Status.CLOSED,
            )
        ) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
