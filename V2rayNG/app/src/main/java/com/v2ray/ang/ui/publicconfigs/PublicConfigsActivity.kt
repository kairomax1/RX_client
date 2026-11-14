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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
    val progress: PublicConfigs.Progress? = null,
    val outcome: PublicConfigs.Outcome? = null,
)

class PublicConfigsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        PublicConfigsState(options = PublicConfigs.savedOptions(), operator = NetworkOperator.current(application))
    )
    val state = _state.asStateFlow()
    private var cancelled = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val (address, provider) = PublicConfigs.whoAmI()
            _state.update { it.copy(address = address, provider = provider) }
        }
    }

    fun change(options: PublicConfigs.Options) = _state.update { it.copy(options = options) }

    fun start() {
        if (_state.value.running) return
        val flag = AtomicBoolean(false)
        cancelled = flag
        _state.update { it.copy(running = true, progress = null, outcome = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                PublicConfigs.collect(getApplication(), _state.value.options, flag) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }.getOrElse { PublicConfigs.Outcome(0, PublicConfigs.Origin.Bundled) }
            _state.update { it.copy(running = false, outcome = outcome) }
        }
    }

    /** "Enough": stops trying more servers; those already found are used. */
    fun stop() = cancelled.set(true)
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

                if (state.running || state.outcome != null) {
                    val progress = state.progress
                    val origin = state.outcome?.origin ?: progress?.origin
                    val text = when {
                        state.outcome != null -> stringResource(R.string.public_none)
                        progress?.origin == null -> stringResource(R.string.public_step_fetch)
                        else -> stringResource(R.string.public_step_try, progress.tried.toString(), progress.found.toString(), progress.reachable.toString())
                    }
                    Text(text, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    if (state.running) {
                        val wanted = (options.count * 3).coerceAtLeast(1)
                        if (progress?.origin == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { (progress.reachable.toFloat() / wanted).coerceIn(0f, 1f) },
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Mux", options.mux) { onChange(options.copy(mux = !options.mux)) }
                        Chip("Fragment", options.fragment) { onChange(options.copy(fragment = !options.fragment)) }
                        Chip(stringResource(R.string.public_game), options.game) { onChange(options.copy(game = !options.game)) }
                    }
                    Text(stringResource(R.string.public_count, options.count.toString()), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Slider(
                        value = options.count.toFloat(),
                        onValueChange = { onChange(options.copy(count = (it / 10).toInt() * 10)) },
                        valueRange = 10f..PublicConfigs.MAX_COUNT.toFloat(),
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
