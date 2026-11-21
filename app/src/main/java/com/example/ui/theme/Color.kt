package com.example.ui.theme

import androidx.compose.ui.graphics.Color

val OledBackground = Color(0xFF07090E)
val OledSurface = Color(0xFF0B0F19)
val CardTranslucent = Color(0xE6111622)
val CardElevated = Color(0xF2151C2C)
val CardBorder = Color(0xFF1E293B)
val CardBorderBright = Color(0xFF334155)
val GlassBarBg = Color(0xEB0D121D)

val NeonCyan = Color(0xFF00F0FF)
val ElectricViolet = Color(0xFF7B61FF)
val StatusEmerald = Color(0xFF00E676)
val StatusAmber = Color(0xFFFFB300)
val StatusCrimson = Color(0xFFFF3D57)

val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

enum class AccentPreset(val key: String, val label: String, val color: Color) {
    CYAN("cyan", "Neon Cyan", NeonCyan),
    VIOLET("violet", "Electric Violet", ElectricViolet),
    EMERALD("emerald", "Matrix Emerald", StatusEmerald),
    AMBER("amber", "Reactor Amber", StatusAmber),
    CRIMSON("crimson", "Pulse Crimson", StatusCrimson);

    companion object {
        fun fromKey(key: String): AccentPreset =
            entries.firstOrNull { it.key == key } ?: CYAN
    }
}
