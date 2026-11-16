package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.Sharing
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptButtons
import com.v2ray.ang.ui.compose.RxPromptDialog
import com.v2ray.ang.ui.compose.RxPromptField
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxPromptSubject
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.RxRisingNote

private enum class DnsPage { Main, Sharing }

/** How long sharing may run, in minutes; 0 is "until it is switched off". */
private val SHARING_TIMES = listOf(0, 30, 120, 480)

/**
 * The DNS mode card: switch it, or share the phone's connection with another
 * device. [onChanged] is called after every change the running tunnel has to
 * pick up.
 */
@Composable
fun MainDnsModeCard(
    state: DnsModeUiState,
    onSetEnabled: (Boolean) -> Unit,
    onUseAether: () -> Unit,
    onSetSharing: (Boolean) -> Unit,
    onSetMinutes: (Int) -> Unit,
    onAllow: (String, String) -> Unit,
    onBlock: (String) -> Unit,
    onForget: (String) -> Unit,
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
                                if (!state.enabled) onSetEnabled(true)
                                noteText = aetherNote
                                noteTick++
                                onUseAether()
                            },
                            modifier = fullWidth,
                            order = 1,
                        )
                    }
                    RxPromptButton(
                        text = stringResource(R.string.sharing_title),
                        tone = RxPromptTone.Ghost,
                        onClick = { page = DnsPage.Sharing },
                        modifier = fullWidth,
                        order = 2,
                    )
                }
            }

            DnsPage.Sharing -> SharingPage(
                state = state,
                note = { text ->
                    noteText = text
                    noteTick++
                },
                onSetSharing = onSetSharing,
                onSetMinutes = onSetMinutes,
                onAllow = onAllow,
                onBlock = onBlock,
                onForget = onForget,
                onBack = { page = DnsPage.Main },
            )
        }
    }
}

/**
 * Sharing: the address other devices use, how long it may be used, and what
 * every device that has reached the phone is doing this moment.
 */
