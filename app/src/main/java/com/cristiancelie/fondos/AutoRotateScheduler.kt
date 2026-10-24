// © 2026 Cristian Luis Celie. All rights reserved. Portfolio excerpt from Fondos: shared for review only, not licensed for use, copying or redistribution.
package com.cristiancelie.fondos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object AutoRotateScheduler {
    private const val REQUEST_CODE = 1001

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutoRotateReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intervalMs = intervalMinutes.coerceAtLeast(15) * 60_000L
        try {
            // ELAPSED_REALTIME (not _WAKEUP): fine for a wallpaper rotation, doesn't need to wake
            // the device, and the OS may still batch/delay it under Doze — that's acceptable here.
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + intervalMs,
                intervalMs,
                pendingIntent(context)
            )
        } catch (_: Exception) {
            // best-effort; auto-rotate just won't fire on this device
        }
    }

    /** Re-arms the alarm using whatever interval is currently saved — used after a device reboot
     * (alarms don't survive one) and after each rotation tick (setInexactRepeating already repeats,
     * but re-scheduling here keeps behavior correct if the interval preference changed meanwhile). */
    fun reschedule(context: Context) {
        val prefs = WallpaperPrefs.prefs(context)
        if (prefs.getBoolean(WallpaperPrefs.KEY_AUTO_ROTATE_ENABLED, false)) {
            schedule(
                context,
                prefs.getInt(WallpaperPrefs.KEY_AUTO_ROTATE_INTERVAL_MIN, WallpaperPrefs.DEFAULT_AUTO_ROTATE_INTERVAL_MIN)
            )
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarmManager.cancel(pendingIntent(context))
        } catch (_: Exception) {
            // best-effort
        }
    }
}
