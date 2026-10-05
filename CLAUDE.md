# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

DiffuseReborn is an Android **live wallpaper** that renders an animated, album-art-driven "diffuse" background reacting to whatever music is playing on the device. It is a from-scratch reimplementation of the original closed-source "Diffuse" app.

- Language: Kotlin. Build: Gradle (wrapper 8.13). `compileSdk`/`targetSdk` 36, `minSdk` 30, `jvmTarget` 11.
- Single module: `:app`. No test suites beyond the AndroidStudio-generated `ExampleInstrumentedTest`.

## Commands

```bash
./gradlew :app:assembleDebug        # build debug APK
./gradlew :app:compileDebugKotlin   # fast compile check (use this for iterating on .kt changes)
./gradlew :app:installDebug         # install to a connected device/emulator
```

There is no meaningful test/lint setup; `compileDebugKotlin` is the primary correctness gate. Note `_JAVA_OPTIONS` may be set in the environment (prints a harmless banner).

To see a rendering change you must **install to a device**, set DiffuseReborn as the live wallpaper (MainActivity has a "set wallpaper" button that fires `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER`), grant the **Notification Listener** permission (Settings deep-link is in MainActivity), and play music. Rendering cannot be verified from a headless build.

## Architecture

Three components run in the app, coordinated by **static state + broadcasts + SharedPreferences** rather than by direct calls or DI:

- **`MusicListenerService`** (a `NotificationListenerService`) — the *data producer*. It finds the active `MediaController` via `MediaSessionManager.getActiveSessions`, pulls album art + title/artist + play state, and runs `androidx.palette` to extract a color list. All of this is published as **companion-object (static) fields**: `currentColors`, `currentAlbumArt`, `currentTitle`, `currentArtist`, `currentIsPlaying`, `currentPackageName`. It broadcasts `ACTION_METADATA_CHANGED` (track changed) and `ACTION_SURGE` (beat/transition pulse).

- **`DiffuseWallpaperService`** (a `WallpaperService`; inner `DiffuseEngine`) — the *renderer/consumer*, and by far the largest file (~2000 lines). Each frame it reads the static fields above, runs a `Choreographer`-driven `doFrame` loop, locks the surface `Canvas`, and draws. Audio reactivity comes from an `android.media.audiofx.Visualizer` (FFT → `bassEnergy`/`trebleEnergy`). It listens for `ACTION_SURGE` and screen on/off broadcasts.

- **`MainActivity`** — the *settings UI*. Every control writes to `SharedPreferences`. `DiffuseEngine` implements `OnSharedPreferenceChangeListener` and re-reads everything via `applySettings()` on any change, so tuning is live.

`DiffusePreviewView`, `NoiseOverlayView`, `OrbitBorderView` are UI-side views used by MainActivity to preview the effect; they also read `MusicListenerService.currentColors`.

### The SharedPreferences contract (important)

All tuning flows through one prefs file named **`"diffuse_prefs"`** (`MODE_PRIVATE`), read/written with **plain string-literal keys** (e.g. `"render_mode_name"`, `"speed"`, `"beat"`, `"vignette"`, `"light_mode"`). There is **no shared constants file** — the same literal is duplicated between MainActivity (writer) and DiffuseWallpaperService (reader). When adding a setting, update **both sides** with the identical string, and add a default in the reader. Saved presets use a separate `"diffuse_presets"` prefs file (JSON blobs).

### Render modes

`render_mode_name` selects the look. `DiffuseEngine` sets boolean flags (`isOgDiffuseMode`, `isOgFluidMode`, `isClassicMode`, `isGlassRenderMode()`, plus `liquidVariant`) from that string and the `draw()` dispatch branches on them. Modes include Liquid Glass (default + variants), Liquid (Reborn), Classic, OG Diffuse, OG Fluid. Colors crossfade via `oldColors`/`targetColors`/`fadeProgress` whenever `MusicListenerService.currentColors` changes mid-frame.

**OG Diffuse** is the one mode that faithfully reproduces the original app's OpenGL pipeline: a heavily blurred album-art color field domain-warped by animated FBM noise. It is implemented with an **AGSL `RuntimeShader`** (`OG_DIFFUSE_AGSL`, a direct port of the original `noise.frag`) on API 33+, with a matrix-drift fallback below 33. The ground-truth reference for this mode is the original decompiled source tree, if available locally — specifically `app/src/main/java/p215y5/Player.java` (the GL renderer) and `app/src/main/assets/*.frag|*.vert` (the shaders). Consult those when tuning OG Diffuse fidelity.

## Repo hygiene note

The project root and `app/src/main/` are cluttered with scratch artifacts unrelated to the build: many `*.png` screenshots, dated `.txt`/`.apk` snapshots (`good-*.txt`, etc.), loose `.java`/`.js` files (Spotify hooks/bypass experiments), and a `merge.py`. These are not part of the app — ignore them when reasoning about the codebase; the real source is under `app/src/main/java/com/gushypushy/diffusereborn/` plus the top-level `DiffusePreviewView.kt`.
