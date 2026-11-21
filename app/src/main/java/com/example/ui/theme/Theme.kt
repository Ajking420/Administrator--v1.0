package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class AdminExtendedColors(
    val oledBackground: Color = OledBackground,
    val cardSurface: Color = CardTranslucent,
    val cardElevated: Color = CardElevated,
    val cardBorder: Color = CardBorder,
    val glassBar: Color = GlassBarBg,
    val cyan: Color = NeonCyan,
    val violet: Color = ElectricViolet,
    val emerald: Color = StatusEmerald,
    val amber: Color = StatusAmber,
    val crimson: Color = StatusCrimson,
    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary,
    val textMuted: Color = TextMuted,
    val accent: Color = NeonCyan,
    val animSpeedScale: Float = 1.0f
)

val LocalAdminColors = staticCompositionLocalOf { AdminExtendedColors() }

val AdminShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun AdministratorTheme(
    accentPreset: AccentPreset = AccentPreset.CYAN,
    animSpeedScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val accent = accentPreset.color
    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF041016),
        primaryContainer = accent.copy(alpha = 0.16f),
        onPrimaryContainer = accent,
        secondary = ElectricViolet,
        onSecondary = Color.White,
        secondaryContainer = ElectricViolet.copy(alpha = 0.18f),
        onSecondaryContainer = Color(0xFFD6CCFF),
        tertiary = StatusEmerald,
        onTertiary = Color(0xFF03140B),
        background = OledBackground,
        onBackground = TextPrimary,
        surface = OledSurface,
        onSurface = TextPrimary,
        surfaceVariant = CardTranslucent,
        onSurfaceVariant = TextSecondary,
        outline = CardBorder,
        outlineVariant = CardBorderBright,
        error = StatusCrimson,
        onError = Color.White,
        errorContainer = StatusCrimson.copy(alpha = 0.18f),
        onErrorContainer = Color(0xFFFFB4BE)
    )

    val extended = AdminExtendedColors(
        accent = accent,
        animSpeedScale = animSpeedScale.coerceIn(0.25f, 2.0f)
    )

    CompositionLocalProvider(LocalAdminColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AdminTypography,
            shapes = AdminShapes,
            content = content
        )
    }
}
