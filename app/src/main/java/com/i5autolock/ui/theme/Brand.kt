package com.i5autolock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Accent colors used for lively, meaningful UI states (not just greys). */
object AccentColors {
    val positive @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Mint else Emerald
    val warning @Composable @ReadOnlyComposable get() = Amber
    val danger @Composable @ReadOnlyComposable get() = Coral
    val info @Composable @ReadOnlyComposable get() = SkyBlue
}

/** Vibrant brand gradient for hero surfaces (headers, status card). */
@Composable
@ReadOnlyComposable
fun brandGradient(): Brush = if (isSystemInDarkTheme()) {
    Brush.linearGradient(listOf(Color(0xFF0F5A3E), Color(0xFF0C766B)))
} else {
    Brush.linearGradient(listOf(Emerald, Teal))
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
