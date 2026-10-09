package com.smartplug.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.util.Log
import com.smartplug.app.ui.MainActivity
import com.smartplug.app.util.CrashLogStore
import dagger.hilt.android.HiltAndroidApp
import kotlin.system.exitProcess

@HiltAndroidApp
class SmartPlugApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashRecoveryHandler()
    }

    /**
     * Last-resort safety net, underneath every per-screen `safeLaunch`/`PollWhileVisible` guard
     * elsewhere in the app: an exception that reaches here is one those specific guards didn't
     * anticipate — a Compose recomposition failure, a callback on some Android API thread we don't
     * control, or genuinely new code. Rather than let the OS show the default "SmartPlug keeps
     * stopping" dialog and strand the user mid-onboarding, this logs the failure and schedules a
     * clean relaunch of [MainActivity] a moment after the process dies, so the app comes back up
     * on its own instead of requiring the user to find and reopen it manually.
     */
    private fun installCrashRecoveryHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SmartPlugApplication", "Uncaught exception on ${thread.name}; restarting app", throwable)
            CrashLogStore.save(applicationContext, thread.name, throwable)
            try {
                scheduleRestart()
            } catch (e: Exception) {
                Log.e("SmartPlugApplication", "Failed to schedule restart after crash", e)
            }
            // Still defer to any previously installed handler (e.g. a crash reporting SDK) before
            // this process dies, but only after our restart is already scheduled.
            previousHandler?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun scheduleRestart() {
        val restartIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        // Inexact `set` deliberately: exact alarms need SCHEDULE_EXACT_ALARM on Android 12+, and a
        // restart doesn't need to fire on the millisecond — half a second of slop is invisible.
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pendingIntent)
    }
}
