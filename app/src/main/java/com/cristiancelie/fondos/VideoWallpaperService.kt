// © 2026 Cristian Luis Celie. All rights reserved. Portfolio excerpt from Fondos: shared for review only, not licensed for use, copying or redistribution.
package com.cristiancelie.fondos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

private const val STALL_TIMEOUT_MS = 15_000L
private const val WATCHDOG_INTERVAL_MS = 5_000L

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var mediaPlayer: MediaPlayer? = null
        private var renderer: GLWallpaperRenderer? = null
        private var isVisible = false
        private var userPaused = false
        private var trimStartMs = 0L
        private var trimEndMs = WallpaperPrefs.NO_TRIM_END

        private val handler = Handler(Looper.getMainLooper())
        private val trimWatcher = object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player != null && isVisible && trimEndMs != WallpaperPrefs.NO_TRIM_END) {
                    try {
                        if (player.currentPosition >= trimEndMs) {
                            player.seekTo(trimStartMs.toInt())
                        }
                    } catch (_: IllegalStateException) {
                        // player mid-transition; skip this tick
                    }
                }
                handler.postDelayed(this, 200L)
            }
        }

        // Self-healing: some devices silently stall the decoder (or reclaim it under memory
        // pressure) after very long continuous playback, with no error callback at all — the
        // wallpaper just freezes on the last frame forever. If no new frame has been drawn in
        // STALL_TIMEOUT_MS while visible, force a full restart rather than staying frozen.
        private val stallWatchdog = object : Runnable {
            override fun run() {
                val r = renderer
                if (r != null && isVisible && !userPaused && !isPowerSaveActive()) {
                    val stalledMs = r.millisSinceLastFrame()
                    if (stalledMs > STALL_TIMEOUT_MS) {
                        startPlayback(surfaceHolder)
                    }
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }

        private val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val player = mediaPlayer ?: return
                try {
                    if (isPowerSaveActive()) {
                        if (player.isPlaying) player.pause()
                    } else if (isVisible && !userPaused && !player.isPlaying) {
                        player.start()
                    }
                } catch (_: IllegalStateException) {
                    // ignore transient state races
                }
            }
        }

        private val prefs: SharedPreferences
            get() = WallpaperPrefs.prefs(this@VideoWallpaperService)

        private fun isPowerSaveActive(): Boolean = try {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }

        /** Only starts playback when visible, not paused by the user, and not in battery saver. */
        private fun startIfAllowed(player: MediaPlayer) {
            if (isVisible && !userPaused && !isPowerSaveActive()) {
                try {
                    player.start()
                } catch (_: IllegalStateException) {
                    // ignore transient state races
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            try {
                registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            } catch (_: Exception) {
                // best-effort; battery saver detection just won't kick in on this device
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            try {
                val frame = holder.surfaceFrame
                val gl = GLWallpaperRenderer()
                renderer = if (gl.start(holder.surface, frame.width(), frame.height())) gl else null
            } catch (_: Exception) {
                renderer = null
            }
            startPlayback(holder)
            handler.post(trimWatcher)
            handler.postDelayed(stallWatchdog, WATCHDOG_INTERVAL_MS)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.resize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            handler.removeCallbacks(trimWatcher)
            handler.removeCallbacks(stallWatchdog)
            releasePlayer()
            renderer?.stop()
            renderer = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            val player = mediaPlayer ?: return
            try {
                if (visible) {
                    startIfAllowed(player)
                } else if (player.isPlaying) {
                    player.pause()
                }
            } catch (_: IllegalStateException) {
                // ignore transient state races
            }
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == "android.wallpaper.tap") {
                userPaused = !userPaused
                val player = mediaPlayer
                if (player != null) {
                    try {
                        if (userPaused) {
                            if (player.isPlaying) player.pause()
                        } else {
                            startIfAllowed(player)
                        }
                    } catch (_: IllegalStateException) {
                        // ignore transient state races
                    }
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val player = mediaPlayer
            when (key) {
                WallpaperPrefs.KEY_VIDEO_URI -> startPlayback(surfaceHolder)
                WallpaperPrefs.KEY_VOLUME -> {
                    val volume = prefs.getFloat(WallpaperPrefs.KEY_VOLUME, WallpaperPrefs.DEFAULT_VOLUME)
                    player?.setVolume(volume, volume)
                }
                WallpaperPrefs.KEY_SPEED -> {
                    val speed = prefs.getFloat(WallpaperPrefs.KEY_SPEED, WallpaperPrefs.DEFAULT_SPEED)
                    try {
                        player?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
                    } catch (_: Exception) {
                        // unsupported speed for this device/codec; keep previous speed
                    }
                }
                WallpaperPrefs.KEY_TRIM_START, WallpaperPrefs.KEY_TRIM_END -> {
                    loadTrimBounds()
                    player?.let { applyLoopMode(it) }
                }
                WallpaperPrefs.KEY_PAN_X, WallpaperPrefs.KEY_PAN_Y -> applyPan()
                WallpaperPrefs.KEY_DIM -> applyDim()
                WallpaperPrefs.KEY_ROTATION -> applyRotation()
                WallpaperPrefs.KEY_ZOOM -> applyZoom()
                WallpaperPrefs.KEY_BRIGHTNESS, WallpaperPrefs.KEY_CONTRAST, WallpaperPrefs.KEY_SATURATION -> applyColorAdjust()
            }
        }

        private fun applyPan() {
            val panX = prefs.getFloat(WallpaperPrefs.KEY_PAN_X, WallpaperPrefs.DEFAULT_PAN)
            val panY = prefs.getFloat(WallpaperPrefs.KEY_PAN_Y, WallpaperPrefs.DEFAULT_PAN)
            renderer?.setPan(panX, panY)
        }

        private fun applyDim() {
            renderer?.setDim(prefs.getFloat(WallpaperPrefs.KEY_DIM, WallpaperPrefs.DEFAULT_DIM))
        }

        private fun applyRotation() {
            renderer?.setRotation(prefs.getInt(WallpaperPrefs.KEY_ROTATION, WallpaperPrefs.DEFAULT_ROTATION))
        }

        private fun applyZoom() {
            renderer?.setZoom(prefs.getFloat(WallpaperPrefs.KEY_ZOOM, WallpaperPrefs.DEFAULT_ZOOM))
        }

        private fun applyColorAdjust() {
            renderer?.setColorAdjust(
                prefs.getFloat(WallpaperPrefs.KEY_BRIGHTNESS, WallpaperPrefs.DEFAULT_BRIGHTNESS),
                prefs.getFloat(WallpaperPrefs.KEY_CONTRAST, WallpaperPrefs.DEFAULT_CONTRAST),
                prefs.getFloat(WallpaperPrefs.KEY_SATURATION, WallpaperPrefs.DEFAULT_SATURATION)
            )
        }

        /**
         * Untrimmed videos use MediaPlayer's native isLooping, which restarts seamlessly.
         * A trim range needs a manual seek-and-restart on completion, which can show a
         * brief stutter at the loop point — so it's only used when actually trimming.
         */
        private fun applyLoopMode(player: MediaPlayer) {
            val hasTrim = trimStartMs > 0L || trimEndMs != WallpaperPrefs.NO_TRIM_END
            player.isLooping = !hasTrim
            if (hasTrim) {
                player.setOnCompletionListener { p ->
                    p.seekTo(trimStartMs.toInt())
                    startIfAllowed(p)
                }
            } else {
                player.setOnCompletionListener(null)
            }
        }

        private fun loadTrimBounds() {
            trimStartMs = prefs.getLong(WallpaperPrefs.KEY_TRIM_START, 0L)
            trimEndMs = prefs.getLong(WallpaperPrefs.KEY_TRIM_END, WallpaperPrefs.NO_TRIM_END)
        }

        private fun startPlayback(holder: SurfaceHolder) {
            releasePlayer()
            loadTrimBounds()

            val uriString = prefs.getString(WallpaperPrefs.KEY_VIDEO_URI, null) ?: return
            val uri = Uri.parse(uriString)
            val volume = prefs.getFloat(WallpaperPrefs.KEY_VOLUME, WallpaperPrefs.DEFAULT_VOLUME)
            val speed = prefs.getFloat(WallpaperPrefs.KEY_SPEED, WallpaperPrefs.DEFAULT_SPEED)
            val activeRenderer = renderer

            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(applicationContext, uri)
                    if (activeRenderer != null) {
                        setSurface(activeRenderer.inputSurface)
                    } else {
                        // GL setup failed on this device — fall back to a fixed center crop.
                        setSurface(holder.surface)
                        setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    }
                    setVolume(volume, volume)
                    applyLoopMode(this)
                    // A dead codec/server after very long playback surfaces here with no other
                    // signal — recreate the player from scratch instead of leaving it frozen.
                    setOnErrorListener { _, _, _ ->
                        handler.post { startPlayback(holder) }
                        true
                    }
                    setOnVideoSizeChangedListener { _, width, height ->
                        activeRenderer?.setVideoSize(width, height)
                    }
                    setOnPreparedListener { player ->
                        activeRenderer?.setVideoSize(player.videoWidth, player.videoHeight)
                        applyPan()
                        applyDim()
                        applyRotation()
                        applyZoom()
                        applyColorAdjust()
                        try {
                            player.playbackParams = player.playbackParams.setSpeed(speed)
                        } catch (_: Exception) {
                            // unsupported speed; keep default
                        }
                        if (trimStartMs > 0) player.seekTo(trimStartMs.toInt())
                        startIfAllowed(player)
                    }
                    prepareAsync()
                } catch (e: Exception) {
                    release()
                    mediaPlayer = null
                }
            }
        }

        private fun releasePlayer() {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: IllegalStateException) {
                    // player was not in a state that allows stop(); safe to ignore
                }
                it.release()
            }
            mediaPlayer = null
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(trimWatcher)
            handler.removeCallbacks(stallWatchdog)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try {
                unregisterReceiver(powerSaveReceiver)
            } catch (_: Exception) {
                // was never registered; ignore
            }
            releasePlayer()
            renderer?.stop()
            renderer = null
        }
    }
}
