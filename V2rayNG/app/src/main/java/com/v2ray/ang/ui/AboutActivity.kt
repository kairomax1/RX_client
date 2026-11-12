package com.v2ray.ang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.rememberAnimationsEnabled
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.delay

private const val MAKER_VISIBLE_MS = 3_000L
private val LINK_SHAPE = RoundedCornerShape(24.dp)
private val LINK_DEPTH = 5.dp

class AboutActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        AboutScreen(onBackClick = { finish() })
    }
}

/** One of the maker's channels. The address stays behind the label and is never shown. */
private class MakerLink(
    val labelRes: Int,
    val iconRes: Int,
    val url: String,
    val top: Color,
    val bottom: Color,
    val lip: Color,
)

private val makerLinks = listOf(
    MakerLink(
        R.string.about_link_github, R.drawable.ic_github_24dp, AppConfig.MAKER_GITHUB_URL,
        Color(0xFF4A4E58), Color(0xFF24262B), Color(0xFF0E0F11),
    ),
    MakerLink(
        R.string.about_link_telegram, R.drawable.ic_telegram_24dp, AppConfig.MAKER_TELEGRAM_URL,
        Color(0xFF58BDF5), Color(0xFF1E83C6), Color(0xFF0F4F7C),
    ),
    MakerLink(
        R.string.about_link_youtube, R.drawable.ic_youtube_24dp, AppConfig.MAKER_YOUTUBE_URL,
        Color(0xFFFF6464), Color(0xFFC62222), Color(0xFF7A1111),
    ),
)

@Composable
fun AboutScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var showMaker by remember { mutableStateOf(false) }

    // The maker line closes by itself.
    LaunchedEffect(showMaker) {
        if (showMaker) {
            delay(MAKER_VISIBLE_MS)
            showMaker = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_about),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = stringResource(R.string.about_app_source),
                onClick = { Utils.openUri(context, AppConfig.APP_URL) }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_person_24dp),
                title = stringResource(R.string.about_maker),
                onClick = { showMaker = !showMaker }
            )
            AnimatedVisibility(
                visible = showMaker,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) +
                        fadeIn(tween(260)),
                exit = shrinkVertically(tween(260)) + fadeOut(tween(200)),
            ) {
                Text(
                    text = stringResource(R.string.about_maker_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(36.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    makerLinks.forEachIndexed { index, link ->
                        MakerLinkButton(
                            link = link,
                            order = index,
                            onClick = { Utils.openUri(context, link.url) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            NavigationBarsSpacer()
        }
    }
}

/**
 * A raised, pill-shaped button: lit from above, with a darker lip underneath that it sinks into
 * while pressed. Each one pops in a moment after the previous.
 */
@Composable
private fun MakerLinkButton(
    link: MakerLink,
    order: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = rememberAnimationsEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateDpAsState(
        targetValue = if (pressed) 0.dp else LINK_DEPTH,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh),
        label = "lift",
    )
    val appear = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        delay(120L + order * 90L)
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }

    Box(
        modifier = modifier
            .height(48.dp + LINK_DEPTH)
            .graphicsLayer {
                val value = appear.value
                alpha = value.coerceIn(0f, 1f)
                scaleX = 0.7f + 0.3f * value
                scaleY = 0.7f + 0.3f * value
            }
    ) {
        // The lip the button rests on.
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .offset(y = LINK_DEPTH)
                .clip(LINK_SHAPE)
                .background(link.lip)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .offset(y = LINK_DEPTH - lift)
                .clip(LINK_SHAPE)
                .background(Brush.verticalGradient(listOf(link.top, link.bottom)))
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.04f))
                    ),
                    LINK_SHAPE,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                painter = painterResource(link.iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(link.labelRes),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
