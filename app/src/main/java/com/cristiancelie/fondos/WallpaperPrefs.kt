// © 2026 Cristian Luis Celie. All rights reserved. Portfolio excerpt from Fondos: shared for review only, not licensed for use, copying or redistribution.
package com.cristiancelie.fondos

import android.content.Context
import android.content.SharedPreferences

data class Preset(
    val uri: String,
    val trimStart: Long,
    val trimEnd: Long,
    val volume: Float,
    val speed: Float,
    val panX: Float,
    val panY: Float,
    val dim: Float,
    val label: String,
    val rotation: Int = 0,
    val zoom: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
)

private const val FIELD_SEP = ""
private const val ENTRY_SEP = ""

object WallpaperPrefs {
    const val PREFS_NAME = "fondos_prefs"

    const val KEY_VIDEO_URI = "video_uri"
    const val KEY_VOLUME = "volume"
    const val KEY_SPEED = "speed"
    const val KEY_TRIM_START = "trim_start_ms"
    const val KEY_TRIM_END = "trim_end_ms"
    const val KEY_PAN_X = "pan_x"
    const val KEY_PAN_Y = "pan_y"
    const val KEY_DIM = "dim"
    const val KEY_ROTATION = "rotation_degrees"
    const val KEY_ZOOM = "zoom"
    const val KEY_BRIGHTNESS = "brightness"
    const val KEY_CONTRAST = "contrast"
    const val KEY_SATURATION = "saturation"
    const val KEY_RECENT_PRESETS = "recent_presets_v1"
    const val KEY_FAVORITE_PRESETS = "favorite_presets_v1"
    const val KEY_AUTO_ROTATE_ENABLED = "auto_rotate_enabled"
    const val KEY_AUTO_ROTATE_INTERVAL_MIN = "auto_rotate_interval_min"

    const val DEFAULT_VOLUME = 0f
    const val DEFAULT_SPEED = 1f
    const val DEFAULT_PAN = 0.5f
    const val DEFAULT_DIM = 0f
    const val DEFAULT_ROTATION = 0
    const val DEFAULT_ZOOM = 1f
    const val MAX_ZOOM = 3f
    const val DEFAULT_BRIGHTNESS = 0f
    const val DEFAULT_CONTRAST = 1f
    const val DEFAULT_SATURATION = 1f
    const val DEFAULT_AUTO_ROTATE_INTERVAL_MIN = 60
    const val NO_TRIM_END = -1L
    const val MAX_RECENT_PRESETS = 5
    const val MAX_FAVORITE_PRESETS = 12

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun serialize(p: Preset): String = listOf(
        p.uri, p.trimStart, p.trimEnd, p.volume, p.speed, p.panX, p.panY, p.dim, p.label,
        p.rotation, p.zoom, p.brightness, p.contrast, p.saturation
    ).joinToString(FIELD_SEP)

    private fun deserialize(s: String): Preset? {
        val parts = s.split(FIELD_SEP)
        // 9 fields = presets saved before rotation/zoom/color existed; default those in.
        if (parts.size != 9 && parts.size != 14) return null
        return try {
            Preset(
                uri = parts[0],
                trimStart = parts[1].toLong(),
                trimEnd = parts[2].toLong(),
                volume = parts[3].toFloat(),
                speed = parts[4].toFloat(),
                panX = parts[5].toFloat(),
                panY = parts[6].toFloat(),
                dim = parts[7].toFloat(),
                label = parts[8],
                rotation = if (parts.size == 14) parts[9].toInt() else DEFAULT_ROTATION,
                zoom = if (parts.size == 14) parts[10].toFloat() else DEFAULT_ZOOM,
                brightness = if (parts.size == 14) parts[11].toFloat() else DEFAULT_BRIGHTNESS,
                contrast = if (parts.size == 14) parts[12].toFloat() else DEFAULT_CONTRAST,
                saturation = if (parts.size == 14) parts[13].toFloat() else DEFAULT_SATURATION
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun readList(context: Context, key: String): List<Preset> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(ENTRY_SEP).mapNotNull { deserialize(it) }
    }

    private fun writeList(context: Context, key: String, presets: List<Preset>) {
        val raw = presets.joinToString(ENTRY_SEP) { serialize(it) }
        prefs(context).edit().putString(key, raw).apply()
    }

    fun getRecentPresets(context: Context): List<Preset> = readList(context, KEY_RECENT_PRESETS)

    fun addRecentPreset(context: Context, preset: Preset) {
        val existing = getRecentPresets(context).filter { it.uri != preset.uri }
        writeList(context, KEY_RECENT_PRESETS, (listOf(preset) + existing).take(MAX_RECENT_PRESETS))
    }

    fun getFavoritePresets(context: Context): List<Preset> = readList(context, KEY_FAVORITE_PRESETS)

    fun isFavorite(context: Context, uri: String): Boolean =
        getFavoritePresets(context).any { it.uri == uri }

    fun addFavoritePreset(context: Context, preset: Preset) {
        val existing = getFavoritePresets(context).filter { it.uri != preset.uri }
        writeList(context, KEY_FAVORITE_PRESETS, (listOf(preset) + existing).take(MAX_FAVORITE_PRESETS))
    }

    fun removeFavoritePreset(context: Context, uri: String) {
        writeList(context, KEY_FAVORITE_PRESETS, getFavoritePresets(context).filter { it.uri != uri })
    }

    /** Applies every field of [preset] to the active prefs — used both when picking a preset by
     * hand and when the auto-rotate alarm advances to the next favorite on its own. */
    fun applyPreset(context: Context, preset: Preset) {
        prefs(context).edit()
            .putString(KEY_VIDEO_URI, preset.uri)
            .putLong(KEY_TRIM_START, preset.trimStart)
            .putLong(KEY_TRIM_END, preset.trimEnd)
            .putFloat(KEY_VOLUME, preset.volume)
            .putFloat(KEY_SPEED, preset.speed)
            .putFloat(KEY_PAN_X, preset.panX)
            .putFloat(KEY_PAN_Y, preset.panY)
            .putFloat(KEY_DIM, preset.dim)
            .putInt(KEY_ROTATION, preset.rotation)
            .putFloat(KEY_ZOOM, preset.zoom)
            .putFloat(KEY_BRIGHTNESS, preset.brightness)
            .putFloat(KEY_CONTRAST, preset.contrast)
            .putFloat(KEY_SATURATION, preset.saturation)
            .apply()
    }
}
