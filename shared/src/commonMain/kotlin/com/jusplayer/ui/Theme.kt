package com.jusplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jusplayer.data.PlayerPrefs

// A dark, mossy palette that matches JusPlayer's green/goblin identity.
val JusInk = Color(0xFF06100B)
val JusSurface = Color(0xFF0C1711)
val JusSurfaceHigh = Color(0xFF15241B)
val JusText = Color(0xFFE6F4EB)
val JusMuted = Color(0xFF9DB4A6)
val JusOutline = Color(0xFF27392E)
val JusError = Color(0xFFFF8A80)

data class JusAccent(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val glow: Color,
)

val JusAccents = listOf(
    JusAccent("Goblin", Color(0xFF6EE7A8), Color(0xFFB9F36B), Color(0xFF123B29)),
    JusAccent("Lagoon", Color(0xFF6EDCE7), Color(0xFF8FC7FF), Color(0xFF10333B)),
    JusAccent("Sunset", Color(0xFFFFB26E), Color(0xFFFFE08A), Color(0xFF3B2813)),
    JusAccent("Rose", Color(0xFFFF8FA6), Color(0xFFFFC2D1), Color(0xFF3B1220)),
    JusAccent("Violet", Color(0xFFB79BFF), Color(0xFFE0D4FF), Color(0xFF251440)),
)

var accentIndex by mutableIntStateOf(0)
    private set

/** Dominant colour (0xRRGGBB) extracted from the current artwork, if any. */
var artworkColor by mutableStateOf<Int?>(null)
    private set

fun applyAccent(index: Int) {
    accentIndex = index.coerceIn(0, JusAccents.lastIndex)
    PlayerPrefs.setAccent(accentIndex)
}

fun restoreAccent() {
    accentIndex = PlayerPrefs.accent().coerceIn(0, JusAccents.lastIndex)
}

/**
 * Feed a colour from the artwork into the theme. Near-greyscale or almost-black
 * covers are ignored so the UI never becomes unreadable.
 */
fun applyArtworkAccent(rgb: Int?) {
    if (rgb == null || rgb == 0) {
        if (artworkColor != null) artworkColor = null
        return
    }
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val usable = max >= 48 && (max - min) >= 18
    val value = if (usable) rgb else null
    if (artworkColor != value) artworkColor = value
}

private fun Color.lighten(amount: Float) = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

private fun Color.darken(amount: Float) = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)

private fun dynamicAccent(rgb: Int): JusAccent {
    val base = Color(0xFF000000L or rgb.toLong())
    return JusAccent(
        name = "Artwork",
        primary = base.lighten(0.32f),
        secondary = base.lighten(0.58f),
        glow = base.darken(0.55f),
    )
}

val currentAccent: JusAccent
    get() {
        val dynamic = artworkColor
        return if (PlayerPrefs.dynamicColor && dynamic != null) dynamicAccent(dynamic)
        else JusAccents[accentIndex]
    }
val JusGreen: Color get() = currentAccent.primary
val JusLime: Color get() = currentAccent.secondary

private fun scheme(accent: JusAccent) = darkColorScheme(
    primary = accent.primary,
    onPrimary = Color(0xFF052014),
    primaryContainer = accent.primary.copy(alpha = 0.22f),
    onPrimaryContainer = accent.secondary,
    secondary = accent.secondary,
    onSecondary = Color(0xFF15210A),
    secondaryContainer = Color(0xFF243617),
    onSecondaryContainer = Color(0xFFDCF5B8),
    tertiary = Color(0xFF7FD7C7),
    background = JusInk,
    onBackground = JusText,
    surface = JusSurface,
    onSurface = JusText,
    surfaceVariant = JusSurfaceHigh,
    onSurfaceVariant = JusMuted,
    surfaceContainer = JusSurface,
    surfaceContainerHigh = JusSurfaceHigh,
    outline = JusOutline,
    outlineVariant = Color(0xFF1C2C22),
    error = Color(0xFFFF8F86),
    onError = Color(0xFF2A0503),
)

private val JusType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun JusTheme(content: @Composable () -> Unit) {
    val colors = remember(accentIndex, artworkColor, PlayerPrefs.dynamicColor) { scheme(currentAccent) }
    MaterialTheme(colorScheme = colors, typography = JusType, content = content)
}

/** Soft accent glow used behind the main content and the full player. */
@Composable
fun JusBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glow = currentAccent.glow
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(glow, JusInk),
                    radius = 1500f,
                ),
            ),
    ) {
        content()
    }
}
