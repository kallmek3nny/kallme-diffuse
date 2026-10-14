from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def sub_once(text: str, pattern: str, repl: str, label: str) -> str:
    out, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 regex match, found {count}")
    return out


wallpaper_path = Path("app/src/main/java/com/gushypushy/diffusereborn/DiffuseWallpaperService.kt")
text = wallpaper_path.read_text()

text = replace_once(
    text,
    '                float2x2 rot = float2x2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5));\n',
    '                // Constant-fold the fixed rotation explicitly so every FBM call avoids trig setup.\n'
    '                float2x2 rot = float2x2(0.87758256, 0.47942554, -0.47942554, 0.87758256);\n',
    "constant FBM rotation",
)

text = replace_once(
    text,
    '''        private var centerArtBitmap: Bitmap? = null
        private var lastProcessedArt: Bitmap? = null
        private val centerArtPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
''',
    '''        private var centerArtBitmap: Bitmap? = null
        private var lastProcessedArt: Bitmap? = null
        private val centerArtExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val centerArtHandler = Handler(Looper.getMainLooper())
        @Volatile private var centerArtBuildSource: Bitmap? = null
        @Volatile private var centerArtBuildBlur = Float.NaN
        private val centerArtPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
''',
    "center art worker state",
)

text = replace_once(
    text,
    '''                vignetteBitmap?.recycle()
                centerArtBitmap?.recycle()
                ogBackdropBitmap?.recycle()
''',
    '''                vignetteBitmap?.recycle()
                centerArtExecutor.shutdownNow()
                centerArtHandler.removeCallbacksAndMessages(null)
                centerArtBuildSource = null
                centerArtBitmap?.recycle()
                ogBackdropBitmap?.recycle()
''',
    "center art worker cleanup",
)

text = replace_once(
    text,
    '''        override fun onSurfaceCreated(holder: android.view.SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            if (isVisible) {
                trySetupVisualizer()
                startDrawing()
            }
        }

        override fun onSurfaceDestroyed(holder: android.view.SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            stopVisualizer()
            stopDrawing()
        }
''',
    '''        override fun onSurfaceCreated(holder: android.view.SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            if (isVisible) {
                // updateSettings() may have selected 120 Hz before the Surface existed. Force the
                // frame-rate request again now that Android has a valid presentation surface.
                updateTargetFrameRate(force = true)
                trySetupVisualizer()
                startDrawing()
            }
        }

        override fun onSurfaceDestroyed(holder: android.view.SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            stopVisualizer()
            stopDrawing()
            clearSurfaceFrameRateRequest()
        }
''',
    "surface frame-rate lifecycle",
)

text = replace_once(
    text,
    '''            centerArtScale = 0.5f + (prefs.getInt("center_size", 50).toFloat() / 50f)
            lastProcessedArt = null

            // --- Smart rendering / system ---
''',
    '''            centerArtScale = 0.5f + (prefs.getInt("center_size", 50).toFloat() / 50f)
            lastProcessedArt = null
            if (!isCenterArtEnabled) {
                centerArtBuildSource = null
                centerArtBitmap?.let { if (!it.isRecycled) it.recycle() }
                centerArtBitmap = null
            }

            // --- Smart rendering / system ---
''',
    "disabled center art cleanup",
)

text = replace_once(
    text,
    '''        private fun updateTargetFrameRate(force: Boolean = false) {
            val desired = desiredFrameRate()
            if (!force && abs(desired - TARGET_FPS) < 0.5f) return
            TARGET_FPS = desired
            frameIntervalNs = (1_000_000_000L / TARGET_FPS).toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val surface = surfaceHolder.surface
                    if (surface != null && surface.isValid) {
                        surface.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                } catch (_: Exception) {}
            }
        }
''',
    '''        private fun updateTargetFrameRate(force: Boolean = false) {
            val desired = desiredFrameRate()
            if (!force && abs(desired - TARGET_FPS) < 0.5f) return
            TARGET_FPS = desired
            frameIntervalNs = (1_000_000_000L / TARGET_FPS).toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val surface = surfaceHolder.surface
                    if (surface != null && surface.isValid) {
                        surface.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                } catch (_: Exception) {}
            }
        }

        private fun clearSurfaceFrameRateRequest() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val surface = surfaceHolder.surface
                    if (surface != null && surface.isValid) {
                        // 0 tells Android this surface no longer has a preferred frame rate. This
                        // lets the display drop refresh while the wallpaper is hidden.
                        surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                } catch (_: Exception) {}
            }
        }
''',
    "clear surface frame-rate helper",
)

