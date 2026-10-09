package com.smartplug.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.DailyScheduleEntry
import com.smartplug.app.ui.theme.AccentGreen
import com.smartplug.app.ui.theme.AccentRed

/**
 * 24 hour strip: each schedule is a single thick line at its time (green = ON, red = OFF); the
 * thinner primary-colored line is the current time.
 */
@Composable
fun ScheduleRail(entries: List<DailyScheduleEntry>, nowMinute: Int, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(track),
        ) {
            entries.forEach { entry ->
                val minute = (entry.hour * 60 + entry.minute).coerceIn(0, 1440)
                val x = (size.width * minute / 1440f).coerceIn(4f, size.width - 4f)
                drawLine(
                    if (entry.turnOn) AccentGreen else AccentRed,
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = 8f,
                )
            }
            val x = size.width * nowMinute.coerceIn(0, 1440) / 1440f
            drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Four-segment progress strip for the pairing flows; segments up to [current] are filled. */
@Composable
fun StepperHeader(current: Int, modifier: Modifier = Modifier, total: Int = 4) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            val color by animateColorAsState(
                if (index < current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                label = "stepSegment",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}
