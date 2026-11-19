package com.v2ray.ang.ui.publicconfigs

import android.app.Application
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.NetworkOperator
import com.v2ray.ang.handler.PublicConfigs
import com.v2ray.ang.handler.PublicConfigs.Kind
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.rxScreenBrush
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PublicConfigsState(
    val options: PublicConfigs.Options,
    val operator: String = "",
    /** Null while it is being asked; empty when it could not be found. */
    val address: String? = null,
    val provider: String = "",
    val running: Boolean = false,
    /** True from the moment "enough" is pressed until the work has stopped. */
    val stopping: Boolean = false,
    val progress: PublicConfigs.Progress? = null,
    val outcome: PublicConfigs.Outcome? = null,
    /** True when the work was refused because the phone has no line right now. */
    val noLine: Boolean = false,
)

class PublicConfigsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        PublicConfigsState(options = PublicConfigs.savedOptions(), operator = NetworkOperator.current(application))
    )
    val state = _state.asStateFlow()
    private var cancelled = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Anything a previous measurement left behind — a scratch group from a
            // run that was killed, or from the version that drew it as a tab — is
            // cleared when the screen opens.
            PublicConfigs.tidyUp()
            val (address, provider) = PublicConfigs.whoAmI()
            _state.update { it.copy(address = address, provider = provider) }
        }
    }

    fun change(options: PublicConfigs.Options) = _state.update { it.copy(options = options) }

    fun start() {
        if (_state.value.running) return
        val flag = AtomicBoolean(false)
        cancelled = flag
        // Asked before the screen says it is working. With no line there is
        // nothing to measure, so the screen says that instead of counting attempts
        // that never left the phone.
        _state.update { it.copy(running = true, progress = null, outcome = null, noLine = false) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!PublicConfigs.hasLine()) {
                _state.update { it.copy(running = false, noLine = true) }
                return@launch
            }
            val outcome = runCatching {
                PublicConfigs.collect(getApplication(), _state.value.options, flag) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }.getOrElse { PublicConfigs.Outcome(0, PublicConfigs.Origin.Bundled) }
            _state.update { it.copy(running = false, stopping = false, outcome = outcome) }
        }
    }

    /**
     * "Enough": stops trying more servers; those already found are used.
     *
     * The button used to set a flag and say nothing, so it looked dead while the
     * measurement already in flight finished — a few seconds of a screen that
     * ignored the press. The work now stops at the next checkpoint and the screen
     * says so at once.
     */
    fun stop() {
        cancelled.set(true)
        if (_state.value.running) _state.update { it.copy(stopping = true) }
    }
}

/**
 * Free public configs: shows which connection the test runs on, lets the user pick the sorts
 * and the number, collects them, and hands over to the main screen for the real test.
 */
class PublicConfigsActivity : BaseComponentActivity() {

    private val viewModel: PublicConfigsViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val added = state.outcome?.added ?: 0
        LaunchedEffect(added) {
            if (added > 0) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_KEEP, state.options.count))
                finish()
            }
        }
        PublicConfigsScreen(state, viewModel::change, viewModel::start, viewModel::stop) { finish() }
    }

    companion object {
        const val EXTRA_KEEP = "keep"
    }
}

private val WarnAmber = Color(0xFFE0A84A)

