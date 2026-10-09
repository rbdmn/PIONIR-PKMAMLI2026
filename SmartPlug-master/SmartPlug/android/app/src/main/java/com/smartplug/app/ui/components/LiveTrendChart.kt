package com.smartplug.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max

private const val MAX_DRAWN_POINTS = 400

data class TimedValue(val timestampMs: Long, val value: Double)

/**
 * Scrolling time-based live chart. The device only produces a new sample every 500 ms (and the
 * app polls no faster than that), so the smoothness here is purely visual: the chart redraws on
 * every display frame and draws the line at a render clock that trails real time by one sample
 * interval, linearly interpolating between the two real samples around it. No extra requests are
 * made to the device and the numeric readout elsewhere still shows only real samples.
 */
@Composable
fun LiveTrendChart(
    samples: List<TimedValue>,
    windowMs: Long,
    labelsForSpan: (spanMs: Long) -> List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
) {
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val context = androidx.compose.ui.platform.LocalContext.current
    val mono = remember { androidx.core.content.res.ResourcesCompat.getFont(context, com.smartplug.app.R.font.ibm_plex_mono_medium) }

    // Only the draw phase reads this state, so ticking it every frame redraws the canvas without
    // recomposing the card around it.
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { clock.longValue = System.currentTimeMillis() }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (samples.size < 2) return@Canvas
        val last = samples.last()
        val lagMs = (last.timestampMs - samples[samples.lastIndex - 1].timestampMs).coerceIn(300L, 2_000L)
        // Never draw beyond the newest real sample: if the device goes quiet the line stops rather
        // than extrapolating invented values.
        val renderNow = (clock.longValue - lagMs).coerceAtMost(last.timestampMs)
        // Until enough history exists to fill the chosen span, the axis covers only the data we
        // have, so the first sample sits exactly on the Y axis instead of floating mid-chart.
        val spanMs = minOf(windowMs, renderNow - samples.first().timestampMs).coerceAtLeast(1_000L)
        val windowStart = renderNow - spanMs
        val xLabels = labelsForSpan(spanMs)

        val shown = ArrayList<TimedValue>(samples.size + 2)
        var i = 0
        while (i < samples.size && samples[i].timestampMs < windowStart) i++
        if (i > 0) shown += interpolate(samples[i - 1], samples[i], windowStart)
        while (i < samples.size && samples[i].timestampMs <= renderNow) { shown += samples[i]; i++ }
        if (i < samples.size && shown.isNotEmpty() && shown.last().timestampMs < renderNow) {
            shown += interpolate(samples[i - 1], samples[i], renderNow)
        }
        if (shown.size < 2) return@Canvas
        // Long windows hold thousands of samples; a few hundred points is visually identical.
        if (shown.size > MAX_DRAWN_POINTS) {
            val stride = shown.size / MAX_DRAWN_POINTS + 1
            val thinned = shown.filterIndexed { idx, _ -> idx % stride == 0 }.toMutableList()
            if (thinned.last() !== shown.last()) thinned += shown.last()
            shown.clear(); shown.addAll(thinned)
        }

        val scale = niceScale(max(shown.maxOf { it.value }, 0.0))
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
        fun xAt(t: Long) = left + plotW * (t - windowStart).toFloat() / spanMs.toFloat()
        fun yAt(v: Double) = bottom - (plotH * (v / scale.top)).toFloat()

        for (k in 0..scale.count) {
            val v = scale.step * k
            val y = yAt(v)
            drawLine(outline.copy(alpha = if (k == 0) 0.7f else 0.3f), Offset(left, y), Offset(right, y), 1.5f)
            drawIntoCanvas { it.nativeCanvas.drawText(fmt(v), left - 8f, y + 8f, tickPaint) }
        }
        val labelPaint = Paint(tickPaint).apply { textAlign = Paint.Align.CENTER }
        val lastLabel = xLabels.lastIndex.coerceAtLeast(1)
        xLabels.forEachIndexed { j, label ->
            val x = left + plotW * j / lastLabel.toFloat()
            drawLine(outline.copy(alpha = 0.3f), Offset(x, top), Offset(x, bottom), 1f)
            drawLine(outline, Offset(x, bottom), Offset(x, bottom + 8f), 1.5f)
            labelPaint.textAlign = when (j) { 0 -> Paint.Align.LEFT; lastLabel -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            drawIntoCanvas { it.nativeCanvas.drawText(label, x, bottom + 30f, labelPaint) }
        }

        val line = Path()
        shown.forEachIndexed { index, s ->
            val x = xAt(s.timestampMs)
            val y = yAt(s.value)
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xAt(shown.last().timestampMs), bottom)
            lineTo(xAt(shown.first().timestampMs), bottom)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)), startY = top, endY = bottom))
        drawPath(line, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = shown.last()
        drawCircle(surface, radius = 8f, center = Offset(xAt(end.timestampMs), yAt(end.value)))
        drawCircle(color, radius = 8f, center = Offset(xAt(end.timestampMs), yAt(end.value)), style = Stroke(width = 4f))
    }
}

private fun interpolate(a: TimedValue, b: TimedValue, atMs: Long): TimedValue {
    val span = (b.timestampMs - a.timestampMs).coerceAtLeast(1L)
    val f = ((atMs - a.timestampMs).toDouble() / span).coerceIn(0.0, 1.0)
    return TimedValue(atMs, a.value + (b.value - a.value) * f)
}
