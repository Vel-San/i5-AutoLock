package com.i5autolock.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Ioniq 5 "Parametric Pixels" motif — clusters of small rounded squares echoing the car's pixel
 * head/tail lights. Used as a subtle, premium brand accent across the app.
 */

// A compact pixel cluster pattern (grid cells that are "lit"), 8 columns x 5 rows.
private val PixelCluster: List<Pair<Int, Int>> = listOf(
    0 to 0, 1 to 0, 3 to 0, 6 to 0, 7 to 0,
    0 to 1, 1 to 1, 3 to 1, 4 to 1, 6 to 1, 7 to 1,
    3 to 2, 4 to 2,
    0 to 3, 1 to 3, 3 to 3, 4 to 3, 6 to 3, 7 to 3,
    0 to 4, 1 to 4, 4 to 4, 6 to 4, 7 to 4,
)
private const val ClusterCols = 8
private const val ClusterRows = 5

/** Draws the pixel cluster within [Modifier.size]. Cells scale to fit and have soft corners. */
@Composable
fun ParametricPixels(
    modifier: Modifier = Modifier,
    color: Color = DigitalTeal,
    gapRatio: Float = 0.28f,
) {
    Canvas(modifier) {
        val cellW = size.width / (ClusterCols + (ClusterCols - 1) * gapRatio)
        val cellH = size.height / (ClusterRows + (ClusterRows - 1) * gapRatio)
        val stepX = cellW * (1 + gapRatio)
        val stepY = cellH * (1 + gapRatio)
        val radius = CornerRadius(minOf(cellW, cellH) * 0.32f)
        PixelCluster.forEach { (cx, cy) ->
            drawRoundRect(
                color = color,
                topLeft = Offset(cx * stepX, cy * stepY),
                size = Size(cellW, cellH),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * A scattered field of pixels tiled across the whole area — meant to sit behind card content as a
 * soft, blurred background texture. Each pixel twinkles independently when [active]. Keep the
 * [color] alpha low and apply `Modifier.blur(...)` at the call site for a subtle wash.
 */
@Composable
fun PixelField(
    modifier: Modifier = Modifier,
    color: Color = DigitalTeal,
    active: Boolean = true,
    cellSize: Dp = 15.dp,
    gap: Dp = 9.dp,
) {
    val transition = rememberInfiniteTransition(label = "field")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
        label = "t",
    )
    Canvas(modifier) {
        val c = cellSize.toPx()
        val g = gap.toPx()
        val step = c + g
        if (step <= 0f) return@Canvas
        val cols = ceil(size.width / step).toInt() + 1
        val rows = ceil(size.height / step).toInt() + 1
        val radius = CornerRadius(c * 0.32f)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val seed = (row * 73856093) xor (col * 19349663)
                // Sparse, organic mask so it reads as scattered pixels, not a solid grid.
                if ((seed ushr 3) % 5 == 0) continue
                val a = if (active) {
                    val phase = ((seed ushr 5) % 628) / 100f
                    (0.45f + 0.4f * sin(t + phase)).coerceIn(0.12f, 0.85f)
                } else 0.5f
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * a),
                    topLeft = Offset(col * step, row * step),
                    size = Size(c, c),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * Like [ParametricPixels] but each lit pixel twinkles independently with a soft bloom — used as
 * living "eye candy" on hero cards. When [active] is false it renders as a calm static cluster.
 */
@Composable
fun SparklingPixels(
    modifier: Modifier = Modifier,
    color: Color = DigitalTeal,
    active: Boolean = true,
    gapRatio: Float = 0.28f,
) {
    val transition = rememberInfiniteTransition(label = "sparkle")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "t",
    )
    Canvas(modifier) {
        val cellW = size.width / (ClusterCols + (ClusterCols - 1) * gapRatio)
        val cellH = size.height / (ClusterRows + (ClusterRows - 1) * gapRatio)
        val stepX = cellW * (1 + gapRatio)
        val stepY = cellH * (1 + gapRatio)
        val radius = CornerRadius(minOf(cellW, cellH) * 0.32f)
        PixelCluster.forEachIndexed { idx, cell ->
            val (cx, cy) = cell
            val left = cx * stepX
            val top = cy * stepY
            // Independent phase per pixel gives an uncorrelated twinkle.
            val a = if (active) (0.5f + 0.42f * sin(t + idx * 1.35f)).coerceIn(0.15f, 0.92f) else 0.30f
            if (active && a > 0.6f) {
                val grow = cellW * 0.4f
                drawRoundRect(
                    color = color.copy(alpha = (a - 0.6f) * 0.8f),
                    topLeft = Offset(left - grow, top - grow),
                    size = Size(cellW + grow * 2, cellH + grow * 2),
                    cornerRadius = CornerRadius(radius.x + grow),
                )
            }
            drawRoundRect(
                color = color.copy(alpha = a),
                topLeft = Offset(left, top),
                size = Size(cellW, cellH),
                cornerRadius = radius,
            )
        }
    }
}

/** A horizontal band of equal pixels (like the light bar), for dividers/accents. */
@Composable
fun PixelBand(
    modifier: Modifier = Modifier,
    color: Color = DigitalTeal,
    cells: Int = 12,
    gapRatio: Float = 0.35f,
    dim: Boolean = true,
) {
    Canvas(modifier) {
        val cell = size.width / (cells + (cells - 1) * gapRatio)
        val step = cell * (1 + gapRatio)
        val h = minOf(cell, size.height)
        val top = (size.height - h) / 2f
        val radius = CornerRadius(cell * 0.3f)
        repeat(cells) { i ->
            drawRoundRect(
                color = if (dim && i % 3 == 1) color.copy(alpha = color.alpha * 0.55f) else color,
                topLeft = Offset(i * step, top),
                size = Size(cell, h),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * An animated pixel band with a "scanner" highlight sweeping across it — used to convey the app
 * is actively watching. Purely decorative and cheap to draw.
 */
@Composable
fun ScanningPixelBand(
    modifier: Modifier = Modifier,
    color: Color = DigitalTeal,
    cells: Int = 16,
    gapRatio: Float = 0.4f,
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = (cells - 1).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "head",
    )
    Canvas(modifier) {
        val cell = size.width / (cells + (cells - 1) * gapRatio)
        val step = cell * (1 + gapRatio)
        val h = minOf(cell, size.height)
        val top = (size.height - h) / 2f
        val radius = CornerRadius(cell * 0.3f)
        repeat(cells) { i ->
            val dist = abs(i - head)
            val glow = (1f - (dist / 3f)).coerceIn(0.15f, 1f)
            drawRoundRect(
                color = color.copy(alpha = color.alpha * glow),
                topLeft = Offset(i * step, top),
                size = Size(cell, h),
                cornerRadius = radius,
            )
        }
    }
}

/** Convenience accent sized like a small badge. */
@Composable
fun PixelBadge(color: Color = DigitalTeal, width: Dp = 44.dp, height: Dp = 28.dp) {
    Box(Modifier.size(width, height)) {
        ParametricPixels(Modifier.size(width, height), color)
    }
}

