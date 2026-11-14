package com.v2ray.ang.ui.xboxdns

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.XboxDns
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.RxRisingNote
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.ui.compose.rxGlassBrush
import com.v2ray.ang.ui.compose.rxScreenBrush

/** Tests every DNS server the app knows for use with an Xbox. */
class XboxDnsActivity : BaseComponentActivity() {

    private val viewModel: XboxDnsViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        XboxDnsScreen(
            viewModel = viewModel,
            onBack = { finish() },
            onTestWithAccount = { result ->
                startActivity(
                    Intent(this, XboxWebActivity::class.java)
                        .putExtra(XboxWebActivity.EXTRA_PRIMARY, result.address)
                        .putExtra(XboxWebActivity.EXTRA_SECONDARY, result.partner)
                        .putExtra(XboxWebActivity.EXTRA_NAME, result.name)
                )
            },
        )
    }
}

private val RelayGreen = Color(0xFF58C99A)

@Composable
private fun XboxDnsScreen(
    viewModel: XboxDnsViewModel,
    onBack: () -> Unit,
    onTestWithAccount: (XboxDns.Proven) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var noteTick by remember { mutableIntStateOf(0) }
    var noteText by remember { mutableStateOf("") }
    val copiedNote = stringResource(R.string.xbox_dns_copied)
    val copy: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DNS", text))
        noteText = copiedNote
        noteTick++
    }
    val running = state.phase == ScanPhase.Sweeping || state.phase == ScanPhase.Proving

    Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
        RxFogBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = stringResource(R.string.log_back),
                        tint = colors.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.xbox_dns_title),
                    style = TextStyle(fontFamily = RxPromptFont, fontSize = 24.sp),
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.xbox_dns_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                item(key = "progress") {
                    AnimatedVisibility(visible = state.phase != ScanPhase.Idle, enter = fadeIn() + expandVertically()) {
                        ProgressCard(state)
                    }
                }
                val best = state.results.firstOrNull()
                if (best != null) {
                    item(key = "best") {
                        val second = if (best.partner.isNotEmpty()) best.partner else state.results.getOrNull(1)?.address.orEmpty()
                        BestCard(primary = best.address, secondary = second, onCopy = copy)
                    }
                }
                itemsIndexed(state.results, key = { _, result -> result.address }) { index, result ->
                    ResultCard(result = result, order = index, onCopy = copy, onTest = { onTestWithAccount(result) })
                }
                if (state.phase == ScanPhase.Done && state.results.isEmpty()) {
                    item(key = "none") {
                        Text(
                            text = stringResource(R.string.xbox_dns_none),
                            style = TextStyle(fontFamily = RxPromptFont, fontSize = 20.sp),
                            color = colors.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (running) {
                    RxPromptButton(
                        text = stringResource(R.string.xbox_dns_stop),
                        tone = RxPromptTone.MistRed,
                        onClick = { viewModel.stop() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    RxPromptButton(
                        text = stringResource(if (state.phase == ScanPhase.Done) R.string.xbox_dns_again else R.string.xbox_dns_start),
                        tone = RxPromptTone.Silver,
                        onClick = { viewModel.start() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        RxRisingNote(text = noteText, tick = noteTick, calm = true)
    }
}

@Composable
private fun Glass(modifier: Modifier = Modifier, edge: Color? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rxGlassBrush())
            .border(1.dp, edge ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
private fun ProgressCard(state: XboxDnsUiState) {
    val colors = MaterialTheme.colorScheme
    val fraction = when (state.phase) {
        ScanPhase.Sweeping -> if (state.total > 0) state.asked.toFloat() / state.total * 0.8f else 0f
        ScanPhase.Proving -> 0.8f + (if (state.toProve > 0) state.proven.toFloat() / state.toProve else 1f) * 0.2f
        ScanPhase.Done -> 1f
        ScanPhase.Idle -> 0f
    }
    val shown by animateFloatAsState(fraction, tween(400), label = "scanProgress")
    Glass {
        Text(
            text = when (state.phase) {
                ScanPhase.Sweeping -> stringResource(R.string.xbox_dns_sweeping, state.asked.toString(), state.total.toString())
                ScanPhase.Proving -> stringResource(R.string.xbox_dns_proving, state.proven.toString(), state.toProve.toString())
                else -> stringResource(
                    if (state.stopped) R.string.xbox_dns_stopped else R.string.xbox_dns_done,
                    state.results.size.toString(),
                )
            },
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.onSurface.copy(alpha = 0.10f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.onSurface.copy(alpha = 0.85f))
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.xbox_dns_answered, state.answered.toString(), state.results.size.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddressRow(label: String, address: String, onCopy: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(84.dp))
        // Addresses read left to right in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = address,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.xbox_dns_copy),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.onSurface.copy(alpha = 0.09f))
                .clickable { onCopy(address) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BestCard(primary: String, secondary: String, onCopy: (String) -> Unit) {
    Glass(edge = RelayGreen.copy(alpha = 0.6f)) {
        Text(
            text = stringResource(R.string.xbox_dns_best),
            style = TextStyle(fontFamily = RxPromptFont, fontSize = 19.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        AddressRow("Primary", primary, onCopy)
        if (secondary.isNotEmpty()) AddressRow("Secondary", secondary, onCopy)
    }
}

@Composable
private fun ResultCard(result: XboxDns.Proven, order: Int, onCopy: (String) -> Unit, onTest: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val staggered = animate && order < 12
    val appear = remember { Animatable(if (staggered) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (staggered) appear.animateTo(1f, tween(durationMillis = 280, delayMillis = order * 30))
    }
    Glass(
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 18.dp.toPx()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.name.ifEmpty { stringResource(R.string.xbox_dns_nameless) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(if (result.redirects) R.string.xbox_dns_kind_relay else R.string.xbox_dns_kind_plain),
                style = MaterialTheme.typography.labelSmall,
                color = if (result.redirects) RelayGreen else colors.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background((if (result.redirects) RelayGreen else colors.onSurface).copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.xbox_dns_result_line,
                result.passed.toString(),
                XboxDns.PROOF_NAMES.size.toString(),
                result.millis.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        AddressRow("Primary", result.address, onCopy)
        if (result.partner.isNotEmpty()) AddressRow("Secondary", result.partner, onCopy)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.xbox_dns_test_account),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.onSurface.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .clickable(onClick = onTest)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
