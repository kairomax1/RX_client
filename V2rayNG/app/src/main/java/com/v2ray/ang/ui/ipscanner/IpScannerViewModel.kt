package com.v2ray.ang.ui.ipscanner

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherEngine
import com.v2ray.ang.handler.ConfigBenchmark
import com.v2ray.ang.handler.CloudflareAssistant
import com.v2ray.ang.handler.IpScanInput
import com.v2ray.ang.handler.IpScanner
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IpScannerViewModel(application: Application) : BaseViewModel(application) {

    enum class InputError {
        InvalidRange,
        TooManyAddresses,
        InvalidPort,
        InvalidSni,
        NoSelection,
        NoConfig,
        ScanFailed,
        ApplyFailed,
        NoWorkingConfig,
    }

    data class SniOutcome(
        val address: String,
        val succeeded: Boolean,
        val handshakeMs: Long?,
    )

    data class UiState(
        val cloudflarePool: Boolean = true,
        val customRange: String = "",
        val portEnabled: Boolean = true,
        val portText: String = "443",
        val sniText: String = "",
        val sniManuallyEdited: Boolean = false,
        val scanning: Boolean = false,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val hasScanned: Boolean = false,
        val results: List<IpScanner.ProbeResult> = emptyList(),
        val selectedAddress: String? = null,
        val checkingSni: Boolean = false,
        val sniOutcome: SniOutcome? = null,
        val checkingSpeed: Boolean = false,
        val applying: Boolean = false,
        val connecting: Boolean = false,
        val connectReadyGuid: String? = null,
        val connectionProgressDone: Int = 0,
        val connectionProgressTotal: Int = 0,
        val sourceConfigGuid: String? = null,
        val sourceConfigName: String? = null,
        val canApplyToConfig: Boolean = false,
        val appliedConfigName: String? = null,
        val appliedConfigPingMs: Long? = null,
        val appliedConfigPingFinished: Boolean = false,
        val error: InputError? = null,
    ) {
        val selectedResult: IpScanner.ProbeResult?
            get() = results.firstOrNull { it.address == selectedAddress }

        val canMeasureSpeed: Boolean
            get() = selectedResult?.let {
                it.port == 443 && CloudflareAssistant.isCloudflareIpv4(it.address)
            } == true && !checkingSpeed && !checkingSni && !scanning && !applying && !connecting

        val canCheckSni: Boolean
            get() = selectedResult?.port != null && sniText.isNotBlank() &&
                !checkingSni && !checkingSpeed && !scanning && !applying && !connecting

        val canConnectToIp: Boolean
            get() = !applying && !connecting && !checkingSni && !checkingSpeed && !scanning
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            IpScanner.cleanupStaleTemporaryProfiles()
            refreshSourceConfig()
            val profile = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
            val defaultSni = profile?.sni?.takeIf { it.isNotBlank() }
                ?: profile?.host?.takeIf { it.isNotBlank() }
                ?: profile?.server?.let(IpScanInput::normalizeSni)
                ?: ""
            _state.update { if (it.sniManuallyEdited) it else it.copy(sniText = defaultSni) }
        }
    }

    /** Updates the source profile label after returning to the activity. */
    fun refreshSource() {
        viewModelScope.launch(Dispatchers.IO) { refreshSourceConfig() }
    }

    private fun refreshSourceConfig() {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let(MmkvManager::decodeServerConfig)
        val canUse = guid != null && guid != AppConfig.FIREFOX_VPN_PROFILE_ID &&
            profile?.server?.isNotBlank() == true && !AetherEngine.isAetherProfile(profile)
        _state.update {
            it.copy(
                sourceConfigGuid = guid,
                sourceConfigName = profile?.remarks?.takeIf { name -> name.isNotBlank() },
                canApplyToConfig = canUse,
            )
        }
    }

    fun selectTarget(cloudflare: Boolean) {
        _state.update {
            it.copy(
                cloudflarePool = cloudflare,
                results = emptyList(),
                selectedAddress = null,
                hasScanned = false,
                sniOutcome = null,
                error = null,
            )
        }
    }

    fun updateCustomRange(value: String) {
        _state.update {
            it.copy(
                customRange = value,
                results = emptyList(),
                selectedAddress = null,
                hasScanned = false,
                sniOutcome = null,
                error = null,
            )
        }
    }

    fun setPortEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                portEnabled = enabled,
                results = emptyList(),
                selectedAddress = null,
                hasScanned = false,
                sniOutcome = null,
                error = null,
            )
        }
    }

    fun updatePort(value: String) {
        _state.update {
            it.copy(
                portText = value.filter(Char::isDigit).take(5),
                results = emptyList(),
                selectedAddress = null,
                hasScanned = false,
                sniOutcome = null,
                error = null,
            )
        }
    }

    fun updateSni(value: String) {
        _state.update { current ->
            current.copy(
                sniText = value,
                sniManuallyEdited = true,
                results = current.results.map {
                    it.copy(tlsHandshakeMs = null, tlsSucceeded = null)
                },
                sniOutcome = null,
                error = null,
            )
        }
    }

    fun selectResult(address: String) {
        _state.update { it.copy(selectedAddress = address, sniOutcome = null, error = null) }
    }

    /** Starts a bounded scan or cancels the current pass when tapped again. */
    fun scan() {
        if (_state.value.scanning) {
            scanJob?.cancel()
            return
        }
        if (_isLoading.value) return

        val snapshot = _state.value
        val prepared = IpScanInput.prepareScan(
            cloudflarePool = snapshot.cloudflarePool,
            customRange = snapshot.customRange,
            portEnabled = snapshot.portEnabled,
            portText = snapshot.portText,
            sniText = snapshot.sniText,
            cloudflareAddresses = IpScanner.cloudflareCandidates(),
        )
        val request = prepared.request ?: run {
            setError(
                when (prepared.error) {
                    IpScanInput.ScanError.InvalidRange -> InputError.InvalidRange
                    IpScanInput.ScanError.TooManyAddresses -> InputError.TooManyAddresses
                    IpScanInput.ScanError.InvalidPort -> InputError.InvalidPort
                    IpScanInput.ScanError.InvalidSni -> InputError.InvalidSni
                    null -> InputError.ScanFailed
                },
            )
            return
        }
        val addresses = request.addresses

        _state.update {
            it.copy(
                scanning = true,
                progressDone = 0,
                progressTotal = addresses.size,
                hasScanned = false,
                results = emptyList(),
                selectedAddress = null,
                sniOutcome = null,
                error = null,
            )
        }
        scanJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val results = IpScanner.scan(
                    addresses = addresses,
                    port = request.port,
                    sni = request.sni,
                    onProgress = { done, total ->
                        _state.update { it.copy(progressDone = done, progressTotal = total) }
                    },
                )
                _state.update {
                    it.copy(
                        scanning = false,
                        hasScanned = true,
                        results = results,
                        selectedAddress = results.firstOrNull()?.address,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setError(InputError.ScanFailed)
            } finally {
                _state.update { it.copy(scanning = false) }
                _isLoading.value = false
                scanJob = null
            }
        }
    }

    /** Performs a fresh TLS handshake to the selected IP with the SNI currently in the field. */
    fun checkSni() {
        val selected = _state.value.selectedResult ?: run {
            setError(InputError.NoSelection)
            return
        }
        val port = selected.port ?: run {
            setError(InputError.InvalidPort)
            return
        }
        val sni = IpScanInput.normalizeSni(_state.value.sniText)
        if (sni == null) {
            setError(InputError.InvalidSni)
            return
        }
        if (_state.value.checkingSni || _state.value.scanning || _isLoading.value) return

        _state.update { it.copy(checkingSni = true, sniOutcome = null, error = null) }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = IpScanner.checkSni(selected.address, port, sni)
                val succeeded = result?.tlsSucceeded == true
                _state.update { current ->
                    current.copy(
                        checkingSni = false,
                        results = current.results.map {
                            if (it.address == selected.address) {
                                it.copy(
                                    tcpConnectMs = result?.tcpConnectMs ?: it.tcpConnectMs,
                                    tlsHandshakeMs = result?.tlsHandshakeMs,
                                    tlsSucceeded = result?.tlsSucceeded ?: false,
                                )
                            } else it
                        },
                        sniOutcome = SniOutcome(
                            address = selected.address,
                            succeeded = succeeded,
                            handshakeMs = result?.tlsHandshakeMs,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        sniOutcome = SniOutcome(selected.address, succeeded = false, handshakeMs = null),
                    )
                }
            } finally {
                _state.update { it.copy(checkingSni = false) }
                _isLoading.value = false
            }
        }
    }

    /** Uses the Cloudflare speed endpoint pinned to the selected Cloudflare IP. */
    fun measureSpeed() {
        val selected = _state.value.selectedResult ?: run {
            setError(InputError.NoSelection)
            return
        }
        if (!CloudflareAssistant.isCloudflareIpv4(selected.address) || selected.port != 443) return
        if (_state.value.checkingSpeed || _state.value.scanning || _isLoading.value) return

        _state.update { it.copy(checkingSpeed = true, error = null) }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val speed = IpScanner.measureSpeed(selected.address, selected.port)
                _state.update { current ->
                    current.copy(
                        checkingSpeed = false,
                        results = current.results.map {
                            if (it.address == selected.address) {
                                it.copy(
                                    downloadMbps = speed.downloadMbps,
                                    uploadMbps = speed.uploadMbps,
                                )
                            } else it
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A missing speed sample is displayed as unavailable, not as a failed IP probe.
            } finally {
                _state.update { it.copy(checkingSpeed = false) }
                _isLoading.value = false
            }
        }
    }

    /** Saves a copy of the active config, preserving its protocol and keeping the original intact. */
    fun useSelectedIp() {
        val selected = _state.value.selectedResult ?: run {
            setError(InputError.NoSelection)
            return
        }
        saveIpToConfig(selected)
    }

    /** Probes saved protocols, preserves a working current config behind this IP when possible. */
    fun connectToIp(address: String) {
        val snapshot = _state.value
        val result = snapshot.results.firstOrNull { it.address == address } ?: run {
            setError(InputError.NoSelection)
            return
        }
        if (snapshot.applying || snapshot.connecting || snapshot.scanning ||
            snapshot.checkingSni || snapshot.checkingSpeed || _isLoading.value
        ) return

        val preferredSni = if (snapshot.portEnabled) {
            snapshot.sniText.trim().takeIf { it.isNotEmpty() }?.let(IpScanInput::normalizeSni)
        } else null
        if (snapshot.portEnabled && snapshot.sniText.isNotBlank() && preferredSni == null) {
            setError(InputError.InvalidSni)
            return
        }

        _state.update {
            it.copy(
                connecting = true,
                applying = false,
                connectReadyGuid = null,
                connectionProgressDone = 0,
                connectionProgressTotal = 0,
                selectedAddress = result.address,
                error = null,
            )
        }
        val app = getApplication<Application>()
        val groupName = localizedContext.getString(R.string.ipscan_group_name)
        val copySuffix = localizedContext.getString(R.string.ipscan_copy_suffix)

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val match = IpScanner.findBestConfigAtAddress(
                    context = app,
                    address = result.address,
                    port = result.port,
                    preferredGuid = snapshot.sourceConfigGuid,
                    preferredSni = preferredSni,
                    applySniToAll = snapshot.sniManuallyEdited,
                    onProgress = { done, total ->
                        _state.update {
                            it.copy(connectionProgressDone = done, connectionProgressTotal = total)
                        }
                    },
                )
                if (match == null) {
                    _state.update {
                        it.copy(applying = false, connecting = false, error = InputError.NoWorkingConfig)
                    }
                    return@launch
                }
                _state.update { it.copy(applying = true) }

                val savedRoute = withContext(Dispatchers.IO) {
                    val entryGuid = IpScanner.saveConfigCopy(
                        sourceGuid = match.sourceGuid,
                        address = result.address,
                        port = result.port,
                        sni = preferredSni.takeIf {
                            snapshot.sniManuallyEdited || match.sourceGuid == snapshot.sourceConfigGuid
                        },
                        groupName = groupName,
                        copySuffix = copySuffix,
                    ) ?: return@withContext null
                    val chainGuid = match.targetGuid?.let { targetGuid ->
                        IpScanner.saveProxyChainCopy(
                            entryGuid = entryGuid,
                            targetGuid = targetGuid,
                            groupName = groupName,
                            copySuffix = copySuffix,
                        )
                    }
                    val selectedGuid = chainGuid ?: entryGuid
                    val selectedPing = if (chainGuid != null) match.pingMs else match.entryPingMs
                    selectedGuid to selectedPing
                }
                val savedGuid = savedRoute?.first
                val selectedPingMs = savedRoute?.second ?: match.pingMs
                if (savedGuid == null) {
                    _state.update {
                        it.copy(applying = false, connecting = false, error = InputError.ApplyFailed)
                    }
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    MmkvManager.setSelectServer(savedGuid)
                    MmkvManager.encodeServerTestDelayMillis(savedGuid, selectedPingMs)
                }
                val name = withContext(Dispatchers.IO) {
                    MmkvManager.decodeServerConfig(savedGuid)?.remarks
                }
                _state.update {
                    it.copy(
                        applying = false,
                        connecting = false,
                        appliedConfigName = name,
                        appliedConfigPingMs = selectedPingMs,
                        appliedConfigPingFinished = true,
                        sourceConfigGuid = savedGuid,
                        sourceConfigName = name,
                        canApplyToConfig = true,
                        connectReadyGuid = savedGuid,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(applying = false, connecting = false, error = InputError.NoWorkingConfig)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun saveIpToConfig(selected: IpScanner.ProbeResult) {
        val snapshot = _state.value
        val sourceGuid = snapshot.sourceConfigGuid
        if (sourceGuid.isNullOrBlank() || !snapshot.canApplyToConfig) {
            setError(InputError.NoConfig)
            return
        }
        if (snapshot.applying || snapshot.connecting || snapshot.scanning ||
            snapshot.checkingSni || snapshot.checkingSpeed || _isLoading.value
        ) return

        val currentSni = snapshot.sniText.trim().takeIf { it.isNotEmpty() }
            ?.let(IpScanInput::normalizeSni)
        if (snapshot.sniText.isNotBlank() && currentSni == null) {
            setError(InputError.InvalidSni)
            return
        }

        _state.update {
            it.copy(
                applying = true,
                connectReadyGuid = null,
                selectedAddress = selected.address,
                error = null,
                appliedConfigPingFinished = false,
            )
        }
        val app = getApplication<Application>()
        val groupName = localizedContext.getString(R.string.ipscan_group_name)
        val copySuffix = localizedContext.getString(R.string.ipscan_copy_suffix)

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val savedGuid = withContext(Dispatchers.IO) {
                    IpScanner.saveConfigCopy(
                        sourceGuid = sourceGuid,
                        address = selected.address,
                        port = selected.port,
                        sni = currentSni,
                        groupName = groupName,
                        copySuffix = copySuffix,
                    )
                }
                if (savedGuid == null) {
                    _state.update {
                        it.copy(applying = false, connecting = false, error = InputError.ApplyFailed)
                    }
                    return@launch
                }

                withContext(Dispatchers.IO) { MmkvManager.setSelectServer(savedGuid) }
                val name = withContext(Dispatchers.IO) {
                    MmkvManager.decodeServerConfig(savedGuid)?.remarks
                }
                val ping = ConfigBenchmark.pingConfigs(app, listOf(savedGuid))[savedGuid]
                    ?.takeIf { it > 0L }
                if (ping != null) {
                    withContext(Dispatchers.IO) { MmkvManager.encodeServerTestDelayMillis(savedGuid, ping) }
                }
                _state.update {
                    it.copy(
                        applying = false,
                        appliedConfigName = name,
                        appliedConfigPingMs = ping,
                        appliedConfigPingFinished = true,
                        sourceConfigGuid = savedGuid,
                        sourceConfigName = name,
                        canApplyToConfig = true,
                    )
                }
                withContext(Dispatchers.Main) { toastSuccess(R.string.ipscan_apply_success) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(applying = false, connecting = false, error = InputError.ApplyFailed)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun setError(error: InputError) {
        _state.update { it.copy(error = error) }
    }
}
