package com.i5autolock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.i5autolock.data.settings.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = EmeraldDeep,
    primaryContainer = Color(0xFF14432F),
    onPrimaryContainer = Mint,
    secondary = Color(0xFF5FD6E3),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF15413F),
    onSecondaryContainer = SkyBlueSoft,
    tertiary = Amber,
    onTertiary = Color(0xFF3E2600),
    tertiaryContainer = Color(0xFF4A3410),
    onTertiaryContainer = AmberSoft,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFBFD6C9),
    outline = OutlineDark,
    error = Coral,
    onError = Color(0xFF3A0710),
    errorContainer = Color(0xFF5A1420),
    onErrorContainer = CoralSoft,
)

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = MintSoft,
    onPrimaryContainer = EmeraldDeep,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = SkyBlueSoft,
    onSecondaryContainer = Color(0xFF06364C),
    tertiary = Amber,
    onTertiary = Color(0xFF3E2600),
    tertiaryContainer = AmberSoft,
    onTertiaryContainer = Color(0xFF3E2600),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF3B4A43),
    outline = OutlineLight,
    error = Coral,
    onError = Color.White,
    errorContainer = CoralSoft,
    onErrorContainer = Color(0xFF5A1420),
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
        typography = Typography(),
        content = content,
    )
}
