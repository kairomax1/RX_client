package com.v2ray.ang.ui.sharing

import android.app.Application
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.Sharing
import com.v2ray.ang.handler.SharingGateway
import com.v2ray.ang.service.SharingService
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxRisingNote
import com.v2ray.ang.ui.compose.rxScreenBrush
import com.v2ray.ang.ui.main.SharingCardState
import com.v2ray.ang.ui.main.SharingPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sharing, on a screen of its own.
 *
 * The card in the main screen opens it too, and the settings open it from a
 * line of their own, because whether the phone's connection may be given to
 * another device is a thing the owner looks for in both places. What is shared
 * is whatever the phone itself has at that moment: the config that is
 * connected, the app's own way of reaching the internet when one is being used,
 * and gaming mode when it is on. Nothing is chosen here, because there is
 * nothing to choose — a device that uses this phone as its proxy gets exactly
 * what the phone gets.
 */
class SharingViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SharingCardState())
    val state = _state.asStateFlow()

    private var watch: Job? = null

    /** Reads everything again. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { read() }
        }
    }

    /**
     * Keeps the screen up to the moment: the address other devices use, the
     * devices that have reached the phone, what each of them is carrying and
     * how long sharing has left.
     */
    fun watch() {
        if (watch != null) return
        watch = viewModelScope.launch {
            while (isActive) {
                val fresh = withContext(Dispatchers.IO) { read() }
                // The switch was left on but nothing is listening: the phone was
                // restarted, or the service was killed. Putting the door back is
                // what leaving the switch on means.
                if (Sharing.isEnabled() && !fresh.sharing && fresh.stopped.isEmpty()) {
                    SharingService.start(getApplication())
                }
                _state.update { fresh }
                delay(1_000)
            }
        }
    }

    fun stopWatching() {
        watch?.cancel()
        watch = null
    }

    private fun read(): SharingCardState {
        val (addresses, hosting) = SharingGateway.network()
        val live = Sharing.live()
        return SharingCardState(
            sharing = live.running || (Sharing.isEnabled() && Sharing.stoppedReason().isEmpty()),
            addresses = addresses,
            hosting = hosting,
            port = Sharing.port(),
            until = live.until,
            minutes = Sharing.minutes(),
            devices = merge(live.devices, Sharing.devices()),
            stopped = Sharing.stoppedReason(),
            vpnUp = ServiceRuntimeState.isRunning(getApplication()),
        )
    }

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
}

class SharingActivity : BaseComponentActivity() {

    private val viewModel: SharingViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var note by remember { mutableStateOf("") }
        var noteTick by remember { mutableStateOf(0) }

        DisposableEffect(Unit) {
            viewModel.refresh()
            viewModel.watch()
            onDispose { viewModel.stopWatching() }
        }

        Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
            RxFogBackground(Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                SharingPage(
                    state = state,
                    note = { text ->
                        note = text
                        noteTick++
                    },
                    onSetSharing = viewModel::setSharing,
                    onSetMinutes = viewModel::setMinutes,
                    onAllow = viewModel::allowDevice,
                    onBlock = viewModel::blockDevice,
                    onForget = viewModel::forgetDevice,
                    onBack = { finish() },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.sharing_what),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (note.isNotEmpty()) {
                RxRisingNote(text = note, tick = noteTick, calm = true)
            }
        }
    }
}
