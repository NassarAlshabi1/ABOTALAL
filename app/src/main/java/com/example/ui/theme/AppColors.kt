package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * NetCraft semantic color tokens.
 *
 * Screens must read colors from here (or from MaterialTheme) instead of
 * hardcoding `Color(0xFF...)` values, so the whole app can switch between
 * light and dark and re-brand from a single place.
 *
 * Usage:
 * ```
 * val colors = NetCraftColors.current
 * Box(Modifier.background(colors.screenBg))
 * ```
 */
data class NetCraftColors(
    // Surfaces
    val screenBg: Color,
    val cardBg: Color,
    val cardBgAlt: Color,
    val cardBorder: Color,
    val divider: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textOnPrimary: Color,
    // Status
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    // Header gradient (teal identity)
    val headerGradient: List<Color>,
    // Live charts
    val chartLinePrimary: Color,
    val chartLineSecondary: Color,
    val chartGrid: Color,
    val chartLabel: Color,
)

/** Light palette — mirrors the values previously hardcoded in the screens. */
val LightNetCraftColors = NetCraftColors(
    screenBg = Color(0xFFF1F5F9),
    cardBg = Color(0xFFFFFFFF),
    cardBgAlt = Color(0xFFF8FAFC),
    cardBorder = Color(0xFFE2E8F0),
    divider = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF64748B),
    textOnPrimary = Color(0xFFFFFFFF),
    success = Color(0xFF10B981),
    warning = Color(0xFFF59E0B),
    error = Color(0xFFEF4444),
    info = Color(0xFF0284C7),
    headerGradient = listOf(Color(0xFF063B40), Color(0xFF0C5A60), Color(0xFF0284C7)),
    chartLinePrimary = Color(0xFF0C5A60),
    chartLineSecondary = Color(0xFF0284C7),
    chartGrid = Color(0xFFE2E8F0),
    chartLabel = Color(0xFF64748B),
)

/** Dark palette — mirrors the dark values previously hardcoded in the screens. */
val DarkNetCraftColors = NetCraftColors(
    screenBg = Color(0xFF0F172A),
    cardBg = Color(0xFF1E293B),
    cardBgAlt = Color(0xFF16202E),
    cardBorder = Color(0xFF334155),
    divider = Color(0xFF334155),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textOnPrimary = Color(0xFFFFFFFF),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    info = Color(0xFF38BDF8),
    headerGradient = listOf(Color(0xFF042F34), Color(0xFF0A4A52), Color(0xFF0C5A60)),
    chartLinePrimary = Color(0xFF38BDF8),
    chartLineSecondary = Color(0xFF34D399),
    chartGrid = Color(0xFF334155),
    chartLabel = Color(0xFF94A3B8),
)

val LocalNetCraftColors = staticCompositionLocalOf { LightNetCraftColors }

/** Whether the current [MyApplicationTheme] is the dark variant. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** Accessor so screens can simply write `NetCraftColors.current`. */
object NetCraftColorsAccessor {
    val current: NetCraftColors
        @Composable get() = LocalNetCraftColors.current

    val isDark: Boolean
        @Composable get() = LocalIsDarkTheme.current
}

@Composable
fun netCraftColors(): NetCraftColors = LocalNetCraftColors.current
