package com.smartplug.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.smartplug.app.ui.localization.AppLanguage

private val Context.dataStore by preferencesDataStore(name = "smartplug_prefs")

private const val KWH_ADJUST_PREFIX = "kwh_adjust_"
private const val MAX_SD_OBSERVATIONS = 200
private const val SD_OBSERVATION_MIN_GAP_MS = 3_600_000L

private fun parseSdObservations(raw: String?): List<com.smartplug.app.util.SdObservation> =
    raw.orEmpty().split(';').mapNotNull { item ->
        val parts = item.split(':')
        if (parts.size != 3) return@mapNotNull null
        val t = parts[0].toLongOrNull() ?: return@mapNotNull null
        val used = parts[1].toLongOrNull() ?: return@mapNotNull null
        val total = parts[2].toLongOrNull() ?: return@mapNotNull null
        com.smartplug.app.util.SdObservation(t, used, total)
    }

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Tap sound choice. [VARIED] keeps one soft timbre but steps through a few harmonically related
 * pitches, so taps sound alike yet never as one monotone beep.
 */
enum class TapSoundStyle(val rawName: String?, val labelId: String, val labelEn: String) {
    VARIED(null, "Variasi", "Varied"),
    CLICK("tap", "Klik", "Click"),
    POP("tap_pop", "Pop", "Pop"),
    TICK("tap_tick", "Tik", "Tick"),
    BUBBLE("tap_bubble", "Gelembung", "Bubble"),
    CHIME("tap_chime", "Lonceng", "Chime"),
    WOOD("tap_wood", "Kayu", "Wood"),
}

data class AppSettings(
    val soundEnabled: Boolean = true,
    val soundStyle: TapSoundStyle = TapSoundStyle.VARIED,
    val kwhDecimals: Int = com.smartplug.app.util.KwhFormat.DEFAULT_DECIMALS,
    val dailySummaryEnabled: Boolean = true,
    val costEnabled: Boolean = false,
    val tariffPerKwh: Double = 0.0,
    /** deviceId -> kWh display adjustment in percent (display-only, per device). */
    val kwhAdjustments: Map<String, Double> = emptyMap(),
    val hapticEnabled: Boolean = true,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val sidebarHidden: Boolean = false,
    val language: AppLanguage = AppLanguage.INDONESIAN,
)

/** Per-device cumulative-kWh limit. App-side only: the device/server contract has no such field. */
data class EnergyLimit(val enabled: Boolean = false, val kwh: Double = 0.0) {
    fun isReached(currentKwh: Double): Boolean = enabled && kwh > 0.0 && currentKwh >= kwh

    /** 0..1 share of the limit already used: current kWh / limit kWh (0.102 / 0.603 = 17%). */
    fun progress(currentKwh: Double): Double =
        if (kwh <= 0.0) 0.0 else (currentKwh / kwh).coerceIn(0.0, 1.0)
}

