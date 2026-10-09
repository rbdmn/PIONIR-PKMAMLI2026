package com.smartplug.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.EnergyHistoryPoint
import android.graphics.Paint
import java.util.Locale

/**
 * Deliberately simple Canvas line chart — no charting library dependency, per spec ("Jangan
 * memuat seluruh data mentah untuk grafik. Grafik harus ringan, responsif, dan mudah dibaca.").
 * The caller is responsible for having already asked the server for a sane resolution
 * (design.md never draws from raw per-500ms samples).
 */
@Composable
fun EnergyLineChart(
    points: List<EnergyHistoryPoint>,
    valueSelector: (EnergyHistoryPoint) -> Double,
    unit: String = "",
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.size < 2) {
        Box(modifier = modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Text(
                "Belum cukup data untuk grafik",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val values = points.map(valueSelector)
    val minValue = values.min()
    val maxValue = values.max()
    val range = (maxValue - minValue).let { if (it == 0.0) 1.0 else it }

    Column(modifier = modifier.fillMaxWidth()) {
        HistoryChartStats(values = values, unit = unit)
        Canvas(modifier = Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp)) {
        val left = 42f
        val right = size.width - 52f
        val top = 10f
        val bottom = size.height - 24f
        val plotWidth = right - left
        val plotHeight = bottom - top
        val stepX = plotWidth / (values.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        val padded = (maxValue - minValue).let { if (it == 0.0) 1.0 else it * 1.12 }
        val center = (maxValue + minValue) / 2.0
        val low = center - padded / 2.0
        val high = center + padded / 2.0
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor.copy(alpha = 0.85f).toArgb()
            textSize = 20f
            textAlign = Paint.Align.RIGHT
        }
        repeat(5) { index ->
            val y = top + plotHeight * index / 4f
            val tick = high - (high - low) * index / 4.0
            drawLine(lineColor.copy(alpha = 0.16f), Offset(left, y), Offset(right, y), 1f)
            drawIntoCanvas { it.nativeCanvas.drawText(historyFormat(tick), left - 5f, y + 7f, labelPaint) }
        }
        drawLine(lineColor.copy(alpha = 0.28f), Offset(left, top), Offset(left, bottom), 1f)
        drawLine(lineColor.copy(alpha = 0.28f), Offset(left, bottom), Offset(right, bottom), 1f)
        values.forEachIndexed { index, value ->
            val x = left + index * stepX
            val normalized = ((value - low) / (high - low)).toFloat()
            val y = bottom - (normalized * plotHeight)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f))
        values.forEachIndexed { index, value ->
            val x = left + index * stepX
            val y = bottom - (((value - low) / (high - low)).toFloat() * plotHeight)
            drawCircle(lineColor, radius = 3f, center = Offset(x, y))
        }
        val latest = values.last()
        val latestY = bottom - (((latest - low) / (high - low)).toFloat() * plotHeight)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.isFakeBoldText = true
        drawLine(lineColor, Offset(right, latestY), Offset(right + 5f, latestY), 2f)
        drawIntoCanvas { it.nativeCanvas.drawText(historyFormat(latest), right + 7f, latestY + 7f, labelPaint) }

        }
    }
}

@Composable
private fun HistoryChartStats(values: List<Double>, unit: String) {
    val last = values.last()
    val min = values.min()
    val average = values.average()
    val max = values.max()
    Text(
        "Now ${historyFormat(last)} $unit  ·  Min ${historyFormat(min)}  ·  Avg ${historyFormat(average)}  ·  Peak ${historyFormat(max)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun historyFormat(value: Double): String = String.format(Locale.US, if (kotlin.math.abs(value) < 10) "%.3f" else "%.1f", value)
