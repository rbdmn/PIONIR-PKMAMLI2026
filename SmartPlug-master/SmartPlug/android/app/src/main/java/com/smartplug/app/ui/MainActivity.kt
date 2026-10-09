package com.smartplug.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.local.AppSettings
import com.smartplug.app.ui.navigation.SmartPlugNavHost
import com.smartplug.app.ui.theme.SmartPlugTheme
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.util.CrashLogStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {
    val settings = appPreferences.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    fun toggleSidebarHidden(current: Boolean) {
        viewModelScope.launch { appPreferences.setSidebarHidden(!current) }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val shellViewModel: AppShellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read once at process start, not per-recomposition: consumeLast() clears the stored
        // value, so calling it more than once would just show it the first time and lose it.
        val crashText = CrashLogStore.consumeLast(applicationContext)
        setContent {
            val settings by shellViewModel.settings.collectAsState()
            CompositionLocalProvider(
                LocalAppLanguage provides settings.language,
                com.smartplug.app.ui.components.LocalCostConfig provides
                    com.smartplug.app.ui.components.CostConfig(settings.costEnabled, settings.tariffPerKwh),
                com.smartplug.app.util.LocalEnergyAdjustments provides settings.kwhAdjustments,
                com.smartplug.app.util.LocalKwhDecimals provides settings.kwhDecimals,
                com.smartplug.app.ui.components.LocalDailySummaryEnabled provides settings.dailySummaryEnabled,
            ) {
                SmartPlugTheme(themeMode = settings.themeMode) {
                    com.smartplug.app.ui.components.TapFeedbackHost(modifier = Modifier.fillMaxSize()) {
                        SmartPlugNavHost(
                            sidebarHidden = settings.sidebarHidden,
                            onToggleSidebarHidden = { shellViewModel.toggleSidebarHidden(settings.sidebarHidden) },
                        )
                    }
                    if (crashText != null) {
                        CrashReportDialog(crashText)
                    }
                }
            }
        }
    }
}

/**
 * Shown once, right after the app auto-restarts from an unhandled crash (see
 * [com.smartplug.app.SmartPlugApplication]). Lets the user get the exact error text out of the
 * device with a single tap — no ADB/USB debugging setup needed to report a bug from the field.
 */
@Composable
private fun CrashReportDialog(crashText: String) {
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Aplikasi sempat berhenti") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Aplikasi otomatis dijalankan ulang setelah mengalami error. Salin detail di bawah " +
                        "dan kirim ke pengembang supaya bisa diperbaiki:",
                )
                Text(
                    crashText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("SmartPlug crash log", crashText))
                visible = false
            }) { Text("Salin") }
        },
        dismissButton = {
            TextButton(onClick = { visible = false }) { Text("Tutup") }
        },
    )
}
