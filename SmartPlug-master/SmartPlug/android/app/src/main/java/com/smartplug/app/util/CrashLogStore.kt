package com.smartplug.app.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the last uncaught-exception stack trace so it survives the process death that follows
 * it, and can be shown to the user on next launch with a "copy" button — no ADB/USB debugging
 * needed to get a real crash report out of a device in the field. Deliberately plain
 * [android.content.SharedPreferences] with a synchronous [android.content.SharedPreferences.Editor.commit],
 * not the app's usual `EncryptedSharedPreferences`: this runs inside an uncaught-exception handler
 * a few hundred milliseconds before the process is killed, so it must be as fast and as unlikely
 * to itself throw as possible, and a stack trace has no sensitive content to protect anyway.
 */
object CrashLogStore {
    private const val PREFS_NAME = "smartplug_crash_log"
    private const val KEY_LAST_CRASH = "last_crash_text"

    fun save(context: Context, threadName: String, throwable: Throwable) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "Waktu: $timestamp\nThread: $threadName\n\n${throwable.stackTraceToString()}"
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH, text)
                .commit()
        }
    }

    /** Returns the saved crash text once, clearing it so the same dialog doesn't reappear forever. */
    fun consumeLast(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_LAST_CRASH, null) ?: return null
        prefs.edit().remove(KEY_LAST_CRASH).apply()
        return text
    }
}
