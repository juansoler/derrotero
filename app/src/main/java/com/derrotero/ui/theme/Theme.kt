package com.derrotero.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
 * Marine instrument palette. The app is deliberately dark: it's used at the helm, often at
 * night, and a dark chart background keeps the night vision intact.
 */
object MarineColors {
    val Abyss = Color(0xFF040B13)
    val Deep = Color(0xFF071523)
    val Surface = Color(0xFF0C2033)
    val SurfaceHigh = Color(0xFF123049)
    val Harbor = Color(0xFF4FD8FF)
    val HarborSoft = Color(0xFF2A9FC4)
    val Sand = Color(0xFFF1D483)
    val Mint = Color(0xFF3BE0A0)
    val Alert = Color(0xFFFF6B6B)
    val Amber = Color(0xFFFFC857)
    val Text = Color(0xFFE6F2FA)
    val TextDim = Color(0xFF9FB6C8)
    val Line = Color(0xFF1E3B52)
}

private val DarkColors = darkColorScheme(
    primary = MarineColors.Harbor,
    onPrimary = Color(0xFF002733),
    primaryContainer = Color(0xFF0B3B4D),
    onPrimaryContainer = Color(0xFFB9F1FF),
    secondary = MarineColors.Sand,
    onSecondary = Color(0xFF2A2100),
    secondaryContainer = Color(0xFF3A3010),
    onSecondaryContainer = Color(0xFFF7E3A8),
    tertiary = MarineColors.Mint,
    onTertiary = Color(0xFF00291A),
    tertiaryContainer = Color(0xFF063A28),
    onTertiaryContainer = Color(0xFFB4FFDA),
    background = MarineColors.Deep,
    onBackground = MarineColors.Text,
    surface = MarineColors.Surface,
    onSurface = MarineColors.Text,
    surfaceVariant = MarineColors.SurfaceHigh,
    onSurfaceVariant = Color(0xFFA8C1D4),
    outline = Color(0xFF3B5A70),
    outlineVariant = MarineColors.Line,
    error = MarineColors.Alert,
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = MarineColors.Text,
    inverseOnSurface = Color(0xFF102433),
    inversePrimary = Color(0xFF006A8A),
    scrim = Color.Black,
    surfaceTint = MarineColors.Harbor,
)

private val MarineTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val MarineShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DerroteroTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MarineTypography,
        shapes = MarineShapes,
        content = content,
    )
}
