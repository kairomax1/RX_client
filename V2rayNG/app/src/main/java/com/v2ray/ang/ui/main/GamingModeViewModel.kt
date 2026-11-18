package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherEngine
import com.v2ray.ang.handler.GamingExitTuner
import com.v2ray.ang.handler.GamingMode
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.Sharing
import com.v2ray.ang.handler.SharingGateway
import com.v2ray.ang.service.SharingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GamingModeUiState(
    val enabled: Boolean = false,
    /** Sites the user added to the tunnelled list. */
    val extraDomains: List<String> = emptyList(),
    /** Whether other devices may use the phone's connection. */
    val sharing: Boolean = false,
    /** This phone's addresses on Wi-Fi / hotspot; empty when it is on neither. */
    val addresses: List<String> = emptyList(),
    /** True when the phone is hosting the network (a hotspot, or sharing by cable). */
    val hosting: Boolean = false,
    val port: Int = 0,
    /** The moment sharing ends by itself; 0 when nothing ends it. */
    val until: Long = 0,
    /** How long sharing is allowed to run; 0 is "until it is switched off". */
    val minutes: Int = 0,
    /** What every device that has reached the phone is doing. */
    val devices: List<Sharing.Device> = emptyList(),
    /** Why sharing stopped on its own; empty while it did not. */
    val stopped: String = "",
    /** The phone's own tunnel: sharing carries what the phone itself has. */
    val vpnUp: Boolean = false,
    /** A proxy password is set; consoles cannot enter one. */
    val needsPassword: Boolean = false,
    /** Where games leave from: the config's exit address, or the phone's own line. */
    val gameExit: String = GamingMode.EXIT_CONFIG,
    /** This build carries the Aether engine, so connecting without a config is possible. */
    val aetherAvailable: Boolean = false,
)

