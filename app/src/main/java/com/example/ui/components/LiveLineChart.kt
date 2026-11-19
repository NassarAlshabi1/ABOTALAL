package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Lightweight live line chart drawn on a Canvas — no external chart library.
 *
 * Designed for the dashboard's continuously polled metrics (CPU %, memory %):
 * every new value animates smoothly. The caller keeps a rolling history list
 * and passes it in; the chart only reads.
 *
 * @param values rolling window of samples, oldest first. Values outside
 *   [minValue]..[maxValue] are clamped when drawn.
 * @param lineColor stroke color of the series.
 * @param fillColor optional gradient fill under the line. Pass null to skip.
 * @param gridColor dashed horizontal grid color.
 */
@Composable
fun LiveLineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color,
    fillColor: Color? = null,
    gridColor: Color = Color.LightGray,
    minValue: Float = 0f,
    maxValue: Float = 100f,
    maxSamples: Int = 40,
) {
    // Re-animate on every new sample so the last segment glides in.
    val progress by animateFloatAsState(
        targetValue = if (values.isEmpty()) 0f else 1f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "live_chart_progress",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (values.isEmpty()) return@Canvas

        val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f
        val samples = if (values.size > maxSamples) values.takeLast(maxSamples) else values
        val n = samples.size
        val stepX = if (n > 1) size.width / (n - 1) else 0f

        fun yFor(v: Float): Float {
            val clamped = v.coerceIn(minValue, maxValue)
            val frac = (clamped - minValue) / range
            // Invert: 100% at the top of the canvas.
            return size.height * (1f - frac)
        }

        // Dashed horizontal grid: 25 / 50 / 75 %.
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 12f))
        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(0f, size.height * frac),
                end = Offset(size.width, size.height * frac),
                strokeWidth = 1.5f,
                pathEffect = dash,
            )
        }

        val path = Path()
        val fillPath = Path()
        samples.forEachIndexed { i, v ->
            val x = if (n > 1) i * stepX else size.width / 2f
            val y = yFor(v)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        // Close the fill shape at the bottom.
        fillPath.lineTo(if (n > 1) (n - 1) * stepX else size.width / 2f, size.height)
        fillPath.close()

        if (fillColor != null) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillColor.copy(alpha = 0.35f), fillColor.copy(alpha = 0.02f)),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4f),
        )

        // Current-value dot on the newest sample.
        val lastX = if (n > 1) (n - 1) * stepX else size.width / 2f
        drawCircle(
            color = lineColor,
            radius = 7f,
            center = Offset(lastX, yFor(samples.last())),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = 3f,
            center = Offset(lastX, yFor(samples.last())),
        )
    }
}
