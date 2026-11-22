package com.airbridge.airplay.ui.theme

import androidx.compose.material3.ColorScheme
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
import androidx.compose.foundation.shape.RoundedCornerShape

/** Brand palette: deep navy surfaces, electric-blue primary, teal live accent. */
object AirBridgeColors {
    val Blue = Color(0xFF4F8CFF)
    val BlueDeep = Color(0xFF2F5FD0)
    val Teal = Color(0xFF2ED3B7)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF6B6B)
    val Navy950 = Color(0xFF0B0E14)
    val Navy900 = Color(0xFF0E1116)
    val Navy850 = Color(0xFF141924)
    val Navy800 = Color(0xFF1A2130)
    val Navy700 = Color(0xFF242D40)
    val Slate300 = Color(0xFFB8C1D6)
    val Slate400 = Color(0xFF8D97AD)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = AirBridgeColors.Blue,
    onPrimary = Color.White,
    primaryContainer = AirBridgeColors.BlueDeep,
    onPrimaryContainer = Color.White,
    secondary = AirBridgeColors.Teal,
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF113B36),
    onSecondaryContainer = Color(0xFFA6F5E7),
    tertiary = AirBridgeColors.Amber,
    onTertiary = Color(0xFF2B1A00),
    background = AirBridgeColors.Navy900,
    onBackground = Color(0xFFE7ECF6),
    surface = AirBridgeColors.Navy900,
    onSurface = Color(0xFFE7ECF6),
    surfaceVariant = AirBridgeColors.Navy800,
    onSurfaceVariant = AirBridgeColors.Slate300,
    surfaceContainerLowest = AirBridgeColors.Navy950,
    surfaceContainerLow = AirBridgeColors.Navy850,
    surfaceContainer = AirBridgeColors.Navy850,
    surfaceContainerHigh = AirBridgeColors.Navy800,
    surfaceContainerHighest = AirBridgeColors.Navy700,
    outline = Color(0xFF3A4459),
    outlineVariant = Color(0xFF2A3245),
    error = AirBridgeColors.Red,
    onError = Color(0xFF2D0000),
    errorContainer = Color(0xFF4A1C1F),
    onErrorContainer = Color(0xFFFFDAD8),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** AirBridge always uses its dark "night stage" palette (matches the window background). */
@Composable
fun AirBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
