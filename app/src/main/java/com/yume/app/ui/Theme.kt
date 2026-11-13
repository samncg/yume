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

/** Procreate-adjacent palette: warm charcoal, paper white, one amber accent. */
object YumeColors {
    val bg = Color(0xFF0C0C0E)
    val bar = Color(0xFF141418)
    val panel = Color(0xF51A1A1F)
    val panelAlt = Color(0xFF23232A)
    val track = Color(0xFF2E2E36)
    val hairline = Color(0x1FFFFFFF)
    val text = Color(0xFFEDEDF1)
    val textDim = Color(0xFF8C8C96)
    val accent = Color(0xFFE9A13B)
    val accentSoft = Color(0x33E9A13B)
    val danger = Color(0xFFC64F4F)
}

private val YumeScheme = darkColorScheme(
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
    MaterialTheme(
        colorScheme = YumeScheme,
        typography = YumeType,
        content = content
    )
}
