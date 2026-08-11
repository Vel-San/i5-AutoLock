package com.i5autolock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Semantic accents used for lively, meaningful UI states (not just greys). */
object AccentColors {
    val positive @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DigitalTeal else Color(0xFF00847A)
    val warning @Composable @ReadOnlyComposable get() = GravityGold
    val danger @Composable @ReadOnlyComposable get() = UltimateRed
    val info @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) ElectricLime else Color(0xFF4C6A00)
    val charge @Composable @ReadOnlyComposable get() = ElectricLime
}

/** Elevation & layout tokens for a consistent, premium spatial system. */
object BrandTokens {
    val screenPadding = 20.dp
    val cardPadding = 22.dp
    val itemSpacing = 16.dp
    val heroCorner = 32.dp
    val cardCorner = 24.dp
    val chipCorner = 14.dp
}

/**
 * Vibrant brand gradient for hero surfaces (headers, status card). Multi-stop so it reads as a
 * pearlescent teal sweep rather than a flat fill.
 */
@Composable
@ReadOnlyComposable
fun brandGradient(): Brush = if (isSystemInDarkTheme()) {
    Brush.linearGradient(
        listOf(
            Color(0xFF063A36),
            DigitalTealMuted,
            Color(0xFF0E5B52),
        ),
    )
} else {
    Brush.linearGradient(
        listOf(
            Color(0xFF00A392),
            Color(0xFF00847A),
            Color(0xFF006E6E),
        ),
    )
}

/** A radial "glow" behind hero content — like the car's ambient lighting. */
@Composable
@ReadOnlyComposable
fun heroGlow(): Brush = Brush.radialGradient(
    colors = if (isSystemInDarkTheme()) {
        listOf(DigitalTeal.copy(alpha = 0.28f), Color.Transparent)
    } else {
        listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)
    },
)

/** Soft tinted gradient for smaller accent chips/cards. */
@Composable
@ReadOnlyComposable
fun softGradient(): Brush = Brush.linearGradient(
    listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
    ),
)

/** Subtle top-to-bottom scrim used to seat content over the hero gradient. */
@Composable
@ReadOnlyComposable
fun heroScrim(): Brush = Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
)

/** Ambient app background wash — a faint teal aurora over the base surface. */
@Composable
@ReadOnlyComposable
fun ambientBackground(): Brush {
    val base = MaterialTheme.colorScheme.background
    return Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceContainerLow,
            base,
            base,
        ),
    )
}

