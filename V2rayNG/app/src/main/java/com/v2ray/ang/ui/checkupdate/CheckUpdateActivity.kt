package com.v2ray.ang.ui.checkupdate

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.handler.UpdateRelease
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptButtons
import com.v2ray.ang.ui.compose.RxPromptDialog
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxPromptSubject
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.RxRisingNote
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.VersionInfoBlock
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.util.LogUtil

class CheckUpdateActivity : BaseComponentActivity() {

    private val viewModel: CheckUpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the screen always checks. Nothing is downloaded before the user agrees to the size.
        if (savedInstanceState == null) viewModel.checkForUpdates()
    }

    @Composable
    override fun ScreenContent() {
        CheckUpdateScreen(viewModel = viewModel, onBackClick = { finish() })
    }
}

@Composable
fun CheckUpdateScreen(
    viewModel: CheckUpdateViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val checkPreRelease by viewModel.checkPreRelease.collectAsStateWithLifecycle()
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val installFile by viewModel.installFile.collectAsStateWithLifecycle()
    val noUpdateTick by viewModel.noUpdateTick.collectAsStateWithLifecycle()
    var askSize by remember { mutableStateOf(false) }

    LaunchedEffect(installFile) {
        val apk = installFile ?: return@LaunchedEffect
        try {
            context.startActivity(UpdateNotifier.installIntent(context, apk))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Could not open the installer", e)
        }
        viewModel.onInstallHandled()
    }

    val result = updateResult
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.update_check_for_update),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        },
        // The button never scrolls away: the text above it can be as long as it likes.
        bottomBar = {
            AnimatedVisibility(
                visible = result != null,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                DownloadButton(
                    state = download,
                    onClick = {
                        when (download) {
                            is DownloadState.Ready -> viewModel.install()
                            is DownloadState.Running -> Unit
                            else -> askSize = true
                        }
                    },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                SettingsSwitchItem(
                    icon = painterResource(R.drawable.ic_source_code_24dp),
                    title = stringResource(R.string.update_check_pre_release),
                    checked = checkPreRelease,
                    onCheckedChange = { viewModel.toggleCheckPreRelease(it) }
                )
                SettingsMenuItem(
                    icon = painterResource(R.drawable.ic_check_update_24dp),
                    title = stringResource(R.string.update_check_for_update),
                    onClick = { viewModel.checkForUpdates() }
                )
                if (result != null) {
                    UpdateCard(
                        result = result,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                } else {
                    VersionInfoBlock(versionText = "v${BuildConfig.VERSION_NAME}")
                }
            }
            // The answer to "is there anything new?" when there is not.
            RxRisingNote(
                text = stringResource(R.string.update_none_note),
                tick = noUpdateTick,
                calm = true,
                holdMillis = 2600,
            )
        }
    }

    if (askSize && result != null) {
        RxPromptDialog(onDismissRequest = { askSize = false }, alwaysRtl = false) {
            RxPromptMessage(stringResource(R.string.update_size_ask))
            val size = UpdateRelease.formatSize(result.downloadSize)
            if (size.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                RxPromptSubject(size)
            }
            RxPromptButtons {
                RxPromptButton(
                    text = stringResource(R.string.update_size_yes),
                    tone = RxPromptTone.Silver,
                    onClick = {
                        askSize = false
                        viewModel.startDownload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                RxPromptButton(
                    text = stringResource(R.string.update_size_no),
                    tone = RxPromptTone.Ghost,
                    onClick = { askSize = false },
                    modifier = Modifier.fillMaxWidth(),
                    order = 1,
                )
            }
        }
    }
}

/** The new release: versions on top, then what changed. Only the text scrolls. */
@Composable
private fun UpdateCard(result: CheckUpdateResult, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val shape = RoundedCornerShape(26.dp)
    val enter = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) enter.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val value = enter.value
                translationY = (1f - value) * 70.dp.toPx()
                alpha = value.coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainer)))
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(colors.onSurface.copy(alpha = 0.30f), colors.onSurface.copy(alpha = 0.05f))
                    ),
                ),
                shape,
            )
    ) {
        RxFogBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                text = stringResource(R.string.update_new_version_found, result.latestVersion.orEmpty()),
                style = TextStyle(fontFamily = RxPromptFont, fontSize = 22.sp),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.update_installed_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.update_notes_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = result.releaseNotes.orEmpty().ifBlank { stringResource(R.string.update_notes_empty) },
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                color = colors.onSurface.copy(alpha = 0.9f),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * One button for the whole journey: "download", then it fills like a tank while the percentage
 * and speed run on it, then it becomes "install". A band of light keeps crossing it while idle.
 */
@Composable
private fun DownloadButton(state: DownloadState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "downloadPress",
    )
    val running = state as? DownloadState.Running
    val fill by animateFloatAsState(
        targetValue = when (state) {
            is DownloadState.Running -> state.progress.fraction
            is DownloadState.Ready -> 1f
            else -> 0f
        },
        animationSpec = tween(450),
        label = "downloadFill",
    )
    val sheen = if (animate) {
        rememberInfiniteTransition(label = "download").animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "downloadSheen",
        )
    } else null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val busy = running != null
    val ink = if (busy) colors.onSurface else colors.onPrimary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(shape)
            .background(
                // While downloading the button is an empty tank; otherwise the app's silver.
                if (busy) Brush.linearGradient(listOf(colors.onSurface.copy(alpha = 0.10f), colors.onSurface.copy(alpha = 0.05f)))
                else Brush.linearGradient(listOf(colors.primary, colors.secondary))
            )
            .border(1.dp, colors.onSurface.copy(alpha = 0.28f), shape)
            .drawWithContent {
                if (busy) {
                    // The filled part grows from the side the text starts on.
                    val width = size.width * fill
                    drawRect(
                        brush = Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.55f), colors.secondary.copy(alpha = 0.55f))),
                        topLeft = Offset(if (rtl) size.width - width else 0f, 0f),
                        size = Size(width, size.height),
                    )
                }
                val position = sheen?.value
                if (position != null) {
                    val x = size.width * position
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = if (busy) 0.14f else 0.30f), Color.Transparent),
                            start = Offset(x - size.height, 0f),
                            end = Offset(x + size.height, size.height),
                        )
                    )
                }
                drawContent()
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = when (state) {
                is DownloadState.Running -> 1
                is DownloadState.Ready -> 2
                DownloadState.Failed -> 3
                DownloadState.Idle -> 0
            },
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 })
            },
            label = "downloadLabel",
        ) { phase ->
            when (phase) {
                1 -> {
                    val progress = running?.progress
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${((progress?.fraction ?: fill) * 100).toInt()}%",
                            style = TextStyle(fontFamily = RxPromptFont, fontSize = 20.sp),
                            color = ink,
                        )
                        val speed = UpdateRelease.formatSize(progress?.bytesPerSecond ?: 0L)
                        val total = UpdateRelease.formatSize(progress?.total ?: 0L)
                        val done = UpdateRelease.formatSize(progress?.bytes ?: 0L)
                        Text(
                            text = listOfNotNull(
                                if (total.isNotEmpty() && done.isNotEmpty()) "$done / $total" else null,
                                if (speed.isNotEmpty()) "$speed/s" else null,
                            ).joinToString("   •   "),
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(if (phase == 2) R.drawable.ic_action_done else R.drawable.ic_cloud_download_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = ink,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            when (phase) {
                                2 -> R.string.update_install
                                3 -> R.string.update_retry
                                else -> R.string.update_download
                            }
                        ),
                        style = TextStyle(fontFamily = RxPromptFont, fontSize = 20.sp),
                        color = ink,
                    )
                }
            }
        }
    }
}
