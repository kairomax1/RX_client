package com.v2ray.ang.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxGlassButton
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.util.ExternalLinks
import kotlinx.coroutines.launch

/** The maker's greeting, shown once: the first time the home screen appears. */
internal object WelcomeMessage {
    const val PREF_WELCOME_SHOWN = "pref_welcome_shown"

    fun shouldShow(): Boolean = !MmkvManager.decodeSettingsBool(PREF_WELCOME_SHOWN, false)

    fun markShown() {
        MmkvManager.encodeSettings(PREF_WELCOME_SHOWN, true)
    }
}

private val WELCOME_SHAPE = RoundedCornerShape(28.dp)
private val LINK_SHAPE = RoundedCornerShape(18.dp)

/**
 * Card over a dimmed screen. It rises and settles with a soft spring, its parts follow one after
 * another, and it sinks away when dismissed.
 */
@Composable
internal fun WelcomeMessageOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled()
    val scope = rememberCoroutineScope()
    val appear = remember { Animatable(if (animate) 0f else 1f) }
    val parts = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) {
            launch { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)) }
            parts.animateTo(1f, tween(1100, delayMillis = 180, easing = FastOutSlowInEasing))
        }
    }
    val close: () -> Unit = {
        scope.launch {
            if (animate) appear.animateTo(0f, tween(220))
            onDismiss()
        }
    }
    BackHandler(onBack = close)

    // Each block fades and slides in during its own slice of the shared progress.
    fun Modifier.part(index: Int): Modifier = graphicsLayer {
        val local = ((parts.value - index * 0.16f) / 0.36f).coerceIn(0f, 1f)
        alpha = local
        translationY = (1f - local) * 18.dp.toPx()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.66f))
            // Swallow touches so nothing behind the card can be pressed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(horizontal = 18.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .graphicsLayer {
                    val value = appear.value
                    scaleX = 0.9f + 0.1f * value
                    scaleY = 0.9f + 0.1f * value
                    translationY = (1f - value) * 36.dp.toPx()
                }
                .clip(WELCOME_SHAPE)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.onSurface.copy(alpha = 0.14f), WELCOME_SHAPE)
        ) {
            RxFogBackground(Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.welcome_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.part(0),
                )
                Text(
                    text = stringResource(R.string.welcome_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 14.dp).part(1),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).part(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WelcomeLink(
                        label = stringResource(R.string.about_link_github),
                        iconRes = R.drawable.ic_github_24dp,
                        top = Color(0xFF4A4E58),
                        bottom = Color(0xFF24262B),
                        onClick = { ExternalLinks.open(context, AppConfig.APP_URL) },
                        modifier = Modifier.weight(1f),
                    )
                    WelcomeLink(
                        label = stringResource(R.string.about_link_telegram),
                        iconRes = R.drawable.ic_telegram_24dp,
                        top = Color(0xFF58BDF5),
                        bottom = Color(0xFF1E83C6),
                        onClick = { ExternalLinks.open(context, AppConfig.MAKER_TELEGRAM_URL) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.welcome_closing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 18.dp).part(3),
                )
                RxGlassButton(
                    onClick = close,
                    accent = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp).part(4),
                ) {
                    Text(
                        text = stringResource(R.string.welcome_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeLink(
    label: String,
    iconRes: Int,
    top: Color,
    bottom: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(LINK_SHAPE)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f))),
                LINK_SHAPE,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
