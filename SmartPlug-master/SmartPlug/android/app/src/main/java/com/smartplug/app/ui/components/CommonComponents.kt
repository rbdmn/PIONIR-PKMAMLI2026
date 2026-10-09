package com.smartplug.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.ui.theme.AccentAmber
import com.smartplug.app.ui.theme.AccentGreen
import com.smartplug.app.ui.theme.AccentRed
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier, label: String = "Memuat...") {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitLoadingIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Three quiet expanding rings, based on the proven SmartDispenser Perso/Topup
 * startup motif.  It is used for both full-screen and compact in-place waits.
 */
@Composable
fun OrbitLoadingIndicator(modifier: Modifier = Modifier.size(64.dp), color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "orbitLoading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_250, easing = LinearEasing)),
        label = "orbitPhase",
    )
    Canvas(modifier = modifier) {
        val base = size.minDimension * 0.18f
        repeat(3) { index ->
            val wave = (phase + index / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = 0.46f * (1f - wave)),
                radius = base + size.minDimension * 0.31f * wave,
                style = Stroke(width = (size.minDimension * 0.035f).coerceAtLeast(1f)),
            )
        }
        drawCircle(color = color, radius = base, style = Stroke(width = (size.minDimension * 0.055f).coerceAtLeast(1f)))
    }
}

@Composable
fun FullScreenError(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val language = LocalAppLanguage.current
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text(localized(language, "Coba lagi", "Try again")) }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

/** Small colored dot + label used for relay/connection status everywhere in the app. */
@Composable
fun StatusPill(label: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pulse) {
            PulsingDot(color = color)
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun relayStatusColor(state: RelayState): Color = when (state) {
    RelayState.ON -> AccentGreen
    RelayState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    RelayState.UNKNOWN -> AccentAmber
}

@Composable
fun relayStatusLabel(state: RelayState): String {
    val language = LocalAppLanguage.current
    return when (state) {
        RelayState.ON -> localized(language, "Menyala", "On")
        RelayState.OFF -> localized(language, "Mati", "Off")
        RelayState.UNKNOWN -> localized(language, "Tidak diketahui", "Unknown")
    }
}

/** Card shell shared by measurement tiles and device rows: rounded, elevated a touch on state
 * change so a fresh reading feels alive without being a distracting animation loop. */
@Composable
fun SmartPlugCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    containerColor: Color? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val elevation by animateDpAsState(
        targetValue = if (highlighted) 6.dp else 2.dp,
        animationSpec = tween(220),
        label = "cardElevation",
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = containerColor ?: MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope
