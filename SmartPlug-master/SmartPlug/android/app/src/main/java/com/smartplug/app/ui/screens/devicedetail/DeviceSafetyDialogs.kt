package com.smartplug.app.ui.screens.devicedetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.PowerOnPolicy
import com.smartplug.app.ui.components.SheetDialog
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.util.Overcurrent

/**
 * Plain-language warnings shown above the live values: a load over the relay rating, and the
 * "switched off automatically" notice with a way to turn it back on.
 */
@Composable
internal fun LoadWarningBanners(
    currentA: Double?,
    overcurrentFlag: Boolean?,
    tripped: Boolean,
    canRestart: Boolean,
    onRestart: () -> Unit,
) {
    val language = LocalAppLanguage.current
    if (tripped) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    localized(language, "Dimatikan otomatis karena beban terlalu besar", "Switched off automatically: load was too high"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (canRestart) {
                    Button(onClick = onRestart) { Text(localized(language, "Nyalakan lagi", "Turn on again")) }
                }
            }
        }
        return
    }
    if (currentA != null && Overcurrent.isWarning(currentA, overcurrentFlag)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    localized(language, "Beban terlalu besar", "Load is too high"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    localized(language, "Melebihi 2 A. Kurangi alat yang tersambung.", "Above 2 A. Unplug something."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
internal fun ProtectionDialog(enabled: Boolean, error: String?, onChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Proteksi beban", "Load protection")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    localized(
                        language,
                        "Matikan otomatis jika beban terlalu besar (4 A atau lebih). Tidak menyala lagi sendiri sampai Anda menyalakannya.",
                        "Switch off automatically if the load is too high (4 A or more). It stays off until you turn it on.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized(language, "Aktifkan proteksi", "Turn on protection"), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onChange)
                }
                Text(
                    localized(language, "Ini bukan pengganti sekering atau MCB.", "This does not replace a fuse or circuit breaker."),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Selesai", "Done")) } },
    )
}

@Composable
internal fun PowerPolicyDialog(
    current: PowerOnPolicy,
    currentDelaySeconds: Int,
    error: String?,
    onSave: (PowerOnPolicy, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    var policy by remember { mutableStateOf(current) }
    var delay by remember { mutableFloatStateOf(currentDelaySeconds.coerceIn(0, 600).toFloat()) }
    var confirming by remember { mutableStateOf(false) }
    val options = listOf(
        PowerOnPolicy.OFF to localized(language, "Tetap mati", "Stay off"),
        PowerOnPolicy.LAST to localized(language, "Seperti sebelum mati", "Like before the outage"),
        PowerOnPolicy.ON to localized(language, "Selalu menyala", "Always on"),
    )
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Saat listrik kembali", "When power returns")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = policy == value, onClick = { policy = value; confirming = false }, role = Role.RadioButton)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = policy == value, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                if (policy != PowerOnPolicy.OFF) {
                    Text(
                        localized(language, "Tunda menyala: ${delay.toInt()} detik", "Wait before turning on: ${delay.toInt()} s"),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(value = delay, onValueChange = { delay = it }, valueRange = 0f..600f, modifier = Modifier.fillMaxWidth())
                    Text(
                        localized(language, "Agar banyak SmartPlug tidak menyala bersamaan.", "So many SmartPlugs do not switch on at once."),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (confirming) {
                    Text(
                        localized(
                            language,
                            "Alat bisa menyala sendiri setelah listrik kembali. Jangan dipakai untuk pemanas, setrika, atau motor tanpa pengawasan.",
                            "The device can switch on by itself when power returns. Do not use it for heaters, irons or motors without supervision.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (policy != PowerOnPolicy.OFF && !confirming) confirming = true
                else onSave(policy, if (policy == PowerOnPolicy.OFF) currentDelaySeconds else delay.toInt())
            }) {
                Text(if (confirming) localized(language, "Ya, simpan", "Yes, save") else localized(language, "Simpan", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}
