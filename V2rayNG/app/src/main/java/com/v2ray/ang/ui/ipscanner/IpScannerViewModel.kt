package com.v2ray.ang.ui.ipscanner

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.IpScanInput
import com.v2ray.ang.handler.IpScanner
import com.v2ray.ang.handler.NetworkOperator
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
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

    data class UiState(
        val publicIp: String? = null,
        val operator: String = NetworkOperator.UNKNOWN,
        val loadingNetworkInfo: Boolean = true,
        val targetSource: IpScanner.TargetSource = IpScanner.TargetSource.MANUAL,
        val addressText: String = "",
        val selectedBuiltInTargetIds: Set<String> = IpScanner.BUILT_IN_TARGETS.map { it.id }.toSet(),
        val remoteUrlText: String = "",
        val remoteTargetsText: String = "",
        val loadingRemoteList: Boolean = false,
        val remoteListError: IpScanner.RemoteListError? = null,
        val countText: String = "16",
        val mode: IpScanner.Mode = IpScanner.Mode.TCP,
        val selectedPorts: List<Int> = IpScanner.DEFAULT_PORTS,
        val customPortsText: String = "",
        val scanning: Boolean = false,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val hasScanned: Boolean = false,
        val results: List<IpScanner.AddressResult> = emptyList(),
        val error: IpScanInput.ScanError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var networkInfoJob: Job? = null
    private var remoteListJob: Job? = null

    init {
        refreshNetworkInfo()
    }

    fun refreshNetworkInfo() {
        networkInfoJob?.cancel()
        val app = getApplication<Application>()
        val operator = try {
            NetworkOperator.current(app)
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "IP scanner network-operator lookup failed", error)
            NetworkOperator.UNKNOWN
        }
        _state.update { it.copy(operator = operator, loadingNetworkInfo = true) }
        networkInfoJob = viewModelScope.launch {
            val publicIp = withContext(Dispatchers.IO) { IpScanner.lookupPublicIp() }
            _state.update { it.copy(publicIp = publicIp, loadingNetworkInfo = false) }
        }
    }

    fun updateTargetSource(source: IpScanner.TargetSource) = updateInputs {
        it.copy(targetSource = source)
    }

    fun updateAddresses(value: String) = updateInputs { it.copy(addressText = value) }

    fun updateCount(value: String) = updateInputs {
        it.copy(countText = IpScanner.sanitizeCountText(value))
    }

    fun updateMode(mode: IpScanner.Mode) = updateInputs { it.copy(mode = mode) }

    fun updateCustomPorts(value: String) = updateInputs {
        it.copy(customPortsText = IpScanner.sanitizeCustomPortsText(value))
    }

    fun togglePresetPort(port: Int) = updateInputs {
        it.copy(selectedPorts = IpScanner.togglePresetPort(it.selectedPorts, port))
    }

    fun toggleBuiltInTarget(id: String) = updateInputs {
        it.copy(selectedBuiltInTargetIds = IpScanner.toggleBuiltInTarget(it.selectedBuiltInTargetIds, id))
    }

    fun updateRemoteUrl(value: String) {
        if (_state.value.scanning) return
        remoteListJob?.cancel()
        _state.update {
            it.copy(
                remoteUrlText = value.take(2_048),
                remoteTargetsText = "",
                loadingRemoteList = false,
                remoteListError = null,
                results = emptyList(),
                hasScanned = false,
                error = null,
            )
        }
    }

    fun loadRemoteTargetList() {
        val snapshot = _state.value
        if (snapshot.scanning || snapshot.loadingRemoteList) return
        remoteListJob?.cancel()
        _state.update {
            it.copy(
                loadingRemoteList = true,
                remoteTargetsText = "",
                remoteListError = null,
                error = null,
            )
        }
        val url = snapshot.remoteUrlText
        remoteListJob = viewModelScope.launch {
            try {
                val result = IpScanner.loadRemoteTargetList(url)
                _state.update {
                    it.copy(
                        remoteTargetsText = result.targetsText.orEmpty(),
                        remoteListError = result.error,
                        loadingRemoteList = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "IP scanner target-list load failed", error)
                _state.update {
                    it.copy(
                        remoteTargetsText = "",
                        remoteListError = IpScanner.RemoteListError.DOWNLOAD_FAILED,
                        loadingRemoteList = false,
                    )
                }
            } finally {
                remoteListJob = null
            }
        }
    }

    /** Starts a bounded scan or cancels the current pass when tapped again. */
    fun scan() {
        val current = _state.value
        if (current.scanning) {
            scanJob?.cancel()
            return
        }
        if (_isLoading.value || current.loadingRemoteList) return

        val snapshot = current
        _state.update {
            it.copy(
                scanning = true,
                progressDone = 0,
                progressTotal = 0,
                hasScanned = false,
                results = emptyList(),
                error = null,
            )
        }
        scanJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val prepared = withContext(Dispatchers.Default) {
                    val targets = IpScanner.resolveTargetInput(
                        source = snapshot.targetSource,
                        manualAddressText = snapshot.addressText,
                        selectedBuiltInTargetIds = snapshot.selectedBuiltInTargetIds,
                        remoteTargetsText = snapshot.remoteTargetsText,
                    )
                    targets.error?.let { error ->
                        return@withContext IpScanner.ScanPreparation(error = error)
                    }
                    IpScanner.prepareScan(
                        addressText = targets.addressText,
                        countText = snapshot.countText,
                        mode = snapshot.mode,
                        selectedPorts = snapshot.selectedPorts,
                        customPortsText = snapshot.customPortsText,
                    )
                }
                val request = prepared.request ?: run {
                    _state.update {
                        it.copy(error = prepared.error ?: IpScanInput.ScanError.InvalidTargets)
                    }
                    return@launch
                }
                val total = request.addresses.size *
                    (if (request.mode == IpScanner.Mode.NONE) 1 else request.ports.size)
                _state.update { it.copy(progressTotal = total) }

                val results = withContext(Dispatchers.Default) {
                    IpScanner.scan(request) { done, progressTotal ->
                        _state.update {
                            it.copy(progressDone = done, progressTotal = progressTotal)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        hasScanned = true,
                        results = results,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "IP scanner pass failed", error)
                _state.update { it.copy(error = IpScanInput.ScanError.ScanFailed) }
            } finally {
                _state.update { it.copy(scanning = false) }
                _isLoading.value = false
                scanJob = null
            }
        }
    }

    private fun updateInputs(transform: (UiState) -> UiState) {
        if (_state.value.scanning) return
        _state.update {
            transform(it).copy(
                results = emptyList(),
                hasScanned = false,
                progressDone = 0,
                progressTotal = 0,
                error = null,
            )
        }
    }
}
