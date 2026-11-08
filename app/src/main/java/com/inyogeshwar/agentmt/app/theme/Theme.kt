package com.inyogeshwar.agentmt.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inyogeshwar.agentmt.data.prefs.ThemeMode

@Immutable
data class Extra(val success: Color, val warning: Color, val danger: Color, val code: Color, val codeText: Color, val userBubble: Color, val subtle: Color)

val LocalExtra = staticCompositionLocalOf { DarkExtra }

private val DarkExtra = Extra(Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF0A0C10), Color(0xFFE6EDF3), Color(0xFF262A33), Color(0xFF8B93A1))
private val LightExtra = Extra(Color(0xFF059669), Color(0xFFB45309), Color(0xFFDC2626), Color(0xFFF3F4F6), Color(0xFF1F2328), Color(0xFFF0F1F4), Color(0xFF6B7280))

private val Dark = darkColorScheme(
    primary = Color(0xFF7AB2FF), onPrimary = Color(0xFF04172F),
    primaryContainer = Color(0xFF1B3358), onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFF22D3EE), onSecondary = Color(0xFF00222A),
    tertiary = Color(0xFFFBBF24), onTertiary = Color(0xFF2A1D00),
    tertiaryContainer = Color(0xFF3A2C08), onTertiaryContainer = Color(0xFFFFE6A6),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0F1115), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1E222A), onSurfaceVariant = Color(0xFFA4ACB9),
    surfaceContainerLowest = Color(0xFF0B0D10), surfaceContainerLow = Color(0xFF14171C),
    surfaceContainer = Color(0xFF181B21), surfaceContainerHigh = Color(0xFF1E222A), surfaceContainerHighest = Color(0xFF262B34),
    outline = Color(0xFF3A404C), outlineVariant = Color(0xFF262B33),
    error = Color(0xFFF87171), onError = Color(0xFF2D0606), errorContainer = Color(0xFF3B1212), onErrorContainer = Color(0xFFFFD9D9),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1D4ED8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF0E7490), tertiary = Color(0xFFB45309),
    tertiaryContainer = Color(0xFFFFF1D6), onTertiaryContainer = Color(0xFF4A2A00),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFF1F3F6), onSurfaceVariant = Color(0xFF5B6270),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF3F4F7), surfaceContainerHigh = Color(0xFFECEEF2), surfaceContainerHighest = Color(0xFFE4E7EC),
    outline = Color(0xFFCDD2DA), outlineVariant = Color(0xFFE6E8EC),
)

val Mono = FontFamily.Monospace

private val AppType = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.1.sp),
        bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AgentMTTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, typography = AppType, shapes = AppShapes, content = content)
    }
}
