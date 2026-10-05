package com.gushypushy.diffusereborn

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * Clone of the original Diffuse settings UI.
 *
 * The original app (`MainFragment` + `layout_main_fragment.xml`) was deliberately minimal: a scroll
 * of transparent, 2dp-outlined cards over the live wallpaper, with only three graphics sliders
 * (Fluid Scale, Speed, Strength), a "Last Playing" card, and a setup card. This reproduces that
 * exactly — no extra options.
 *
 * Slider ranges follow the original's `Player.java` uniforms:
 *   scale    -> u_scale    = slider * 1.2 + 0.4      (slider 0..1)
 *   speed    -> timeSpeed target multiplier          (slider 0..1)
 *   strength -> u_border amplitude                   (slider 0..1)
 *
 * Values are stored in the shared `"diffuse_prefs"` file as 0..100 ints, matching the rest of the
 * app's SharedPreferences contract, and are re-read live by [DiffuseWallpaperService.DiffuseEngine].
 */
class ClassicActivity : AppCompatActivity() {

    companion object {
        /** Render mode this GUI drives; routes to the real OG Diffuse pipeline in DiffuseEngine. */
        const val CLASSIC_MODE = "Classic (OG Diffuse)"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_classic)
        prefs = getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)

        // This screen IS the Diffuse clone, so opening it selects the clone renderer. Writing it
        // through the SharedPreferences API (rather than assuming whatever is on disk) is what
        // fires DiffuseEngine's OnSharedPreferenceChangeListener - the engine caches prefs in
        // memory, so an out-of-band edit to the XML would never reach a running wallpaper.
        if (prefs.getString("render_mode_name", "") != CLASSIC_MODE) {
            prefs.edit().putString("render_mode_name", CLASSIC_MODE).apply()
        }

        bindSeek(R.id.seekClassicScale, "og_scale", 50)
        bindSeek(R.id.seekClassicSpeed, "og_speed", 50)
        bindSeek(R.id.seekClassicStrength, "og_strength", 50)

        findViewById<Button>(R.id.classicAllowNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.classicSetWallpaper).setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@ClassicActivity, DiffuseWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.classicOpenMusic).setOnClickListener {
            val pkg = MusicListenerService.currentPackageName
            val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) startActivity(launch)
        }

        // The original had no "back to the other UI" concept; here it returns to the full settings.
        findViewById<Button>(R.id.classicExit).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNowPlaying()
    }

    /** Mirrors the original's "Last Playing" card: album art, title, artist. */
    private fun refreshNowPlaying() {
        val title = MusicListenerService.currentTitle
        val artist = MusicListenerService.currentArtist
        findViewById<TextView>(R.id.classicTitle).text =
            if (title.isNullOrBlank()) getString(R.string.label_no_track) else title
        findViewById<TextView>(R.id.classicArtist).text = artist ?: ""

        val art = MusicListenerService.currentAlbumArt
        val view = findViewById<ImageView>(R.id.classicArt)
        if (art != null && !art.isRecycled) view.setImageBitmap(art) else view.setImageDrawable(null)
    }

    /** Persist a 0..100 slider into the shared prefs file the wallpaper engine reads. */
    private fun bindSeek(viewId: Int, key: String, default: Int) {
        val seek = findViewById<SeekBar>(viewId) ?: return
        seek.progress = prefs.getInt(key, default)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(key, value).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
}
