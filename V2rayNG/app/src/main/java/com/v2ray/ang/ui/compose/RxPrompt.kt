package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import kotlinx.coroutines.delay

/**
 * The app's own way of asking and telling: a card that rises from the bottom of the screen and
 * settles in the middle, written in the display face, with buttons that arrive one after another
 * and sink when pressed. Every conversational prompt (naming a subscription, deleting a config,
 * the backup and restore steps) is built from the pieces in this file so they feel like one voice.
 *
 * These prompts speak Persian in every app language, so the card is always laid out right to left.
 */
val RxPromptFont = FontFamily(Font(R.font.lalezar_regular))

private val PROMPT_SHAPE = RoundedCornerShape(30.dp)
private val PROMPT_BUTTON_SHAPE = RoundedCornerShape(20.dp)

/** Colour personality of a prompt button. */
enum class RxPromptTone {
    /** The app's white-to-black silver: the plain "go on". */
    Silver,

    /** Soft, foggy red; present without shouting. */
    MistRed,

    /** Black with green in it. */
    DarkGreen,

    /** Outline only: the quiet way out. */
    Ghost,
}

/** Splits text into the pieces a typewriter shows one at a time; a surrogate pair stays whole. */
internal fun rxTypingUnits(text: String): List<String> {
    val units = ArrayList<String>(text.length)
    var index = 0
    while (index < text.length) {
        val end = index + Character.charCount(text.codePointAt(index))
        units.add(text.substring(index, end))
        index = end
    }
    return units
}

/**
 * Full-window dialog holding one prompt card. [overlay] is drawn over the whole window, above the
 * card, for notes that travel across the screen.
 */
@Composable
fun RxPromptDialog(
    onDismissRequest: () -> Unit,
    dismissOnOutsideTap: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val animate = rememberAnimationsEnabled()
    val rise = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (rise.value < 1f) {
            rise.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessLow))
        }
    }
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContentColor provides colors.onSurface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = dismissOnOutsideTap,
                        onClick = onDismissRequest,
                    )
                    .systemBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .graphicsLayer {
                            // From below the screen up to the middle, tipping upright as it lands.
                            val value = rise.value
                            translationY = (1f - value) * size.height.coerceAtLeast(600f) * 1.4f
                            rotationX = (1f - value).coerceIn(0f, 1f) * 22f
                            alpha = (value * 1.6f).coerceIn(0f, 1f)
                            cameraDistance = 14f * density
                        }
                        .clip(PROMPT_SHAPE)
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.surfaceContainerHighest, colors.surfaceContainer)
                            )
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        colors.onSurface.copy(alpha = 0.38f),
                                        colors.onSurface.copy(alpha = 0.06f),
                                    )
                                ),
                            ),
                            PROMPT_SHAPE,
                        )
                        // Taps on the card must not fall through to the dismissing backdrop.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                ) {
                    RxFogBackground(Modifier.matchParentSize())
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content,
                    )
                }
                overlay()
            }
        }
    }
}

/** The line the card says, in the display face. */
@Composable
fun RxPromptMessage(text: String, modifier: Modifier = Modifier, fontSize: Int = 23) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = TextStyle(
            fontFamily = RxPromptFont,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.5f).sp,
            textAlign = TextAlign.Center,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * [text] typed out calmly, each character fading in. [onFinished] fires once the last one is in.
 * With animations off the whole line is simply there.
 */
@Composable
fun RxTypedMessage(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 23,
    charMillis: Int = 85,
    onFinished: () -> Unit = {},
) {
    val animate = rememberAnimationsEnabled()
    val units = remember(text) { rxTypingUnits(text) }
    val progress = remember(text) { Animatable(if (animate) 0f else units.size.toFloat()) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(text) {
        if (progress.value < units.size) {
            delay(260)
            progress.animateTo(units.size.toFloat(), tween(units.size * charMillis, easing = LinearEasing))
        }
        finished()
    }
    val ink = MaterialTheme.colorScheme.onSurface
    Text(
        text = typedAnnotated(units, progress.value, ink),
        modifier = modifier.fillMaxWidth(),
        style = TextStyle(
            fontFamily = RxPromptFont,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.5f).sp,
            textAlign = TextAlign.Center,
        ),
    )
}

/**
 * The whole line is always laid out, with the part not typed yet left invisible, so the card
 * keeps its size and the words do not jump as they arrive.
 */
