package com.v2ray.ang.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NetworkOperator
import com.v2ray.ang.handler.PublicConfigs
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.Tuning
import com.v2ray.ang.handler.TuningRunner
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.base.BaseViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class BestTuningState(
    val isRunning: Boolean = false,
    val statusText: String = "",
    val isError: Boolean = false,
)

class SettingsViewModel(application: Application) : BaseViewModel(application) {

    private val _systemVpnSettingsAvailable = MutableStateFlow(false)
    val systemVpnSettingsAvailable = _systemVpnSettingsAvailable.asStateFlow()

    private val _bestTuningState = MutableStateFlow(BestTuningState())
    val bestTuningState = _bestTuningState.asStateFlow()

    private var bestTuningJob: Job? = null

    suspend fun refreshSystemVpnSettingsAvailability() {
        _systemVpnSettingsAvailable.value = withContext(Dispatchers.IO) {
            // Android exposes the VPN page, not a direct link to the Always-on VPN switch.
            Intent(Settings.ACTION_VPN_SETTINGS).resolveActivity(getApplication<Application>().packageManager) != null
        }
    }

    /**
     * Measures the user's current network (Wi-Fi/Cellular + direct TLS probe +
     * selected config test when available) and applies the optimal Fragment and/or
     * Mux settings before connecting.
     */
    fun applyBestTuning(
        context: Context,
        fragmentEnabled: Boolean,
        muxEnabled: Boolean,
        onApplied: () -> Unit,
    ) {
        bestTuningJob?.cancel()
        MmkvManager.encodeSettings(AppConfig.PREF_RX_FRAGMENT_ENABLED, fragmentEnabled)
        MmkvManager.encodeSettings(AppConfig.PREF_RX_MUX_ENABLED, muxEnabled)
        val mode = Tuning.Mode.of(fragmentEnabled, muxEnabled)
        if (mode == Tuning.Mode.NONE) {
            _bestTuningState.value = BestTuningState()
            return
        }

        if (!PublicConfigs.isNetworkAvailable(context)) {
            _bestTuningState.value = BestTuningState(
                isRunning = false,
                statusText = context.getString(R.string.settings_best_tuning_offline),
                isError = true,
            )
            return
        }

        val searchingRes = when (mode) {
            Tuning.Mode.MUX_ONLY -> R.string.rx_tuning_searching_mux
            Tuning.Mode.FRAGMENT_ONLY -> R.string.rx_tuning_searching_fragment
            else -> R.string.rx_tuning_searching
        }
        _bestTuningState.value = BestTuningState(
            isRunning = true,
            statusText = context.getString(searchingRes),
            isError = false,
        )

        bestTuningJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                measureAndApplyBestTuning(context, mode)
            }
            _bestTuningState.value = result.uiState
            onApplied()
        }
    }

    private data class TuningMeasureResult(val uiState: BestTuningState)

    private suspend fun measureAndApplyBestTuning(
        context: Context,
        mode: Tuning.Mode,
    ): TuningMeasureResult {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
        val networkLabel = if (isCellular) {
            val op = runCatching { NetworkOperator.current(context) }.getOrDefault("")
            if (op.isNotBlank() && op != NetworkOperator.WIFI && op != NetworkOperator.UNKNOWN) {
                "${context.getString(R.string.settings_network_cellular)} ($op)"
            } else {
                context.getString(R.string.settings_network_cellular)
            }
        } else {
            context.getString(R.string.settings_network_wifi)
        }

        val probeMs = probeDirectTlsMillis()
        val baseline = Tuning.networkPreset(isCellular, probeMs, mode)

        val targetGuid = MmkvManager.getSelectServer()?.takeIf { it.isNotBlank() }
            ?: MmkvManager.decodeServerList(PublicConfigs.GROUP_ID).firstOrNull()
            ?: MmkvManager.decodeAllServerList().firstOrNull()
        val profile = targetGuid?.let { MmkvManager.decodeServerConfig(it) }
        val running = ServiceRuntimeState.isRunning(context)

        var chosen: Tuning.Choice = baseline
        var measuredDelayMs: Long? = null

        if (!targetGuid.isNullOrBlank() && profile != null && !running) {
            val isVpn = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) == AppConfig.VPN
            val canStartTunnel = !isVpn || VpnService.prepare(context) == null
            if (mode.mux && Tuning.supportsMux(profile) && canStartTunnel) {
                val outcome = TuningRunner.tune(context, targetGuid, mode) {}
                if (outcome.bypassed) {
                    chosen = if (outcome.choice == Tuning.PLAIN && (mode.mux || mode.fragment)) {
                        // Keep PLAIN if PLAIN genuinely outperformed, or honour user's active choice
                        outcome.choice
                    } else {
                        outcome.choice
                    }
                    measuredDelayMs = MmkvManager.decodeServerAffiliationInfo(targetGuid)
                        ?.testDelayMillis
                        ?.takeIf { it > 0L }
                }
            } else if (mode.fragment && Tuning.supportsFragment(profile)) {
                val delayUrl = SettingsManager.getDelayTestUrl()
                val candidates = Tuning.plan(profile, allowMux = false, allowFragment = true)
                    .filter { it != Tuning.PLAIN } + Tuning.PLAIN
                var bestDelay = Long.MAX_VALUE
                var bestChoice: Tuning.Choice? = null
                for (candidate in candidates.distinctBy { it.key }) {
                    val built = CoreConfigManager.getV2rayConfig4Speedtest(
                        context,
                        targetGuid,
                        tuningOverride = candidate,
                    )
                    if (!built.status) continue
                    val d = CoreNativeManager.measureOutboundDelay(built.content, delayUrl)
                    if (d in 1 until bestDelay) {
                        bestDelay = d
                        bestChoice = candidate
                    }
                }
                if (bestChoice != null) {
                    chosen = if (mode.mux) {
                        bestChoice.copy(mux = true, concurrency = baseline.concurrency)
                    } else {
                        bestChoice
                    }
                    measuredDelayMs = bestDelay
                }
            }
        }

        // When user explicitly asked to apply best settings for Fragment/Mux, make sure
        // the enabled feature parameters are populated even if PLAIN won or no config was set.
        if (chosen == Tuning.PLAIN) {
            chosen = baseline
        }

        Tuning.applyAndRememberPreset(targetGuid, chosen, mode)
        if (running) {
            SettingsChangeManager.makeRestartService()
        }

        val summaryMs = measuredDelayMs ?: probeMs
        val statusText = if (summaryMs != null && summaryMs > 0L) {
            context.getString(R.string.settings_best_tuning_applied_ms, networkLabel, summaryMs.toInt())
        } else {
            context.getString(R.string.settings_best_tuning_applied, networkLabel)
        }
        return TuningMeasureResult(
            BestTuningState(
                isRunning = false,
                statusText = statusText,
                isError = false,
            )
        )
    }

    private fun probeDirectTlsMillis(): Long? {
        val targets = listOf("cp.cloudflare.com" to 443, "www.gstatic.com" to 443)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        for ((host, port) in targets) {
            val start = System.currentTimeMillis()
            val ok = runCatching {
                (factory.createSocket() as SSLSocket).use { sock ->
                    sock.soTimeout = 2500
                    sock.connect(InetSocketAddress(host, port), 2500)
                    sock.startHandshake()
                }
                true
            }.getOrDefault(false)
            if (ok) return (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
        return null
    }

    /**
     * Checks for root access and requests it if necessary.
     * Updates [isLoading] during the process.
     */
    fun checkAndRequestRoot(onSuccess: () -> Unit) {
        launchLoading {
            val hasRoot = withContext(Dispatchers.IO) {
                RootManager.refresh()
            }
            if (hasRoot) {
                onSuccess()
            } else {
                toastError(R.string.toast_root_required)
            }
        }
    }

    /**
     * Validates if the given string is a valid observatory duration.
     * Shows error toast if invalid.
     * @return The trimmed value if valid, null otherwise.
     */
    fun validateObservatoryDuration(value: String): String? {
        val duration = value.trim()
        return if (AppConfig.OBSERVATORY_DURATION_PATTERN.matches(duration)) {
            duration
        } else {
            toastError(R.string.toast_invalid_observatory_duration)
            null
        }
    }

    /**
     * Validates if the given string is a valid observatory sampling value.
     * Shows error toast if invalid.
     * @return The value if valid, null otherwise.
     */
    fun validateObservatorySampling(value: String): String? {
        val sampling = value.trim().toIntOrNull()?.takeIf { it > 0 }
        return if (sampling != null) {
            sampling.toString()
        } else {
            toastError(R.string.toast_invalid_observatory_sampling)
            null
        }
    }
}
