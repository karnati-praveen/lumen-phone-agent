package com.aegis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aegis v3.0 Minimalist Zinc/Obsidian Design System.
 * Calm neutral surfaces, high-contrast off-white primary actions, and restrained semantic accents.
 */
object AegisTokens {
    // Neutral Zinc / Obsidian Palette
    val Canvas = Color(0xFF09090B)
    val Surface = Color(0xFF121216)
    val SurfaceRaised = Color(0xFF1A1A20)
    val SurfaceBubble = Color(0xFF24242B)
    val Divider = Color(0xFF23232A)
    val BorderSubtle = Color(0xFF2A2A32)

    // Primary & Semantic Accents (Restrained)
    val AccentPrimary = Color(0xFFFAFAFA)
    val AccentOnPrimary = Color(0xFF09090B)
    val TrustGreen = Color(0xFF22C55E)
    val TrustGreenSoft = Color(0x1A22C55E)
    val WarnAmber = Color(0xFFF59E0B)
    val WarnAmberSoft = Color(0x1AF59E0B)
    val DangerRed = Color(0xFFEF4444)
    val DangerRedSoft = Color(0x1AEF4444)
    val GlyphCyan = Color(0xFF22D3EE)
    val GlyphIndigo = Color(0xFF6366F1)
    val GlyphViolet = Color(0xFFA855F7)
    val GlyphCyanSoft = Color(0x1A22D3EE)
    val SurfaceGlass = Color(0xE8101218)
    val BorderLuminous = Color(0x5522D3EE)

    // Typography Inks
    val TextPrimary = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFFA1A1AA)
    val TextMuted = Color(0xFF71717A)

    val MinTouchTarget = 48.dp

    // Backwards-compatible aliases used by existing components
    val SurfaceObsidian = Canvas
    val SurfaceElevated = Surface
    val BorderHairline = BorderSubtle
    val TrustEmerald = TrustGreen
    val TrustEmeraldBright = TrustGreen
    val TrustEmeraldSoft = TrustGreenSoft
    val ApprovalAmber = WarnAmber
    val ApprovalAmberSoft = WarnAmberSoft
    val AlertCrimson = DangerRed
    val AlertCrimsonSoft = DangerRedSoft
    val InkPrimary = TextPrimary
    val InkSecondary = TextSecondary
    val InkMuted = TextMuted
}

private val AegisDarkScheme = darkColorScheme(
    primary = AegisTokens.AccentPrimary,
    onPrimary = AegisTokens.AccentOnPrimary,
    secondary = AegisTokens.TrustGreen,
    error = AegisTokens.DangerRed,
    background = AegisTokens.Canvas,
    surface = AegisTokens.Surface,
    surfaceVariant = AegisTokens.SurfaceRaised,
    onBackground = AegisTokens.TextPrimary,
    onSurface = AegisTokens.TextPrimary,
    onSurfaceVariant = AegisTokens.TextSecondary,
    outline = AegisTokens.BorderSubtle
)

val AegisTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
        color = AegisTokens.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
        color = AegisTokens.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = AegisTokens.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = AegisTokens.TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = AegisTokens.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = AegisTokens.TextMuted
    )
)

@Composable
fun AegisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AegisDarkScheme,
        typography = AegisTypography,
        content = content
    )
}
