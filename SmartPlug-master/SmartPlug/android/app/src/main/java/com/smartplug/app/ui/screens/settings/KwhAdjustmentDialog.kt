package com.smartplug.app.ui.screens.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.ui.components.SheetDialog
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.util.EnergyAdjustment
import com.smartplug.app.util.applyEnergyAdjustment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private fun fmtPercent(p: Double) = String.format(Locale.US, "%.2f", p)
private fun fmtKwh(v: Double) = String.format(Locale.US, "%.4f", v)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KwhAdjustmentDialog(
    devices: List<SmartPlugDevice>,
    adjustments: Map<String, Double>,
    loadRawKwh: suspend (SmartPlugDevice) -> Double?,
    onSet: (deviceId: String, percent: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    var selectedId by remember { mutableStateOf(devices.firstOrNull()?.deviceId) }
    val device = devices.firstOrNull { it.deviceId == selectedId }

    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Penyesuaian kWh", "kWh adjustment")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (device == null) {
                    Text(localized(language, "Belum ada SmartPlug tersimpan.", "No saved SmartPlug."))
                    return@Column
                }
                if (devices.size > 1) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        devices.forEach { item ->
                            FilterChip(
                                selected = item.deviceId == selectedId,
                                onClick = { selectedId = item.deviceId },
                                label = { Text(item.displayName) },
                            )
                        }
                    }
                } else {
                    Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                }
                AdjustmentControls(
                    device = device,
                    savedPercent = adjustments[device.deviceId] ?: 0.0,
                    loadRawKwh = loadRawKwh,
                    onSet = onSet,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Selesai", "Done")) } },
    )
}

@Composable
private fun AdjustmentControls(
    device: SmartPlugDevice,
    savedPercent: Double,
    loadRawKwh: suspend (SmartPlugDevice) -> Double?,
    onSet: (String, Double) -> Unit,
) {
    val language = LocalAppLanguage.current
    // null = still following the saved value (it can arrive after the dialog opens).
    var edited by remember(device.deviceId) { mutableStateOf<Double?>(null) }
    var typed by remember(device.deviceId) { mutableStateOf<String?>(null) }
    val percent = edited ?: savedPercent
    val latestOnSet by rememberUpdatedState(onSet)

    fun setPercent(value: Double) {
        edited = EnergyAdjustment.normalize(value)
        typed = null
    }

    // Persist after a short pause so dragging the slider does not write on every frame.
    LaunchedEffect(device.deviceId, edited) {
        val value = edited ?: return@LaunchedEffect
        delay(150)
        latestOnSet(device.deviceId, value)
    }

    var rawKwh by remember(device.deviceId) { mutableStateOf<Double?>(null) }
    var rawLoaded by remember(device.deviceId) { mutableStateOf(false) }
    LaunchedEffect(device.deviceId) {
        rawKwh = runCatching { loadRawKwh(device) }.getOrNull()
        rawLoaded = true
    }

    Text(
        EnergyAdjustment.formatPercent(percent),
        style = MaterialTheme.typography.headlineMedium,
        color = if (EnergyAdjustment.isAdjusted(percent)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )

    // Continuous slider (no `steps`): tens of thousands of ticks would make it very slow to draw.
    Slider(
        value = EnergyAdjustment.percentToSlider(percent),
        onValueChange = { setPercent(EnergyAdjustment.sliderToPercent(it)) },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("-99,99%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("0%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("+300%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RepeatStepButton(isPlus = false, onStep = { delta -> setPercent((edited ?: savedPercent) - delta) })
        OutlinedTextField(
            value = typed ?: fmtPercent(percent),
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
                    .let { s -> if (s.indexOf('-', 1) >= 0) s.take(1) + s.drop(1).replace("-", "") else s }
                typed = cleaned
                // Empty / "-" / "." are ignored while typing; anything else is clamped and applied.
                EnergyAdjustment.parseInput(cleaned)?.let { edited = it }
            },
            label = { Text(localized(language, "Persen (%)", "Percent (%)")) },
            singleLine = true,
            isError = typed != null && EnergyAdjustment.parseInput(typed.orEmpty()) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f),
        )
        RepeatStepButton(isPlus = true, onStep = { delta -> setPercent((edited ?: savedPercent) + delta) })
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // The decimal keypad on many phones has no minus key, so the sign can be flipped here.
        OutlinedButton(onClick = { setPercent(-(edited ?: savedPercent)) }) { Text("±") }
        OutlinedButton(onClick = { setPercent(0.0) }) {
            Text(localized(language, "Reset ke 0%", "Reset to 0%"))
        }
    }

    val previewText = when {
        !rawLoaded -> localized(language, "kWh sekarang: memuat…", "Current kWh: loading…")
        rawKwh == null -> localized(language, "kWh sekarang: tidak terbaca (perangkat offline)", "Current kWh: unavailable (device offline)")
        else -> {
            val raw = rawKwh!!.coerceAtLeast(0.0)
            localized(
                language,
                "kWh sekarang ${fmtKwh(raw)} -> ${fmtKwh(applyEnergyAdjustment(raw, percent))}",
                "Current kWh ${fmtKwh(raw)} -> ${fmtKwh(applyEnergyAdjustment(raw, percent))}",
            )
        }
    }
    Text(previewText, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)

    Text(
        localized(
            language,
            "Hanya mengubah tampilan di HP ini. Counter di SmartPlug tidak berubah; HP lain dan server tetap menampilkan nilai asli.",
            "Only changes the display on this phone. The SmartPlug counter is not changed; other phones and the server still show the original value.",
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** -/+ button: one fine step (0.01%) per tap; holding repeats and speeds up the longer it is held. */
@Composable
private fun RepeatStepButton(isPlus: Boolean, onStep: (Double) -> Unit) {
    val scope = rememberCoroutineScope()
    val latestStep by rememberUpdatedState(onStep)
    // A plain Surface, not an IconButton: a button's own click handling consumes the press before
    // this long-press-repeat detector can see it.
    androidx.compose.material3.Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        latestStep(0.01)
                        val job = scope.launch {
                            delay(400)
                            var held = 0
                            while (true) {
                                val step = when {
                                    held < 15 -> 0.01
                                    held < 40 -> 0.1
                                    else -> 1.0
                                }
                                latestStep(step)
                                held++
                                delay(60)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    },
                )
            },
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            Icon(
                if (isPlus) Icons.Filled.Add else Icons.Filled.Remove,
                contentDescription = if (isPlus) "+0.01%" else "-0.01%",
            )
        }
    }
}
