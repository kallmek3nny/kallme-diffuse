# Diffuse Reborn

Diffuse Reborn is an Android live wallpaper that turns the artwork from the currently playing track into a slowly moving, animated color field. The field is blurred and warped with animated fractal noise, with optional beat response and now-playing details.

## Features

- Reads track title, artist, album art, and playback state from Android media sessions, with a notification-metadata fallback.
- Extracts colors from album art and crossfades between tracks.
- Draws the OG Diffuse field with an animated noise warp. Android 13 (API 33) and newer use a `RuntimeShader`; Android 11 and 12 use a drifting fallback.
- Offers controls for field scale and speed, transitions, beat strength, color, vignette, and grain.
- Supports optional centered album art and a now-playing overlay, with position and size controls.
- Lets you save and load presets, filter music apps with allow/block lists, and enable a power-saving frame rate.
- Includes a standalone [OG Diffuse browser preview](preview/og-diffuse-preview.html).

## Requirements

- Android 11 or newer (minimum SDK 30).
- Android SDK Platform 36 to build the current app configuration.
- Android Studio or a compatible JDK installation. The repository includes the Gradle 8.13 wrapper; the Android Gradle Plugin version is declared in `gradle/libs.versions.toml`.

## Build

From the project root, build a debug APK with the Gradle wrapper.

**Windows PowerShell**

```powershell
.\gradlew.bat :app:assembleDebug
```

**macOS or Linux**

```bash
./gradlew :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To install it on a connected device, run `.\gradlew.bat :app:installDebug` on Windows or `./gradlew :app:installDebug` on macOS/Linux.

## Set up the wallpaper

1. Install and open **Diffuse Reborn** on an Android 11 or newer device.
2. Grant **notification access** in Android settings. Diffuse uses it to access active media-session information and supported playback notification metadata.
3. If you want audio-level beat response, grant the optional **microphone** permission. The wallpaper can still render without it.
4. Choose **Set as live wallpaper**, then confirm it in Android's wallpaper picker.
5. Start playback in a music app that publishes a media session or playback notification.

The settings screen includes the field, color, beat, artwork, and now-playing controls, along with presets and music-source filters.

## Project layout

```text
app/src/main/java/com/gushypushy/diffusereborn/
  MainActivity.kt                 Settings and setup UI
  MusicListenerService.kt         Active playback metadata and album-art colors
  DiffuseWallpaperService.kt      Live wallpaper renderer and optional audio response
  ClassicActivity.kt              Classic settings entry point
app/src/main/java/DiffusePreviewView.kt  Animated preview used by the app UI
app/src/main/res/                 Android layouts, icons, and other resources
preview/og-diffuse-preview.html   Standalone browser preview
```

## Permissions and data

Track information and album art are read through Android's media-session and notification APIs. Beat response uses Android's audio visualizer when microphone access is granted. The app manifest does not request internet access.

## License

No license file is included yet.
