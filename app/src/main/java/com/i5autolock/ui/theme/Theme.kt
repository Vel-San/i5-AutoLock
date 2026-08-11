package com.i5autolock.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.i5autolock.data.settings.ThemeMode

// ── Colour schemes ──────────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary = DigitalTeal,
    onPrimary = DigitalTealDeep,
    primaryContainer = DigitalTealMuted,
    onPrimaryContainer = DigitalTealSoft,
    secondary = CyberSand,
    onSecondary = CyberSandDeep,
    secondaryContainer = Color(0xFF41341A),
    onSecondaryContainer = CyberSandSoft,
    tertiary = GravityGold,
    onTertiary = GravityGoldDeep,
    tertiaryContainer = Color(0xFF433110),
    onTertiaryContainer = GravityGoldSoft,
    background = PhantomBlack,
    onBackground = OnPhantomBlack,
    surface = PhantomBlack,
    onSurface = OnPhantomBlack,
    surfaceVariant = PhantomBlackVariant,
    onSurfaceVariant = Color(0xFFB8C8C4),
    outline = OutlineDark,
    error = UltimateRed,
    onError = UltimateRedDeep,
    errorContainer = Color(0xFF5B1A20),
    onErrorContainer = UltimateRedSoft,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00786D),
    onPrimary = Color.White,
    primaryContainer = DigitalTealSoft,
    onPrimaryContainer = DigitalTealDeep,
    secondary = Color(0xFF836A3A),
    onSecondary = Color.White,
    secondaryContainer = CyberSandSoft,
    onSecondaryContainer = CyberSandDeep,
    tertiary = GravityGold,
    onTertiary = Color.White,
    tertiaryContainer = GravityGoldSoft,
    onTertiaryContainer = GravityGoldDeep,
    background = AtlasWhite,
    onBackground = OnAtlasWhite,
    surface = AtlasWhite,
    onSurface = OnAtlasWhite,
    surfaceVariant = ShootingStarLight,
    onSurfaceVariant = Color(0xFF3C4A47),
    outline = OutlineLight,
    error = UltimateRed,
    onError = Color.White,
    errorContainer = UltimateRedSoft,
    onErrorContainer = UltimateRedDeep,
)

// ── Shapes — Ioniq 5 pixel-block corner language ───────────────────
private val IonicShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

// ── Typography — tightened tracking + heavier display for a car UI ─
private val IonicTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.0).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.15.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun AutoLockTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IonicTypography,
        shapes = IonicShapes,
        content = content,
    )
}
