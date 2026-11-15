package com.yume.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One background family of the UI (Settings → Appearance). */
data class YumeTheme(
    val id: String,
    val label: String,
    val bg: Color,
    val bar: Color,
    val panel: Color,
    val panelAlt: Color,
    val track: Color,
    /** Light background: dark text and a pale glass veil. */
    val light: Boolean = false
)

/** An accent colour (Settings → Appearance). */
data class YumeAccent(val id: String, val label: String, val color: Color)

object YumeThemes {
    val themes = listOf(
        YumeTheme("graphite", "Graphite", Color(0xFF0C0C0E), Color(0xFF141418), Color(0xF51A1A1F), Color(0xFF23232A), Color(0xFF2E2E36)),
        YumeTheme("midnight", "Midnight", Color(0xFF090C13), Color(0xFF10151F), Color(0xF5141A27), Color(0xFF1D2535), Color(0xFF283245)),
        YumeTheme("oled", "OLED black", Color(0xFF000000), Color(0xFF080808), Color(0xF50E0E10), Color(0xFF19191C), Color(0xFF26262A)),
        YumeTheme("espresso", "Espresso", Color(0xFF100D0B), Color(0xFF191411), Color(0xF51E1815), Color(0xFF2A221D), Color(0xFF382E27)),
        YumeTheme("forest", "Forest", Color(0xFF0A0F0C), Color(0xFF111813), Color(0xF5151E18), Color(0xFF1E2A22), Color(0xFF29382E)),
        YumeTheme("slate", "Slate", Color(0xFF1A1C21), Color(0xFF22252B), Color(0xF5282B32), Color(0xFF32363E), Color(0xFF41464F)),
        YumeTheme("paper", "Paper", Color(0xFFF5F4F1), Color(0xFFECEAE6), Color(0xF5FFFFFF), Color(0xFFEAE8E4), Color(0xFFD6D3CD), light = true),
        YumeTheme("cloud", "Cloud", Color(0xFFF1F3F7), Color(0xFFE6EAF0), Color(0xF5FCFDFF), Color(0xFFE3E8EF), Color(0xFFCBD3DE), light = true),
        YumeTheme("sand", "Sand", Color(0xFFF5EEE3), Color(0xFFEDE4D6), Color(0xF5FFFBF5), Color(0xFFEBE1D2), Color(0xFFD7C9B4), light = true)
    )

    val accents = listOf(
        YumeAccent("amber", "Amber", Color(0xFFE9A13B)),
        YumeAccent("coral", "Coral", Color(0xFFF07A5A)),
        YumeAccent("rose", "Rose", Color(0xFFEC7398)),
        YumeAccent("violet", "Violet", Color(0xFFA98BF5)),
        YumeAccent("sky", "Sky", Color(0xFF5DA9F2)),
        YumeAccent("teal", "Teal", Color(0xFF3CC4B2)),
        YumeAccent("lime", "Lime", Color(0xFFA8D24C)),
        YumeAccent("pearl", "Pearl", Color(0xFFE4E4EA))
    )

    /** The theme in use (reads settings state, so the UI follows changes). */
    val current: YumeTheme
        get() = themes.firstOrNull { it.id == com.yume.app.data.AppSettings.theme } ?: themes[0]

    val accent: Color
        get() = (accents.firstOrNull { it.id == com.yume.app.data.AppSettings.accent } ?: accents[0]).color
}

/** The UI palette: background family and accent come from Settings. */
object YumeColors {
    val bg: Color get() = YumeThemes.current.bg
    val bar: Color get() = YumeThemes.current.bar
    val panel: Color get() = YumeThemes.current.panel
    val panelAlt: Color get() = YumeThemes.current.panelAlt
    val track: Color get() = YumeThemes.current.track
    val light: Boolean get() = YumeThemes.current.light
    val hairline: Color get() = if (light) Color(0x22000000) else Color(0x1FFFFFFF)
    val text: Color get() = if (light) Color(0xFF1B1B21) else Color(0xFFEDEDF1)
    val textDim: Color get() = if (light) Color(0xFF6A6A74) else Color(0xFF8C8C96)
    /** The colour of strokes, tracks and veils drawn over the UI: white on dark themes, black on light. */
    val ink: Color get() = if (light) Color(0xFF000000) else Color(0xFFFFFFFF)
    val accent: Color get() = YumeThemes.accent
    val accentSoft: Color get() = YumeThemes.accent.copy(alpha = 0.2f)
    val danger = Color(0xFFC64F4F)
}

private val YumeType = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun YumeTheme(content: @Composable () -> Unit) {
    val scheme = if (YumeColors.light) androidx.compose.material3.lightColorScheme(
        primary = YumeColors.accent,
        onPrimary = Color(0xFF1A1206),
        secondary = YumeColors.accent,
        background = YumeColors.bg,
        onBackground = YumeColors.text,
        surface = YumeColors.panel,
        onSurface = YumeColors.text,
        surfaceVariant = YumeColors.panelAlt,
        onSurfaceVariant = YumeColors.textDim,
        outline = YumeColors.hairline,
        error = YumeColors.danger
    ) else darkColorScheme(
        primary = YumeColors.accent,
        onPrimary = Color(0xFF1A1206),
        secondary = YumeColors.accent,
        background = YumeColors.bg,
        onBackground = YumeColors.text,
        surface = YumeColors.panel,
        onSurface = YumeColors.text,
        surfaceVariant = YumeColors.panelAlt,
        onSurfaceVariant = YumeColors.textDim,
        outline = YumeColors.hairline,
        error = YumeColors.danger
    )
    // UI size (Settings): every dp in the interface scales with it.
    val base = androidx.compose.ui.platform.LocalDensity.current
    val scale = com.yume.app.data.AppSettings.uiScale
    val density = androidx.compose.ui.unit.Density(base.density * scale, base.fontScale)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        MaterialTheme(
            colorScheme = scheme,
            typography = YumeType,
            content = content
        )
    }
}
