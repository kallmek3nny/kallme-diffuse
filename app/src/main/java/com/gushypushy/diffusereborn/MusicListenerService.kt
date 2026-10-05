package com.gushypushy.diffusereborn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.palette.graphics.Palette
import java.util.concurrent.Executors
import kotlin.math.hypot

class MusicListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_SURGE = "com.gushypushy.diffusereborn.ACTION_SURGE"
        const val ACTION_METADATA_CHANGED = "com.gushypushy.diffusereborn.ACTION_METADATA_CHANGED"

        @Volatile
        var currentColors: List<Int> = listOf(
            Color.LTGRAY, Color.LTGRAY, Color.LTGRAY, Color.LTGRAY, Color.LTGRAY
        )

        @Volatile
        var currentTitle: String = "Diffuse Reborn"

        @Volatile
        var currentArtist: String = "Waiting for music..."

        @Volatile
        var currentAlbum: String = ""

        @Volatile
        var currentPackageName: String = ""

        @Volatile
        var currentIsPlaying: Boolean = false

        @Volatile
        var currentAlbumArt: Bitmap? = null

        private var isHomeVisible = false
        private var instance: MusicListenerService? = null

        private var blockedPackages: Set<String> = emptySet()
        private var allowedPackages: Set<String> = emptySet()

        fun setHomeState(visible: Boolean) {
            isHomeVisible = visible
            if (visible) {
                instance?.reloadPreferences()
                instance?.startPolling()
            } else {
                instance?.stopPolling()
            }
        }

        fun forceCheck() {
            instance?.checkMediaSession()
            instance?.checkActiveNotifications()
        }

        fun togglePause() {
            instance?.triggerPlayPause()
        }

        // NEW: next/previous for the island buttons
        fun next() {
            instance?.triggerNext()
        }

        fun previous() {
            instance?.triggerPrevious()
        }
    }

    private val bitmapExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var lastNotifyKey: String = ""

    private val pollRunner = object : Runnable {
        override fun run() {
            if (!isHomeVisible) return
            if (!checkMediaSession()) checkActiveNotifications()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)
        reloadPreferences()
    }

    fun reloadPreferences() {
        blockedPackages = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        allowedPackages = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        reloadPreferences()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        stopPolling()
    }

    fun startPolling() {
        handler.removeCallbacks(pollRunner)
        handler.post(pollRunner)
    }

    fun stopPolling() {
        handler.removeCallbacks(pollRunner)
    }

    private fun isPackageAllowed(pkg: String): Boolean {
        if (blockedPackages.contains(pkg)) return false
        if (allowedPackages.isNotEmpty()) return allowedPackages.contains(pkg)
        return true
    }

    private fun notifyMetadataChangedIfNeeded() {
        val key =
            "${currentPackageName}|${currentTitle}|${currentArtist}|${currentAlbum}|${currentIsPlaying}|${currentAlbumArt?.hashCode() ?: 0}"
        if (key == lastNotifyKey) return
        lastNotifyKey = key

        // App-only broadcast
        val intent = Intent(ACTION_METADATA_CHANGED).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun pickActiveController(): android.media.session.MediaController? {
        return try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicListenerService::class.java)
            val controllers = manager.getActiveSessions(component)

            controllers.firstOrNull {
                val state = it.playbackState?.state ?: PlaybackState.STATE_NONE
                state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
            } ?: controllers.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun triggerPlayPause() {
        try {
            val controller = pickActiveController() ?: return
            val state = controller.playbackState?.state ?: return
            if (state == PlaybackState.STATE_PLAYING) controller.transportControls.pause()
            else controller.transportControls.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // NEW
    private fun triggerNext() {
        try {
            val controller = pickActiveController() ?: return
            controller.transportControls.skipToNext()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // NEW
    private fun triggerPrevious() {
        try {
            val controller = pickActiveController() ?: return
            controller.transportControls.skipToPrevious()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!isHomeVisible) return
        reloadPreferences()

        if (sbn == null) return
        if (!isPackageAllowed(sbn.packageName)) return

        sendBroadcast(Intent(ACTION_SURGE))

        try {
            // MediaSession is preferred
            if (checkMediaSession()) return
            checkNotificationExtras(sbn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkActiveNotifications() {
        try {
            val notifications = activeNotifications
            for (sbn in notifications) {
                if (!isPackageAllowed(sbn.packageName)) continue
                checkNotificationExtras(sbn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkMediaSession(): Boolean {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicListenerService::class.java)
            val controllers = manager.getActiveSessions(component)

            for (controller in controllers) {
                if (!isPackageAllowed(controller.packageName)) continue

                val pkg = controller.packageName
                val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                val isPlaying = state == PlaybackState.STATE_PLAYING

                val metadata = controller.metadata
                if (metadata != null) {
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
                    } catch (e: SecurityException) {
                        null
                    }

                    if (bitmap != null && !bitmap.isRecycled) {
                        processBitmapFast(bitmap)
                    }
                    return title.isNotEmpty()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun checkNotificationExtras(sbn: StatusBarNotification?) {
        val extras = sbn?.notification?.extras ?: return
        val pkg = sbn.packageName

        val title = extras.getString("android.title") ?: ""
        val artist = extras.getString("android.text") ?: ""

        if (title.isNotEmpty()) {
            currentPackageName = pkg
            currentTitle = title
            currentArtist = artist
            if (currentAlbum.isEmpty()) currentAlbum = ""
            // Notification doesn't always provide playing state; assume playing
            currentIsPlaying = true
            notifyMetadataChangedIfNeeded()
        }

        val bitmap = getBitmapFromBundle(extras, "android.largeIcon")
            ?: getBitmapFromBundle(extras, "android.picture")

        if (bitmap != null && !bitmap.isRecycled) processBitmapFast(bitmap)
    }

    private fun getBitmapFromBundle(bundle: Bundle, key: String): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 33) {
            bundle.getParcelable(key, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable(key)
        }
    }

    private fun processBitmapFast(original: Bitmap) {
        bitmapExecutor.execute {
            try {
                var workableBitmap = original
                if (original.config == Bitmap.Config.HARDWARE) {
                    workableBitmap = original.copy(Bitmap.Config.ARGB_8888, false)
                }

                val artBitmap = Bitmap.createScaledBitmap(workableBitmap, 1200, 1200, false)

                if (currentAlbumArt != null && currentAlbumArt!!.sameAs(artBitmap)) {
                    if (artBitmap != currentAlbumArt) artBitmap.recycle()
                    return@execute
                }

                currentAlbumArt = artBitmap
                notifyMetadataChangedIfNeeded()

                val colorBitmap = Bitmap.createScaledBitmap(artBitmap, 100, 100, false)

                Palette.from(colorBitmap)
                    .maximumColorCount(24)
                    .generate { palette ->
                        if (palette != null) {
                            val swatches = palette.swatches

                            val bestSwatches = swatches.filter { swatch ->
                                val hsl = swatch.hsl
                                val isColor = hsl[1] > 0.25f
                                val notTooDark = hsl[2] > 0.15f
                                val notTooLight = hsl[2] < 0.85f
                                isColor && notTooDark && notTooLight
                            }.sortedByDescending { it.population }

                            val primarySwatch = bestSwatches.firstOrNull() ?: palette.dominantSwatch
                            val primaryColor = primarySwatch?.rgb ?: Color.DKGRAY

                            val finalColors = mutableListOf<Int>()
                            finalColors.add(primaryColor)
                            finalColors.add(palette.getLightVibrantColor(manipulateColor(primaryColor, 1.3f)))
                            finalColors.add(palette.getDarkVibrantColor(manipulateColor(primaryColor, 0.7f)))
                            val secondarySwatch = bestSwatches.firstOrNull { it != primarySwatch }
                            finalColors.add(secondarySwatch?.rgb ?: manipulateColor(primaryColor, 0.9f))
                            finalColors.add(palette.getMutedColor(Color.rgb(40, 40, 40)))

                            val distinctColors = finalColors.distinct().toMutableList()
                            while (distinctColors.size < 5) distinctColors.add(distinctColors[0])

                            if (currentColors != distinctColors) currentColors = distinctColors
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun manipulateColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}