private fun typedAnnotated(units: List<String>, progress: Float, ink: Color): AnnotatedString =
    buildAnnotatedString {
        val done = progress.toInt().coerceIn(0, units.size)
        withStyle(SpanStyle(color = ink)) { for (i in 0 until done) append(units[i]) }
        if (done < units.size) {
            withStyle(SpanStyle(color = ink.copy(alpha = (progress - done).coerceIn(0f, 1f)))) {
                append(units[done])
            }
            withStyle(SpanStyle(color = Color.Transparent)) {
                for (i in done + 1 until units.size) append(units[i])
            }
        }
    }

/** A name the prompt is talking about, set apart on its own plate. */
@Composable
fun RxPromptSubject(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Text(
        text = text,
        modifier = modifier
            .clip(shape)
            .background(colors.onSurface.copy(alpha = 0.08f))
            .border(1.dp, colors.onSurface.copy(alpha = 0.16f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Square-cornered box to write in. It takes focus as soon as it appears. */
@Composable
fun RxPromptField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shakeTick: Int = 0,
    onDone: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val shape = RoundedCornerShape(6.dp)
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(350)
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0 && animate) {
            for (offset in listOf(-12f, 12f, -8f, 8f, -4f, 0f)) shake.animateTo(offset, tween(55))
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = colors.onSurface,
            fontSize = 19.sp,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(colors.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .focusRequester(focus),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .clip(shape)
                    .background(colors.onSurface.copy(alpha = 0.06f))
                    .border(1.5.dp, colors.onSurface.copy(alpha = 0.34f), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = TextStyle(fontFamily = RxPromptFont, fontSize = 19.sp),
                        color = colors.onSurface.copy(alpha = 0.42f),
                    )
                }
                inner()
            }
        },
    )
}

/**
 * Button of a prompt. It arrives a moment after the one before it ([order]), sinks and springs
 * back under the finger, and ticks the phone when pressed.
 */
@Composable
fun RxPromptButton(
    text: String,
    tone: RxPromptTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    order: Int = 0,
    shape: Shape = PROMPT_BUTTON_SHAPE,
) {
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "promptPress",
    )
    val arrive = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (arrive.value < 1f) {
            delay(240L + 110L * order)
            arrive.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val (fill, edge, ink) = when (tone) {
        RxPromptTone.Silver -> Triple(
            Brush.linearGradient(listOf(colors.primary, colors.secondary)),
            colors.primary.copy(alpha = 0.6f),
            colors.onPrimary,
        )

        RxPromptTone.MistRed -> Triple(
            Brush.linearGradient(listOf(Color(0xFFB98488), Color(0xFF93595E))),
            Color(0xFFD9AEB1).copy(alpha = 0.55f),
            Color(0xFFFFF4F4),
        )

        RxPromptTone.DarkGreen -> Triple(
            Brush.linearGradient(listOf(Color(0xFF163126), Color(0xFF0A1711))),
            Color(0xFF3F7A5E).copy(alpha = 0.8f),
            Color(0xFFDDF5E8),
        )

        RxPromptTone.Ghost -> Triple(
            Brush.linearGradient(listOf(colors.onSurface.copy(alpha = 0.07f), colors.onSurface.copy(alpha = 0.03f))),
            colors.onSurface.copy(alpha = 0.3f),
            colors.onSurface,
        )
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                val value = arrive.value
                val scale = (0.82f + 0.18f * value) * press
                scaleX = scale
                scaleY = scale
                alpha = value.coerceIn(0f, 1f) * if (enabled) 1f else 0.45f
                translationY = (1f - value) * 20.dp.toPx()
            }
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = RxPromptFont, fontSize = 18.sp, textAlign = TextAlign.Center),
            color = ink,
        )
    }
}

/** Buttons of a prompt, one under the other, full width. */
@Composable
fun RxPromptButtons(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * A short note that travels from the bottom of the window to its middle, stays a moment and
 * fades. Shown again each time [tick] changes; nothing is shown while it is 0.
 */
@Composable
fun BoxScope.RxRisingNote(text: String, tick: Int) {
    if (tick <= 0) return
    val animate = rememberAnimationsEnabled()
    val travel = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        travel.snapTo(if (animate) 0f else 1f)
        fade.snapTo(1f)
        travel.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        delay(1500)
        fade.animateTo(0f, tween(380))
    }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .graphicsLayer {
                translationY = (1f - travel.value) * 520.dp.toPx()
                val scale = 0.8f + 0.2f * travel.value
                scaleX = scale
                scaleY = scale
                alpha = fade.value
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFFC0696F), Color(0xFF8A3E45))))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .padding(horizontal = 26.dp, vertical = 16.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = RxPromptFont, fontSize = 22.sp),
            color = Color.White,
        )
    }
}
