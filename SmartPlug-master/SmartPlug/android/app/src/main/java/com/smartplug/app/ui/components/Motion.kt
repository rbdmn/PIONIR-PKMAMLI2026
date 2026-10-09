package com.smartplug.app.ui.components

import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Quiet, optional motion shared by the screens. Everything here is presentation only: it never
 * reads or writes device/server state. Compose animation specs honour Android's "animator
 * duration scale", so users who disable animations get the final value immediately.
 */

/** Counts a number up from zero on first display, then eases to each new live reading. */
@Composable
fun AnimatedDecimal(
    value: Double,
    decimals: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    fontFamily: androidx.compose.ui.text.font.FontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
) {
    var shown by remember { mutableDoubleStateOf(0.0) }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(value) {
        val start = shown
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(if (firstRun) 900 else 350, easing = FastOutSlowInEasing)) {
            shown = start + (value - start) * this.value
        }
        shown = value
        firstRun = false
    }
    Text(
        text = String.format(Locale.US, "%.${decimals}f", shown),
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        fontFamily = fontFamily,
    )
}

/** Small status dot that breathes with a soft expanding ring while [active]. */
@Composable
fun PulsingDot(color: Color, active: Boolean = true, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulsingDot")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "pulsingDotPhase",
    )
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                if (active) {
                    drawCircle(color.copy(alpha = 0.45f * (1f - phase)), radius = this.size.minDimension / 2f * (1f + 1.6f * phase))
                }
            }
            .clip(CircleShape)
            .background(color),
    )
}

/** Fades and lifts a list item into place. [index] staggers neighbours by a few dozen ms. */
fun Modifier.entrance(index: Int = 0): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, 8) * 45L)
        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24f
    }
}

/** Scales a status icon in with a small overshoot, e.g. the "ready" check after pairing. */
fun Modifier.popIn(): Modifier = composed {
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        alpha = ((scale.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
    }
}

/** Round relay button: breathing ring while ON, quick spring when pressed. */
@Composable
fun RelayPowerButton(
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "relayRing")
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "relayRingPhase",
    )
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "relayPress",
        finishedListener = { pressed = false },
    )
    val container = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val glow = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(84.dp)
            .drawBehind {
                if (on) drawCircle(glow.copy(alpha = 0.22f * (1f - ring * 0.7f)), radius = this.size.minDimension / 2f * (1.12f + 0.2f * ring))
            }
            .scale(scale)
            .clip(CircleShape)
            .background(container.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                pressed = true
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = contentDescription, tint = content, modifier = Modifier.size(34.dp))
    }
}


/** Destructive confirm that only fires after the button is held for [holdMillis]; a fill shows progress. */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 2_000,
) {
    val progress = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val danger = MaterialTheme.colorScheme.error
    Box(
        modifier = modifier
            .size(width = 220.dp, height = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(danger.copy(alpha = 0.16f))
            .pointerInput(holdMillis) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            onConfirmed()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            job.cancel()
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .background(danger.copy(alpha = 0.45f)),
        )
        Text(label, color = danger, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
