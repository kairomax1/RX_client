package com.v2ray.ang.ui.applog

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.RxRisingNote
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.ui.compose.rxGlassBorder
import com.v2ray.ang.ui.compose.rxGlassBrush
import com.v2ray.ang.ui.compose.rxScreenBrush
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The app's own log, sorted by the part of the app each line came from. */
class AppLogActivity : BaseComponentActivity() {

    private val viewModel: AppLogViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        AppLogScreen(viewModel = viewModel, onBack = { finish() })
    }
}

private val SoftRed = Color(0xFFE5737F)

@Composable
private fun sectionLabel(section: AppLog.Section?): String = stringResource(
    when (section) {
        null -> R.string.log_section_all
        AppLog.Section.Connection -> R.string.log_section_connection
        AppLog.Section.Core -> R.string.log_section_core
        AppLog.Section.Aether -> R.string.log_section_aether
        AppLog.Section.DnsMode -> R.string.log_section_dns_mode
        AppLog.Section.Test -> R.string.log_section_test
        AppLog.Section.Subscription -> R.string.log_section_subscription
        AppLog.Section.Update -> R.string.log_section_update
        AppLog.Section.Other -> R.string.log_section_other
    }
)

@Composable
fun AppLogScreen(viewModel: AppLogViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteTick by remember { mutableIntStateOf(0) }
    var noteText by remember { mutableStateOf("") }
    val emptyNote = stringResource(R.string.log_file_nothing)
    val clearedNote = stringResource(R.string.log_cleared)
    val shareTitle = stringResource(R.string.log_make_file)

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
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.drawer_app_log),
                    style = TextStyle(fontFamily = RxPromptFont, fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // What to show: the main steps, only the failures, or everything.
            ChipRow {
                AppLog.Mode.entries.forEach { mode ->
                    LogChip(
                        text = stringResource(
                            when (mode) {
                                AppLog.Mode.Normal -> R.string.log_mode_normal
                                AppLog.Mode.Errors -> R.string.log_mode_errors
                                AppLog.Mode.Debug -> R.string.log_mode_debug
                            }
                        ),
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // One tab per part of the app, with the number of lines it has in this mode.
            ChipRow {
                (listOf<AppLog.Section?>(null) + AppLog.Section.entries).forEach { section ->
                    val count = state.counts[section] ?: 0
                    LogChip(
                        text = sectionLabel(section) + if (count > 0) "  $count" else "",
                        selected = state.section == section,
                        onClick = { viewModel.setSection(section) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = state.section to state.mode,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "logTab",
                ) { _ ->
                    if (state.loaded && state.lines.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            RxPromptMessage(
                                stringResource(
                                    if (state.mode == AppLog.Mode.Errors) R.string.log_empty_errors else R.string.log_empty
                                ),
                                fontSize = 20,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(state.lines, key = { index, entry -> "${entry.timeMillis}-${state.lines.size - index}" }) { index, entry ->
                                LogLine(entry = entry, order = index, showSection = state.section == null)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RxPromptButton(
                    text = stringResource(R.string.log_make_file),
                    tone = RxPromptTone.Silver,
                    onClick = {
                        scope.launch {
                            val file = viewModel.buildFile()
                            if (file == null) {
                                noteText = emptyNote
                                noteTick++
                                return@launch
                            }
                            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.cache", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }
                    },
                    modifier = Modifier.weight(1.6f),
                )
                RxPromptButton(
                    text = stringResource(R.string.log_clear),
                    tone = RxPromptTone.MistRed,
                    onClick = {
                        viewModel.clear()
                        noteText = clearedNote
                        noteTick++
                    },
                    modifier = Modifier.weight(1f),
                    order = 1,
                )
            }
        }
        RxRisingNote(text = noteText, tick = noteTick, calm = true)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun LogChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val fill by animateColorAsState(
        if (selected) colors.onSurface.copy(alpha = 0.92f) else colors.onSurface.copy(alpha = 0.07f),
        tween(220),
        label = "chipFill",
    )
    val ink by animateColorAsState(if (selected) colors.surface else colors.onSurface, tween(220), label = "chipInk")
    Text(
        text = text,
        color = ink,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, colors.onSurface.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun LogLine(entry: AppLog.Entry, order: Int, showSection: Boolean) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    // The first screenful arrives one line after the other; lines further down are just there.
    val staggered = animate && order < 14
    val appear = remember { Animatable(if (staggered) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (staggered) appear.animateTo(1f, tween(durationMillis = 260, delayMillis = order * 28))
    }
    val isError = entry.level == AppLog.Level.Error
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx()
            }
            .clip(shape)
            .background(rxGlassBrush())
            .border(1.dp, if (isError) SoftRed.copy(alpha = 0.55f) else colors.onSurface.copy(alpha = 0.10f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clock.format(Date(entry.timeMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            if (showSection) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sectionLabel(entry.section),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.onSurface.copy(alpha = 0.09f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (isError) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.log_tag_error),
                    style = MaterialTheme.typography.labelSmall,
                    color = SoftRed,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Log text is technical and Latin: always left to right, in a fixed-width face.
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                textDirection = androidx.compose.ui.text.style.TextDirection.Ltr,
            ),
            color = if (entry.level == AppLog.Level.Debug) colors.onSurfaceVariant else colors.onSurface,
            textAlign = TextAlign.Left,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
