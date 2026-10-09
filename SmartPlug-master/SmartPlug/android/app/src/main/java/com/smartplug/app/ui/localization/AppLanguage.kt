package com.smartplug.app.ui.localization

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage { INDONESIAN, ENGLISH }

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.INDONESIAN }

/** Small, explicit UI dictionary. New visible copy must go through [localized] so the
 * language selector affects the screen immediately without restarting the app. */
fun localized(language: AppLanguage, indonesian: String, english: String): String =
    if (language == AppLanguage.ENGLISH) english else indonesian
