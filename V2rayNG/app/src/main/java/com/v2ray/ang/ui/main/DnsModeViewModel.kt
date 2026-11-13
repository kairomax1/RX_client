package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.DnsMode
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DnsModeUiState(
    val enabled: Boolean = false,
    /** Sites the user added to the tunnelled list. */
    val extraDomains: List<String> = emptyList(),
    /** Whether other devices on the phone's network may use its proxy. */
    val sharing: Boolean = false,
    /** This phone's addresses on Wi-Fi / hotspot; empty when it is on neither. */
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    /** A proxy password is set; consoles cannot enter one. */
    val needsPassword: Boolean = false,
)

/** State of the DNS mode card. Every change that the running tunnel must pick up returns true. */
class DnsModeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DnsModeUiState())
    val state: StateFlow<DnsModeUiState> = _state.asStateFlow()

    /** Reads everything again; called each time the card opens. */
    fun refresh() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                DnsModeUiState(
                    enabled = DnsMode.isEnabled(),
                    extraDomains = DnsMode.extraDomains(),
                    sharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false),
                    addresses = DnsMode.shareAddresses(),
                    port = SettingsManager.getHttpPort(),
                    needsPassword = SettingsManager.getSocksUsername() != null,
                )
            }
            _state.value = fresh
        }
    }

    fun setEnabled(enabled: Boolean) {
        DnsMode.setEnabled(enabled)
        _state.update { it.copy(enabled = enabled) }
    }

    /** False when the text is not a site name; nothing is stored then. */
    fun addDomain(text: String): Boolean {
        if (!DnsMode.addExtraDomain(text)) return false
        _state.update { it.copy(extraDomains = DnsMode.extraDomains()) }
        return true
    }

    fun clearDomains() {
        DnsMode.clearExtraDomains()
        _state.update { it.copy(extraDomains = emptyList()) }
    }

    fun setSharing(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, enabled)
        _state.update { it.copy(sharing = enabled) }
        refresh()
    }
}
