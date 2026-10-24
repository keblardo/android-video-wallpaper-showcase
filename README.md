<h1 align="center">Fondos — video live wallpapers for Android</h1>

<p align="center">
  Android · Kotlin · OpenGL ES 2.0 · EGL · WallpaperService · MediaPlayer
</p>

> **Portfolio excerpt — not the full source.** This repository contains a small selection of files from a
> private project so reviewers can see how I write code. The UI, resources and build configuration are
> intentionally left out. All rights reserved (see [LICENSE](LICENSE)).

## What it is

An Android app that turns any video from the phone into a live wallpaper, and lets you adjust how it looks
before setting it. I built it for my own phone; it is not on Google Play.

- **Framing:** pan to choose which part of the video is visible, zoom up to 3×, and rotate 90/180/270° to fix
  sideways recordings.
- **Look:** brightness, contrast, saturation and dimming, applied live on the GPU.
- **Playback:** trim start and end, playback speed, volume. Tap the home screen to pause or resume.
- **Battery:** playback stops when the wallpaper isn't visible and pauses automatically in battery saver.
- **Favorites and rotation:** save a video with all its settings as a preset (up to 12 favorites plus 5 recent ones)
  and let the app switch to a random favorite on a schedule, even after a reboot.
- **Self-healing:** a watchdog restarts playback if the decoder silently stalls after hours of playback.

## Status

| | |
|---|---|
| Built | August 22–29, 2026 |
| Distribution | Personal use, not published |
| Version | 4.0 (versionCode 18), compileSdk 36, minSdk 24 |
| Size | About 2,100 lines of Kotlin |

## How the rendering works

Android's `MediaPlayer` can only stretch or center-crop video on a surface; it can't choose *which* part of the frame
to show. So instead of drawing straight to the wallpaper surface, the video is decoded into a `SurfaceTexture`
(an OES external texture) and drawn by my own OpenGL ES 2.0 renderer:

1. EGL context on a dedicated render thread, bound to the wallpaper's surface.
2. Every new frame: rotation matrix, then a crop sub-rectangle computed from the video and screen aspect ratios,
   zoom and pan, then the SurfaceTexture's own transform. All of it is done in texture space, so a 90° rotation never stretches the image.
3. A fragment shader applies saturation, contrast, brightness and dimming.

If GL setup fails on a device, the service falls back to `MediaPlayer`'s plain center crop.

## What's in this excerpt

| File | What it shows |
|---|---|
| `GLWallpaperRenderer.kt` | EGL/GLES 2.0 renderer for an external video texture: crop/pan/zoom/rotation matrices, color shader, thread-safe API, bounded waits so a stuck GL thread can't hang the app |
| `VideoWallpaperService.kt` | `WallpaperService` engine: visibility and battery-saver handling, tap to pause, trim looping, live settings through SharedPreferences, stall watchdog and error recovery |
| `WallpaperPrefs.kt` | Settings and presets (favorites/recents), with backward-compatible deserialization of older presets |
| `AutoRotateScheduler.kt`, `AutoRotateReceiver.kt`, `BootReceiver.kt` | Scheduled rotation between favorites with `AlarmManager`, re-armed after reboot |

All files are under `app/src/main/java/com/cristiancelie/fondos/`.

**Not included on purpose:** the activities and editor screens, layouts, resources, and the Gradle build.
The excerpt can't be built or run.

---

Built by **Cristian Luis Celie**: [GitHub profile and CV](https://github.com/keblardo) · cristiancelie07@gmail.com
