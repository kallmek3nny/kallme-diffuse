package com.gushypushy.diffusereborn

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DiffusePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback, SharedPreferences.OnSharedPreferenceChangeListener {

    private val choreographer = Choreographer.getInstance()
    private val prefs: SharedPreferences = context.getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)

    // Engine Vars
    private val BLOB_COUNT = 8
    private lateinit var baseBlob: Bitmap

    private val positions = FloatArray(BLOB_COUNT * 2)
    private val paints = ArrayList<Paint>(BLOB_COUNT)
    private val destRect = RectF()
    private val sheenPath = Path()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    // Colors
    private var drawingColors = MusicListenerService.currentColors.toMutableList()
    private var oldColors = MusicListenerService.currentColors
    private var targetColors = MusicListenerService.currentColors
    private var fadeProgress = 1.0f

    private var userFadeSpeed = 0.003f

    // State
    private var time = 0f
    private var isVisible = false
    private var screenW = 0f
    private var screenH = 0f
    private var lastFrameTimeNs = 0L
    private val slowMotionFactor = 0.25f
    private val hsvCache = FloatArray(3)

    // Settings
    private var userSpeed = 0.006f
    private var userSizeMult = 1.0f
    private var userWander = 1.0f
    private var userSat = 1.0f
    private var userBright = 1.0f

    // TUNING
    private var userHue = 0f
    private var userBlobAlpha = 200
    private var blendModeXfermode: PorterDuffXfermode? = null

    // NEW: classic vs liquid mode
    private var isClassicMode = false
    private var isOgDiffuseMode = false
    private var isOgFluidMode = false
    private var renderModeName = "Liquid Glass"
    private var liquidVariant = 0
    private var activeBlobCount = BLOB_COUNT

    init {
        for (i in 0 until BLOB_COUNT) {
            paints.add(Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
                isDither = true
                alpha = 200
            })
        }

        createBaseBlob()
        updateSettings()

        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    private fun createBaseBlob() {
        val size = 1024
        if (::baseBlob.isInitialized && !baseBlob.isRecycled) {
            baseBlob.recycle()
        }
        baseBlob = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(baseBlob)
        val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            isFilterBitmap = true
            color = Color.WHITE
            maskFilter = BlurMaskFilter(size / 4f, BlurMaskFilter.Blur.NORMAL)
        }
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 8f
        when (prefs.getString("blob_shape", "Circle (Default)")) {
            "Soft Square" -> {
                val r = radius * 1.28f
                destRect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawRoundRect(destRect, r * 0.52f, r * 0.52f, paint)
            }
            "Ribbon" -> {
                canvas.save()
                canvas.rotate(-24f, cx, cy)
                destRect.set(cx - radius * 2.3f, cy - radius * 0.78f, cx + radius * 2.3f, cy + radius * 0.78f)
                canvas.drawRoundRect(destRect, radius, radius, paint)
                canvas.restore()
            }
            "Petal" -> {
                val path = Path()
                path.moveTo(cx, cy - radius * 1.85f)
                path.cubicTo(cx + radius * 1.9f, cy - radius * 1.1f, cx + radius * 1.75f, cy + radius * 1.25f, cx, cy + radius * 1.85f)
                path.cubicTo(cx - radius * 1.75f, cy + radius * 1.25f, cx - radius * 1.9f, cy - radius * 1.1f, cx, cy - radius * 1.85f)
                path.close()
                canvas.drawPath(path, paint)
            }
            "Comet" -> {
                val path = Path()
                path.moveTo(cx - radius * 2.6f, cy)
                path.cubicTo(cx - radius * 1.2f, cy - radius * 1.4f, cx + radius * 1.65f, cy - radius * 1.05f, cx + radius * 2.15f, cy)
                path.cubicTo(cx + radius * 1.65f, cy + radius * 1.05f, cx - radius * 1.2f, cy + radius * 1.4f, cx - radius * 2.6f, cy)
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "blob_shape") createBaseBlob()
        updateSettings()
    }

    private fun updateSettings() {
        userSpeed = 0.0001f + (prefs.getInt("speed", 50).toFloat() / 100f) * 0.08f
        userSizeMult = 0.7f + (prefs.getInt("size", 50).toFloat() / 100f)
        userWander = 0.2f + (prefs.getInt("wander", 50).toFloat() / 100f) * 1.5f

        userSat = (prefs.getInt("sat", 50).toFloat() / 50f) * 1.25f
        userBright = (prefs.getInt("bright", 50).toFloat() / 50f) * 1.0f

        userHue = prefs.getInt("hue_shift", 0).toFloat()
        userBlobAlpha = prefs.getInt("alpha", 200).coerceIn(0, 255)

        val tSpeed = prefs.getInt("transition_speed", 20).toFloat() / 100f
        userFadeSpeed = 0.0005f + (tSpeed * tSpeed * 0.05f)

        val modeName = prefs.getString("blend_mode_name", "Normal")
        val mode = when (modeName) {
            "Add" -> PorterDuff.Mode.ADD
            "Screen" -> PorterDuff.Mode.SCREEN
            "Multiply" -> PorterDuff.Mode.MULTIPLY
            "Overlay" -> PorterDuff.Mode.OVERLAY
            "Lighten" -> PorterDuff.Mode.LIGHTEN
            else -> PorterDuff.Mode.SRC_OVER
        }
        blendModeXfermode = if (mode != PorterDuff.Mode.SRC_OVER) PorterDuffXfermode(mode) else null

        renderModeName = prefs.getString("render_mode_name", "Liquid Glass") ?: "Liquid Glass"
        isClassicMode = renderModeName == "Classic (Legacy)" || renderModeName == "Classic (OG Diffuse)"
        isOgDiffuseMode = renderModeName == "OG Diffuse"
        isOgFluidMode = renderModeName == "OG Fluid"
        liquidVariant = when (renderModeName) {
            "Aurora Glass" -> 1
            "Prism Melt" -> 2
            "Neon Plasma" -> 3
            "Lava Lamp" -> 4
            "Ocean Caustics" -> 5
            "Ink Bloom" -> 6
            "Chrome Silk" -> 7
            else -> 0
        }
        activeBlobCount = (3 + (prefs.getInt("density", 50).toFloat() / 100f * (BLOB_COUNT - 3))).toInt().coerceIn(3, BLOB_COUNT)
    }

    fun onResume() {
        isVisible = true
        lastFrameTimeNs = 0L
        choreographer.postFrameCallback(this)
    }

    fun onPause() {
        isVisible = false
        choreographer.removeFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenW = w.toFloat()
        screenH = h.toFloat()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isVisible) return

        if (lastFrameTimeNs == 0L) lastFrameTimeNs = frameTimeNanos
        val dtNs = frameTimeNanos - lastFrameTimeNs
        lastFrameTimeNs = frameTimeNanos

        val dtMs = (dtNs.toFloat() / 1_000_000f).coerceIn(0f, 50f)
        time += userSpeed * (dtMs / 16.6667f) * slowMotionFactor

        invalidate()
        choreographer.postFrameCallback(this)
    }

    private fun lerpColor(c1: Int, c2: Int, p: Float): Int {
        val inv = 1.0f - p
        val a = (Color.alpha(c1) * inv + Color.alpha(c2) * p).toInt()
        val r = (Color.red(c1) * inv + Color.red(c2) * p).toInt()
        val g = (Color.green(c1) * inv + Color.green(c2) * p).toInt()
        val b = (Color.blue(c1) * inv + Color.blue(c2) * p).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun stylePaletteColor(index: Int): Int {
        val palettes = arrayOf(
            intArrayOf(Color.rgb(85, 214, 255), Color.rgb(166, 114, 255), Color.rgb(214, 242, 255), Color.rgb(68, 255, 204)),
            intArrayOf(Color.rgb(70, 255, 190), Color.rgb(255, 104, 218), Color.rgb(92, 161, 255), Color.rgb(255, 231, 126)),
            intArrayOf(Color.rgb(255, 53, 214), Color.rgb(49, 231, 255), Color.rgb(255, 221, 65), Color.rgb(125, 78, 255)),
            intArrayOf(Color.rgb(18, 246, 255), Color.rgb(255, 38, 170), Color.rgb(117, 83, 255), Color.rgb(57, 92, 255)),
            intArrayOf(Color.rgb(255, 76, 28), Color.rgb(255, 164, 42), Color.rgb(255, 33, 86), Color.rgb(95, 21, 12)),
            intArrayOf(Color.rgb(38, 236, 221), Color.rgb(30, 111, 255), Color.rgb(151, 255, 238), Color.rgb(7, 45, 105)),
            intArrayOf(Color.rgb(44, 33, 86), Color.rgb(116, 49, 146), Color.rgb(232, 223, 255), Color.rgb(17, 18, 30)),
            intArrayOf(Color.rgb(194, 222, 255), Color.rgb(107, 159, 255), Color.rgb(224, 245, 255), Color.rgb(112, 255, 229))
        )
        val palette = palettes[liquidVariant.coerceIn(0, palettes.lastIndex)]
        return palette[index % palette.size]
    }

    private fun styleBackgroundSeed(): Int {
        return when (liquidVariant) {
            1 -> Color.rgb(8, 38, 43)
            2 -> Color.rgb(28, 7, 68)
            3 -> Color.rgb(13, 5, 48)
            4 -> Color.rgb(48, 7, 6)
            5 -> Color.rgb(4, 24, 52)
            6 -> Color.rgb(8, 8, 18)
            7 -> Color.rgb(12, 18, 34)
            else -> Color.rgb(8, 26, 36)
        }
    }

    private fun applyTuning(color: Int): Int {
        val a = Color.alpha(color)
        Color.colorToHSV(color, hsvCache)
        hsvCache[0] = (hsvCache[0] + userHue) % 360f
        hsvCache[1] *= userSat
        val finalColor = Color.HSVToColor(a, hsvCache)
        val fR = (Color.red(finalColor) * userBright).toInt().coerceIn(0, 255)
        val fG = (Color.green(finalColor) * userBright).toInt().coerceIn(0, 255)
        val fB = (Color.blue(finalColor) * userBright).toInt().coerceIn(0, 255)
        return Color.argb(a, fR, fG, fB)
    }

    private fun albumGlassColor(rawColor: Int, index: Int): Int {
        val tuned = applyTuning(rawColor)
        Color.colorToHSV(tuned, hsvCache)
        val hueOffset = when (liquidVariant) {
            1 -> index * 7f
            2 -> index * 15f
            3 -> index * 10f
            4 -> -10f + index * 4f
            5 -> 160f + index * 4f
            6 -> index * 3f
            7 -> index * 2f
            else -> index * 4f
        }
        if (liquidVariant == 5) {
            hsvCache[0] = (hsvCache[0] * 0.45f + hueOffset) % 360f
        } else {
            hsvCache[0] = (hsvCache[0] + hueOffset) % 360f
        }
        val satFloor = when (liquidVariant) {
            6, 7 -> 0.28f
            else -> 0.48f
        }
        hsvCache[1] = (hsvCache[1] * 1.18f).coerceAtLeast(satFloor).coerceIn(0f, 1f)
        hsvCache[2] = (hsvCache[2] * 1.06f).coerceIn(0.16f, 1f)
        return Color.HSVToColor(Color.alpha(tuned), hsvCache)
    }

    private fun albumGlassBackground(rawColor: Int, darken: Float): Int {
        val tuned = albumGlassColor(rawColor, 0)
        Color.colorToHSV(tuned, hsvCache)
        hsvCache[1] = (hsvCache[1] * 0.95f).coerceIn(0.28f, 0.95f)
        hsvCache[2] = (hsvCache[2] * darken).coerceIn(0.035f, 0.34f)
        return Color.HSVToColor(255, hsvCache)
    }

    private fun isGlassRenderMode(): Boolean {
        return renderModeName != "Liquid (Reborn)" && !isClassicMode && !isOgDiffuseMode && !isOgFluidMode
    }

    private fun updateBlobPositions(glassy: Boolean) {
        val wanderX = screenW * 0.36f * userWander
        val wanderY = screenH * 0.28f * userWander
        val centerPull = if (glassy) 0.42f else 0.0f
        val variantPhase = liquidVariant * 0.73f

        for (i in 0 until BLOB_COUNT) {
            val fi = i.toFloat()
            val angle = ((fi / BLOB_COUNT) * (PI.toFloat() * 2f)) +
                    time * (0.12f + fi * 0.018f) + variantPhase
            val ripple = sin(time * (0.41f + fi * 0.03f) + fi * 1.7f + variantPhase)
            val anchorX = when (i % 4) {
                0 -> screenW * 0.20f
                1 -> screenW * 0.80f
                2 -> screenW * 0.32f
                else -> screenW * 0.68f
            }
            val anchorY = when (i % 4) {
                0 -> screenH * 0.22f
                1 -> screenH * 0.32f
                2 -> screenH * 0.74f
                else -> screenH * 0.78f
            }
            val orbitX = cos(angle) * wanderX * (0.52f + abs(ripple) * 0.48f)
            val orbitY = sin(angle * 0.86f + fi) * wanderY * (0.55f + (1f - abs(ripple)) * 0.42f)
            positions[i * 2] = anchorX * (1f - centerPull) + (screenW * 0.5f) * centerPull + orbitX
            positions[i * 2 + 1] = anchorY * (1f - centerPull) + (screenH * 0.5f) * centerPull + orbitY
        }
    }

    private fun styledColor(color: Int, index: Int): Int {
        val tuned = lerpColor(applyTuning(color), stylePaletteColor(index), if (liquidVariant == 0) 0.42f else 0.88f)
        Color.colorToHSV(tuned, hsvCache)
        when (liquidVariant) {
            1 -> {
                hsvCache[0] = (hsvCache[0] + 18f + index * 5f) % 360f
                hsvCache[1] = (hsvCache[1] * 1.18f).coerceIn(0f, 1f)
                hsvCache[2] = (hsvCache[2] * 1.08f).coerceIn(0f, 1f)
            }
            2 -> {
                hsvCache[0] = (hsvCache[0] + index * 28f) % 360f
                hsvCache[1] = (hsvCache[1] * 1.28f).coerceIn(0f, 1f)
                hsvCache[2] = (hsvCache[2] * 1.15f).coerceIn(0f, 1f)
            }
            3 -> {
                hsvCache[0] = (hsvCache[0] + 115f + index * 22f) % 360f
                hsvCache[1] = 1f
                hsvCache[2] = (hsvCache[2] * 1.35f).coerceIn(0.35f, 1f)
            }
            4 -> {
                hsvCache[0] = (hsvCache[0] * 0.35f + 18f + index * 8f) % 360f
                hsvCache[1] = (hsvCache[1] * 1.35f).coerceIn(0.35f, 1f)
                hsvCache[2] = (hsvCache[2] * 0.95f).coerceIn(0.18f, 1f)
            }
            5 -> {
                hsvCache[0] = (175f + index * 12f + sin(time + index) * 16f) % 360f
                hsvCache[1] = (0.62f + hsvCache[1] * 0.35f).coerceIn(0f, 1f)
                hsvCache[2] = (hsvCache[2] * 1.18f).coerceIn(0.25f, 1f)
            }
            6 -> {
                hsvCache[1] = (hsvCache[1] * 0.55f).coerceIn(0f, 1f)
                hsvCache[2] = (hsvCache[2] * 0.72f).coerceIn(0.10f, 0.86f)
            }
            7 -> {
                hsvCache[1] = (hsvCache[1] * 0.38f).coerceIn(0f, 1f)
                hsvCache[2] = (hsvCache[2] * 1.28f).coerceIn(0.35f, 1f)
            }
        }
        val satFloor = when (liquidVariant) {
            1 -> 0.58f
            2 -> 0.82f
            3 -> 0.95f
            4 -> 0.74f
            5 -> 0.66f
            6 -> 0.32f
            7 -> 0.18f
            else -> 0.52f
        }
        val valueFloor = when (liquidVariant) {
            3 -> 0.58f
            4 -> 0.34f
            6 -> 0.22f
            7 -> 0.56f
            else -> 0.42f
        }
        hsvCache[1] = hsvCache[1].coerceAtLeast(satFloor).coerceIn(0f, 1f)
        hsvCache[2] = hsvCache[2].coerceAtLeast(valueFloor).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(tuned), hsvCache)
    }

    private fun glassBackgroundColor(color: Int): Int {
        return albumGlassBackground(color, 0.34f)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun ogDiffuseColor(rawColor: Int, index: Int): Int {
        val tuned = applyTuning(rawColor)
        Color.colorToHSV(tuned, hsvCache)
        val hueShift = when (index % 7) {
            0 -> 0f
            1 -> 14f
            2 -> -18f
            3 -> 138f
            4 -> -126f
            5 -> 34f
            else -> 176f
        }
        hsvCache[0] = (hsvCache[0] + hueShift + 360f) % 360f
        val accent = index >= 3
        hsvCache[1] = (hsvCache[1] * if (accent) 0.92f else 1.18f)
            .coerceIn(if (accent) 0.30f else 0.34f, 0.94f)
        hsvCache[2] = (hsvCache[2] * if (accent) 1.18f else 1.10f)
            .coerceIn(if (accent) 0.28f else 0.24f, 0.96f)
        return Color.HSVToColor(Color.alpha(tuned), hsvCache)
    }

    private fun ogDiffuseBackground(rawColor: Int, darken: Float): Int {
        val color = ogDiffuseColor(rawColor, 0)
        Color.colorToHSV(color, hsvCache)
        hsvCache[1] = (hsvCache[1] * 0.82f).coerceIn(0.18f, 0.72f)
        hsvCache[2] = (hsvCache[2] * darken).coerceIn(0.16f, 0.62f)
        return Color.HSVToColor(255, hsvCache)
    }

    private fun drawOgEllipse(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        color: Int,
        alpha: Int,
        falloff: Float = 0.50f
    ) {
        val radius = rx.coerceAtLeast(1f)
        glowPaint.alpha = 255
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                withAlpha(color, alpha),
                withAlpha(color, (alpha * 0.42f).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, falloff.coerceIn(0.28f, 0.72f), 1f),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.scale(1f, ry / radius, cx, cy)
        canvas.drawCircle(cx, cy, radius, glowPaint)
        canvas.restore()
        glowPaint.shader = null
    }

    private fun drawOgPreview(canvas: Canvas, visualProgress: Float) {
        val rawBg = lerpColor(
            oldColors.getOrElse(0) { Color.DKGRAY },
            targetColors.getOrElse(0) { Color.DKGRAY },
            visualProgress
        )
        val rawBg2 = lerpColor(
            oldColors.getOrElse(1) { rawBg },
            targetColors.getOrElse(1) { rawBg },
            visualProgress
        )
        val rawBg3 = lerpColor(
            oldColors.getOrElse(3) { rawBg2 },
            targetColors.getOrElse(3) { rawBg2 },
            visualProgress
        )

        val primary = ogDiffuseColor(rawBg, 0)
        val light = ogDiffuseColor(rawBg2, 1)
        val deep = ogDiffuseColor(rawBg3, 2)
        val counter = ogDiffuseColor(rawBg, 3)
        val secondCounter = ogDiffuseColor(rawBg2, 4)
        val glow = ogDiffuseColor(targetColors.getOrElse(4) { rawBg3 }, 5)

        glowPaint.shader = LinearGradient(
            0f, 0f, screenW, screenH,
            intArrayOf(
                ogDiffuseBackground(rawBg, 0.82f),
                ogDiffuseBackground(rawBg2, 0.66f),
                ogDiffuseBackground(rawBg3, 0.78f)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, screenH, glowPaint)
        glowPaint.shader = null

        glowPaint.shader = LinearGradient(
            0f, screenH * 0.1f, screenW, screenH,
            intArrayOf(withAlpha(light, 70), Color.TRANSPARENT, withAlpha(counter, 78)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, screenH, glowPaint)
        glowPaint.shader = null

        val baseAlpha = (userBlobAlpha * 0.44f).toInt().coerceIn(72, 158)
        val p = time * 0.12f
        drawOgEllipse(canvas, screenW * (0.18f + sin(p) * 0.035f), screenH * (0.06f + cos(p * 0.8f) * 0.020f), screenW * 0.84f, screenH * 0.42f, light, baseAlpha + 18, 0.44f)
        drawOgEllipse(canvas, screenW * (0.88f + cos(p * 0.9f) * 0.030f), screenH * (0.18f + sin(p * 0.7f) * 0.030f), screenW * 0.72f, screenH * 0.54f, primary, baseAlpha + 8, 0.50f)
        drawOgEllipse(canvas, screenW * (0.20f + cos(p * 1.2f) * 0.035f), screenH * (0.56f + sin(p * 0.9f) * 0.028f), screenW * 0.86f, screenH * 0.62f, deep, baseAlpha + 34, 0.48f)
        drawOgEllipse(canvas, screenW * (0.94f + sin(p * 0.7f) * 0.020f), screenH * (0.76f + cos(p * 1.1f) * 0.026f), screenW * 0.78f, screenH * 0.52f, counter, baseAlpha - 6, 0.54f)
        drawOgEllipse(canvas, screenW * (0.46f + sin(p * 0.6f) * 0.030f), screenH * (1.06f + cos(p * 0.8f) * 0.018f), screenW * 0.96f, screenH * 0.48f, secondCounter, baseAlpha - 10, 0.58f)
        drawOgEllipse(canvas, screenW * (0.54f + cos(p * 1.4f) * 0.025f), screenH * (0.36f + sin(p * 1.0f) * 0.030f), screenW * 0.58f, screenH * 0.34f, glow, baseAlpha - 18, 0.42f)
    }

    private fun drawOgRibbon(
        canvas: Canvas,
        yNorm: Float,
        color: Int,
        alpha: Int,
        phase: Float,
        thickness: Float
    ) {
        val y = screenH * yNorm
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.strokeJoin = Paint.Join.ROUND
        glowPaint.strokeWidth = thickness
        glowPaint.alpha = 255
        glowPaint.shader = LinearGradient(
            0f, y - thickness,
            screenW, y + thickness,
            intArrayOf(Color.TRANSPARENT, withAlpha(color, alpha), withAlpha(color, (alpha * 0.62f).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )

        sheenPath.reset()
        sheenPath.moveTo(-screenW * 0.24f, y + sin(phase) * screenH * 0.055f)
        sheenPath.cubicTo(
            screenW * 0.08f,
            y - screenH * (0.09f + sin(phase * 0.7f) * 0.026f),
            screenW * 0.34f,
            y + screenH * (0.13f + cos(phase * 0.9f) * 0.030f),
            screenW * 0.58f,
            y + sin(phase + 1.1f) * screenH * 0.070f
        )
        sheenPath.cubicTo(
            screenW * 0.78f,
            y - screenH * (0.15f + cos(phase * 0.8f) * 0.026f),
            screenW * 1.02f,
            y + screenH * (0.10f + sin(phase * 1.1f) * 0.024f),
            screenW * 1.24f,
            y + cos(phase) * screenH * 0.045f
        )
        canvas.drawPath(sheenPath, glowPaint)

        glowPaint.shader = null
        glowPaint.style = Paint.Style.FILL
        glowPaint.strokeWidth = 1f
        glowPaint.alpha = 255
    }

    private fun drawOgFluidPreview(canvas: Canvas, visualProgress: Float) {
        val rawBg = lerpColor(
            oldColors.getOrElse(0) { Color.DKGRAY },
            targetColors.getOrElse(0) { Color.DKGRAY },
            visualProgress
        )
        val rawBg2 = lerpColor(
            oldColors.getOrElse(1) { rawBg },
            targetColors.getOrElse(1) { rawBg },
            visualProgress
        )
        val rawBg3 = lerpColor(
            oldColors.getOrElse(3) { rawBg2 },
            targetColors.getOrElse(3) { rawBg2 },
            visualProgress
        )

        val primary = ogDiffuseColor(rawBg, 0)
        val light = ogDiffuseColor(rawBg2, 1)
        val deep = ogDiffuseColor(rawBg3, 2)
        val counter = ogDiffuseColor(rawBg, 3)
        val secondCounter = ogDiffuseColor(rawBg2, 4)
        val glow = ogDiffuseColor(targetColors.getOrElse(4) { rawBg3 }, 5)

        glowPaint.shader = LinearGradient(
            0f, 0f, screenW * 0.8f, screenH,
            intArrayOf(
                ogDiffuseBackground(rawBg, 0.70f),
                ogDiffuseBackground(rawBg2, 0.56f),
                ogDiffuseBackground(rawBg3, 0.72f)
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, screenH, glowPaint)
        glowPaint.shader = null

        val baseAlpha = (userBlobAlpha * 0.38f).toInt().coerceIn(62, 138)
        val p = time * (0.20f + userWander * 0.035f)

        drawOgEllipse(canvas, screenW * (0.10f + sin(p * 0.55f) * 0.035f), screenH * 0.10f, screenW * 0.76f, screenH * 0.40f, light, baseAlpha + 10, 0.42f)
        drawOgEllipse(canvas, screenW * (1.02f + cos(p * 0.50f) * 0.025f), screenH * 0.28f, screenW * 0.66f, screenH * 0.48f, primary, baseAlpha + 2, 0.46f)
        drawOgEllipse(canvas, screenW * (0.18f + cos(p * 0.68f) * 0.028f), screenH * 0.78f, screenW * 0.84f, screenH * 0.48f, secondCounter, baseAlpha - 10, 0.54f)

        drawOgRibbon(canvas, 0.18f, light, baseAlpha + 36, p + 0.2f, screenH * 0.24f)
        drawOgRibbon(canvas, 0.36f, counter, baseAlpha + 18, p + 1.6f, screenH * 0.30f)
        drawOgRibbon(canvas, 0.56f, deep, baseAlpha + 44, p + 3.0f, screenH * 0.34f)
        drawOgRibbon(canvas, 0.75f, primary, baseAlpha + 28, p + 4.4f, screenH * 0.28f)
        drawOgRibbon(canvas, 0.92f, glow, baseAlpha + 6, p + 5.7f, screenH * 0.24f)

        glowPaint.shader = LinearGradient(
            screenW * 0.10f, 0f,
            screenW * 0.92f, screenH,
            intArrayOf(withAlpha(Color.WHITE, 20), Color.TRANSPARENT, withAlpha(Color.WHITE, 14)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, screenH, glowPaint)
        glowPaint.shader = null
    }

    private fun drawPreviewGlass(canvas: Canvas) {
        glowPaint.shader = null
        glowPaint.color = albumGlassBackground(targetColors.getOrElse(0) { Color.DKGRAY }, 0.26f)
        glowPaint.alpha = when (liquidVariant) {
            3 -> 70
            6 -> 78
            else -> 56
        }
        canvas.drawRect(0f, 0f, screenW, screenH, glowPaint)

        val alpha = 24
        glowPaint.shader = RadialGradient(
            screenW * (0.42f + sin(time * 0.18f) * 0.08f),
            screenH * (0.34f + cos(time * 0.16f) * 0.08f),
            hypot(screenW.toDouble(), screenH.toDouble()).toFloat() * 0.62f,
            intArrayOf(Color.argb(alpha, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, screenH, glowPaint)
        glowPaint.shader = null

        sheenPaint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val newColors = MusicListenerService.currentColors
        if (newColors != targetColors) {
            oldColors = drawingColors.toList()
            targetColors = newColors
            fadeProgress = 0f
        }
        if (fadeProgress < 1.0f) {
            fadeProgress += userFadeSpeed
            if (fadeProgress > 1.0f) fadeProgress = 1.0f
        }

        // Quintic Ease-Out
        val invT = 1.0f - fadeProgress
        val visualProgress = 1.0f - (invT * invT * invT * invT * invT)

        if (isOgDiffuseMode) {
            drawOgPreview(canvas, visualProgress)
            return
        }
        if (isOgFluidMode) {
            drawOgFluidPreview(canvas, visualProgress)
            return
        }

        // BACKGROUND
        var rawOldBg = oldColors.getOrElse(0) { Color.GRAY }
        var rawNewBg = targetColors.getOrElse(0) { Color.GRAY }

        val glassy = isGlassRenderMode()
        val blendedBg = if (glassy) {
            glassBackgroundColor(lerpColor(rawOldBg, rawNewBg, visualProgress))
        } else {
            lerpColor(applyTuning(rawOldBg), applyTuning(rawNewBg), visualProgress)
        }
        canvas.drawColor(blendedBg)

        updateBlobPositions(glassy)

        val baseRadius = if (isClassicMode) {
            hypot(screenW.toDouble(), screenH.toDouble()).toFloat() * 0.5f * userSizeMult
        } else if (glassy) {
            hypot(screenW.toDouble(), screenH.toDouble()).toFloat() * (0.10f + userSizeMult * 0.070f)
        } else {
            screenW * (0.9f * userSizeMult)
        }

        val drawCount = if (glassy) activeBlobCount.coerceAtMost(5) else activeBlobCount
        for (i in 0 until drawCount) {
            val blobX = positions[i * 2]
            val blobY = positions[i * 2 + 1]

            val rawOld = oldColors.getOrElse(i) { Color.GRAY }
            val rawNew = targetColors.getOrElse(i) { Color.GRAY }

            val blendedBlob = if (glassy) {
                albumGlassColor(lerpColor(rawOld, rawNew, visualProgress), i)
            } else {
                lerpColor(applyTuning(rawOld), applyTuning(rawNew), visualProgress)
            }
            if (drawingColors.size > i) drawingColors[i] = blendedBlob

            paints[i].colorFilter = PorterDuffColorFilter(blendedBlob, PorterDuff.Mode.SRC_IN)
            paints[i].alpha = if (glassy) (userBlobAlpha * 0.52f).toInt().coerceIn(55, 170) else userBlobAlpha
            paints[i].xfermode = if (isClassicMode || glassy) null else blendModeXfermode

            val radius = if (glassy) baseRadius * (0.86f + abs(sin(time * (0.32f + i * 0.02f) + i)) * 0.34f) else baseRadius

            destRect.set(blobX - radius, blobY - radius, blobX + radius, blobY + radius)
            canvas.drawBitmap(baseBlob, null, destRect, paints[i])
        }

        if (glassy) {
            drawPreviewGlass(canvas)
        }
    }
}