text = replace_once(
    text,
    '''        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                MusicListenerService.setHomeState(true)
                trySetupVisualizer()
                startDrawing()
            } else {
                MusicListenerService.setHomeState(false)
                stopVisualizer()
                stopDrawing()
            }
        }
''',
    '''        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                MusicListenerService.setHomeState(true)
                // Re-assert the target when the wallpaper becomes the presented surface again.
                updateTargetFrameRate(force = true)
                trySetupVisualizer()
                startDrawing()
            } else {
                MusicListenerService.setHomeState(false)
                stopVisualizer()
                stopDrawing()
                clearSurfaceFrameRateRequest()
            }
        }
''',
    "visibility frame-rate lifecycle",
)

center_worker = r'''        private fun ensureCenterArtPrepared(currentArt: Bitmap?) {
            if (!isCenterArtEnabled || currentArt == null || currentArt.isRecycled) return
            val blur = centerArtBlur
            val ready = centerArtBitmap
            if (currentArt === lastProcessedArt && ready != null && !ready.isRecycled) return
            if (centerArtBuildSource === currentArt && abs(centerArtBuildBlur - blur) < 0.0001f) return

            centerArtBuildSource = currentArt
            centerArtBuildBlur = blur
            centerArtExecutor.execute {
                var tiny: Bitmap? = null
                var built: Bitmap? = null
                try {
                    built = if (blur < 0.05f) {
                        Bitmap.createScaledBitmap(currentArt, 1200, 1200, true)
                    } else {
                        val blurFactor = 1f - (blur * 0.95f)
                        val targetW = (currentArt.width * blurFactor).toInt().coerceAtLeast(10)
                        val targetH = (currentArt.height * blurFactor).toInt().coerceAtLeast(10)
                        tiny = Bitmap.createScaledBitmap(currentArt, targetW, targetH, true)
                        Bitmap.createScaledBitmap(tiny!!, 1000, 1000, true)
                    }
                } catch (_: Exception) {
                    built = null
                } finally {
                    tiny?.let { if (it !== built && !it.isRecycled) it.recycle() }
                }

                centerArtHandler.post {
                    val stillWanted = isCenterArtEnabled &&
                            centerArtBuildSource === currentArt &&
                            abs(centerArtBuildBlur - blur) < 0.0001f &&
                            !currentArt.isRecycled
                    if (!stillWanted) {
                        built?.let { if (!it.isRecycled) it.recycle() }
                        return@post
                    }

                    centerArtBuildSource = null
                    val old = centerArtBitmap
                    centerArtBitmap = built
                    if (built != null) lastProcessedArt = currentArt
                    old?.let { if (it !== built && !it.isRecycled) it.recycle() }
                }
            }
        }

'''
text = replace_once(text, "        private fun draw() {\n", center_worker + "        private fun draw() {\n", "async center art function")

text = sub_once(
    text,
    r'''                    if \(isCenterArtEnabled\) \{\n                        val currentArt = MusicListenerService\.currentAlbumArt\n                        if \(currentArt != null && \(currentArt != lastProcessedArt \|\| centerArtBitmap == null\)\) \{.*?\n                        if \(centerArtBitmap != null\) \{\n                            val drawSize = 800f \* centerArtScale\n                            val halfSize = drawSize / 2f\n                            centerArtPaint\.alpha = centerArtOpacity\n                            destRect\.set\(cx - halfSize, cy - halfSize, cx \+ halfSize, cy \+ halfSize\)\n                            canvas\.drawBitmap\(centerArtBitmap!!, null, destRect, centerArtPaint\)\n                        \}\n                    \}\n''',
    '''                    if (isCenterArtEnabled) {
                        ensureCenterArtPrepared(MusicListenerService.currentAlbumArt)
                        val preparedCenterArt = centerArtBitmap
                        if (preparedCenterArt != null && !preparedCenterArt.isRecycled) {
                            val drawSize = 800f * centerArtScale
                            val halfSize = drawSize / 2f
                            centerArtPaint.alpha = centerArtOpacity
                            destRect.set(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize)
                            canvas.drawBitmap(preparedCenterArt, null, destRect, centerArtPaint)
                        }
                    }
''',
    "center art render path",
)

wallpaper_path.write_text(text)

music_path = Path("app/src/main/java/com/gushypushy/diffusereborn/MusicListenerService.kt")
text = music_path.read_text()

