// © 2026 Cristian Luis Celie. All rights reserved. Portfolio excerpt from Fondos: shared for review only, not licensed for use, copying or redistribution.
package com.cristiancelie.fondos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms don't survive a reboot — re-arm auto-rotate if it was enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutoRotateScheduler.reschedule(context)
        }
    }
}