/** Non-secret UI/app preferences. Secrets live in [SecureTokenStore] instead. */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SOUND_STYLE = stringPreferencesKey("sound_style")
        val COST_ENABLED = booleanPreferencesKey("cost_enabled")
        val KWH_DECIMALS = intPreferencesKey("kwh_decimals")
        val DAILY_SUMMARY = booleanPreferencesKey("daily_summary")
        val TARIFF_PER_KWH = doublePreferencesKey("tariff_per_kwh")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SIDEBAR_HIDDEN = booleanPreferencesKey("sidebar_hidden")
        val LANGUAGE = stringPreferencesKey("language")
        val SD_OBSERVATIONS = stringPreferencesKey("sd_observations")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            soundStyle = prefs[Keys.SOUND_STYLE]?.let { runCatching { TapSoundStyle.valueOf(it) }.getOrNull() }
                ?: TapSoundStyle.VARIED,
            kwhAdjustments = prefs.asMap().mapNotNull { (key, value) ->
                val name = key.name
                if (name.startsWith(KWH_ADJUST_PREFIX) && value is Double) name.removePrefix(KWH_ADJUST_PREFIX) to value else null
            }.toMap(),
            kwhDecimals = com.smartplug.app.util.KwhFormat.clamp(prefs[Keys.KWH_DECIMALS] ?: com.smartplug.app.util.KwhFormat.DEFAULT_DECIMALS),
            dailySummaryEnabled = prefs[Keys.DAILY_SUMMARY] ?: true,
            costEnabled = prefs[Keys.COST_ENABLED] ?: false,
            tariffPerKwh = prefs[Keys.TARIFF_PER_KWH] ?: 0.0,
            hapticEnabled = prefs[Keys.HAPTIC_ENABLED] ?: true,
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
                ?: AppThemeMode.SYSTEM,
            sidebarHidden = prefs[Keys.SIDEBAR_HIDDEN] ?: false,
            language = prefs[Keys.LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.INDONESIAN,
        )
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setSoundStyle(style: TapSoundStyle) {
        context.dataStore.edit { it[Keys.SOUND_STYLE] = style.name }
    }

    fun energyLimit(deviceId: String): Flow<EnergyLimit> = context.dataStore.data.map { prefs ->
        EnergyLimit(
            enabled = prefs[booleanPreferencesKey("limit_enabled_$deviceId")] ?: false,
            kwh = prefs[doublePreferencesKey("limit_kwh_$deviceId")] ?: 0.0,
        )
    }

    suspend fun setEnergyLimit(deviceId: String, enabled: Boolean, kwh: Double) {
        context.dataStore.edit {
            it[booleanPreferencesKey("limit_enabled_$deviceId")] = enabled
            if (kwh.isFinite() && kwh > 0.0) it[doublePreferencesKey("limit_kwh_$deviceId")] = kwh
        }
    }

    fun kwhAdjustPercent(deviceId: String): Flow<Double> = context.dataStore.data.map { prefs ->
        com.smartplug.app.util.EnergyAdjustment.normalize(prefs[doublePreferencesKey("$KWH_ADJUST_PREFIX$deviceId")] ?: 0.0)
    }

    suspend fun setKwhAdjustPercent(deviceId: String, percent: Double) {
        val value = com.smartplug.app.util.EnergyAdjustment.normalize(percent)
        context.dataStore.edit { it[doublePreferencesKey("$KWH_ADJUST_PREFIX$deviceId")] = value }
    }

    /** Locally remembered SD usage samples "timestamp:used:total;..." for the fill-time estimate. */
    val sdObservations: Flow<List<com.smartplug.app.util.SdObservation>> = context.dataStore.data.map { prefs ->
        parseSdObservations(prefs[Keys.SD_OBSERVATIONS])
    }

    /** Records a sample at most once per hour and keeps the newest [MAX_SD_OBSERVATIONS]. */
    suspend fun recordSdObservation(sample: com.smartplug.app.util.SdObservation) {
        context.dataStore.edit { prefs ->
            val list = parseSdObservations(prefs[Keys.SD_OBSERVATIONS])
            val last = list.maxByOrNull { it.timestampMs }
            if (last != null && sample.timestampMs - last.timestampMs < SD_OBSERVATION_MIN_GAP_MS) return@edit
            val merged = (list + sample).sortedBy { it.timestampMs }.takeLast(MAX_SD_OBSERVATIONS)
            prefs[Keys.SD_OBSERVATIONS] = merged.joinToString(";") { "${it.timestampMs}:${it.usedBytes}:${it.totalBytes}" }
        }
    }

    suspend fun clearSdObservations() {
        context.dataStore.edit { it.remove(Keys.SD_OBSERVATIONS) }
    }

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DAILY_SUMMARY] = enabled }
    }

    suspend fun setKwhDecimals(decimals: Int) {
        context.dataStore.edit { it[Keys.KWH_DECIMALS] = com.smartplug.app.util.KwhFormat.clamp(decimals) }
    }

    suspend fun setCostEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COST_ENABLED] = enabled }
    }

    /** A non-positive or non-finite tariff is stored as 0.0, which keeps the estimate inactive. */
    suspend fun setTariffPerKwh(tariff: Double) {
        context.dataStore.edit { it[Keys.TARIFF_PER_KWH] = if (tariff.isFinite() && tariff > 0.0) tariff else 0.0 }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTIC_ENABLED] = enabled }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setSidebarHidden(hidden: Boolean) {
        context.dataStore.edit { it[Keys.SIDEBAR_HIDDEN] = hidden }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    /** Restores non-secret app preferences after an explicit full app reset. */
    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
