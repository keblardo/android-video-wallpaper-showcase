// © 2026 Cristian Luis Celie. All rights reserved. Portfolio excerpt from Fondos: shared for review only, not licensed for use, copying or redistribution.
package com.cristiancelie.fondos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired on a schedule by [AutoRotateScheduler] — advances the active wallpaper to a random
 * favorite different from the current one. The live wallpaper engine (if running) picks up the
 * change through its own SharedPreferences listener, same as picking a favorite by hand. */
class AutoRotateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val favorites = WallpaperPrefs.getFavoritePresets(context)
        if (favorites.isEmpty()) return
        val currentUri = WallpaperPrefs.prefs(context).getString(WallpaperPrefs.KEY_VIDEO_URI, null)
        val candidates = favorites.filter { it.uri != currentUri }
        val next = (candidates.ifEmpty { favorites }).random()
        WallpaperPrefs.applyPreset(context, next)
    }
}