private fun kindLabel(kind: Kind) = when (kind) {
    Kind.Vless -> "VLESS"
    Kind.Vmess -> "VMess"
    Kind.Trojan -> "Trojan"
    Kind.Shadowsocks -> "Shadowsocks"
    Kind.Hysteria -> "Hysteria"
    Kind.WireGuard -> "WireGuard"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublicConfigsScreen(
    state: PublicConfigsState,
    onChange: (PublicConfigs.Options) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    // The figure being typed. Held as text so a half-typed number is not rounded
    // or thrown away, and shared with the slider so both show the same value.
    var typedCount by rememberSaveable(state.options.count) {
        mutableStateOf(state.options.count.toString())
    }

    val colors = MaterialTheme.colorScheme
    val options = state.options
    Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
        RxFogBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.log_back), tint = colors.onSurface)
                }
                Text(
                    text = stringResource(R.string.public_title),
                    style = TextStyle(fontFamily = RxPromptFont, fontSize = 24.sp),
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // Fixed line: which connection the servers are tried on.
            val address = when {
                state.address == null -> "…"
                state.address.isEmpty() -> stringResource(R.string.public_ip_unknown)
                else -> state.address
            }
            val operator = if (state.operator == NetworkOperator.WIFI && state.provider.isNotEmpty()) state.provider else state.operator
            Text(
                text = stringResource(R.string.public_test_on, operator, address),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, colors.onSurface.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.public_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.public_warning), style = MaterialTheme.typography.bodyMedium, color = WarnAmber)

                // Said before anything else, because it is the one answer that
                // explains itself: with no line there is nothing to measure, and no
                // number on this screen may move until there is.
                if (state.noLine) {
                    Text(
                        stringResource(R.string.public_no_line),
                        style = MaterialTheme.typography.titleMedium,
                        color = WarnAmber,
                    )
                }

                if (state.running || state.outcome != null) {
                    val progress = state.progress
                    val origin = state.outcome?.origin ?: progress?.origin
                    val wanted = progress?.wanted?.takeIf { it > 0 } ?: options.count.coerceAtLeast(1)
                    val text = when {
                        state.stopping -> stringResource(R.string.public_stopping)
                        // What happened, told truthfully: the number reached, and
                        // when it fell short, why — the pool was tried and did not
                        // answer on this line. "No server could be reached" was
                        // wrong whenever some were found and added.
                        state.outcome != null -> {
                            val got = state.outcome.added
                            if (got <= 0) {
                                stringResource(R.string.public_none)
                            } else if (got >= wanted) {
                                stringResource(R.string.public_reached, got, wanted)
                            } else {
                                stringResource(R.string.public_short, got, wanted)
                            }
                        }
                        progress?.origin == null -> stringResource(R.string.public_step_fetch)
                        // Read the way the question is asked: how many are in
                        // the list, out of how many were asked for, and how much
                        // was tried to get them. The first figure is the tab's own
                        // count — the number of configs really added — so the
                        // screen and the list can never disagree, which they did:
                        // it showed the size of the proof list, sat at zero while
                        // configs were arriving, and left the count to be found on
                        // the other screen.
                        else -> stringResource(
                            R.string.public_step_try,
                            progress.carried,
                            wanted,
                            progress.verified,
                        )
                    }
                    Text(text, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    if (state.running) {
                        // The bar fills with the configs really in the list, against
                        // the number asked for — the same figure as the line above
                        // it, so neither can be the odd one out.
                        if (progress?.origin == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { (progress.carried.toFloat() / wanted).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (origin != null && origin != PublicConfigs.Origin.Fresh) {
                        Text(
                            stringResource(if (origin == PublicConfigs.Origin.Saved) R.string.public_origin_saved else R.string.public_origin_bundled),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarnAmber,
                        )
                    }
                }

                if (!state.running) {
                    Text(stringResource(R.string.public_kinds), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kind.entries.forEach { kind ->
                            Chip(kindLabel(kind), kind in options.kinds) {
                                val kinds = if (kind in options.kinds) options.kinds - kind else options.kinds + kind
                                if (kinds.isNotEmpty()) onChange(options.copy(kinds = kinds))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.public_auto_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.public_count, options.count.toString()), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    // A number to type, above the slider. The slider is for a rough
                    // idea; typing is how a figure is actually chosen, and a slider
                    // that moves by ten across two and a half thousand places
                    // cannot be aimed at all.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.public_count_exact),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = typedCount,
                            onValueChange = { input ->
                                val digits = input.filter { it.isDigit() }.take(4)
                                typedCount = digits
                                digits.toIntOrNull()?.let { typed ->
                                    if (typed > 0) {
                                        onChange(options.copy(count = typed.coerceIn(1, PublicConfigs.MAX_COUNT)))
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    Slider(
                        value = options.count.toFloat(),
                        onValueChange = { raw ->
                            val value = raw.toInt()
                            val step = if (value <= 500) 10 else 100
                            val next = (value / step) * step
                            if (next != options.count) typedCount = next.toString()
                            onChange(options.copy(count = next))
                        },
                        valueRange = 1f..PublicConfigs.MAX_COUNT.toFloat(),
                    )
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (state.running) {
                    RxPromptButton(stringResource(R.string.xbox_dns_stop), RxPromptTone.Silver, onStop, Modifier.fillMaxWidth())
                } else {
                    RxPromptButton(stringResource(R.string.public_start), RxPromptTone.Silver, onStart, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) colors.surface else colors.onSurface,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.onSurface else Color.Transparent)
            .border(1.dp, colors.onSurface.copy(alpha = 0.3f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
