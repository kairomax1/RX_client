package com.v2ray.ang.ui.backup

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptButtons
import com.v2ray.ang.ui.compose.RxPromptDialog
import com.v2ray.ang.ui.compose.RxPromptField
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxPromptTone
import com.v2ray.ang.ui.compose.RxRisingNote
import com.v2ray.ang.ui.compose.RxTypedMessage
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.util.BackupCipher
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Where the backup and restore conversation currently is. */
internal sealed interface BackupStep {
    data object Idle : BackupStep

    /** Asking for the password of a new backup. */
    data object BackupPassword : BackupStep

    /** The greeting that types itself and then opens the file picker. */
    data object RestoreHello : BackupStep

    /** Asking for the password of the picked file. [wrongTick] replays the "wrong" note. */
    data class RestorePassword(val file: File, val wrongCount: Int = 0, val wrongTick: Int = 0) : BackupStep

    /** Three wrong passwords: this attempt is over. */
    data object RestoreGaveUp : BackupStep

    data class RestoreDone(val wasLocked: Boolean) : BackupStep
}

/** Wrong passwords allowed before the attempt is closed. The file itself is never locked. */
internal const val MAX_WRONG_PASSWORDS = 3

/** What a wrong password leads to: asking again, or giving up after the third one. */
internal fun afterWrongPassword(step: BackupStep.RestorePassword): BackupStep {
    val wrong = step.wrongCount + 1
    return if (wrong >= MAX_WRONG_PASSWORDS) {
        BackupStep.RestoreGaveUp
    } else {
        step.copy(wrongCount = wrong, wrongTick = step.wrongTick + 1)
    }
}

class BackupActivity : HelperBaseComponentActivity() {

    private val viewModel: BackupViewModel by viewModels()

    private var step by mutableStateOf<BackupStep>(BackupStep.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewModelEvent.collect { event ->
                    when (event) {
                        is BackupViewModel.BackupViewModelEvent.ExportLocal ->
                            handleExportLocal(event.cachePath, event.targetUri)

                        is BackupViewModel.BackupViewModelEvent.RestorePasswordRequired ->
                            step = BackupStep.RestorePassword(event.cachedFile)

                        is BackupViewModel.BackupViewModelEvent.RestorePasswordMismatch -> {
                            val current = step as? BackupStep.RestorePassword
                                ?: BackupStep.RestorePassword(event.cachedFile)
                            val next = afterWrongPassword(current)
                            if (next is BackupStep.RestoreGaveUp) viewModel.cancelPendingRestore()
                            step = next
                        }

                        is BackupViewModel.BackupViewModelEvent.RestoreSuccess -> {
                            SettingsManager.initApp(this@BackupActivity)
                            step = BackupStep.RestoreDone(event.wasLocked)
                        }

                        is BackupViewModel.BackupViewModelEvent.RestoreFailed -> {
                            step = BackupStep.Idle
                            toastError(R.string.toast_restore_failed)
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        BackupScreen(
            isLoadingState = viewModel.isLoading,
            step = step,
            onBackupClick = { step = BackupStep.BackupPassword },
            onRestoreClick = { step = BackupStep.RestoreHello },
            onBackupPassword = { password ->
                step = BackupStep.Idle
                backupViaLocal(password)
            },
            onRestoreHelloDone = {
                step = BackupStep.Idle
                restoreViaLocal()
            },
            onRestorePassword = { file, password ->
                viewModel.restoreConfiguration(cacheDir, file, password)
            },
            onClose = {
                if (step is BackupStep.RestorePassword) viewModel.cancelPendingRestore()
                step = BackupStep.Idle
            },
            onBackClick = { finish() }
        )
    }

    private fun handleExportLocal(cachePath: String, targetUri: Uri) {
        try {
            contentResolver.openOutputStream(targetUri)?.use { output ->
                File(cachePath).inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            File(cachePath).delete()
            toastSuccess(R.string.backup_saved)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to copy backup to Uri", e)
            toastError(R.string.toast_failure)
        }
    }

    private fun backupViaLocal(password: String) {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.US
        ).format(System.currentTimeMillis())
        val defaultFileName = "${getString(R.string.app_name)}_${dateFormatted}.${BackupCipher.EXTENSION}"

        launchCreateDocument(defaultFileName) { uri ->
            if (uri != null) {
                viewModel.prepareBackupForUri(cacheDir, getString(R.string.app_name), uri, password)
            }
        }
    }

    private fun restoreViaLocal() {
        launchFileChooser(mimeType = "*/*") { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            try {
                val targetFile = File(cacheDir, "restore_${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri).use { input ->
                    targetFile.outputStream().use { fileOut ->
                        input?.copyTo(fileOut)
                    }
                }
                viewModel.restoreConfiguration(cacheDir, targetFile, null)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error during file restore", e)
                toastError(R.string.toast_failure)
            }
        }
    }
}

@Composable
internal fun BackupScreen(
    isLoadingState: StateFlow<Boolean>,
    step: BackupStep,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onBackupPassword: (String) -> Unit,
    onRestoreHelloDone: () -> Unit,
    onRestorePassword: (File, String) -> Unit,
    onClose: () -> Unit,
    onBackClick: () -> Unit
) {
    val isLoading by isLoadingState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_configuration_backup_restore),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupTile(
                icon = painterResource(R.drawable.ic_backup_24dp),
                title = stringResource(R.string.backup_file_title),
                description = stringResource(R.string.backup_file_desc),
                order = 0,
                onClick = onBackupClick,
            )
            BackupTile(
                icon = painterResource(R.drawable.ic_restore_24dp),
                title = stringResource(R.string.restore_file_title),
                description = stringResource(R.string.restore_file_desc),
                order = 1,
                onClick = onRestoreClick,
            )
            NavigationBarsSpacer()
        }
    }