/** State of the DNS mode card. Every change that the running tunnel must pick up returns true. */
class GamingModeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(GamingModeUiState())
    val state: StateFlow<GamingModeUiState> = _state.asStateFlow()

    /** Reads everything again; called each time the card opens. */
    fun refresh() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val (addresses, hosting) = SharingGateway.network()
                val live = Sharing.live()
                GamingModeUiState(
                    enabled = GamingMode.isEnabled(),
                    extraDomains = GamingMode.extraDomains(),
                    gameExit = GamingMode.gameExit(),
                    sharing = live.running,
                    addresses = addresses,
                    hosting = hosting,
                    port = Sharing.port(),
                    until = live.until,
                    minutes = Sharing.minutes(),
                    devices = merge(live.devices, Sharing.devices()),
                    stopped = Sharing.stoppedReason(),
                    vpnUp = ServiceRuntimeState.isRunning(getApplication()),
                    needsPassword = SettingsManager.getSocksUsername() != null,
                    aetherAvailable = AetherEngine.isAvailable(getApplication()),
                )
            }
            _state.value = fresh
        }
    }

    /**
     * Keeps the card up to the moment while it is open: the address, the
     * devices, what each of them is carrying and how long sharing has left.
     */
    fun watchSharing() {
        if (watch != null) return
        watch = viewModelScope.launch {
            while (isActive) {
                val (addresses, hosting) = withContext(Dispatchers.IO) { SharingGateway.network() }
                val live = withContext(Dispatchers.IO) { Sharing.live() }
                val stored = withContext(Dispatchers.IO) { Sharing.devices() }
                val vpnUp = withContext(Dispatchers.IO) { ServiceRuntimeState.isRunning(getApplication()) }
                val on = live.running || (Sharing.isEnabled() && Sharing.stoppedReason().isEmpty())
                if (Sharing.isEnabled() && !live.running && Sharing.stoppedReason().isEmpty()) {
                    // The switch was left on but nothing is listening: the
                    // phone was restarted, or the service was killed. Putting
                    // it back is what the switch means.
                    SharingService.start(getApplication())
                }
                _state.update {
                    it.copy(
                        sharing = on,
                        addresses = addresses,
                        hosting = hosting,
                        port = Sharing.port(),
                        until = live.until,
                        minutes = Sharing.minutes(),
                        devices = merge(live.devices, stored),
                        stopped = Sharing.stoppedReason(),
                        vpnUp = vpnUp,
                    )
                }
                delay(1_000)
            }
        }
    }

    fun stopWatchingSharing() {
        watch?.cancel()
        watch = null
    }

    private var watch: Job? = null

    /** Devices that were decided about but have not been seen since are kept. */
    private fun merge(live: List<Sharing.Device>, stored: List<Sharing.Device>): List<Sharing.Device> {
        val seen = live.associateBy { it.ip }.toMutableMap()
        for (device in stored) {
            if (!seen.containsKey(device.ip)) {
                seen[device.ip] = Sharing.Device(
                    ip = device.ip,
                    name = device.name,
                    verdict = device.verdict,
                    addedAt = device.addedAt,
                )
            }
        }
        return seen.values.sortedWith(
            compareByDescending<Sharing.Device> { it.waiting }
                .thenByDescending { it.lastSeen }
                .thenBy { it.ip }
        )
    }

    fun setEnabled(enabled: Boolean) {
        GamingMode.setEnabled(enabled)
        _state.update { it.copy(enabled = enabled) }
        if (enabled) tuneExit()
    }

    /**
     * Measures the addresses the selected config can leave from and keeps the one that answers
     * first. It runs beside the interface rather than in front of it: a game is judged on its
     * round trip, and the owner should not wait on a measurement to start playing.
     */
    private fun tuneExit() {
        viewModelScope.launch {
            runCatching {
                GamingExitTuner.tune(
                    context = getApplication(),
                    groupName = getApplication<Application>().getString(R.string.cloudflare_group_name),
                    remarksSuffix = getApplication<Application>().getString(R.string.cloudflare_copy_suffix),
                )
            }.onFailure { LogUtil.w(AppConfig.TAG, "Gaming: measuring the exits failed", it) }
        }
    }

    /**
     * Switches the mode on with the exit the owner chose for games. Asked every
     * time the mode is switched on, because the right answer depends on which
     * game is about to be played.
     */
    fun setGameExit(exit: String) {
        GamingMode.setGameExit(exit)
        _state.update { it.copy(gameExit = exit) }
        // The line the games use was just chosen, so the fastest address on it is worth finding.
        tuneExit()
    }

    /** False when the text is not a site name; nothing is stored then. */
    fun addDomain(text: String): Boolean {
        if (!GamingMode.addExtraDomain(text)) return false
        _state.update { it.copy(extraDomains = GamingMode.extraDomains()) }
        return true
    }

    fun clearDomains() {
        GamingMode.clearExtraDomains()
        _state.update { it.copy(extraDomains = emptyList()) }
    }

    // ── Sharing ───────────────────────────────────────────────────

    /** Switches sharing on or off; on, the door opens at once. */
    fun setSharing(enabled: Boolean) {
        if (enabled) {
            Sharing.setStoppedReason(null)
            Sharing.setEnabled(true)
            _state.update { it.copy(sharing = true, stopped = "") }
            SharingService.start(getApplication())
        } else {
            Sharing.setEnabled(false)
            Sharing.setStoppedReason(null)
            SharingService.stop(getApplication())
            _state.update { it.copy(sharing = false, stopped = "", devices = emptyList()) }
        }
    }

    /** How long sharing may run; a running share gets the new time at once. */
    fun setMinutes(minutes: Int) {
        Sharing.setMinutes(minutes)
        _state.update { it.copy(minutes = minutes) }
        if (_state.value.sharing) SharingService.restartClock(getApplication())
    }

    fun allowDevice(ip: String, name: String) {
        SharingService.allow(getApplication(), ip, name)
        _state.update { current ->
            current.copy(devices = current.devices.map { if (it.ip == ip) it.copy(verdict = Sharing.ALLOW, name = name) else it })
        }
    }

    fun blockDevice(ip: String) {
        SharingService.block(getApplication(), ip)
        _state.update { current ->
            current.copy(devices = current.devices.map { if (it.ip == ip) it.copy(verdict = Sharing.BLOCK) else it })
        }
    }

    fun forgetDevice(ip: String) {
        Sharing.forget(ip)
        _state.update { current -> current.copy(devices = current.devices.filter { it.ip != ip }) }
    }

    fun renameDevice(ip: String, name: String) {
        Sharing.rename(ip, name)
        _state.update { current ->
            current.copy(devices = current.devices.map { if (it.ip == ip) it.copy(name = name) else it })
        }
    }
}
