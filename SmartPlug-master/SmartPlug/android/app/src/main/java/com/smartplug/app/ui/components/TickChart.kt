package com.smartplug.app.ui.components

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Area line chart with a Y axis that scales automatically but always starts at zero, a tick
 * on every X label and a dot on the line at each tick. The line draws itself whenever
 * [animateKey] changes (for example when another metric is selected).
 */
@Composable
fun TickChart(
    values: List<Double>,
    xLabels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    animateKey: Any? = Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val context = androidx.compose.ui.platform.LocalContext.current
    val mono = remember { androidx.core.content.res.ResourcesCompat.getFont(context, com.smartplug.app.R.font.ibm_plex_mono_medium) }
    val progress = remember(animateKey) { Animatable(0f) }
    LaunchedEffect(animateKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val maxValue = max(values.max(), 0.0)
        val scale = niceScale(maxValue)
        val decimals = if (scale.step >= 1.0) (if (scale.step % 1.0 != 0.0) 1 else 0) else if (scale.step >= 0.1) 2 else 5
        val fmt = { v: Double -> String.format(Locale.US, "%.${decimals}f", v) }
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = outline.copy(alpha = 0.95f).toArgb()
            textSize = 24f
            textAlign = Paint.Align.RIGHT
            typeface = mono
        }
        val widest = (0..scale.count).maxOf { tickPaint.measureText(fmt(scale.step * it)) }
        val left = widest + 14f
        val right = size.width - 10f
        val top = 12f
        val bottom = size.height - 34f
        val plotW = right - left
        val plotH = bottom - top
        fun xAt(i: Int) = left + plotW * i / (values.size - 1).toFloat()
        fun yAt(v: Double) = bottom - (plotH * (v / scale.top)).toFloat()

        // Grid + Y ticks (zero at the bottom).
        for (k in 0..scale.count) {
            val v = scale.step * k
            val y = yAt(v)
            drawLine(outline.copy(alpha = if (k == 0) 0.7f else 0.3f), Offset(left, y), Offset(right, y), 1.5f)
            drawIntoCanvas { it.nativeCanvas.drawText(fmt(v), left - 8f, y + 8f, tickPaint) }
        }
        // X ticks + labels + dots on the line.
        val labelPaint = Paint(tickPaint).apply { textAlign = Paint.Align.CENTER }
        val last = xLabels.lastIndex.coerceAtLeast(1)
        xLabels.forEachIndexed { j, label ->
            val idx = ((values.size - 1) * j.toFloat() / last).toInt().coerceIn(0, values.lastIndex)
            val x = xAt(idx)
            drawLine(outline.copy(alpha = 0.3f), Offset(x, top), Offset(x, bottom), 1f)
            drawLine(outline, Offset(x, bottom), Offset(x, bottom + 8f), 1.5f)
            labelPaint.textAlign = when (j) { 0 -> Paint.Align.LEFT; last -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            drawIntoCanvas { it.nativeCanvas.drawText(label, x, bottom + 30f, labelPaint) }
        }
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(xAt(i), yAt(v)) else line.lineTo(xAt(i), yAt(v)) }
        // Area under the line fades in behind the drawing line.
        val area = Path().apply {
            addPath(line)
            lineTo(xAt(values.lastIndex), bottom)
            lineTo(xAt(0), bottom)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f * p), color.copy(alpha = 0f)), startY = top, endY = bottom))
        val measure = PathMeasure().apply { setPath(line, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * p, partial, true)
        drawPath(partial, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        xLabels.forEachIndexed { j, _ ->
            val idx = ((values.size - 1) * j.toFloat() / last).toInt().coerceIn(0, values.lastIndex)
            if (idx <= (values.lastIndex * p).toInt()) {
                drawCircle(surface, radius = 7f, center = Offset(xAt(idx), yAt(values[idx])))
                drawCircle(color, radius = 7f, center = Offset(xAt(idx), yAt(values[idx])), style = Stroke(width = 3.5f))
            }
        }
    }
}

internal class NiceScale(val step: Double, val count: Int) {
    val top: Double get() = step * count
}

/** Round tick step (1, 2, 2.5, 5 x 10^n) so the axis has about four intervals from zero. */
internal fun niceScale(maxValue: Double): NiceScale {
    val rough = (if (maxValue <= 0.0) 1.0 else maxValue) / 4.0
    val magnitude = 10.0.pow(floor(log10(rough)))
    val n = rough / magnitude
    val step = (if (n <= 1) 1.0 else if (n <= 2) 2.0 else if (n <= 2.5) 2.5 else if (n <= 5) 5.0 else 10.0) * magnitude
    val count = max(2, ceil(maxValue / step - 1e-9).toInt())
    return NiceScale(step, count)
}

/** Vertical bar chart (e.g. kWh per time slot) whose bars grow from the baseline. */
@Composable
fun GrowingBars(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    highlightIndex: Int = -1,
) {
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val outline = MaterialTheme.colorScheme.outline
    val context = androidx.compose.ui.platform.LocalContext.current
    val mono = remember { androidx.core.content.res.ResourcesCompat.getFont(context, com.smartplug.app.R.font.ibm_plex_mono_medium) }
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val maxValue = max(values.max(), 1e-9)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = outline.toArgb(); textSize = 22f; textAlign = Paint.Align.CENTER; typeface = mono
        }
        val bottom = size.height - 30f
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            val h = (bottom - 8f) * (v / maxValue).toFloat() * grow.value
            val bw = slot * 0.62f
            val x = slot * i + (slot - bw) / 2f
            drawRoundRect(
                color.copy(alpha = if (highlightIndex < 0 || i == highlightIndex) 1f else 0.42f),
                topLeft = Offset(x, bottom - h),
                size = androidx.compose.ui.geometry.Size(bw, max(h, 2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
            labels.getOrNull(i)?.let { label ->
                drawIntoCanvas { it.nativeCanvas.drawText(label, slot * i + slot / 2f, size.height - 6f, labelPaint) }
            }
        }
    }
}

internal fun absOrZero(value: Double): Double = if (value.isNaN()) 0.0 else abs(value)
