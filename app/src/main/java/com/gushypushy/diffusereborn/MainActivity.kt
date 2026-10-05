package com.gushypushy.diffusereborn

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQUEST_AUDIO_PERMISSION = 101
        const val PREFS_NAME = "diffuse_prefs"
        const val PRESETS_NAME = "diffuse_presets"
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var previewView: DiffusePreviewView
    private lateinit var albumArtView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var notificationButton: Button
    private lateinit var audioButton: Button
    private lateinit var paletteSwatches: LinearLayout
    private var lastArtwork: Bitmap? = null
    private var lastPalette: List<Int> = emptyList()
    private var receiverRegistered = false

    private data class SliderSetting(
        val rowId: Int,
        val key: String,
        val label: String,
        val caption: String,
        val defaultValue: Int = 50,
        val max: Int = 100,
        val format: (Int) -> String = { "$it%" }
    )

    private data class SwitchSetting(
        val rowId: Int,
        val key: String,
        val label: String,
        val caption: String,
        val defaultValue: Boolean = false,
        val groupId: Int? = null
    )

    private data class LaunchableApp(val packageName: String, val label: String)

    private val sliderSettings = listOf(
        SliderSetting(R.id.rowOgScale, "og_scale", "Field scale", "Controls how tightly the album colors fill the screen."),
        SliderSetting(R.id.rowOgSpeed, "og_speed", "Drift speed", "The center position is the original, relaxed pace.", format = { String.format(Locale.getDefault(), "%.1f×", it / 50f) }),
        SliderSetting(R.id.rowOgTransition, "og_transition", "Track transition", "Time taken to blend into a new album palette.", defaultValue = 16, max = 60, format = { String.format(Locale.getDefault(), "%.1f s", it.coerceAtLeast(1) / 10f) }),
        SliderSetting(R.id.rowBeat, "beat", "Beat pulse", "Sets how strongly low frequencies push the field."),
        SliderSetting(R.id.rowOgStrength, "og_strength", "Pulse strength", "Limits the motion added by each beat."),
        SliderSetting(R.id.rowSat, "sat", "Saturation", "Adds or softens color from the current artwork."),
        SliderSetting(R.id.rowBright, "bright", "Brightness", "Adjusts the overall light level of the field."),
        SliderSetting(R.id.rowHue, "hue_shift", "Hue shift", "Rotate the palette without changing its structure.", defaultValue = 0, max = 360, format = { "$it°" }),
        SliderSetting(R.id.rowVignette, "vignette", "Vignette", "Darken the edges to give the center more depth.", defaultValue = 0),
        SliderSetting(R.id.rowGrain, "grain", "Film grain", "A fine texture that helps break up smooth gradients.", defaultValue = 22),
        SliderSetting(R.id.rowCenterSize, "center_size", "Artwork size", "Size of the optional cover image."),
        SliderSetting(R.id.rowCenterBlur, "center_blur", "Artwork blur", "Soften the optional cover image."),
        SliderSetting(R.id.rowCenterOpacity, "center_opacity", "Artwork opacity", "Transparency of the optional cover image.", defaultValue = 100),
        SliderSetting(R.id.rowWidgetScale, "widget_scale", "Text size", "Scale the title and artist on the wallpaper."),
        SliderSetting(R.id.rowPosX, "pos_x", "Horizontal position", "Move the now-playing overlay left or right."),
        SliderSetting(R.id.rowPosY, "pos_y", "Vertical position", "Move the now-playing overlay up or down.")
    )

    private val switchSettings = listOf(
        SwitchSetting(R.id.rowCenterArt, "center_art_on", "Center artwork", "Show the current cover in the wallpaper.", groupId = R.id.groupCenterArt),
        SwitchSetting(R.id.rowWidget, "widget_on", "Now-playing overlay", "Show the track title and artist.", defaultValue = true, groupId = R.id.groupWidget),
        SwitchSetting(R.id.rowPowerSaver, "power_saver", "Power saver", "Limit wallpaper rendering to 30 frames per second."),
        SwitchSetting(R.id.rowForceDark, "force_dark_icons", "Dark system icons", "Use dark launcher icons when supported."),
        SwitchSetting(R.id.rowDebug, "debug_mode", "Show frame rate", "Display renderer frame rate on the wallpaper.")
    )

    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicListenerService.ACTION_METADATA_CHANGED) {
                refreshNowPlaying()
            }
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            refreshPermissionState()
            if (receiverRegistered) uiHandler.postDelayed(this, 1000L)
        }
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        previewView = findViewById(R.id.previewView)
        albumArtView = findViewById(R.id.imgAlbumArt)
        titleView = findViewById(R.id.textTrackTitle)
        artistView = findViewById(R.id.textTrackArtist)
        permissionStatus = findViewById(R.id.textPermissionStatus)
        notificationButton = findViewById(R.id.btnNotifPerm)
        audioButton = findViewById(R.id.btnAudioPermission)
        paletteSwatches = findViewById(R.id.paletteSwatches)

        installWindowInsets()
        bindSliders()
        bindSwitches()
        bindActions()
        refreshSettingsControls()
        refreshNowPlaying()
        refreshPermissionState()
        updateSourceButtonLabels()
    }

    private fun installWindowInsets() {
        val scroll = findViewById<ScrollView>(R.id.mainScroll)
        val content = findViewById<View>(R.id.mainContainer)
        val bottomBar = findViewById<View>(R.id.bottomActionBar)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPaddingRelative(
                dp(22) + safe.left,
                dp(26) + safe.top,
                dp(22) + safe.right,
                dp(150) + safe.bottom
            )
            bottomBar.setPaddingRelative(
                dp(22) + safe.left,
                dp(18),
                dp(22) + safe.right,
                dp(12) + safe.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun bindSliders() {
        for (setting in sliderSettings) {
            val row = findViewById<View>(setting.rowId) ?: continue
            val label = row.findViewById<TextView>(R.id.sliderLabel) ?: continue
            val caption = row.findViewById<TextView>(R.id.sliderCaption) ?: continue
            val value = row.findViewById<TextView>(R.id.sliderValue) ?: continue
            val seek = row.findViewById<SeekBar>(R.id.sliderBar) ?: continue

            label.text = setting.label
            caption.text = setting.caption
            caption.visibility = View.VISIBLE
            seek.max = setting.max
            val current = prefs.getInt(setting.key, setting.defaultValue).coerceIn(0, setting.max)
            seek.progress = current
            value.text = setting.format(current)
            seek.contentDescription = setting.label
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = setting.format(progress)
                    if (fromUser) prefs.edit().putInt(setting.key, progress).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
    }

    private fun bindSwitches() {
        for (setting in switchSettings) {
            val row = findViewById<View>(setting.rowId) ?: continue
            val label = row.findViewById<TextView>(R.id.switchLabel) ?: continue
            val caption = row.findViewById<TextView>(R.id.switchCaption) ?: continue
            val toggle = row.findViewById<SwitchCompat>(R.id.switchToggle) ?: continue

            label.text = setting.label
            caption.text = setting.caption
            caption.visibility = View.VISIBLE
            toggle.isChecked = prefs.getBoolean(setting.key, setting.defaultValue)
            toggle.contentDescription = setting.label
            toggle.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(setting.key, checked).apply()
                setting.groupId?.let { updateExpandableGroup(it, checked, animate = true) }
            }
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        }
    }

    private fun refreshSettingsControls() {
        for (setting in sliderSettings) {
            val row = findViewById<View>(setting.rowId) ?: continue
            val seek = row.findViewById<SeekBar>(R.id.sliderBar) ?: continue
            val value = row.findViewById<TextView>(R.id.sliderValue) ?: continue
            val progress = prefs.getInt(setting.key, setting.defaultValue).coerceIn(0, setting.max)
            if (seek.progress != progress) seek.progress = progress
            value.text = setting.format(progress)
        }

        for (setting in switchSettings) {
            val row = findViewById<View>(setting.rowId) ?: continue
            val toggle = row.findViewById<SwitchCompat>(R.id.switchToggle) ?: continue
            toggle.isChecked = prefs.getBoolean(setting.key, setting.defaultValue)
            setting.groupId?.let {
                updateExpandableGroup(it, toggle.isChecked, animate = false)
            }
        }
        updateSourceButtonLabels()
    }

    private fun updateExpandableGroup(groupId: Int, expanded: Boolean, animate: Boolean) {
        val group = findViewById<View>(groupId) ?: return
        val targetVisibility = if (expanded) View.VISIBLE else View.GONE
        if (group.visibility == targetVisibility) return
        if (animate) {
            TransitionManager.beginDelayedTransition(
                findViewById(R.id.mainContainer),
                AutoTransition().setDuration(180L)
            )
        }
        group.visibility = targetVisibility
    }

    private fun bindActions() {
        notificationButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { showToast("Open Android Settings and enable notification access for Diffuse Reborn.") }
        }

        audioButton.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                showToast("Audio response is enabled.")
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    REQUEST_AUDIO_PERMISSION
                )
            }
        }

        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, DiffuseWallpaperService::class.java)
                )
            }
            runCatching { startActivity(intent) }
                .onFailure { runCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } }
        }

        findViewById<Button>(R.id.btnWhitelist).setOnClickListener { showAppFilterDialog(allowList = true) }
        findViewById<Button>(R.id.btnBlacklist).setOnClickListener { showAppFilterDialog(allowList = false) }
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener { showSavePresetDialog() }
        findViewById<Button>(R.id.btnLoadPreset).setOnClickListener { showLoadPresetDialog() }
        findViewById<Button>(R.id.btnResetAll).setOnClickListener { confirmReset() }
    }

    private fun refreshPermissionState() {
        val notificationGranted = isNotificationAccessGranted()
        permissionStatus.text = if (notificationGranted) {
            "Music access is ready. Diffuse can read supported playback details."
        } else {
            "Allow notification access to show the current track and album artwork."
        }
        notificationButton.text = if (notificationGranted) "Manage notification access" else "Grant notification access"

        val audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audioButton.text = if (audioGranted) "Audio response enabled" else "Allow audio response"
        audioButton.isEnabled = !audioGranted
        audioButton.alpha = if (audioGranted) 0.72f else 1f
    }

    private fun isNotificationAccessGranted(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.isNotificationListenerAccessGranted(
            ComponentName(this, MusicListenerService::class.java)
        )
    }

    private fun refreshNowPlaying() {
        val rawTitle = MusicListenerService.currentTitle.trim()
        val waiting = rawTitle.isBlank() || rawTitle == "Diffuse Reborn" || rawTitle == "Waiting for music..."
        titleView.text = if (waiting) "Nothing playing" else rawTitle

        val artist = MusicListenerService.currentArtist.trim()
        artistView.text = when {
            waiting -> "Start music to see its colors here"
            artist.isBlank() || artist == "Waiting for music..." -> "Now playing"
            MusicListenerService.currentIsPlaying -> artist
            else -> "$artist  ·  Paused"
        }

        val art = MusicListenerService.currentAlbumArt?.takeUnless { it.isRecycled }
        if (art !== lastArtwork) {
            if (art == null) albumArtView.setImageResource(R.drawable.ic_music_placeholder)
            else albumArtView.setImageBitmap(art)
            lastArtwork = art
        }

        val colors = MusicListenerService.currentColors
        if (colors != lastPalette) {
            renderPalette(colors)
            lastPalette = colors.toList()
        }
    }

    private fun renderPalette(colors: List<Int>) {
        paletteSwatches.removeAllViews()
        for ((index, color) in colors.take(5).withIndex()) {
            val swatch = View(this).apply {
                contentDescription = "Album color ${index + 1}"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), 0x70FFFFFF)
                }
            }
            val params = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                if (index > 0) marginStart = dp(6)
            }
            paletteSwatches.addView(swatch, params)
        }
    }

    private fun showAppFilterDialog(allowList: Boolean) {
        val settingKey = if (allowList) "allowed_apps" else "blocked_apps"
        val apps = loadLaunchableApps()
        if (apps.isEmpty()) {
            showToast("No launchable apps were found.")
            return
        }

        val selected = (prefs.getStringSet(settingKey, emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].packageName in selected }
        val labels = apps.map { it.label }.toTypedArray()
        val title = if (allowList) "Allow music from" else "Block music from"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val packageName = apps[which].packageName
                if (isChecked) selected.add(packageName) else selected.remove(packageName)
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit().remove(settingKey).apply()
                updateSourceButtonLabels()
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet(settingKey, selected.toSet()).apply()
                updateSourceButtonLabels()
            }
            .show()
    }

    private fun loadLaunchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                if (activityInfo.packageName == packageName) return@mapNotNull null
                LaunchableApp(
                    activityInfo.packageName,
                    info.loadLabel(packageManager)?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                        ?: activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun updateSourceButtonLabels() {
        val allowed = prefs.getStringSet("allowed_apps", emptySet())?.size ?: 0
        val blocked = prefs.getStringSet("blocked_apps", emptySet())?.size ?: 0
        findViewById<Button>(R.id.btnWhitelist)?.text = if (allowed == 0) "Allowed apps · All" else "Allowed apps · $allowed"
        findViewById<Button>(R.id.btnBlacklist)?.text = if (blocked == 0) "Blocked apps · None" else "Blocked apps · $blocked"
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Preset name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Save current settings")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> savePreset(input.text.toString()) }
            .show()
    }

    private fun savePreset(name: String) {
        val cleanedName = name.trim()
        if (cleanedName.isEmpty()) {
            showToast("Enter a name for this preset.")
            return
        }

        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (value !is Set<*>) values.put(key, value)
        }
        getSharedPreferences(PRESETS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(cleanedName, values.toString())
            .apply()
        showToast("Preset saved.")
    }

    private fun showLoadPresetDialog() {
        val store = getSharedPreferences(PRESETS_NAME, Context.MODE_PRIVATE)
        val names = store.all.keys.sorted()
        if (names.isEmpty()) {
            showToast("Save a preset first.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Load preset")
            .setItems(names.toTypedArray()) { _, which -> loadPreset(store.getString(names[which], null)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPreset(jsonText: String?) {
        if (jsonText.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(jsonText)
            val editor = prefs.edit()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = json.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
            refreshSettingsControls()
            refreshNowPlaying()
        }.onFailure { showToast("That preset could not be loaded.") }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset settings?")
            .setMessage("All wallpaper settings and app filters will return to their defaults. Saved presets will stay available.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                prefs.edit().clear().apply()
                refreshSettingsControls()
                refreshPermissionState()
                showToast("Settings reset.")
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(MusicListenerService.ACTION_METADATA_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(metadataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(metadataReceiver, filter)
            }
            receiverRegistered = true
        }
        MusicListenerService.setHomeState(true)
        MusicListenerService.forceCheck()
        refreshNowPlaying()
        refreshPermissionState()
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshRunnable)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(metadataReceiver) }
            receiverRegistered = false
        }
        MusicListenerService.setHomeState(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        previewView.onResume()
        refreshPermissionState()
    }

    override fun onPause() {
        previewView.onPause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            refreshPermissionState()
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                showToast("Audio response is enabled.")
            } else {
                showToast("The wallpaper still works without audio response.")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
