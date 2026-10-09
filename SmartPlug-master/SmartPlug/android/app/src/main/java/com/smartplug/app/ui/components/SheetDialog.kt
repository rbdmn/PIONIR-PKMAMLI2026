package com.smartplug.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drop-in replacement for AlertDialog that presents the same title / text / buttons as a bottom
 * sheet: easier to reach with one hand and the screen behind stays visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = state, modifier = modifier) {
        TapFeedbackHost {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() } }
            text?.let { ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() } }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
        }
    }
}

/**
 * Card that slides left to reveal action cells behind it (for example Reset / Lepas). The
 * content receives whether the actions are open and a function that closes them again.
 */
@Composable
fun SwipeRevealRow(
    modifier: Modifier = Modifier,
    actionsWidth: Dp = 156.dp,
    actions: @Composable RowScope.(close: () -> Unit) -> Unit,
    content: @Composable (open: Boolean, close: () -> Unit) -> Unit,
) {
    val maxPx = with(LocalDensity.current) { actionsWidth.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) } }
    Box(modifier.clip(RoundedCornerShape(20.dp))) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
        ) { actions(close) }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                offset.animateTo(
                                    if (offset.value < -maxPx / 2f) -maxPx else 0f,
                                    spring(dampingRatio = 0.8f, stiffness = 400f),
                                )
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(-maxPx, 0f)) }
                    }
                },
        ) {
            content(offset.value < -1f, close)
        }
    }
}
