package com.gushypushy.diffusereborn

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import android.animation.ValueAnimator
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import org.json.JSONObject
import java.util.Collections
import java.util.Comparator

class MainActivity : Activity() {

    private val PERMISSION_REQUEST_CODE = 101
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var previewView: DiffusePreviewView

    // Island views
    private lateinit var island: View
    private lateinit var border: OrbitBorderView
    private lateinit var header: View
    private lateinit var chevron: ImageView
    private lateinit var divider: View
    private lateinit var details: View

    private lateinit var artOld: ImageView
    private lateinit var artNew: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView

    private lateinit var rowSourceApp: View
    private lateinit var appIconView: ImageView
    private lateinit var sourceAppView: TextView
    private lateinit var pkgView: TextView
    private lateinit var albumView: TextView
    private lateinit var stateView: TextView

    // NEW: media controls on the bar
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private var islandVisible = false
    private var islandExpanded = false
    private var hiddenY = -240f

    // last values for animation decisions
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastAlbum: String? = null
    private var lastPkg: String? = null
    private var lastPlaying: Boolean? = null
    private var lastArt: Bitmap? = null

    private val metaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicListenerService.ACTION_METADATA_CHANGED) {
                updateIsland(animated = true)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val fluidInterpolator by lazy {
        if (Build.VERSION.SDK_INT >= 21) PathInterpolator(0.2f, 0f, 0f, 1f)
        else DecelerateInterpolator(1.6f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)
        previewView = findViewById(R.id.previewView)

        // Update subtitle based on previously selected render mode
        findViewById<TextView>(R.id.txtEditionSubtitle)?.let { subtitle ->
            subtitle.text = when (prefs.getString("render_mode_name", "Liquid Glass")) {
                "OG Diffuse" -> "OG Diffuse Edition"
                "OG Fluid" -> "OG Fluid Edition"
                "Classic (OG Diffuse)" -> "Classic Edition"
                "Classic (Legacy)" -> "Classic Edition"
                "Liquid (Reborn)" -> "Liquid Edition"
                "Liquid Glass" -> "Liquid Glass Edition"
                else -> "Liquid Edition"
            }
        }

        hideStatusBar()

        bindIsland()
        setupIslandBehavior()

        setupButtons()
        setupSliders()
        setupSwitches()
        setupSearch()
        setupRenderModeSpinner()  // NEW
        setupShapeSpinner()
        setupFluidEffectSpinner()
        setupBlendModeSpinner()
        setupTooltips()
        checkAndRequestStorage()

        applyClassicSkin()
        setupScrollEffects()
        runEntranceAnimation()
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.statusBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun bindIsland() {
        island = findViewById(R.id.nowPlayingIsland)
        border = findViewById(R.id.nowPlayingBorder)
        header = findViewById(R.id.nowPlayingHeader)
        chevron = findViewById(R.id.nowPlayingChevron)
        divider = findViewById(R.id.nowPlayingDivider)
        details = findViewById(R.id.nowPlayingDetails)

        artOld = findViewById(R.id.nowPlayingArtOld)
        artNew = findViewById(R.id.nowPlayingArtNew)
        titleView = findViewById(R.id.nowPlayingTitle)
        artistView = findViewById(R.id.nowPlayingArtist)

        rowSourceApp = findViewById(R.id.rowSourceApp)
        appIconView = findViewById(R.id.nowPlayingAppIcon)
        sourceAppView = findViewById(R.id.nowPlayingSourceApp)
        pkgView = findViewById(R.id.nowPlayingPackage)
        albumView = findViewById(R.id.nowPlayingAlbum)
        stateView = findViewById(R.id.nowPlayingState)

        // NEW: control buttons
        btnPrev = findViewById(R.id.btnPrev)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)

        // initial icon
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)

        island.post {
            hiddenY = -island.height.toFloat() - dp(16).toFloat()
            if (!islandVisible) {
                island.translationY = hiddenY
                island.alpha = 0f
                island.scaleX = 0.985f
                island.scaleY = 0.985f
            }
        }
    }

    private fun beginIslandLayoutTransition() {
        val root = island as? ViewGroup ?: return
        val t = AutoTransition().apply {
            duration = 280L
            interpolator = fluidInterpolator
        }
        TransitionManager.beginDelayedTransition(root, t)
    }

    private fun setupIslandBehavior() {
        header.setOnClickListener {
            toggleIslandExpanded()
        }

        rowSourceApp.setOnClickListener {
            val pkg = MusicListenerService.currentPackageName
            if (pkg.isNotBlank()) {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) startActivity(launch)
                else Toast.makeText(this, "Can't open app", Toast.LENGTH_SHORT).show()
            }
        }

        // NEW: media controls
        btnPrev.setOnClickListener {
            MusicListenerService.previous()
            uiHandler.postDelayed({ MusicListenerService.forceCheck() }, 250)
        }
        btnPlayPause.setOnClickListener {
            MusicListenerService.togglePause()
            uiHandler.postDelayed({ MusicListenerService.forceCheck() }, 250)
        }
        btnNext.setOnClickListener {
            MusicListenerService.next()
            uiHandler.postDelayed({ MusicListenerService.forceCheck() }, 250)
        }

        applyTouchAnimation(btnPrev)
        applyTouchAnimation(btnPlayPause)
        applyTouchAnimation(btnNext)
    }

    private fun setIslandVisible(show: Boolean) {
        if (islandVisible == show) return
        islandVisible = show

        island.animate().cancel()

        if (show) {
            border.start()
            island.scaleX = 0.985f
            island.scaleY = 0.985f
        } else {
            // recompute hidden based on CURRENT height (expanded/collapsed)
            hiddenY = -island.height.toFloat() - dp(16).toFloat()
        }

        val targetY = if (show) 0f else hiddenY
        val targetA = if (show) 1f else 0f
        val targetS = if (show) 1f else 0.985f
        val targetPadding = if (show) dp(96) else 0

        val sv = findViewById<ScrollView>(R.id.scrollView)
        if (sv != null) {
            val startPadding = sv.paddingTop
            val paddingAnim = ValueAnimator.ofInt(startPadding, targetPadding)
            paddingAnim.addUpdateListener { anim ->
                sv.setPadding(sv.paddingLeft, anim.animatedValue as Int, sv.paddingRight, sv.paddingBottom)
            }
            paddingAnim.duration = if (show) 360L else 240L
            paddingAnim.interpolator = fluidInterpolator
            paddingAnim.start()
        }

        island.animate()
            .translationY(targetY)
            .alpha(targetA)
            .scaleX(targetS)
            .scaleY(targetS)
            .setDuration(if (show) 360 else 240)
            .setInterpolator(fluidInterpolator)
            .withEndAction {
                if (!show) border.stop()
            }
            .start()
    }

    private fun toggleIslandExpanded() {
        islandExpanded = !islandExpanded

        chevron.animate().cancel()
        chevron.animate()
            .rotation(if (islandExpanded) 180f else 0f)
            .setDuration(260)
            .setInterpolator(fluidInterpolator)
            .start()

        beginIslandLayoutTransition()

        if (islandExpanded) {
            divider.visibility = View.VISIBLE
            details.visibility = View.VISIBLE

            // extra “feel” on expand
            details.alpha = 0f
            details.translationY = dp(4).toFloat()
            details.animate().cancel()
            details.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(fluidInterpolator)
                .start()
        } else {
            // Let AutoTransition animate height + fade
            details.visibility = View.GONE
            divider.visibility = View.GONE

            // IMPORTANT: force wrap_content to shrink immediately after transition begins
            island.requestLayout()
        }
    }

    /**
     * Classic skin. The original Diffuse settings UI was deliberately flat: MaterialCardViews with
     * `cardBackgroundColor=@color/transparent`, `cardElevation=0dp`, and a hard `strokeWidth=2dp`
     * outline in `colorOnSurface` (see the decompiled `layout_main_frag_config.xml` /
     * `layout_main_frag_playing.xml`), with Overline-style uppercase labels and a 1dp 60%-alpha
     * divider. This walks the view tree and swaps our glass drawables for that look whenever the
     * selected render mode is a Classic one, and restores the glass look otherwise.
     */
    private fun applyClassicSkin() {
        val mode = prefs.getString("render_mode_name", "Liquid Glass") ?: "Liquid Glass"
        val classic = mode.startsWith("Classic")
        val root = findViewById<View>(R.id.mainContainer) ?: return
        skinViewTree(root, classic)
        findViewById<View>(R.id.nowPlayingIsland)?.let { island ->
            island.setBackgroundResource(
                if (classic) R.drawable.classic_panel else R.drawable.oneui_nowplaying_bar
            )
        }
    }

    /** Recursively re-skin panels, buttons, selectors and dividers for the Classic look. */
    private fun skinViewTree(view: View, classic: Boolean) {
        when (view.id) {
            R.id.nowPlayingDivider -> view.setBackgroundResource(
                if (classic) R.drawable.classic_divider else R.drawable.glass_edge_highlight
            )
        }
        val tag = view.tag as? String
        // Layout XML marks each themed surface with a tag so we know what to swap it to.
        when (tag) {
            "skin_panel" -> view.setBackgroundResource(
                if (classic) R.drawable.classic_panel else R.drawable.glass_frosted_panel
            )
            "skin_button" -> view.setBackgroundResource(
                if (classic) R.drawable.classic_button else R.drawable.glass_button
            )
            "skin_select" -> view.setBackgroundResource(
                if (classic) R.drawable.classic_button else R.drawable.glass_select
            )
        }
        if (view is TextView && tag == "skin_label") {
            // Overline typography from the original: uppercase, wide tracking, small.
            view.isAllCaps = classic
            if (Build.VERSION.SDK_INT >= 21) view.letterSpacing = if (classic) 0.16f else 0f
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) skinViewTree(view.getChildAt(i), classic)
        }
    }

    private fun setupScrollEffects() {
        val scroll = findViewById<ScrollView>(R.id.scrollView)
        val topFade = findViewById<View>(R.id.topFade)
        val bottomFade = findViewById<View>(R.id.bottomFade)

        fun update() {
            topFade.animate().alpha(if (scroll.canScrollVertically(-1)) 1f else 0f).setDuration(160).start()
            bottomFade.animate().alpha(if (scroll.canScrollVertically(1)) 1f else 0f).setDuration(160).start()
            setIslandVisible(scroll.scrollY > dp(56))
        }

        scroll.viewTreeObserver.addOnScrollChangedListener { update() }
        scroll.post { update() }
    }

    private fun startIslandPolling() {
        stopIslandPolling()
        pollRunnable = object : Runnable {
            override fun run() {
                updateIsland(animated = false)
                uiHandler.postDelayed(this, 800)
            }
        }
        uiHandler.post(pollRunnable!!)
    }

    private fun stopIslandPolling() {
        pollRunnable?.let { uiHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun updateIsland(animated: Boolean) {
        val title = MusicListenerService.currentTitle
        val artist = MusicListenerService.currentArtist
        val album = MusicListenerService.currentAlbum
        val pkg = MusicListenerService.currentPackageName
        val isPlaying = MusicListenerService.currentIsPlaying
        val art = MusicListenerService.currentAlbumArt

        val titleChanged = (title != lastTitle)
        val artistChanged = (artist != lastArtist)
        val albumChanged = (album != lastAlbum)
        val pkgChanged = (pkg != lastPkg)
        val stateChanged = (isPlaying != lastPlaying)
        val artChanged = (art !== lastArt)

        lastTitle = title
        lastArtist = artist
        lastAlbum = album
        lastPkg = pkg
        lastPlaying = isPlaying
        lastArt = art

        // NEW: play/pause icon
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        // Update app label/icon
        if (pkgChanged || sourceAppView.text.toString() == "—") {
            val label = getAppLabel(pkg)
            val icon = getAppIcon(pkg)
            sourceAppView.text = label.ifBlank { "—" }
            appIconView.setImageDrawable(icon)
        }

        pkgView.text = "Package: ${if (pkg.isBlank()) "—" else pkg}"
        albumView.text = "Album: ${if (album.isBlank()) "—" else album}"
        stateView.text = "State: ${if (isPlaying) "Playing" else "Paused"}"

        if (!animated) {
            titleView.text = if (title.isBlank()) "Not playing" else title
            artistView.text = if (artist.isBlank()) "—" else artist
            if (art != null && !art.isRecycled) {
                artOld.setImageBitmap(art)
                artNew.setImageBitmap(art)
            }
            return
        }

        if (titleChanged) animateTextSwap(titleView, if (title.isBlank()) "Not playing" else title)
        if (artistChanged) animateTextSwap(artistView, if (artist.isBlank()) "—" else artist)

        if (artChanged) {
            val currentDrawable = artNew.drawable
            if (currentDrawable != null) artOld.setImageDrawable(currentDrawable)

            artOld.animate().cancel()
            artNew.animate().cancel()

            artOld.alpha = 1f
            artNew.alpha = 0f

            if (art != null && !art.isRecycled) artNew.setImageBitmap(art)
            else artNew.setImageResource(android.R.drawable.ic_media_play)

            artNew.scaleX = 1.08f
            artNew.scaleY = 1.08f
            artNew.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320)
                .setInterpolator(fluidInterpolator)
                .start()

            artOld.animate()
                .alpha(0f)
                .setDuration(260)
                .setInterpolator(fluidInterpolator)
                .start()
        }

        // Subtle “content refresh” nudge when expanded
        if (islandExpanded && (pkgChanged || albumChanged || stateChanged)) {
            details.animate().cancel()
            details.alpha = 0.92f
            details.translationY = dp(2).toFloat()
            details.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(fluidInterpolator)
                .start()
        }
    }

    private fun animateTextSwap(tv: TextView, newText: String) {
        tv.animate().cancel()
        tv.animate()
            .alpha(0f)
            .translationY((-dp(4)).toFloat())
            .setDuration(140)
            .setInterpolator(fluidInterpolator)
            .withEndAction {
                tv.text = newText
                tv.translationY = dp(4).toFloat()
                tv.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(fluidInterpolator)
                    .start()
            }
            .start()
    }

    private fun getAppLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun getAppIcon(pkg: String): Drawable? {
        if (pkg.isBlank()) return null
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
    }

    // ===== your existing UI code (unchanged where possible) =====

    private fun runEntranceAnimation() {
        val container = findViewById<LinearLayout>(R.id.mainContainer) ?: return
        val count = container.childCount
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 80f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 35).toLong())
                .setDuration(450)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    private fun runExitAnimation(onEnd: () -> Unit) {
        val container = findViewById<LinearLayout>(R.id.mainContainer) ?: return onEnd()
        val count = container.childCount
        var hasTriggered = false
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            child.animate().alpha(0f).translationY(80f).setStartDelay((i * 20).toLong())
                .setDuration(300).setInterpolator(AccelerateInterpolator()).start()
        }
        container.postDelayed({ if (!hasTriggered) { hasTriggered = true; onEnd() } }, 400)
    }

    private fun applyTouchAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    v.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.9f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (v !is Switch) v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(1.8f)).start()
                    else v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
                }
            }
            false
        }
    }

    private fun animateSwitchFlip(view: View, isChecked: Boolean) {
        view.animate().cancel()
        view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        }.start()
    }

    private fun setupSearch() {
        val inputSearch = findViewById<EditText>(R.id.inputSearch)
        inputSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSettings(s.toString())
            }
        })
    }

    private fun filterSettings(query: String) {
        val container = findViewById<LinearLayout>(R.id.mainContainer) ?: return
        val lowerQuery = query.lowercase()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is LinearLayout && child.background != null) {
                var hasMatch = false
                for (j in 0 until child.childCount) {
                    val row = child.getChildAt(j)
                    if (rowHasText(row, lowerQuery)) { row.visibility = View.VISIBLE; hasMatch = true }
                    else { row.visibility = if (lowerQuery.isEmpty()) View.VISIBLE else View.GONE }
                }
                child.visibility = if (hasMatch || lowerQuery.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun rowHasText(view: View, query: String): Boolean {
        if (query.isEmpty()) return true
        if (view is TextView && view.text.toString().lowercase().contains(query)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (rowHasText(view.getChildAt(i), query)) return true
            }
        }
        return false
    }

    private fun setupTooltips() {
        fun showHelp(title: String, msg: String) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(msg).setPositiveButton("Got it", null).show()
        }
        findViewById<View>(R.id.lblHeaderColors)?.setOnClickListener {
            showHelp("Colors", "Vibrancy controls saturation.\nHue Shift rotates the palette color.")
        }
        findViewById<View>(R.id.lblHeaderPhysics)?.setOnClickListener {
            showHelp("Fluid Physics", "Flow Speed: Movement speed.\nBeat Pulse: Bass reaction.\nWander: Distance traveled.")
        }
        findViewById<View>(R.id.lblHeaderSystem)?.setOnClickListener {
            showHelp("System", "Power Saver: 30 FPS cap.\nOLED: Pitch black background.")
        }
    }

    private fun setupChoiceSelector(
        viewId: Int,
        values: Array<String>,
        prefKey: String,
        defaultValue: String,
        onChanged: (String) -> Unit = {}
    ) {
        val selector = findViewById<TextView>(viewId) ?: return
        val stored = prefs.getString(prefKey, defaultValue) ?: defaultValue
        val current = if (values.contains(stored)) stored else defaultValue
        selector.text = current

        fun commit(value: String) {
            selector.text = value
            prefs.edit().putString(prefKey, value).apply()
            onChanged(value)
            selector.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        selector.setOnClickListener {
            selector.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(values) { _, which -> commit(values[which]) }
                .show()
        }
        applyTouchAnimation(selector)
    }

    // NEW: render-mode selector
    private fun setupRenderModeSpinner() {
        val modes = arrayOf(
            "OG Diffuse",
            "OG Fluid",
            "Liquid Glass",
            "Aurora Glass",
            "Prism Melt",
            "Neon Plasma",
            "Lava Lamp",
            "Ocean Caustics",
            "Ink Bloom",
            "Chrome Silk",
            "Liquid (Reborn)",
            // The Diffuse clone. MUST be present in this list: setupChoiceSelector falls back to
            // defaultValue for any stored mode it does not recognise, and that fallback rewrites
            // the pref - which is what silently kicked the wallpaper back off the clone renderer.
            "Classic (OG Diffuse)",
            "Classic (Legacy)"
        )
        val subtitle = findViewById<TextView>(R.id.txtEditionSubtitle)
        setupChoiceSelector(R.id.spinnerRender, modes, "render_mode_name", "Liquid Glass") { mode ->
            // Classic modes use the original Diffuse's flat outlined UI; re-skin live on change.
            applyClassicSkin()
            subtitle?.text = when(mode) {
                "OG Diffuse" -> "OG Diffuse Edition"
                "OG Fluid" -> "OG Fluid Edition"
                "Classic (OG Diffuse)" -> "Classic Edition"
                "Classic (Legacy)" -> "Classic Edition"
                "Liquid (Reborn)" -> "Liquid Edition"
                "Liquid Glass" -> "Liquid Glass Edition"
                else -> "Liquid Edition"
            }
        }
    }

    private fun setupShapeSpinner() {
        val shapes = arrayOf("Circle (Default)", "Ribbon", "Petal", "Comet", "Soft Square", "Cloud", "Hexagon", "Diamond")
        setupChoiceSelector(R.id.spinnerShape, shapes, "blob_shape", "Circle (Default)")
    }

    private fun setupFluidEffectSpinner() {
        val effects = arrayOf("Glass Rain", "Bubbles", "Liquid Trails", "Caustic Sparks", "Mist", "None")
        setupChoiceSelector(R.id.spinnerFluidEffect, effects, "fluid_effect_name", "Glass Rain")
    }

    private fun setupBlendModeSpinner() {
        val modes = arrayOf("Normal", "Add", "Screen", "Multiply", "Overlay", "Lighten")
        setupChoiceSelector(R.id.spinnerBlend, modes, "blend_mode_name", "Normal")
    }

    private fun setupButtons() {
        val btnHide = findViewById<Button>(R.id.btnHide)
        btnHide.setOnClickListener {
            btnHide.isEnabled = false
            runExitAnimation { finish() }
        }
        applyTouchAnimation(btnHide)

        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
            try {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                intent.putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, DiffuseWallpaperService::class.java)
                )
                startActivity(intent)
            } catch (_: Exception) {
                try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (_: Exception) {}
            }
        }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnMic).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        }

        val btnSave = findViewById<Button>(R.id.btnSavePreset)
        btnSave.setOnClickListener { showSavePresetDialog() }
        applyTouchAnimation(btnSave)

        val btnLoad = findViewById<Button>(R.id.btnLoadPreset)
        btnLoad.setOnClickListener { showLoadPresetDialog() }
        applyTouchAnimation(btnLoad)

        val btnBlacklist = findViewById<Button>(R.id.btnBlacklist)
        btnBlacklist.setOnClickListener { showAppBlockDialog(false) }
        applyTouchAnimation(btnBlacklist)

        val btnWhitelist = findViewById<Button>(R.id.btnWhitelist)
        btnWhitelist.setOnClickListener { showAppBlockDialog(true) }
        applyTouchAnimation(btnWhitelist)
    }

    private fun showSavePresetDialog() {
        val input = EditText(this)
        input.hint = "Preset Name"; input.setTextColor(Color.WHITE); input.setHintTextColor(Color.GRAY)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> saveCurrentPreset(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCurrentPreset(name: String) {
        if (name.isEmpty()) return
        val json = JSONObject()
        for ((key, value) in prefs.all) { if (key != "allowed_apps" && key != "blocked_apps") json.put(key, value) }
        getSharedPreferences("diffuse_presets", Context.MODE_PRIVATE).edit().putString(name, json.toString()).apply()
        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
    }

    private fun showLoadPresetDialog() {
        val presetsPref = getSharedPreferences("diffuse_presets", Context.MODE_PRIVATE)
        val allPresets = presetsPref.all.keys.toTypedArray()
        if (allPresets.isEmpty()) { Toast.makeText(this, "No presets found", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Load Preset")
            .setItems(allPresets) { _, which ->
                val json = JSONObject(presetsPref.getString(allPresets[which], "{}")!!)
                val editor = prefs.edit()
                val iter = json.keys()
                while (iter.hasNext()) {
                    val key = iter.next()
                    val v = json.get(key)
                    when (v) {
                        is Boolean -> editor.putBoolean(key, v)
                        is Int -> editor.putInt(key, v)
                        is Double -> editor.putFloat(key, v.toFloat())
                        is String -> editor.putString(key, v)
                    }
                }
                editor.apply()
                recreate()
            }.show()
    }

    private fun setupSwitches() {
        fun bindSwitch(id: Int, key: String) {
            val sw = findViewById<Switch>(id) ?: return
            sw.isChecked = prefs.getBoolean(key, false)
            sw.setOnCheckedChangeListener { v, c -> prefs.edit().putBoolean(key, c).apply(); animateSwitchFlip(v, c) }
            applyTouchAnimation(sw)
        }
        bindSwitch(R.id.switchDebug, "debug_mode")
        bindSwitch(R.id.switchSaver, "power_saver")
        bindSwitch(R.id.switchOled, "oled_mode")
        bindSwitch(R.id.switchTouch, "touch_mode")
        bindSwitch(R.id.switchWidget, "widget_on")
        bindSwitch(R.id.switchDust, "dust_on")
        bindSwitch(R.id.switchAodDetection, "aod_detection_on")
        bindSwitch(R.id.switchLight, "light_mode")
        bindSwitch(R.id.switchDarkIcons, "force_dark_icons")
        bindSwitch(R.id.switchCenterArt, "center_art_on")
        bindSwitch(R.id.switchRain, "rain_on")
        bindSwitch(R.id.switchDoubleTap, "double_tap_on")
    }

    private fun setupSliders() {
        fun setupSeek(id: Int, lblId: Int, key: String, max: Int = 100) {
            val seek = findViewById<SeekBar>(id)
            val lbl = findViewById<TextView>(lblId) ?: return
            val defaultVal = when (key) {
                "alpha" -> 200
                "transition_speed" -> 20
                "grain" -> 22
                else -> 50
            }
            val saved = prefs.getInt(key, defaultVal)

            seek?.max = max
            seek?.progress = saved
            lbl.text = if (max > 100) "$saved" else "$saved%"
            seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, b: Boolean) {
                    lbl.text = if (max > 100) "$v" else "$v%"
                    prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) { lbl.setTextColor(Color.WHITE) }
                override fun onStopTrackingTouch(s: SeekBar?) { lbl.setTextColor(Color.parseColor("#A8C7FA")) }
            })
            if (seek != null) applyTouchAnimation(seek)
        }

        setupSeek(R.id.seekSat, R.id.lblSat, "sat")
        setupSeek(R.id.seekBright, R.id.lblBright, "bright")
        setupSeek(R.id.seekTransition, R.id.lblTransition, "transition_speed")

        setupSeek(R.id.seekHue, R.id.lblHue, "hue_shift", 360)
        setupSeek(R.id.seekAlpha, R.id.lblAlpha, "alpha", 255)

        setupSeek(R.id.seekSpeed, R.id.lblSpeed, "speed")
        setupSeek(R.id.seekWander, R.id.lblWander, "wander")
        setupSeek(R.id.seekBeat, R.id.lblBeat, "beat")
        setupSeek(R.id.seekShake, R.id.lblShake, "shake")
        setupSeek(R.id.seekSize, R.id.lblSize, "size")
        setupSeek(R.id.seekDensity, R.id.lblDensity, "density")
        setupSeek(R.id.seekVig, R.id.lblVig, "vignette")
        setupSeek(R.id.seekDust, R.id.lblDust, "dust")
        setupSeek(R.id.seekGrain, R.id.lblGrain, "grain")
        setupSeek(R.id.seekDustSize, R.id.lblDustSize, "dust_size")
        setupSeek(R.id.seekDustCount, R.id.lblDustCount, "dust_count")
        setupSeek(R.id.seekPosX, R.id.lblPosX, "pos_x")
        setupSeek(R.id.seekPosY, R.id.lblPosY, "pos_y")
        setupSeek(R.id.seekWidgetScale, R.id.lblWidgetScale, "widget_scale")
        setupSeek(R.id.seekCenterBlur, R.id.lblCenterBlur, "center_blur")
        setupSeek(R.id.seekCenterOpacity, R.id.lblCenterOpacity, "center_opacity")
        setupSeek(R.id.seekCenterSize, R.id.lblCenterSize, "center_size")
        setupSeek(R.id.seekRainCount, R.id.lblRainCount, "rain_count")
        setupSeek(R.id.seekRainSpeed, R.id.lblRainSpeed, "rain_speed")
        setupSeek(R.id.seekRainSize, R.id.lblRainSize, "rain_size")
        setupSeek(R.id.seekRainOpacity, R.id.lblRainOpacity, "rain_opacity")
    }

    private fun checkAndRequestStorage() {
        val hasStorage =
            if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        if (!hasStorage) {
            if (Build.VERSION.SDK_INT >= 33)
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_AUDIO), PERMISSION_REQUEST_CODE)
            else
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
        }
    }

    private fun showAppBlockDialog(isWhitelist: Boolean) {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null); mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val appNames = ArrayList<String>()
        val packageNames = ArrayList<String>()
        val checkedItems = BooleanArray(resolveInfos.size)

        val prefKey = if (isWhitelist) "allowed_apps" else "blocked_apps"
        val savedSet = prefs.getStringSet(prefKey, emptySet()) ?: emptySet()

        Collections.sort(resolveInfos, Comparator { a, b ->
            a.loadLabel(pm).toString().compareTo(b.loadLabel(pm).toString(), ignoreCase = true)
        })

        for (i in resolveInfos.indices) {
            val info = resolveInfos[i]
            val pkg = info.activityInfo.packageName
            appNames.add(info.loadLabel(pm).toString())
            packageNames.add(pkg)
            if (savedSet.contains(pkg)) checkedItems[i] = true
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (isWhitelist) "Only Allow Music From:" else "Block Music From:")
            .setMultiChoiceItems(appNames.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val newSet = HashSet<String>()
                for (i in checkedItems.indices) if (checkedItems[i]) newSet.add(packageNames[i])
                prefs.edit().putStringSet(prefKey, newSet).apply()
                Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
                MusicListenerService.setHomeState(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()

        MusicListenerService.setHomeState(true)
        MusicListenerService.forceCheck()

        val filter = IntentFilter(MusicListenerService.ACTION_METADATA_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(metaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(metaReceiver, filter)
        }

        startIslandPolling()
        updateIsland(animated = false)
        previewView.onResume()
    }

    override fun onPause() {
        super.onPause()
        stopIslandPolling()
        border.stop()
        try { unregisterReceiver(metaReceiver) } catch (_: Exception) {}
        MusicListenerService.setHomeState(false)
        previewView.onPause()
    }
}
