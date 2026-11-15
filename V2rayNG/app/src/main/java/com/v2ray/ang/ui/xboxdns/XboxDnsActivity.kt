package com.v2ray.ang.ui.xboxdns

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.v2ray.ang.ui.compose.rxGlassBrush
import com.v2ray.ang.ui.compose.rxScreenBrush

/**
 * Tests every DNS server the app carries against the names the Xbox app uses and lists the
 * ones that relay them. Ordinary resolvers stay folded away below.
 */
class XboxDnsActivity : BaseComponentActivity() {

    private val viewModel: XboxDnsViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        XboxDnsScreen(viewModel = viewModel, onBack = { finish() })
    }
}

private val RelayGreen = Color(0xFF58C99A)
private val WarnAmber = Color(0xFFE0A84A)

@Composable
private fun XboxDnsScreen(viewModel: XboxDnsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var noteTick by remember { mutableIntStateOf(0) }
    var noteText by remember { mutableStateOf("") }
    var showOrdinary by rememberSaveable { mutableStateOf(false) }
    var purposeName by rememberSaveable { mutableStateOf("") }
    val purpose = XboxDns.Purpose.entries.firstOrNull { it.name == purposeName }
    // The five the scanner was asked for first are chosen from the start.
    var games by rememberSaveable { mutableStateOf("cod,ea,warthunder,roblox") }
    val chosenGames = games.split(',').filter { it.isNotEmpty() }.toSet()
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

            // Stays in place above the list, so results never push it around.
            AnimatedVisibility(visible = state.phase != ScanPhase.Idle, enter = fadeIn() + expandVertically()) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { ProgressCard(state) }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.phase == ScanPhase.Idle) {
                    item(key = "ask") {
                        Text(
                            text = stringResource(R.string.dns_scan_ask),
                            style = TextStyle(fontFamily = RxPromptFont, fontSize = 20.sp),
                            color = colors.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                    item(key = "purposes") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice(stringResource(R.string.dns_scan_xbox), purpose == XboxDns.Purpose.Xbox) { purposeName = XboxDns.Purpose.Xbox.name }
                            Choice(stringResource(R.string.dns_scan_discord), purpose == XboxDns.Purpose.Discord) { purposeName = XboxDns.Purpose.Discord.name }
                            Choice(stringResource(R.string.dns_scan_games), purpose == XboxDns.Purpose.Games) { purposeName = XboxDns.Purpose.Games.name }
                        }
                    }
                    if (purpose == XboxDns.Purpose.Games) {
                        item(key = "gamesTitle") {
                            Text(
                                text = stringResource(R.string.dns_scan_pick_games),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                        items(XboxDns.GAMES, key = { "game-" + it.id }) { game ->
                            Choice(game.label, game.id in chosenGames) {
                                games = (if (game.id in chosenGames) chosenGames - game.id else chosenGames + game.id).joinToString(",")
                            }
                        }
                    }
                    item(key = "intro") {
                        Text(
                            text = stringResource(
                                when (purpose) {
                                    XboxDns.Purpose.Discord -> R.string.dns_scan_intro_discord
                                    XboxDns.Purpose.Games -> R.string.dns_scan_intro_games
                                    else -> R.string.xbox_dns_intro
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    if (state.openAlready.isNotEmpty()) {
                        item(key = "openAlready") {
                            Text(
                                text = stringResource(R.string.dns_scan_open_already, state.openAlready.joinToString("، ")),
                                style = MaterialTheme.typography.bodyMedium,
                                color = RelayGreen,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    item(key = "relaysTitle") {
                        SectionTitle(stringResource(R.string.xbox_dns_relays_title, state.relays.size.toString()), RelayGreen)
                    }
                    items(state.relays, key = { "relay-" + it.address }) { result ->
                        ResultRow(result = result, onCopy = copy)
                    }
                    if (state.phase == ScanPhase.Done && state.relays.isEmpty() && (state.purpose == XboxDns.Purpose.Xbox || state.openAlready.isEmpty())) {
                        item(key = "none") {
                            Text(
                                text = stringResource(if (state.purpose == XboxDns.Purpose.Xbox) R.string.xbox_dns_none else R.string.dns_scan_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                            )
                        }
                    }
                    if (state.relays.isNotEmpty()) {
                        item(key = "how") {
                            Text(
                                text = stringResource(R.string.xbox_dns_how),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    if (state.ordinary.isNotEmpty()) {
                        item(key = "ordinaryToggle") {
                            Text(
                                text = if (showOrdinary) {
                                    stringResource(R.string.xbox_dns_hide_plain)
                                } else {
                                    stringResource(R.string.xbox_dns_show_plain, state.ordinary.size.toString())
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurface,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, colors.onSurface.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                                    .clickable { showOrdinary = !showOrdinary }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        if (showOrdinary) {
                            items(state.ordinary, key = { "plain-" + it.address }) { result ->
                                ResultRow(result = result, onCopy = copy)
                            }
                        }
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RxPromptButton(
                            text = stringResource(if (state.phase == ScanPhase.Done) R.string.xbox_dns_again else R.string.xbox_dns_start),
                            tone = RxPromptTone.Silver,
                            onClick = {
                                val wanted = if (state.phase == ScanPhase.Done) state.purpose else purpose
                                if (wanted != null) viewModel.start(wanted, chosenGames)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.phase == ScanPhase.Done || (purpose != null && (purpose != XboxDns.Purpose.Games || chosenGames.isNotEmpty())),
                        )
                        if (state.phase == ScanPhase.Done) {
                            RxPromptButton(
                                text = stringResource(R.string.dns_scan_other),
                                tone = RxPromptTone.Silver,
                                onClick = { viewModel.reset() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        RxRisingNote(text = noteText, tick = noteTick, calm = true)
    }
}

/** One line to pick: filled when chosen. */
@Composable
private fun Choice(text: String, chosen: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (chosen) colors.surface else colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (chosen) colors.onSurface else Color.Transparent)
            .border(1.dp, colors.onSurface.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text = text,
        style = TextStyle(fontFamily = RxPromptFont, fontSize = 19.sp),
        color = color,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun Glass(modifier: Modifier = Modifier, edge: Color? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rxGlassBrush())
            .border(1.dp, edge ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
                    state.relays.size.toString(),
                )
            },
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.xbox_dns_answered, state.answered.toString(), state.relays.size.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddressLine(address: String, onCopy: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
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
                .clip(RoundedCornerShape(10.dp))
                .background(colors.onSurface.copy(alpha = 0.09f))
                .clickable { onCopy(address) }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** One server, or the two addresses of one service, in a few lines. */
@Composable
private fun ResultRow(result: XboxDns.Proven, onCopy: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val inIran = result.country == "IR"
    Glass(edge = if (result.redirects) (if (inIran) WarnAmber else RelayGreen).copy(alpha = 0.45f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.name.ifEmpty { stringResource(R.string.xbox_dns_nameless) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (result.redirects) {
                    stringResource(
                        R.string.xbox_dns_result_line,
                        result.passed.toString(),
                        XboxDns.APP_NAMES.size.toString(),
                        result.millis.toString(),
                    )
                } else {
                    stringResource(R.string.xbox_dns_plain_line, result.millis.toString())
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (result.redirects && result.country.isNotEmpty()) {
            Text(
                text = if (inIran) stringResource(R.string.xbox_dns_country_bad) else stringResource(R.string.xbox_dns_country, result.country),
                style = MaterialTheme.typography.labelSmall,
                color = if (inIran) WarnAmber else RelayGreen,
            )
        }
        if (result.services.isNotEmpty()) {
            Text(
                text = stringResource(R.string.dns_scan_opens, result.services.joinToString("، ")),
                style = MaterialTheme.typography.labelMedium,
                color = RelayGreen,
            )
        }
        AddressLine(result.address, onCopy)
        if (result.partner.isNotEmpty()) AddressLine(result.partner, onCopy)
    }
}
