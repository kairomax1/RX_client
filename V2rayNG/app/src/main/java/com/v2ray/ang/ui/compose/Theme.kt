package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Light side of the same product: cool paper, graphite ink, silver edges. No brand hue of its own,
// so the RX mark and the connect button read the same way they do in the dark.
private val LightColor = lightColorScheme(
    primary = Color(0xFF1F2530),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE2EA),
    onPrimaryContainer = Color(0xFF141920),
    secondary = Color(0xFF566070),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E5EC),
    onSecondaryContainer = Color(0xFF1B2029),
    tertiary = Color(0xFF1F7353),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5EEE2),
    onTertiaryContainer = Color(0xFF0D3324),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFEEF1F5),
    onBackground = Color(0xFF171B22),
    surface = Color(0xFFEEF1F5),
    onSurface = Color(0xFF171B22),
    surfaceVariant = Color(0xFFDFE3EA),
    onSurfaceVariant = Color(0xFF4A5260),
    outline = Color(0xFF737B88),
    outlineVariant = Color(0xFFC9CED8),
    inverseSurface = Color(0xFF262B34),
    inverseOnSurface = Color(0xFFEEF1F5),
    inversePrimary = Color(0xFFC9D0DC),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF1F2530),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFFAFBFC),
    surfaceContainerHigh = Color(0xFFF3F5F8),
    surfaceContainerHighest = Color(0xFFE4E8EE),
)

// Deep blue-grey and brushed silver. Pure black under bright silver text is tiring to read, so no
// surface here is #000: the darkest tone is a slate that still reads as black next to the logo.
private val DarkColor = darkColorScheme(
    primary = Color(0xFFF4F6FA),
    onPrimary = Color(0xFF0D1015),
    primaryContainer = Color(0xFF2B2F38),
    onPrimaryContainer = Color(0xFFF4F6FA),
    secondary = Color(0xFFA9B0BD),
    onSecondary = Color(0xFF12151A),
    secondaryContainer = Color(0xFF232730),
    onSecondaryContainer = Color(0xFFE9ECF1),
    tertiary = Color(0xFF9DCEB7),
    onTertiary = Color(0xFF103627),
    tertiaryContainer = Color(0xFF264D3D),
    onTertiaryContainer = Color(0xFFDCEEE4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1218),
    onBackground = Color(0xFFEDEFF4),
    surface = Color(0xFF0F1218),
    onSurface = Color(0xFFEDEFF4),
    surfaceVariant = Color(0xFF1C2029),
    onSurfaceVariant = Color(0xFFA6ADB9),
    outline = Color(0xFF6E7582),
    outlineVariant = Color(0xFF2C313B),
    inverseSurface = Color(0xFFE9ECF1),
    inverseOnSurface = Color(0xFF181C24),
    inversePrimary = Color(0xFF5D636E),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFF4F6FA),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF14171E),
    surfaceContainer = Color(0xFF181C24),
    surfaceContainerHigh = Color(0xFF1E222B),
    surfaceContainerHighest = Color(0xFF252A34),
)

// Semantic colors stay distinct from the decorative glass treatment.
val colorPing = Color(0xFF356B57)
val colorPingRed = Color(0xFFBA1A1A)
val colorConfigType = Color(0xFF805522)
/**
 * One corner language for the whole app: chips, cards, dialogs and menus all draw from here, so
 * screens written at different times still look like the same product.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * Tone for a measured delay: calm while it is fast, warm when it drags, hard red when it failed.
 * Reading it from the scheme keeps the same meaning in light, dark and dynamic color.
 */
@Composable
fun rxPingColor(delayMs: Long): Color = when {
    delayMs < 0L -> MaterialTheme.colorScheme.error
    delayMs <= 400L -> MaterialTheme.colorScheme.tertiary
    delayMs <= 900L -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

/** Backing tint for [rxPingColor]; same hue, low enough to keep the label readable on top. */
@Composable
fun rxPingContainer(delayMs: Long): Color = rxPingColor(delayMs).copy(alpha = 0.14f)

val colorFabActive = Color(0xFF805522)
val colorFabInactiveLight = Color(0xFF62625E)
val colorFabInactiveDark = Color(0xFF424649)
val dividerColorLight = Color(0xFFD4D3CD)
val dividerColorDark = Color(0xFF424649)

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xD94A4F54) // Darker Gray
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD93F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    // Only the missing-key default changes; an explicit saved theme is preserved.
    const val DEFAULT_UI_MODE = "2"

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_UI_MODE) ?: DEFAULT_UI_MODE
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_UI_MODE) ?: DEFAULT_UI_MODE
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return resolveDarkTheme(mode, isSystemInDarkTheme())
}

internal fun resolveDarkTheme(mode: String, systemDark: Boolean): Boolean = when (mode) {
    "1" -> false
    "2" -> true
    else -> systemDark
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
