package com.i5autolock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Semantic accents used for lively, meaningful UI states (not just greys). */
object AccentColors {
    val positive @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DigitalTeal else Color(0xFF00786D)
    val warning @Composable @ReadOnlyComposable get() = GravityGold
    val danger @Composable @ReadOnlyComposable get() = UltimateRed
    val info @Composable @ReadOnlyComposable get() = CyberSand
}

/** Vibrant brand gradient for hero surfaces (headers, status card). */
@Composable
@ReadOnlyComposable
fun brandGradient(): Brush = if (isSystemInDarkTheme()) {
    Brush.linearGradient(listOf(DigitalTealMuted, PhantomBlackVariant))
} else {
    Brush.linearGradient(listOf(DigitalTeal, Color(0xFF00786D)))
}

/** Soft tinted gradient for smaller accent chips/cards. */
@Composable
@ReadOnlyComposable
fun softGradient(): Brush = Brush.linearGradient(
    listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
    ),
)