text = replace_once(
    text,
    'import android.media.MediaMetadata\nimport android.media.session.MediaSessionManager\n',
    'import android.media.MediaMetadata\nimport android.media.session.MediaController\nimport android.media.session.MediaSessionManager\n',
    "MediaController import",
)

text = replace_once(
    text,
    '''    private var lastNotifyKey: String = ""
    @Volatile private var lastArtworkKey: String = ""
    @Volatile private var pendingArtworkKey: String = ""

    private val pollRunner = object : Runnable {
''',
    '''    private var lastNotifyKey: String = ""
    @Volatile private var lastArtworkKey: String = ""
    @Volatile private var pendingArtworkKey: String = ""
    private var activeController: MediaController? = null
    private val fallbackPollIntervalMs = 15_000L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            activeController?.let { updateFromController(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            activeController?.let { updateFromController(it) }
        }

        override fun onSessionDestroyed() {
            setActiveController(null)
            if (isHomeVisible) {
                handler.post {
                    if (!checkMediaSession()) checkActiveNotifications()
                }
            }
        }
    }

    private val pollRunner = object : Runnable {
''',
    "media controller callback state",
)

text = replace_once(
    text,
    '            handler.postDelayed(this, 1000)\n',
    '            // Event callbacks handle normal metadata/playback changes. This slow poll is only\n'
    '            // a safety net for media apps that do not reliably publish callbacks.\n'
    '            handler.postDelayed(this, fallbackPollIntervalMs)\n',
    "fallback poll interval",
)

text = replace_once(
    text,
    '''    fun stopPolling() {
        handler.removeCallbacks(pollRunner)
    }
''',
    '''    fun stopPolling() {
        handler.removeCallbacks(pollRunner)
        setActiveController(null)
    }

    private fun setActiveController(controller: MediaController?) {
        val currentToken = activeController?.sessionToken
        val newToken = controller?.sessionToken
        if (currentToken == newToken) return
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        activeController = controller
        if (controller != null) {
            runCatching { controller.registerCallback(controllerCallback, handler) }
        }
    }
''',
    "media controller registration",
)

text = replace_once(
    text,
    '''    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        reloadPreferences()

        if (sbn == null) return
        if (!isPackageAllowed(sbn.packageName)) return

        if (isHomeVisible) sendBroadcast(Intent(ACTION_SURGE).setPackage(packageName))

        try {
            // MediaSession is preferred
            if (checkMediaSession()) return
            checkNotificationExtras(sbn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
''',
    '''    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        reloadPreferences()

        if (sbn == null) return
        if (!isPackageAllowed(sbn.packageName)) return

        if (isHomeVisible) sendBroadcast(Intent(ACTION_SURGE).setPackage(packageName))

        try {
            // MediaSession is preferred
            if (checkMediaSession()) return
            checkNotificationExtras(sbn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!isHomeVisible) return
        handler.post {
            if (!checkMediaSession()) checkActiveNotifications()
        }
    }
''',
    "notification removal refresh",
)

new_media_block = r'''    private fun updateFromController(controller: MediaController): Boolean {
        if (!isPackageAllowed(controller.packageName)) return false

        val pkg = controller.packageName
        val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        val isPlaying = state == PlaybackState.STATE_PLAYING
        val metadata = controller.metadata ?: return false
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        if (title.isNotEmpty()) {
            currentPackageName = pkg
            currentTitle = title
            currentArtist = artist
            currentAlbum = album
            currentIsPlaying = isPlaying
            notifyMetadataChangedIfNeeded()
        }

        val bitmap = try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        } catch (_: SecurityException) {
            null
        }
        if (bitmap != null && !bitmap.isRecycled) processBitmapFast(bitmap)

        // A session can have fresh text but no image. Keep notification fallback available then.
        return title.isNotEmpty() && bitmap != null && !bitmap.isRecycled
    }

    private fun checkMediaSession(): Boolean {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicListenerService::class.java)
            val controllers = manager.getActiveSessions(component)

            for (controller in controllers) {
                if (!isPackageAllowed(controller.packageName)) continue
                if (isHomeVisible) setActiveController(controller)
                if (updateFromController(controller)) return true
            }
            if (isHomeVisible) setActiveController(null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
'''
text = sub_once(
    text,
    r'''    private fun checkMediaSession\(\): Boolean \{.*?\n    \}\n\n    private fun checkNotificationExtras''',
    new_media_block + "\n    private fun checkNotificationExtras",
    "event-driven media session block",
)

music_path.write_text(text)