@Composable
private fun SharingPage(
    state: DnsModeUiState,
    note: (String) -> Unit,
    onSetSharing: (Boolean) -> Unit,
    onSetMinutes: (Int) -> Unit,
    onAllow: (String, String) -> Unit,
    onBlock: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.sharing_copied)
    val address = state.addresses.firstOrNull()
    val fullWidth = Modifier.fillMaxWidth()

    RxPromptMessage(stringResource(R.string.sharing_title), fontSize = 20)
    Spacer(Modifier.height(10.dp))

    if (state.stopped == "expired") {
        CardNote(stringResource(R.string.sharing_stopped_expired))
        Spacer(Modifier.height(10.dp))
    } else if (state.stopped == "network") {
        CardNote(stringResource(R.string.sharing_stopped_network))
        Spacer(Modifier.height(10.dp))
    } else if (state.stopped == "port") {
        CardNote(stringResource(R.string.sharing_stopped_port))
        Spacer(Modifier.height(10.dp))
    }

    if (address == null) {
        CardNote(stringResource(R.string.sharing_no_network))
    } else if (!state.sharing) {
        CardNote(stringResource(R.string.sharing_about))
    } else {
        CardNote(stringResource(R.string.sharing_how))
        Spacer(Modifier.height(14.dp))
        RxPromptSubject(stringResource(R.string.sharing_address, address))
        Spacer(Modifier.height(8.dp))
        RxPromptSubject(stringResource(R.string.sharing_port, state.port.toString()))
        if (!state.vpnUp) {
            Spacer(Modifier.height(10.dp))
            CardNote(stringResource(R.string.sharing_need_vpn))
        }
    }

    if (state.sharing && address != null) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.sharing_until_ask),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        for (row in SHARING_TIMES.chunked(2)) {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                for (minutes in row) {
                    val label = when (minutes) {
                        0 -> R.string.sharing_until_off
                        30 -> R.string.sharing_until_30
                        120 -> R.string.sharing_until_120
                        else -> R.string.sharing_until_480
                    }
                    RxPromptButton(
                        text = stringResource(label),
                        tone = if (state.minutes == minutes) RxPromptTone.Silver else RxPromptTone.Ghost,
                        onClick = { onSetMinutes(minutes) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (state.until > 0) {
            CardNote(
                stringResource(R.string.sharing_left, timeLeft(state.until))
            )
        }

        Spacer(Modifier.height(6.dp))
        if (state.devices.isEmpty()) {
            CardNote(stringResource(R.string.sharing_devices_none))
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for (device in state.devices) {
                    DeviceCard(
                        modifier = Modifier.fillMaxWidth(),
                        device = device,
                        // With nothing to carry, every device's internet is cut
                        // even while it sits connected to the phone.
                        carry = state.vpnUp,
                        onAllow = { name -> onAllow(device.ip, name) },
                        onBlock = { onBlock(device.ip) },
                        onForget = { onForget(device.ip) },
                    )
                }
            }
        }
    }

    RxPromptButtons {
        if (state.sharing) {
            if (address != null) {
                RxPromptButton(
                    text = stringResource(R.string.sharing_copy),
                    tone = RxPromptTone.Silver,
                    onClick = {
                        clipboard.setText(AnnotatedString("$address:${state.port}"))
                        note(copied)
                    },
                    modifier = fullWidth,
                )
            }
            RxPromptButton(
                text = stringResource(R.string.sharing_turn_off),
                tone = RxPromptTone.MistRed,
                onClick = { onSetSharing(false) },
                modifier = fullWidth,
                order = 1,
            )
        } else {
            RxPromptButton(
                text = stringResource(R.string.sharing_turn_on),
                tone = if (address == null) RxPromptTone.Ghost else RxPromptTone.Silver,
                enabled = address != null,
                onClick = { onSetSharing(true) },
                modifier = fullWidth,
            )
        }
        RxPromptButton(
            text = stringResource(R.string.dns_mode_back),
            tone = RxPromptTone.Ghost,
            onClick = onBack,
            modifier = fullWidth,
            order = 2,
        )
    }
}

/** One device: what it is, what it is doing, and the word on it. */
@Composable
private fun DeviceCard(
    modifier: Modifier = Modifier,
    device: Sharing.Device,
    carry: Boolean,
    onAllow: (String) -> Unit,
    onBlock: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shapeSize = 14.dp
    val hairline = colors.onSurface.copy(alpha = 0.18f)
    var name by remember(device.ip) { mutableStateOf("") }
    val working = device.active > 0
    val quiet = !working && System.currentTimeMillis() - device.lastSeen < 60_000

    val label = when {
        device.waiting -> stringResource(R.string.sharing_device_waiting)
        device.verdict == Sharing.BLOCK -> stringResource(R.string.sharing_state_blocked)
        !carry -> stringResource(R.string.sharing_state_gone)
        working -> stringResource(R.string.sharing_state_working)
        device.lastSeen == 0L -> stringResource(R.string.sharing_state_unseen)
        quiet -> stringResource(R.string.sharing_state_quiet)
        else -> stringResource(R.string.sharing_state_gone)
    }
    val ink = when {
        device.waiting -> colors.primary
        device.verdict == Sharing.BLOCK -> colors.error
        !carry || !working && !quiet && device.lastSeen > 0L -> colors.error
        working -> colors.primary
        else -> colors.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .drawBehind {
                drawRoundRect(
                    color = hairline,
                    cornerRadius = CornerRadius(shapeSize.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = device.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ink,
            )
            if (device.bytes > 0) {
                Text(
                    text = stringResource(R.string.sharing_traffic, device.bytes.toTrafficString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (!working && device.lastSeen > 0 && !device.waiting && device.verdict != Sharing.BLOCK) {
                Text(
                    text = stringResource(R.string.sharing_last_seen, agoOf(device.lastSeen)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (!device.reaching && !device.waiting) {
                Text(
                    text = stringResource(R.string.sharing_no_carry),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                )
            }
        }

        if (device.waiting) {
            Spacer(Modifier.height(8.dp))
            RxPromptMessage(
                text = stringResource(R.string.sharing_device_ask, device.title),
                fontSize = 15,
            )
            Spacer(Modifier.height(8.dp))
            RxPromptField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.sharing_device_name_hint),
                onDone = { onAllow(name) },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                RxPromptButton(
                    text = stringResource(R.string.sharing_allow),
                    tone = RxPromptTone.DarkGreen,
                    onClick = { onAllow(name) },
                    modifier = Modifier.weight(1f),
                )
                RxPromptButton(
                    text = stringResource(R.string.sharing_block),
                    tone = RxPromptTone.MistRed,
                    onClick = onBlock,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (device.verdict != Sharing.ASK) {
            Spacer(Modifier.height(8.dp))
            RxPromptButton(
                text = stringResource(R.string.sharing_forget),
                tone = RxPromptTone.Ghost,
                onClick = onForget,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** How long sharing has left, in the plainest words. */
private fun timeLeft(until: Long): String {
    val millis = (until - System.currentTimeMillis()).coerceAtLeast(0)
    val minutes = millis / 60_000
    val hours = minutes / 60
    return when {
        hours > 0 -> "$hours h ${minutes % 60} min"
        else -> "$minutes min"
    }
}

/** "3 minutes ago", in the phone's own words. */
private fun agoOf(millis: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()

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
