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
    inversePrimary = DigitalTealDeep,
    secondary = CyberSand,
    onSecondary = CyberSandDeep,
    secondaryContainer = Color(0xFF3E3117),
    onSecondaryContainer = CyberSandSoft,
    tertiary = ElectricLime,
    onTertiary = ElectricLimeDeep,
    tertiaryContainer = Color(0xFF2E4400),
    onTertiaryContainer = ElectricLimeSoft,
    background = PhantomBlack,
    onBackground = OnPhantomBlack,
    surface = PhantomBlack,
    onSurface = OnPhantomBlack,
    surfaceVariant = PhantomBlackHigh,
    onSurfaceVariant = Color(0xFFB2C4BF),
    surfaceTint = DigitalTeal,
    surfaceBright = PhantomBlackHigh,
    surfaceDim = PhantomBlack,
    surfaceContainerLowest = Color(0xFF040807),
    surfaceContainerLow = PhantomBlackElevated,
    surfaceContainer = PhantomBlackVariant,
    surfaceContainerHigh = PhantomBlackHigh,
    surfaceContainerHighest = Color(0xFF283532),
    inverseSurface = OnPhantomBlack,
    inverseOnSurface = PhantomBlack,
    outline = OutlineDark,
    outlineVariant = OutlineDarkSoft,
    error = UltimateRed,
    onError = UltimateRedDeep,
    errorContainer = Color(0xFF6B1620),
    onErrorContainer = UltimateRedSoft,
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00847A),
    onPrimary = Color.White,
    primaryContainer = DigitalTealSoft,
    onPrimaryContainer = DigitalTealDeep,
    inversePrimary = DigitalTeal,
    secondary = Color(0xFF7A6533),
    onSecondary = Color.White,
    secondaryContainer = CyberSandSoft,
    onSecondaryContainer = CyberSandDeep,
    tertiary = Color(0xFF4C6A00),
    onTertiary = Color.White,
    tertiaryContainer = ElectricLimeSoft,
    onTertiaryContainer = ElectricLimeDeep,
    background = AtlasWhite,
    onBackground = OnAtlasWhite,
    surface = AtlasSurface,
    onSurface = OnAtlasWhite,
    surfaceVariant = ShootingStarLight,
    onSurfaceVariant = Color(0xFF3A4744),
    surfaceTint = Color(0xFF00847A),
    surfaceBright = AtlasSurface,
    surfaceDim = Color(0xFFDBE3E0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = ShootingStarLightHigh,
    surfaceContainer = ShootingStarLight,
    surfaceContainerHigh = Color(0xFFDCE5E2),
    surfaceContainerHighest = Color(0xFFD5DFDB),
    inverseSurface = Color(0xFF16211F),
    inverseOnSurface = Color(0xFFEDF2F0),
    outline = OutlineLight,
    outlineVariant = OutlineLightSoft,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = UltimateRedSoft,
    onErrorContainer = UltimateRedDeep,
    scrim = Color(0xFF000000),
)

// ── Shapes — Ioniq 5 pixel-block corner language, softened & modern ─
private val IonicShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ── Typography ─────────────────────────────────────────────────────
// Display/headlines: heavy grotesk-style sans with tight tracking (confident, automotive).
// Labels & data: monospace with wide tracking — an "EV instrument cluster" readout identity.
private val IonicTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 57.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 45.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp,
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
        letterSpacing = 0.0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp,
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