    when (step) {
        BackupStep.Idle -> Unit

        BackupStep.BackupPassword -> PasswordPrompt(
            message = stringResource(R.string.backup_pass_ask),
            placeholder = stringResource(R.string.prompt_write_hint),
            busy = isLoading,
            onSubmit = onBackupPassword,
            onCancel = onClose,
        )

        BackupStep.RestoreHello -> RestoreHelloPrompt(onDone = onRestoreHelloDone, onCancel = onClose)

        is BackupStep.RestorePassword -> PasswordPrompt(
            message = stringResource(R.string.restore_pass_ask),
            placeholder = stringResource(R.string.prompt_dots_hint),
            busy = isLoading,
            wrongTick = step.wrongTick,
            onSubmit = { password -> onRestorePassword(step.file, password) },
            onCancel = onClose,
        )

        BackupStep.RestoreGaveUp -> ResultPrompt(
            message = stringResource(R.string.restore_give_up),
            button = stringResource(R.string.restore_give_up_ok),
            capsule = false,
            onClose = onClose,
        )

        is BackupStep.RestoreDone -> ResultPrompt(
            message = stringResource(if (step.wasLocked) R.string.restore_ok_locked else R.string.restore_ok_plain),
            button = stringResource(if (step.wasLocked) R.string.restore_ok_locked_btn else R.string.restore_ok_plain_btn),
            capsule = true,
            onClose = onClose,
        )
    }
}

/** One of the two big choices of the screen: a floating card that drifts in and sinks on press. */
@Composable
private fun BackupTile(
    icon: Painter,
    title: String,
    description: String,
    order: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val shape = RoundedCornerShape(26.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "backupTilePress",
    )
    val enter = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) {
            delay(90L + 130L * order)
            enter.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val value = enter.value
                translationY = (1f - value) * 60.dp.toPx()
                alpha = value.coerceIn(0f, 1f)
                scaleX = press
                scaleY = press
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
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    ) {
        RxFogBackground(Modifier.matchParentSize())
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(colors.primary, colors.secondary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = colors.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Password card, shared by backup and restore. An empty password is refused with a shake; a wrong
 * one ([wrongTick] changes) empties the box and sends the note up from the bottom of the screen.
 */
@Composable
private fun PasswordPrompt(
    message: String,
    placeholder: String,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    wrongTick: Int = 0,
) {
    var password by remember { mutableStateOf("") }
    var emptyShakes by remember { mutableStateOf(0) }
    LaunchedEffect(wrongTick) {
        if (wrongTick > 0) password = ""
    }
    val submit: () -> Unit = {
        if (password.isEmpty()) {
            emptyShakes++
        } else if (!busy) {
            onSubmit(password)
        }
    }
    RxPromptDialog(
        onDismissRequest = onCancel,
        dismissOnOutsideTap = false,
        overlay = { RxRisingNote(stringResource(R.string.restore_wrong), wrongTick) },
    ) {
        RxPromptMessage(message)
        Spacer(Modifier.height(18.dp))
        RxPromptField(
            value = password,
            onValueChange = { password = it },
            placeholder = placeholder,
            shakeTick = emptyShakes + wrongTick,
            onDone = submit,
        )
        RxPromptButtons {
            RxPromptButton(
                text = stringResource(R.string.prompt_do_it),
                tone = RxPromptTone.Silver,
                onClick = submit,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            RxPromptButton(
                text = stringResource(R.string.prompt_cancel),
                tone = RxPromptTone.Ghost,
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                order = 1,
            )
        }
    }
}

/** Types its greeting, waits a second and moves on to the file picker by itself. */
@Composable
private fun RestoreHelloPrompt(onDone: () -> Unit, onCancel: () -> Unit) {
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(typed) {
        if (typed) {
            delay(1000)
            onDone()
        }
    }
    RxPromptDialog(onDismissRequest = onCancel, dismissOnOutsideTap = false) {
        RxTypedMessage(
            text = stringResource(R.string.restore_hello),
            fontSize = 25,
            onFinished = { typed = true },
        )
    }
}

/** A closing word with a single button; [capsule] gives the button fully round ends. */
@Composable
private fun ResultPrompt(message: String, button: String, capsule: Boolean, onClose: () -> Unit) {
    RxPromptDialog(onDismissRequest = onClose) {
        RxPromptMessage(message)
        RxPromptButtons {
            if (capsule) {
                RxPromptButton(
                    text = button,
                    tone = RxPromptTone.Silver,
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                )
            } else {
                RxPromptButton(
                    text = button,
                    tone = RxPromptTone.Ghost,
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
