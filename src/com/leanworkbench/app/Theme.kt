package com.leanworkbench.app

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A quiet terminal: monospace throughout, square corners, ink on paper (light) or
// phosphor on black (dark), one accent. Emphasis is reverse video, not shadows.

private val Paper = lightColorScheme(
    background = Color(0xFFF6F4EE), surface = Color(0xFFF6F4EE),
    surfaceContainer = Color(0xFFECE9E0), surfaceContainerLow = Color(0xFFF0EDE5),
    surfaceContainerHigh = Color(0xFFE6E3D9), surfaceVariant = Color(0xFFE9E6DC),
    onBackground = Color(0xFF1E1E1C), onSurface = Color(0xFF1E1E1C), onSurfaceVariant = Color(0xFF5B5A55),
    primary = Color(0xFF1F6B3A), onPrimary = Color(0xFFF6F4EE),
    primaryContainer = Color(0xFFDCE8D9), onPrimaryContainer = Color(0xFF12391F),
    secondary = Color(0xFF5B5A55), secondaryContainer = Color(0xFFE2E6DA), onSecondaryContainer = Color(0xFF1E1E1C),
    tertiary = Color(0xFF9A5B00),
    outline = Color(0xFF8C897F), outlineVariant = Color(0xFFD5D1C4),
    error = Color(0xFFB0281E), errorContainer = Color(0xFFF5DFDA), onErrorContainer = Color(0xFF5E120C),
)

private val Phosphor = darkColorScheme(
    background = Color(0xFF121311), surface = Color(0xFF121311),
    surfaceContainer = Color(0xFF1A1C19), surfaceContainerLow = Color(0xFF161815),
    surfaceContainerHigh = Color(0xFF20231F), surfaceVariant = Color(0xFF20231F),
    onBackground = Color(0xFFD9DBD2), onSurface = Color(0xFFD9DBD2), onSurfaceVariant = Color(0xFF9A9D93),
    primary = Color(0xFF7FD196), onPrimary = Color(0xFF0F1A12),
    primaryContainer = Color(0xFF1E3325), onPrimaryContainer = Color(0xFFBFE8C9),
    secondary = Color(0xFF9A9D93), secondaryContainer = Color(0xFF23302A), onSecondaryContainer = Color(0xFFD9DBD2),
    tertiary = Color(0xFFE0A84E),
    outline = Color(0xFF72766C), outlineVariant = Color(0xFF2C302A),
    error = Color(0xFFFF8A7A), errorContainer = Color(0xFF3B1C18), onErrorContainer = Color(0xFFFFD2CB),
)

private fun monospaced(t: Typography): Typography {
    fun TextStyle.m() = copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
    return Typography(
        displayLarge = t.displayLarge.m(), displayMedium = t.displayMedium.m(), displaySmall = t.displaySmall.m(),
        headlineLarge = t.headlineLarge.m(), headlineMedium = t.headlineMedium.m(), headlineSmall = t.headlineSmall.m(),
        titleLarge = t.titleLarge.m().copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.m().copy(fontWeight = FontWeight.Bold), titleSmall = t.titleSmall.m(),
        bodyLarge = t.bodyLarge.m().copy(fontSize = 15.sp), bodyMedium = t.bodyMedium.m(), bodySmall = t.bodySmall.m(),
        labelLarge = t.labelLarge.m(), labelMedium = t.labelMedium.m(), labelSmall = t.labelSmall.m(),
    )
}

/** Barely rounded: square enough to read as a terminal, soft enough not to look broken. */
val Sq = RoundedCornerShape(2.dp)

private val SquareShapes = Shapes(extraSmall = Sq, small = Sq, medium = Sq, large = Sq, extraLarge = RoundedCornerShape(4.dp))

@Composable
fun TermTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Phosphor else Paper,
        typography = monospaced(Typography()), shapes = SquareShapes, content = content)
}

// Meaning colours, from the palette so they hold in both modes.
val TermOk: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val TermWarn: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
val TermErr: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

/**
 * A command, written as a word. [strong] is the one action a screen is for, in
 * reverse video; the others are plain accent-coloured text.
 */
@Composable
fun Cmd(label: String, enabled: Boolean = true, strong: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = when {
        !enabled -> cs.outline.copy(alpha = 0.6f)
        strong -> cs.onPrimary
        else -> cs.primary
    }
    val base = modifier.padding(horizontal = 2.dp, vertical = 4.dp)
    val bg = if (strong) base.background(if (enabled) cs.primary else cs.outlineVariant, Sq) else base
    Text(if (strong) " $label " else "[$label]", bg.clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.Bold)
}

/** A tappable token: a symbol, a name, a tactic, a view. [on] is reverse video. */
@Composable
fun Token(text: String, on: Boolean = false, style: TextStyle = MaterialTheme.typography.labelLarge,
          color: Color = Color.Unspecified, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(text, modifier.border(1.dp, if (on) cs.onSurface else cs.outlineVariant, Sq)
        .background(if (on) cs.onSurface else Color.Transparent, Sq)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        style = style, color = if (on) cs.surface else if (color != Color.Unspecified) color else cs.onSurface)
}
