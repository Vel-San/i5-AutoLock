package com.i5autolock.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ioniq 5 "Parametric Pixels" motif — clusters of small squares echoing the car's pixel
 * head/tail lights. Used as a subtle brand accent across the app.
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

/** Draws the pixel cluster within [Modifier.size]. Cells scale to fit. */
@Composable
fun ParametricPixels(
    modifier: Modifier = Modifier,
    color: Color = DigitalTeal,
    gapRatio: Float = 0.25f,
) {
    Canvas(modifier) {
        val cellW = size.width / (ClusterCols + (ClusterCols - 1) * gapRatio)
        val cellH = size.height / (ClusterRows + (ClusterRows - 1) * gapRatio)
        val stepX = cellW * (1 + gapRatio)
        val stepY = cellH * (1 + gapRatio)
        PixelCluster.forEach { (cx, cy) ->
            drawRect(
                color = color,
                topLeft = Offset(cx * stepX, cy * stepY),
                size = Size(cellW, cellH),
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
) {
    Canvas(modifier) {
        val cell = size.width / (cells + (cells - 1) * gapRatio)
        val step = cell * (1 + gapRatio)
        val h = minOf(cell, size.height)
        val top = (size.height - h) / 2f
        repeat(cells) { i ->
            drawRect(
                color = if (i % 3 == 1) color.copy(alpha = color.alpha * 0.55f) else color,
                topLeft = Offset(i * step, top),
                size = Size(cell, h),
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
