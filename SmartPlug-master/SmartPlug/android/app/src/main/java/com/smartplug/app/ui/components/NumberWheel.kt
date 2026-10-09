package com.smartplug.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Looping drum-style number picker. Dragging moves the numbers continuously under the finger and,
 * on release, glides and snaps to the nearest value. Three rows are visible (previous / selected /
 * next), everything centered; tapping the previous or next row steps by one.
 */
@Composable
fun NumberWheel(
    range: IntRange,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 52.dp,
    width: Dp = 72.dp,
) {
    val count = range.last - range.first + 1
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val scope = rememberCoroutineScope()
    var position by remember { mutableFloatStateOf((value - range.first).toFloat()) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val latestOnChange by rememberUpdatedState(onValueChange)

    fun indexOf(pos: Float) = Math.floorMod(pos.roundToInt(), count)

    fun settleTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(initialValue = position, targetValue = target, animationSpec = tween(320, easing = FastOutSlowInEasing)) { v, _ ->
                position = v
            }
        }
    }

    // External changes (e.g. the dialog being re-seeded) move the wheel; our own changes are no-ops.
    LaunchedEffect(value) {
        if (indexOf(position) != value - range.first) position = (value - range.first).toFloat()
    }
    val feedback = rememberTapFeedback()
    val latestFeedback by rememberUpdatedState(feedback)
    LaunchedEffect(Unit) {
        var previous = indexOf(position)
        snapshotFlow { indexOf(position) }.collect {
            if (it != previous) latestFeedback.onTap()
            previous = it
            latestOnChange(range.first + it)
        }
    }

    val dragState = rememberDraggableState { delta ->
        position -= delta / rowPx
    }
    val base = position.roundToInt()
    val fraction = position - base
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .width(width)
            .height(rowHeight * 3)
            .clipToBounds()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { settleJob?.cancel() },
                onDragStopped = { velocity ->
                    settleTo((position - velocity / rowPx * 0.25f).roundToInt().toFloat())
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        )
        for (k in -2..2) {
            val distance = abs(k - fraction)
            val emphasis = (1f - distance).coerceIn(0f, 1f)
            val number = range.first + Math.floorMod(base + k, count)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .offset { IntOffset(0, ((k - fraction) * rowPx).roundToInt()) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (k != 0) settleTo(position + k) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(number),
                    color = lerp(muted.copy(alpha = 0.4f), primary, emphasis),
                    fontWeight = if (emphasis > 0.5f) FontWeight.Bold else FontWeight.Normal,
                    style = if (emphasis > 0.5f) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
