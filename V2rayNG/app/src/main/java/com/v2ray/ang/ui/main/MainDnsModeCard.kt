package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptButtons
import com.v2ray.ang.ui.compose.RxPromptDialog
import com.v2ray.ang.ui.compose.RxPromptField
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxPromptSubject
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.RxRisingNote

private enum class DnsPage { Main, AddSite, Devices, Xbox }

/**
 * The DNS mode card: switch it, add a site that did not open, or get the address another device
 * (a console, a TV) has to use. [onChanged] is called after every change the running tunnel has
 * to pick up.
 */
@Composable
fun MainDnsModeCard(
    state: DnsModeUiState,
    onSetEnabled: (Boolean) -> Unit,
    /** Switches the mode without looking for a config to connect to. */
    onSetEnabledOnly: (Boolean) -> Unit,
    onAddDomain: (String) -> Boolean,
    onClearDomains: () -> Unit,
    onSetSharing: (Boolean) -> Unit,
    onProbeConsoleDns: () -> Unit,
    onUseAether: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(DnsPage.Main) }
    var noteTick by remember { mutableIntStateOf(0) }
    var noteText by remember { mutableStateOf("") }
    val fullWidth = Modifier.fillMaxWidth()

    RxPromptDialog(
        onDismissRequest = onDismiss,
        alwaysRtl = false,
        overlay = { RxRisingNote(text = noteText, tick = noteTick, calm = true) },
    ) {
        when (page) {
            DnsPage.Main -> {
                val aetherNote = stringResource(R.string.dns_mode_aether_note)
                RxPromptMessage(stringResource(R.string.dns_mode_title))
                Spacer(Modifier.height(10.dp))
                CardNote(stringResource(R.string.dns_mode_about))
                Spacer(Modifier.height(14.dp))
                RxPromptSubject(
                    stringResource(if (state.enabled) R.string.dns_mode_state_on else R.string.dns_mode_state_off)
                )
                RxPromptButtons {
                    RxPromptButton(
                        text = stringResource(if (state.enabled) R.string.dns_mode_turn_off else R.string.dns_mode_turn_on),
                        tone = if (state.enabled) RxPromptTone.Ghost else RxPromptTone.Silver,
                        onClick = {
                            onSetEnabled(!state.enabled)
                            onChanged()
                        },
                        modifier = fullWidth,
                    )
                    if (state.aetherAvailable) {
                        RxPromptButton(
                            text = stringResource(R.string.dns_mode_use_aether),
                            tone = RxPromptTone.Ghost,
                            onClick = {
                                if (!state.enabled) onSetEnabledOnly(true)
                                noteText = aetherNote
                                noteTick++
                                onUseAether()
                            },
                            modifier = fullWidth,
                            order = 1,
                        )
                    }
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_add_site),
                        tone = RxPromptTone.Ghost,
                        onClick = { page = DnsPage.AddSite },
                        modifier = fullWidth,
                        order = 2,
                    )
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_devices),
                        tone = RxPromptTone.Ghost,
                        onClick = { page = DnsPage.Devices },
                        modifier = fullWidth,
                        order = 3,
                    )
                }
            }

            DnsPage.AddSite -> {
                var text by remember { mutableStateOf("") }
                var shake by remember { mutableIntStateOf(0) }
                val added = stringResource(R.string.dns_mode_site_added)
                val submit: () -> Unit = {
                    if (onAddDomain(text)) {
                        text = ""
                        noteText = added
                        noteTick++
                        onChanged()
                    } else {
                        shake++
                    }
                }
                RxPromptMessage(stringResource(R.string.dns_mode_add_site_ask), fontSize = 20)
                Spacer(Modifier.height(14.dp))
                RxPromptField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "example.com",
                    shakeTick = shake,
                    onDone = submit,
                )
                if (state.extraDomains.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    CardNote(state.extraDomains.joinToString("  •  "))
                }
                RxPromptButtons {
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_site_add),
                        tone = RxPromptTone.Silver,
                        onClick = submit,
                        modifier = fullWidth,
                    )
                    if (state.extraDomains.isNotEmpty()) {
                        RxPromptButton(
                            text = stringResource(R.string.dns_mode_site_clear),
                            tone = RxPromptTone.MistRed,
                            onClick = {
                                onClearDomains()
                                onChanged()
                            },
                            modifier = fullWidth,
                            order = 1,
                        )
                    }
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_back),
                        tone = RxPromptTone.Ghost,
                        onClick = { page = DnsPage.Main },
                        modifier = fullWidth,
                        order = 2,
                    )
                }
            }

            DnsPage.Devices -> {
                val clipboard = LocalClipboardManager.current
                val copied = stringResource(R.string.dns_mode_copied)
                val address = state.addresses.firstOrNull()
                RxPromptMessage(stringResource(R.string.dns_mode_devices), fontSize = 20)
                Spacer(Modifier.height(10.dp))
                when {
                    !state.sharing -> CardNote(stringResource(R.string.dns_mode_devices_about))
                    state.needsPassword -> CardNote(stringResource(R.string.dns_mode_devices_password))
                    address == null -> CardNote(stringResource(R.string.dns_mode_devices_no_network))
                    else -> {
                        CardNote(stringResource(R.string.dns_mode_devices_how))
                        Spacer(Modifier.height(14.dp))
                        RxPromptSubject(stringResource(R.string.dns_mode_devices_address, address))
                        Spacer(Modifier.height(8.dp))
                        RxPromptSubject(stringResource(R.string.dns_mode_devices_port, state.port.toString()))
                    }
                }
                RxPromptButtons {
                    if (!state.sharing) {
                        RxPromptButton(
                            text = stringResource(R.string.dns_mode_devices_on),
                            tone = RxPromptTone.Silver,
                            onClick = {
                                onSetSharing(true)
                                onChanged()
                            },
                            modifier = fullWidth,
                        )
                    } else {
                        if (address != null && !state.needsPassword) {
                            RxPromptButton(
                                text = stringResource(R.string.dns_mode_copy),
                                tone = RxPromptTone.Silver,
                                onClick = {
                                    clipboard.setText(AnnotatedString("$address:${state.port}"))
                                    noteText = copied
                                    noteTick++
                                },
                                modifier = fullWidth,
                            )
                        }
                        RxPromptButton(
                            text = stringResource(R.string.dns_mode_devices_off),
                            tone = RxPromptTone.MistRed,
                            onClick = {
                                onSetSharing(false)
                                onChanged()
                            },
                            modifier = fullWidth,
                            order = 1,
                        )
                    }
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_xbox),
                        tone = RxPromptTone.Ghost,
                        onClick = {
                            onProbeConsoleDns()
                            page = DnsPage.Xbox
                        },
                        modifier = fullWidth,
                        order = 2,
                    )
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_back),
                        tone = RxPromptTone.Ghost,
                        onClick = { page = DnsPage.Main },
                        modifier = fullWidth,
                        order = 3,
                    )
                }
            }

            DnsPage.Xbox -> {
                val clipboard = LocalClipboardManager.current
                val copied = stringResource(R.string.dns_mode_copied)
                val best = state.consoleDns?.firstOrNull()
                RxPromptMessage(stringResource(R.string.dns_mode_xbox), fontSize = 20)
                Spacer(Modifier.height(10.dp))
                when {
                    state.probing || state.consoleDns == null -> CardNote(stringResource(R.string.dns_mode_xbox_checking))
                    best == null -> CardNote(stringResource(R.string.dns_mode_xbox_none))
                    else -> {
                        CardNote(stringResource(R.string.dns_mode_xbox_how))
                        Spacer(Modifier.height(14.dp))
                        RxPromptSubject(
                            stringResource(R.string.dns_mode_xbox_service, best.service.name, best.millis.toString())
                        )
                        Spacer(Modifier.height(8.dp))
                        RxPromptSubject(stringResource(R.string.dns_mode_xbox_primary, best.service.primary))
                        Spacer(Modifier.height(8.dp))
                        RxPromptSubject(stringResource(R.string.dns_mode_xbox_secondary, best.service.secondary))
                        Spacer(Modifier.height(12.dp))
                        CardNote(stringResource(R.string.dns_mode_xbox_limits))
                    }
                }
                RxPromptButtons {
                    if (best != null && !state.probing) {
                        RxPromptButton(
                            text = stringResource(R.string.dns_mode_copy),
                            tone = RxPromptTone.Silver,
                            onClick = {
                                clipboard.setText(AnnotatedString("${best.service.primary}\n${best.service.secondary}"))
                                noteText = copied
                                noteTick++
                            },
                            modifier = fullWidth,
                        )
                    }
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_xbox_again),
                        tone = RxPromptTone.Ghost,
                        onClick = onProbeConsoleDns,
                        enabled = !state.probing,
                        modifier = fullWidth,
                        order = 1,
                    )
                    RxPromptButton(
                        text = stringResource(R.string.dns_mode_back),
                        tone = RxPromptTone.Ghost,
                        onClick = { page = DnsPage.Devices },
                        modifier = fullWidth,
                        order = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
