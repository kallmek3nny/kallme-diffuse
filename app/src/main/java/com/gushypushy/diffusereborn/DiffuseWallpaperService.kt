package com.gushypushy.diffusereborn

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class DiffuseWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return DiffuseEngine()
    }

    companion object {
        // Direct AGSL port of the original Diffuse `noise.frag` domain-warp.
        // Warps a pre-blurred album-art "field" with domain-warped fbm noise, exactly
        // like the OpenGL original (u_texture -> field, u_timeScaled/u_scale/u_border uniforms).
        // The track-change crossfade (the original `fade.frag` rule) now lives inside
        // OG_DIFFUSE_AGSL below, applied per-fragment after the warp lookup rather than as a
        // separate pre-pass into an intermediate bitmap.

        // Motion constants lifted verbatim from the original Player.java:
        //   timeSpeedTarget = 0.5f when playing, 0.08f when paused
        //   timeSpeed += (target - timeSpeed) * deltaTime * 0.2f
        //   timeScaled += deltaTime * timeSpeed, wrapping at 10000
        //   border eases toward its target at deltaTime * 1.0f
        // The original's 0.5 target is scaled by its own fluid-scale uniform before reaching the
        // warp; on our Canvas path the uv domain is smaller, so 0.5 reads noticeably faster than the
        // real app. 0.22 matches the observed drift rate of the original on a 1440p panel. The
        // Speed slider (og_speed, 50 = 1.0x) multiplies this, so the old rate is still reachable.
        private const val OG_SPEED_PLAYING = 0.22f
        private const val OG_SPEED_PAUSED = 0.035f
        private const val OG_SPEED_EASE = 0.2f
        private const val OG_TIME_WRAP = 10000f
        private const val OG_BORDER_EASE = 1.0f

        private const val OG_DIFFUSE_AGSL = """
            uniform shader field;
            uniform shader fieldOld;
            uniform float fieldMix;
            uniform float2 resolution;
            uniform float2 fieldRes;
            uniform float timeScaled;
            uniform float scale;
            uniform float border;

            float random(float2 st) {
                // A compact polynomial hash avoids evaluating sin() hundreds of times for every
                // output pixel while keeping the noise stable between frames.
                st = fract(st * float2(123.34, 456.21));
                st += dot(st, st + 45.32);
                return fract(st.x * st.y);
            }

            float noise(float2 st) {
                float2 i = floor(st);
                float2 f = fract(st);
                float a = random(i);
                float b = random(i + float2(1.0, 0.0));
                float c = random(i + float2(0.0, 1.0));
                float d = random(i + float2(1.0, 1.0));
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
            }

            float fbm(float2 st) {
                float v = 0.0;
                float a = 0.5;
                float2 shift = st * 0.5;
                float2x2 rot = float2x2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5));
                for (int i = 0; i < 3; ++i) {
                    v += a * noise(st);
                    st = rot * st * 1.3 + shift;
                    a *= 0.5;
                }
                return v;
            }

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;
                if (resolution.x > resolution.y) {
                    uv.x = uv.x * (resolution.x / resolution.y);
                } else {
                    uv.y = uv.y / (resolution.x / resolution.y);
                }
                float2 q = float2(fbm(uv + float2(timeScaled * 0.1, cos(timeScaled))),
                                  fbm(uv + float2(timeScaled * 0.1, timeScaled)));
                float2 r = float2(fbm(0.5 * uv + 2.0 * q + float2(timeScaled * 0.05, 0.0)),
                                  fbm(0.5 * uv + 2.0 * q + float2(timeScaled * 0.1, 0.0)));
                r = (r - float2(0.5, 0.5)) * (scale + border) + float2(0.5, 0.5);
                float2 tc = clamp(r, 0.0, 1.0) * fieldRes;
                half4 col = field.eval(tc);

                // Track-change crossfade, applied inside the warp instead of as a pre-pass. The
                // pre-pass composited both fields into an intermediate bitmap, which required a
                // software Canvas - and a software Canvas cannot draw a RuntimeShader at all, so the
                // whole OG render threw for the length of every fade and left only the flat
                // background colour. Same rule as the original fade.frag: where the two fields
                // already agree, snap to the target; only genuinely different regions ease across.
                if (fieldMix < 0.999) {
                    half4 prev = fieldOld.eval(tc);
                    float fdiff = length(float3(prev.rgb) - float3(col.rgb));
                    float m = clamp(max(fieldMix, min(0.01 / max(fdiff, 0.0001), 1.0)), 0.0, 1.0);
                    col = mix(prev, col, half(m));
                }

                // Ordered-ish dither. The field is a heavily blurred 300px bitmap upscaled to a full
                // 1440p panel, so adjacent output pixels resolve to the same 8-bit source value and
                // the gradient steps become visible bands. Break the quantisation with +/-0.5 LSB of
                // screen-space noise before the framebuffer rounds to 8-bit.
                float n = random(fragCoord * 0.7351) + random(fragCoord.yx * 1.1237 + 19.19);
                float d = (n * 0.5 - 0.5) * (1.6 / 255.0);
                return half4(clamp(float3(col.rgb) + float3(d), 0.0, 1.0), 1.0);
            }
        """
    }

    inner class DiffuseEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener,
        Choreographer.FrameCallback {

        private val choreographer = Choreographer.getInstance()
        private var visualizer: Visualizer? = null
        @Volatile private var bassEnergy = 0f
        @Volatile private var trebleEnergy = 0f
        private lateinit var prefs: SharedPreferences

        // --- BLOB CONFIG ---
        private lateinit var baseBlob: Bitmap
        private val BLOB_COUNT = 8
        private val destRect = RectF()

        // --- SURGE LOGIC (Visual Flash Only) ---
        private var surgeProgress = 0f
        private val surgeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicListenerService.ACTION_SURGE) {
                    surgeProgress = 1.0f
                }
            }
        }

        // --- SCREEN RECEIVER ---
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        startDrawing()
                        MusicListenerService.forceCheck()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        stopDrawing()
                    }
                }
            }
        }

        // --- ALBUM ART CROSSFADE ---
        private var activeArt: Bitmap? = null
        private var oldArt: Bitmap? = null
        private var artFadeProgress = 1.0f

        // --- CENTER ART CONFIG ---
        private var isCenterArtEnabled = false
        private var centerArtBlur = 0f
        private var centerArtOpacity = 255
        private var centerArtScale = 1.0f
        private var centerArtBitmap: Bitmap? = null
        private var lastProcessedArt: Bitmap? = null
        private val centerArtPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // --- RAIN / DROPLET CONFIG ---
        private var isRainEnabled = false
        private var userRainCount = 0
        private var userRainSpeedMult = 1.0f
        private var userRainSizeMult = 1.0f
        private var userRainOpacity = 255
        private var fluidEffectName = "Glass Rain"

        private val MAX_RAIN = 300
        private val rainX = FloatArray(MAX_RAIN)
        private val rainY = FloatArray(MAX_RAIN)
        private val rainSpeed = FloatArray(MAX_RAIN)
        private val rainSize = FloatArray(MAX_RAIN)
        private val rainAlpha = IntArray(MAX_RAIN)
        private val rainColor = IntArray(MAX_RAIN)

        private val rainPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // PARTICLES
        private val MAX_PARTICLES = 100
        private val partX = FloatArray(MAX_PARTICLES)
        private val partY = FloatArray(MAX_PARTICLES)
        private val partSpeed = FloatArray(MAX_PARTICLES)
        private val partAlpha = FloatArray(MAX_PARTICLES)
        private val particlePoints = FloatArray(MAX_PARTICLES * 2)
        private val particlePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = false
            strokeCap = Paint.Cap.ROUND
        }

        // WIDGET PAINTS
        private val titlePaint = Paint().apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val artistPaint = Paint().apply {
            color = Color.LTGRAY
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        private val artPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        private val artFadePaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // PHYSICS
        @Volatile private var touchX = -1000f
        @Volatile private var touchY = -1000f
        @Volatile private var isTouching = false
        private val blobOffsetX = FloatArray(BLOB_COUNT)
        private val blobOffsetY = FloatArray(BLOB_COUNT)

        private lateinit var gestureDetector: GestureDetector

        // VIGNETTE
        private val vignettePaint = Paint().apply { isDither = true }
        private val vignetteDrawPaint = Paint().apply { isDither = true }
        private var vignetteBitmap: Bitmap? = null
        private var screenW = 0
        private var screenH = 0

        // COLORS & TRANSITION
        private var drawingColors: MutableList<Int> = MusicListenerService.currentColors.toMutableList()
        private var oldColors: List<Int> = MusicListenerService.currentColors
        private var targetColors: List<Int> = MusicListenerService.currentColors
        private var fadeProgress = 1.0f

        // SMOOTHNESS CONFIG
        private var userFadeSpeed = 0.003f

        // SETTINGS
        private var userSpeed = 0.006f
        private var userSizeMult = 1.0f
        private var userBright = 1.0f
        private var userBeatPulse = 1.0f
        private var userBeatShake = 1.0f
        private var userDensity = 180
        private var userWander = 1.0f
        private var userSat = 1.0f
        private var userVignette = 0f
        private var isDustEnabled = true
        private var userDustOpacity = 0f
        private var userDustSize = 1.0f
        private var userDustCount = 50
        private var userGrainOpacity = 0.22f
        private var isWidgetEnabled = true
        private var userPosX = 0.5f
        private var userPosY = 0.5f
        private var userWidgetScale = 1.0f
        private var isTouchEnabled = true
        private var isPowerSaver = false
        private var isOledMode = false
        private var isLightMode = false
        private var isForceDarkIcons = false
        private var isDebug = false
        private var isDoubleTapEnabled = false

        // TUNING VARS
        private var userHue = 0f
        private var userBlobAlpha = 200
        private var blendModeXfermode: PorterDuffXfermode? = null

        private var isClassicMode = false
        private var isOgDiffuseMode = false
        private var isOgFluidMode = false
        private var renderModeName = "Liquid (Reborn)"
        private var liquidVariant = 0
        private var activeBlobCount = BLOB_COUNT
        private var userGlassSheen = 0.55f
        private var userGlassWarp = 0.65f
        private var userGlassDepth = 0.55f

        // --- LIQUID MESH ---
        private val MESH_COLS = 12
        private val MESH_ROWS = 20
        private val verts = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)
        private val origVerts = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)
        private val meshPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
        private val highlightPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 40
        }
        private val glassSheenPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glassFillPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.FILL
        }
        private val glassEdgePaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glassPath = Path()
        private val glassPath2 = Path()
        private val albumGlassBgPaint = Paint().apply {
            isAntiAlias = false
            isDither = true
        }
        private val albumGlassScrimPaint = Paint().apply {
            isAntiAlias = false
            isDither = true
            color = Color.BLACK
        }
        private var ogBackdropBitmap: Bitmap? = null
        private var ogBackdropSource: Bitmap? = null
        // --- REAL OG DIFFUSE (blurred-album field warped by FBM noise, à la the original GL app) ---
        // Colour tuning (saturation / brightness / hue) applied to the field as one ColorMatrix.
        private var ogTuneFilter: android.graphics.ColorMatrixColorFilter? = null
        private var ogFieldSource: Bitmap? = null   // the album art this field was built from
        private var ogFieldNew: Bitmap? = null       // current blurred field
        private var ogFieldOld: Bitmap? = null       // previous field (for crossfade)
        private var ogFieldBlend: Bitmap? = null      // per-frame crossfade composite
        private var ogFieldBlendCanvas: Canvas? = null
        private var ogRuntimeShader: android.graphics.RuntimeShader? = null
        private var ogFieldShaderBitmap: Bitmap? = null
        private var ogFieldShader: BitmapShader? = null
        private var ogOldFieldShaderBitmap: Bitmap? = null
        private var ogOldFieldShader: BitmapShader? = null
        private val idleFieldPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
        private var idleFieldBitmap: Bitmap? = null
        private var idleFieldColors: List<Int> = emptyList()
        private val ogFieldPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
        private val ogFieldMatrix = android.graphics.Matrix()
        private var ogNoiseClock = 0f          // == u_timeScaled in the original
        private var ogSpeedEased = 0.08f       // eases 0.08 (paused) -> 0.5 (playing)
        private var ogBorderEased = 0f         // beat-driven zoom border
        private var ogLastDtSec = 1f / 60f     // last frame delta in seconds, for time-based easing
        // The original Diffuse exposed exactly three graphics sliders; these are their values.
        private var ogUserScale = 1.0f         // u_scale (slider * 1.2 + 0.4)
        private var ogUserSpeed = 1.0f         // multiplier on the timeSpeed target
        private var ogUserStrength = 1.0f      // amplitude of the beat-driven u_border zoom
        // Album-art field crossfade, on its own clock (see ensureOgFields). 0 = fully old field,
        // 1 = fully new. Advances in real seconds so the fade is the same length on any refresh rate.
        private var ogFadeProgress = 1f
        private var ogFadeSeconds = 1.6f
        // Field builds run on this single worker so they never stall the Choreographer frame
        // callback; results are published back on the main thread via ogFieldHandler.
        private val ogFieldExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val ogFieldHandler = Handler(Looper.getMainLooper())
        private var ogFieldBuilding: Bitmap? = null
        // Resolution of the soft colour field. The original GL app blurred into a 192px buffer but
        // sampled it with GL_LINEAR on a full-screen quad; on Canvas we need more headroom because
        // the 8-bit field is the only source of gradient detail, so a larger buffer plus LINEAR
        // filtering plus the shader dither is what actually removes the banding.
        private val ogFieldPx = 512            // resolution of the soft color field
        private val ogBlendPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        private val ogBackdropPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
        private val ogOverlayPaint = Paint().apply {
            isAntiAlias = false
            isDither = true
        }
        private val ogScrimPaint = Paint().apply {
            isAntiAlias = false
            isDither = true
            color = Color.BLACK
        }
        private var meshBitmap: Bitmap? = null
        private var meshCanvas: Canvas? = null

        // DEBUG FPS
        private var lastTime = 0L
        private var frames = 0
        private var fps = 0
        private val debugPaint = Paint().apply {
            color = Color.GREEN
            textSize = 60f
            style = Paint.Style.FILL
        }

        // PAINTS ARRAY
        private val paints = ArrayList<Paint>(BLOB_COUNT).apply {
            for (i in 0 until BLOB_COUNT) {
                add(Paint().apply {
                    isFilterBitmap = true
                    isAntiAlias = true
                    isDither = true
                    alpha = 200
                })
            }
        }

        private val positions = FloatArray(BLOB_COUNT * 2)
        private var time = 0f
        private val hsvCache = FloatArray(3)

        private var TARGET_FPS = 120f
        private var frameIntervalNs = (1_000_000_000L / TARGET_FPS).toLong()
        private val slowMotionFactor = 0.25f
        private var lastFrameTimeNs = System.nanoTime()
        private var lastDrawTimeNs = 0L
        private var debugLastTimeNs = System.nanoTime()

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            surfaceHolder?.setFormat(PixelFormat.RGBA_8888)
            setTouchEventsEnabled(true)

            gestureDetector = GestureDetector(this@DiffuseWallpaperService, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isDoubleTapEnabled) {
                        MusicListenerService.togglePause()
                        surgeProgress = 0.5f // Visual Feedback
                        return true
                    }
                    return super.onDoubleTap(e)
                }
            })

            try {
                prefs = getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)
                prefs.registerOnSharedPreferenceChangeListener(this)
                updateSettings()
                createBaseBlob()

                // Init Particles
                for(i in 0 until MAX_PARTICLES) {
                    partX[i] = (Math.random() * 1000).toFloat()
                    partY[i] = (Math.random() * 2000).toFloat()
                    partSpeed[i] = (1f + Math.random() * 2f).toFloat()
                    partAlpha[i] = (Math.random() * 255).toFloat()
                }

                // Init Rain
                for(i in 0 until MAX_RAIN) {
                    resetRainDrop(i, true)
                }

                val filter = IntentFilter(MusicListenerService.ACTION_SURGE)
                val screenFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }

                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(surgeReceiver, filter, Context.RECEIVER_EXPORTED)
                    registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(surgeReceiver, filter)
                    registerReceiver(screenReceiver, screenFilter)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            try {
                unregisterReceiver(surgeReceiver)
                unregisterReceiver(screenReceiver)
                prefs.unregisterOnSharedPreferenceChangeListener(this)
                stopVisualizer()
                stopDrawing()
                if (::baseBlob.isInitialized) baseBlob.recycle()
                vignetteBitmap?.recycle()
                centerArtBitmap?.recycle()
                ogBackdropBitmap?.recycle()
                // Stop the field worker before recycling its output targets, otherwise an in-flight
                // build can publish onto a torn-down engine.
                ogFieldExecutor.shutdownNow()
                ogFieldHandler.removeCallbacksAndMessages(null)
                ogFieldBuilding = null
                ogFieldNew?.recycle()
                ogFieldOld?.recycle()
                ogFieldBlend?.recycle()
                idleFieldBitmap?.recycle()
                ogFieldShaderBitmap = null
                ogOldFieldShaderBitmap = null
                ogFieldShader = null
                ogOldFieldShader = null
                ogRuntimeShader = null
            } catch (e: Exception) {}
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (!isTouchEnabled || event == null) return

            gestureDetector.onTouchEvent(event)

            try {
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        touchX = event.x; touchY = event.y; isTouching = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isTouching = false; touchX = -1000f; touchY = -1000f
                    }
                }
            } catch (e: Exception) {}
        }

        override fun onSurfaceCreated(holder: android.view.SurfaceHolder?) {
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

            val shapeMode = prefs.getString("blob_shape", "Circle (Default)")
            val cx = size / 2f
            val cy = size / 2f
            val radius = size / 8f

            when (shapeMode) {
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
                "Hexagon" -> {
                    val path = Path()
                    val section = (2.0 * Math.PI / 6).toFloat()
                    path.moveTo(
                        (cx + radius * cos(0.0)).toFloat(),
                        (cy + radius * sin(0.0)).toFloat()
                    )
                    for (i in 1 until 6) {
                        path.lineTo(
                            (cx + radius * cos(section * i)).toFloat(),
                            (cy + radius * sin(section * i)).toFloat()
                        )
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "Diamond" -> {
                    val path = Path()
                    val dR = radius * 1.2f
                    path.moveTo(cx, cy - dR)
                    path.lineTo(cx + dR, cy)
                    path.lineTo(cx, cy + dR)
                    path.lineTo(cx - dR, cy)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "Cloud" -> {
                    canvas.drawCircle(cx - radius*0.5f, cy, radius*0.8f, paint)
                    canvas.drawCircle(cx + radius*0.5f, cy, radius*0.8f, paint)
                    canvas.drawCircle(cx, cy - radius*0.5f, radius, paint)
                }
                else -> {
                    canvas.drawCircle(cx, cy, radius, paint)
                }
            }
        }

        private fun resetRainDrop(i: Int, randomY: Boolean) {
            rainX[i] = (Math.random() * 2000).toFloat()
            rainY[i] = if (randomY) (Math.random() * 3000).toFloat() else -(Math.random() * 500).toFloat()

            val palette = MusicListenerService.currentColors
            if (palette.isNotEmpty()) {
                rainColor[i] = palette[(Math.random() * palette.size).toInt()]
            } else {
                rainColor[i] = Color.WHITE
            }

            val sizeVar = Math.random().toFloat()
            rainSize[i] = (2f + (sizeVar * 6f)) * userRainSizeMult
            rainSpeed[i] = 5f + (sizeVar * 15f)
            rainAlpha[i] = 50 + (sizeVar * 150).toInt()
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

        override fun onSurfaceChanged(holder: android.view.SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width; screenH = height

            for(i in 0 until MAX_PARTICLES) {
                partX[i] = (Math.random() * width).toFloat()
                partY[i] = (Math.random() * height).toFloat()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val s = surfaceHolder.surface
                    if (s != null && s.isValid) {
                        s.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                } catch (e: Exception) {}
            }

            val radius = hypot(width.toDouble(), height.toDouble()).toFloat() / 1.2f
            vignetteDrawPaint.shader = android.graphics.RadialGradient(
                width / 2f, height / 2f, radius,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0.4f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
            try {
                vignetteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(vignetteBitmap!!)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignetteDrawPaint)
            } catch (e: Exception) {
                vignetteBitmap = null
                e.printStackTrace()
            }

            initMesh(width, height)
        }

        private fun initMesh(w: Int, h: Int) {
            val cellW = w.toFloat() / MESH_COLS
            val cellH = h.toFloat() / MESH_ROWS
            var index = 0
            for (y in 0..MESH_ROWS) {
                val fy = y * cellH
                for (x in 0..MESH_COLS) {
                    val fx = x * cellW
                    origVerts[index * 2] = fx
                    origVerts[index * 2 + 1] = fy
                    verts[index * 2] = fx
                    verts[index * 2 + 1] = fy
                    index++
                }
            }
            meshBitmap?.recycle()
            meshBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            meshCanvas = Canvas(meshBitmap!!)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            if (key == "blob_shape") createBaseBlob()
            updateSettings()
        }

        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT >= 27) {
                if (isForceDarkIcons) {
                    return WallpaperColors(Color.valueOf(Color.BLACK), null, null, 0)
                }
                if (isLightMode) {
                    return WallpaperColors(Color.valueOf(Color.WHITE), null, null, WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
                } else {
                    return WallpaperColors(Color.valueOf(Color.BLACK), null, null, 0)
                }
            }
            return super.onComputeColors()
        }

        private fun isGlassRenderMode(): Boolean {
            return renderModeName != "Liquid (Reborn)" && !isClassicMode && !isOgDiffuseMode && !isOgFluidMode
        }

        /**
         * Reads the OG Diffuse settings contract. This build ships exactly one renderer (the faithful
         * OG Diffuse pipeline), so only the keys that renderer actually consumes are read here. Keys
         * belonging to the retired blob/glass/fluid modes are gone; leftover values in the prefs file
         * from older builds are simply ignored.
         */
        private fun updateSettings() {
            // --- Field (the three graphics sliders the original app exposed) ---
            // scale:    u_scale = slider * 1.2 + 0.4, per the original Player.java uniform setup.
            // speed:    scales the timeSpeed target; 50 == the original's stock playing rate.
            // strength: amplitude of the beat-driven u_border zoom.
            ogUserScale = (prefs.getInt("og_scale", 50).toFloat() / 100f) * 1.2f + 0.4f
            ogUserSpeed = (prefs.getInt("og_speed", 50).toFloat() / 50f)
            ogUserStrength = (prefs.getInt("og_strength", 50).toFloat() / 50f)

            // --- Reactivity ---
            // beat drives the zoom border alongside og_strength; both are live in drawOgDiffuseReal.
            userBeatPulse = (prefs.getInt("beat", 50).toFloat() / 50f) * 2.0f

            // --- Colour ---
            userSat = (prefs.getInt("sat", 50).toFloat() / 50f) * 1.25f
            userBright = (prefs.getInt("bright", 50).toFloat() / 50f) * 1.0f
            userHue = prefs.getInt("hue_shift", 0).toFloat()
            // Collapse the three colour controls into one ColorMatrix applied to the field draw.
            buildOgTuneFilter()

            // --- Depth / finish ---
            userVignette = (prefs.getInt("vignette", 0).toFloat() / 100f) * 200f
            userGrainOpacity = prefs.getInt("grain", 22).toFloat() / 100f

            // --- Now Playing overlay ---
            isWidgetEnabled = prefs.getBoolean("widget_on", true)
            userWidgetScale = 0.5f + (prefs.getInt("widget_scale", 50).toFloat() / 50f)
            userPosX = prefs.getInt("pos_x", 50).toFloat() / 100f
            userPosY = prefs.getInt("pos_y", 50).toFloat() / 100f

            // --- Album art centrepiece ---
            isCenterArtEnabled = prefs.getBoolean("center_art_on", false)
            centerArtBlur = prefs.getInt("center_blur", 0).toFloat() / 100f
            centerArtOpacity = (prefs.getInt("center_opacity", 100).toFloat() / 100f * 255).toInt()
            centerArtScale = 0.5f + (prefs.getInt("center_size", 50).toFloat() / 50f)
            lastProcessedArt = null

            // --- System ---
            isPowerSaver = prefs.getBoolean("power_saver", false)
            isDebug = prefs.getBoolean("debug_mode", false)

            val wasForceDark = isForceDarkIcons
            isForceDarkIcons = prefs.getBoolean("force_dark_icons", false)

            // The track-change palette crossfade. og_transition is in seconds so it reads the way the
            // user thinks about it; the legacy "transition_speed" curve was unitless and confusing.
            ogFadeSeconds = (prefs.getInt("og_transition", 16).toFloat() / 10f).coerceIn(0.1f, 6.0f)
            // Palette lerp is matched to the field fade so colour and field arrive together.
            userFadeSpeed = (1f / (ogFadeSeconds * 60f)).coerceIn(0.0005f, 0.2f)

            // This build is OG Diffuse only. The mode flags stay so the shared draw() dispatch and
            // isGlassRenderMode() keep type-checking, but they are fixed, not user-selectable.
            renderModeName = "OG Diffuse"
            isOgDiffuseMode = true
            isClassicMode = false
            isOgFluidMode = false
            liquidVariant = 0

            if (wasForceDark != isForceDarkIcons && Build.VERSION.SDK_INT >= 27) {
                notifyColorsChanged()
            }

            TARGET_FPS = if (isPowerSaver) 30f else 60f
            frameIntervalNs = (1_000_000_000L / TARGET_FPS).toLong()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val s = surfaceHolder.surface
                    if (s != null && s.isValid) {
                        s.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                } catch (e: Exception) {}
            }
        }

        private fun startDrawing() {
            stopDrawing()
            lastFrameTimeNs = System.nanoTime()
            lastDrawTimeNs = 0L
            debugLastTimeNs = System.nanoTime()
            choreographer.postFrameCallback(this)
        }

        private fun stopDrawing() {
            choreographer.removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            val dtNs = frameTimeNanos - lastFrameTimeNs
            lastFrameTimeNs = frameTimeNanos

            val dtMs = (dtNs.toFloat() / 1_000_000f).coerceIn(0f, 50f)
            time += userSpeed * (dtMs / 16.6667f) * slowMotionFactor

            // OG Diffuse noise clock, matching the original Player.java exactly:
            //   timeSpeed += (target - timeSpeed) * deltaTime * EASE      (target 0.5 playing / 0.08 paused)
            //   timeScaled += deltaTime * timeSpeed                        (wraps at 10000)
            // NOTE: the original accumulates in *seconds*, not frames. Advancing per-frame made the
            // warp run ~60x too fast, which is why the field churned instead of drifting.
            if (isOgDiffuseMode) {
                val dtSec = dtMs / 1000f
                ogLastDtSec = dtSec
                // Album-art field crossfade runs on wall-clock seconds, independent of the palette
                // transition, so a track change always fades over ogFadeSeconds.
                if (ogFadeProgress < 1f) {
                    ogFadeProgress = (ogFadeProgress + dtSec / ogFadeSeconds).coerceAtMost(1f)
                }
                val playSpeed = (if (MusicListenerService.currentIsPlaying) OG_SPEED_PLAYING else OG_SPEED_PAUSED) * ogUserSpeed
                ogSpeedEased += (playSpeed - ogSpeedEased) * dtSec * OG_SPEED_EASE
                ogNoiseClock += dtSec * ogSpeedEased
                if (ogNoiseClock > OG_TIME_WRAP) ogNoiseClock -= OG_TIME_WRAP
            }

            if (surgeProgress > 0.01f) {
                surgeProgress *= 0.94f
            } else {
                surgeProgress = 0f
            }

            if (lastDrawTimeNs == 0L || frameTimeNanos - lastDrawTimeNs >= frameIntervalNs) {
                draw()
                lastDrawTimeNs = frameTimeNanos
            }

            if (isVisible) {
                choreographer.postFrameCallback(this)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
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

        private fun trySetupVisualizer() {
            if (visualizer != null) return
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                visualizer = Visualizer(0)
                visualizer?.captureSize = Visualizer.getCaptureSizeRange()[1]
                visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null && fft.size > 10) {
                            var bassMag = 0f
                            for (i in 1 until 3) {
                                val r = fft[i * 2].toFloat(); val iVal = fft[i * 2 + 1].toFloat();
                                bassMag += hypot(r, iVal)
                            }
                            // FFT bytes are signed 8-bit magnitudes. Normalize the band before it
                            // reaches the zoom control; using the raw 0..128 range made ordinary
                            // music pin the old 0..1.4 clamp and every track hit with the same pulse.
                            val rawBass = bassMag / 2f
                            val targetBass = ((rawBass - 5f) / 72f).coerceIn(0f, 1f)
                            val bassEase = if (targetBass > bassEnergy) 0.34f else 0.12f
                            bassEnergy += (targetBass - bassEnergy) * bassEase

                            var trebleMag = 0f
                            for (i in 10 until 30) {
                                val r = fft[i * 2].toFloat(); val iVal = fft[i * 2 + 1].toFloat();
                                trebleMag += hypot(r, iVal)
                            }
                            val rawTreble = trebleMag / 20f
                            val targetTreble = ((rawTreble - 4f) / 68f).coerceIn(0f, 1f)
                            val trebleEase = if (targetTreble > trebleEnergy) 0.28f else 0.10f
                            trebleEnergy += (targetTreble - trebleEnergy) * trebleEase
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                visualizer?.enabled = true
            } catch (e: Exception) { stopVisualizer() }
        }

        private fun stopVisualizer() {
            runCatching { visualizer?.release() }
            visualizer = null
            bassEnergy = 0f
            trebleEnergy = 0f
        }

        private fun drawMarquee(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint, maxWidth: Float) {
            val textWidth = paint.measureText(text)
            val shouldScroll = text.length > 12 && textWidth > maxWidth

            if (!shouldScroll) {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, cx, cy, paint)
            } else {
                paint.textAlign = Paint.Align.LEFT
                val speed = 100f
                val gap = 150f
                val loopWidth = textWidth + gap
                val shift = (time * speed) % loopWidth

                canvas.save()
                canvas.clipRect(cx - (maxWidth/2 + 20), cy - 100, cx + (maxWidth/2 + 20), cy + 100)
                val startX = (cx - (maxWidth/2))
                val drawX = startX + (maxWidth) - shift

                canvas.drawText(text, drawX, cy, paint)
                if (drawX + textWidth + gap < cx + (maxWidth/2)) {
                    canvas.drawText(text, drawX + loopWidth, cy, paint)
                }
                if (drawX > startX) {
                    canvas.drawText(text, drawX - loopWidth, cy, paint)
                }
                canvas.restore()
            }
        }

        private fun updateBlobPositions(w: Float, h: Float, glassy: Boolean) {
            val wanderX = w * 0.36f * userWander
            val wanderY = h * 0.28f * userWander
            val variantPhase = liquidVariant * 0.73f
            val centerPull = if (glassy) 0.42f else 0.0f

            for (i in 0 until BLOB_COUNT) {
                val fi = i.toFloat()
                val angle = ((i.toFloat() / BLOB_COUNT) * (PI.toFloat() * 2f)) +
                        time * (0.12f + fi * 0.018f) + variantPhase
                val ripple = sin(time * (0.41f + fi * 0.03f) + fi * 1.7f + variantPhase)
                val anchorX = when (i % 4) {
                    0 -> w * 0.20f
                    1 -> w * 0.80f
                    2 -> w * 0.32f
                    else -> w * 0.68f
                }
                val anchorY = when (i % 4) {
                    0 -> h * 0.22f
                    1 -> h * 0.32f
                    2 -> h * 0.74f
                    else -> h * 0.78f
                }
                val orbitX = cos(angle) * wanderX * (0.52f + abs(ripple) * 0.48f)
                val orbitY = sin(angle * 0.86f + fi) * wanderY * (0.55f + (1f - abs(ripple)) * 0.42f)
                positions[i * 2] = anchorX * (1f - centerPull) + (w * 0.5f) * centerPull + orbitX
                positions[i * 2 + 1] = anchorY * (1f - centerPull) + (h * 0.5f) * centerPull + orbitY
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
            val styled = lerpColor(styledColor(color, 0), styleBackgroundSeed(), 0.82f)
            Color.colorToHSV(styled, hsvCache)
            hsvCache[1] = hsvCache[1].coerceAtLeast(0.62f).coerceIn(0f, 1f)
            hsvCache[2] = when (liquidVariant) {
                3 -> (hsvCache[2] * 0.36f).coerceIn(0.07f, 0.30f)
                6 -> (hsvCache[2] * 0.30f).coerceIn(0.05f, 0.24f)
                7 -> (hsvCache[2] * 0.34f).coerceIn(0.08f, 0.30f)
                else -> (hsvCache[2] * 0.34f).coerceIn(0.07f, 0.34f)
            }
            return Color.HSVToColor(255, hsvCache)
        }

        private fun drawAlbumGlassFast(canvas: Canvas, w: Float, h: Float, visualProgress: Float) {
            updateBlobPositions(w, h, true)

            val rawBg = lerpColor(
                oldColors.getOrElse(0) { Color.DKGRAY },
                targetColors.getOrElse(0) { Color.DKGRAY },
                visualProgress
            )
            val rawBg2 = lerpColor(
                oldColors.getOrElse(2) { rawBg },
                targetColors.getOrElse(2) { rawBg },
                visualProgress
            )
            val bgA = albumGlassBackground(rawBg, 0.36f)
            val bgB = albumGlassBackground(rawBg2, 0.24f)
            val bgC = albumGlassBackground(rawBg, 0.16f)
            albumGlassBgPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(bgA, bgB, bgC),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, albumGlassBgPaint)
            albumGlassBgPaint.shader = null

            val pulseBass = bassEnergy * userBeatPulse
            val blobCount = activeBlobCount.coerceAtMost(5)
            val baseRadius = min(w, h) * (0.22f + userSizeMult * 0.11f)
            val alphaLift = (pulseBass * 22f + surgeProgress * 28f).toInt()
            val finalAlpha = ((userBlobAlpha * 0.50f).toInt() + alphaLift).coerceIn(55, 170)

            for (i in 0 until blobCount) {
                if (isTouching) {
                    val dx = (positions[i * 2] + blobOffsetX[i]) - touchX
                    val dy = (positions[i * 2 + 1] + blobOffsetY[i]) - touchY
                    val dist = hypot(dx, dy)
                    val radius = w * 0.28f
                    if (dist < radius) {
                        val force = (radius - dist) / radius
                        blobOffsetX[i] += dx * force * 0.10f
                        blobOffsetY[i] += dy * force * 0.10f
                    }
                }
                blobOffsetX[i] *= 0.94f
                blobOffsetY[i] *= 0.94f

                val rawOld = oldColors.getOrElse(i) { rawBg }
                val rawNew = targetColors.getOrElse(i) { rawBg }
                val color = albumGlassColor(lerpColor(rawOld, rawNew, visualProgress), i)
                if (drawingColors.size > i) drawingColors[i] = color

                paints[i].colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                paints[i].alpha = finalAlpha
                paints[i].xfermode = blendModeXfermode

                val drift = 0.78f + abs(sin(time * (0.35f + i * 0.03f) + i)) * 0.24f
                val radius = baseRadius * drift
                val blobX = positions[i * 2] + blobOffsetX[i]
                val blobY = positions[i * 2 + 1] + blobOffsetY[i]
                destRect.set(blobX - radius, blobY - radius, blobX + radius, blobY + radius)
                canvas.drawBitmap(baseBlob, null, destRect, paints[i])
            }

            albumGlassScrimPaint.alpha = if (liquidVariant == 6) 82 else 54
            canvas.drawRect(0f, 0f, w, h, albumGlassScrimPaint)
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
            ogOverlayPaint.alpha = 255
            ogOverlayPaint.shader = RadialGradient(
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
            canvas.drawCircle(cx, cy, radius, ogOverlayPaint)
            canvas.restore()
            ogOverlayPaint.shader = null
        }

        private fun ensureOgBackdropBitmap(art: Bitmap?): Bitmap? {
            if (art == null || art.isRecycled) return null
            val cached = ogBackdropBitmap
            if (ogBackdropSource === art && cached != null && !cached.isRecycled) return cached

            try {
                ogBackdropBitmap?.recycle()
                val tiny = Bitmap.createScaledBitmap(art, 8, 8, true)
                ogBackdropBitmap = Bitmap.createScaledBitmap(tiny, 72, 72, true)
                if (tiny != ogBackdropBitmap && !tiny.isRecycled) tiny.recycle()
                ogBackdropSource = art
            } catch (_: Exception) {
                ogBackdropBitmap = null
                ogBackdropSource = null
            }
            return ogBackdropBitmap
        }

        /**
         * Builds the soft, blurred album-art colour field that the original Diffuse feeds into its
         * noise warp. The GL app ran an 8-pass Kawase blur down to a 192px buffer; we emulate the
         * same "structure-preserving heavy blur" with a mip-style down/up scale chain.
         */
        private fun buildOgField(art: Bitmap): Bitmap? {
            return try {
                // Mirror the original dual-filter pipeline: progressive down-sample (each step is a
                // bilinear box tap, i.e. one Kawase down pass), then progressive up-sample with a
                // 4-tap diagonal offset blend on the way back up. That preserves the large-scale
                // colour structure of the art while destroying all detail, which is exactly what the
                // GL app's 8-pass kawaseBlur -> 192px buffer produced.
                val downSteps = intArrayOf(128, 64, 32, 16, 8)
                var cur = Bitmap.createScaledBitmap(art, downSteps[0], downSteps[0], true)
                for (i in 1 until downSteps.size) {
                    val next = Bitmap.createScaledBitmap(cur, downSteps[i], downSteps[i], true)
                    if (!cur.isRecycled) cur.recycle()
                    cur = next
                }

                // Up-sample chain with the Kawase 4-tap diagonal spread at each level.
                val upSteps = intArrayOf(16, 32, 64, 128, ogFieldPx)
                for (size in upSteps) {
                    val next = kawaseUpPass(cur, size)
                    if (!cur.isRecycled) cur.recycle()
                    cur = next
                }

                // The original applied a brightness offset in kawaseBlur.frag so the field never
                // crushed to black on dark art; replicate it with a light screen-style lift.
                applyOgFieldLift(cur)
                cur
            } catch (_: Exception) {
                null
            }
        }

        /**
         * One Kawase up-sample pass: scale to [size] and composite four half-alpha diagonal taps,
         * matching `reSample()` in the original `kawaseBlur.frag` (4 taps at +/- (d+0.5)/res).
         */
        private fun kawaseUpPass(src: Bitmap, size: Int): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            val dst = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
            // Offset of one source texel, expressed in destination pixels (the shader's (d+0.5)/res).
            val d = (size.toFloat() / src.width.toFloat()) * 1.5f
            val offsets = arrayOf(
                floatArrayOf(-d, -d), floatArrayOf(d, -d),
                floatArrayOf(-d, d), floatArrayOf(d, d)
            )
            // Incremental average: tap n is composited at alpha 1/(n+1), so after all four taps each
            // has contributed exactly 1/4 — the equal-weight 4-tap of the original reSample().
            for ((i, o) in offsets.withIndex()) {
                val share = 1f / (i + 1).toFloat()
                p.alpha = (share * 255f).toInt().coerceIn(0, 255)
                c.save()
                c.translate(o[0], o[1])
                c.drawBitmap(src, null, dst, p)
                c.restore()
            }
            return out
        }

        /** Brightness offset from the original kawaseBlur.frag, so dark art still reads as a wash. */
        private fun applyOgFieldLift(field: Bitmap) {
            val c = Canvas(field)
            val p = Paint().apply {
                color = Color.WHITE
                alpha = 18
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
            }
            c.drawRect(0f, 0f, field.width.toFloat(), field.height.toFloat(), p)
        }

        /**
         * Refresh the field pair when the album art changes, keeping the previous one for crossfade.
         *
         * The field swap drives its OWN fade clock ([ogFadeProgress]) rather than reusing the global
         * colour-transition [fadeProgress]. Two reasons:
         *  - The palette transition and the album-art transition do not fire on the same frame
         *    (Palette runs async), so fadeProgress was routinely already at 1.0 by the time the new
         *    field existed - the crossfade branch never ran, which is why track changes hard-cut.
         *  - The original app faded the field over a fixed wall-clock duration, independent of any
         *    colour easing.
         */
        private fun ensureOgFields(art: Bitmap?) {
            if (art == null || art.isRecycled) return
            if (ogFieldSource === art && ogFieldNew != null && !ogFieldNew!!.isRecycled) return
            if (ogFieldBuilding === art) return   // build already in flight for this art

            // Build OFF the render thread. buildOgField runs a 10-step scale chain with four Kawase
            // composites per level at ogFieldPx; doing that inline stalled doFrame for hundreds of
            // milliseconds on every track change, which is what produced the stale-render flash
            // before the new field appeared. While the build runs we keep drawing the current field.
            ogFieldBuilding = art
            ogFieldExecutor.execute {
                val next = buildOgField(art)
                ogFieldHandler.post {
                    ogFieldBuilding = null
                    if (next == null) return@post
                    if (art.isRecycled) { next.recycle(); return@post }
                    ogFieldOld?.let { if (it !== ogFieldNew && !it.isRecycled) it.recycle() }
                    ogFieldOld = ogFieldNew
                    ogFieldNew = next
                    ogFieldSource = art
                    // Start a fresh field fade. If there was no previous field (first art of the
                    // session) skip straight to 1.0 so we don't fade in from an empty buffer.
                    ogFadeProgress = if (ogFieldOld == null) 1f else 0f
                }
            }
        }

        /**
         * Faithful OG Diffuse renderer: a heavily blurred album-art field, domain-warped by animated
         * fbm noise (direct AGSL port of the original `noise.frag`), with beat-reactive zoom and a
         * crossfade between tracks. On API < 33 (no RuntimeShader) it falls back to the same field
         * with an animated drift matrix, which still reads far closer to the original than blobs.
         */
        private fun drawOgDiffuseReal(canvas: Canvas, w: Float, h: Float) {
            ensureOgFields(MusicListenerService.currentAlbumArt)
            val albumField = ogFieldNew?.takeUnless { it.isRecycled }
            val isIdleField = albumField == null
            val newField = albumField ?: ensureIdleField()

            if (newField == null || newField.isRecycled) {
                // Some players publish metadata without cover art. Keep the idle state atmospheric
                // by using a cached, palette-derived wash instead of a single flat color.
                canvas.drawColor(Color.DKGRAY)
                return
            }

            // Track-change crossfade state. On API 33+ this is handed straight to the warp shader,
            // which mixes the two fields per-fragment. It is NOT pre-composited into an intermediate
            // bitmap any more: that required a software Canvas, and a software Canvas cannot draw a
            // RuntimeShader, so every fade threw and the frame fell back to the flat background
            // colour for the whole ogFadeSeconds window.
            val fieldFade = if (isIdleField) 1f else ogFadeProgress
            val transitioning = !isIdleField && fieldFade < 0.999f && ogFieldOld != null && !ogFieldOld!!.isRecycled
            val prevField = if (transitioning) ogFieldOld else null

            // Beat-reactive zoom border, eased exactly like the original: border += (target - border)
            // * deltaTime * 1.0f. Time-based, not per-frame, so it drifts at the same rate as the GL app.
            val beatTarget = ((bassEnergy * userBeatPulse + surgeProgress * 0.6f) * ogUserStrength).coerceIn(0f, 1.4f)
            ogBorderEased += (beatTarget - ogBorderEased) * ogLastDtSec * OG_BORDER_EASE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawOgWarpShader(canvas, w, h, newField, prevField, fieldFade)
            } else {
                // Pre-33 has no RuntimeShader at all, so the alpha-composite pre-pass is still the
                // only option there - and an ARGB_8888 Bitmap canvas handles plain drawBitmap fine.
                val field: Bitmap = if (transitioning) {
                    val blend = ensureOgBlendBitmap()
                    if (blend != null) {
                        val bc = ogFieldBlendCanvas!!
                        val fieldRect = RectF(0f, 0f, ogFieldPx.toFloat(), ogFieldPx.toFloat())
                        bc.drawBitmap(ogFieldOld!!, null, fieldRect, null)
                        ogBlendPaint.alpha = (fieldFade * 255f).toInt().coerceIn(0, 255)
                        bc.drawBitmap(newField, null, fieldRect, ogBlendPaint)
                        blend
                    } else newField
                } else newField
                drawOgFieldFallback(canvas, w, h, field)
            }
        }

        /** Build a soft, organic palette field once per palette. A multi-stop diagonal gradient
         * created straight bands across the wallpaper when media metadata had no cover image. */
        private fun ensureIdleField(): Bitmap? {
            val palette = List(4) { index -> targetColors.getOrElse(index) { Color.DKGRAY } }
            val current = idleFieldBitmap
            if (current != null && !current.isRecycled && idleFieldColors == palette) return current

            current?.recycle()
            val size = ogFieldPx
            val bitmap = try {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            } catch (_: Exception) {
                idleFieldBitmap = null
                return null
            }
            val fieldCanvas = Canvas(bitmap)
            fieldCanvas.drawColor(ogDiffuseBackground(palette[0], 0.44f))

            val centers = arrayOf(
                floatArrayOf(0.20f, 0.20f),
                floatArrayOf(0.80f, 0.29f),
                floatArrayOf(0.25f, 0.78f),
                floatArrayOf(0.82f, 0.82f),
                floatArrayOf(0.52f, 0.52f)
            )
            val shades = intArrayOf(
                ogDiffuseBackground(palette[0], 0.96f),
                ogDiffuseBackground(palette[1], 0.90f),
                ogDiffuseBackground(palette[2], 0.82f),
                ogDiffuseBackground(palette[3], 0.88f),
                ogDiffuseBackground(palette[0], 0.72f)
            )
            val radius = size * 0.58f
            for (index in centers.indices) {
                val cx = centers[index][0] * size
                val cy = centers[index][1] * size
                idleFieldPaint.shader = RadialGradient(
                    cx,
                    cy,
                    radius,
                    intArrayOf(
                        withAlpha(shades[index], if (index == centers.lastIndex) 135 else 190),
                        withAlpha(shades[index], 70),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.56f, 1f),
                    Shader.TileMode.CLAMP
                )
                fieldCanvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), idleFieldPaint)
            }
            idleFieldPaint.shader = null
            idleFieldBitmap = bitmap
            idleFieldColors = palette
            return bitmap
        }

        private fun ensureOgBlendBitmap(): Bitmap? {
            val existing = ogFieldBlend
            if (existing != null && !existing.isRecycled) return existing
            return try {
                val b = Bitmap.createBitmap(ogFieldPx, ogFieldPx, Bitmap.Config.ARGB_8888)
                ogFieldBlend = b
                ogFieldBlendCanvas = Canvas(b)
                b
            } catch (_: Exception) { null }
        }

        private fun drawOgWarpShader(
            canvas: Canvas,
            w: Float,
            h: Float,
            field: Bitmap,
            oldField: Bitmap?,
            fieldMix: Float
        ) {
            val shader = ogRuntimeShader ?: android.graphics.RuntimeShader(OG_DIFFUSE_AGSL).also {
                ogRuntimeShader = it
            }
            val fieldShader = cachedFieldShader(field, previous = false)
            shader.setInputShader("field", fieldShader)
            // The crossfade partner. AGSL requires every declared child shader to be bound, so when
            // no transition is running we bind the current field to both inputs and set fieldMix = 1.
            val prev = if (oldField != null && !oldField.isRecycled) oldField else field
            val prevShader = if (prev === field) fieldShader else cachedFieldShader(prev, previous = true)
            shader.setInputShader("fieldOld", prevShader)
            shader.setFloatUniform(
                "fieldMix",
                if (oldField != null && !oldField.isRecycled) fieldMix.coerceIn(0f, 1f) else 1f
            )
            shader.setFloatUniform("resolution", w, h)
            shader.setFloatUniform("fieldRes", ogFieldPx.toFloat(), ogFieldPx.toFloat())
            shader.setFloatUniform("timeScaled", ogNoiseClock)
            // u_scale in the original is (slider*1.2 + 0.4); centred slider ≈ 1.0.
            shader.setFloatUniform("scale", ogUserScale)
            shader.setFloatUniform("border", ogBorderEased * 0.35f)
            ogFieldPaint.shader = shader
            ogFieldPaint.colorFilter = ogTuneFilter
            canvas.drawRect(0f, 0f, w, h, ogFieldPaint)
            ogFieldPaint.shader = null
            ogFieldPaint.colorFilter = null
        }

        /** Reuse the shader wrappers bound to RuntimeShader children instead of allocating two per frame. */
        private fun cachedFieldShader(bitmap: Bitmap, previous: Boolean): BitmapShader {
            val cachedBitmap = if (previous) ogOldFieldShaderBitmap else ogFieldShaderBitmap
            val cachedShader = if (previous) ogOldFieldShader else ogFieldShader
            if (cachedBitmap === bitmap && cachedShader != null && !bitmap.isRecycled) return cachedShader

            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // RuntimeShader child bitmaps otherwise use point sampling and expose the field's texels.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
            if (previous) {
                ogOldFieldShaderBitmap = bitmap
                ogOldFieldShader = shader
            } else {
                ogFieldShaderBitmap = bitmap
                ogFieldShader = shader
            }
            return shader
        }

        /**
         * Builds the colour-tuning filter (saturation / brightness / hue) that is applied to the OG
         * field as it is drawn. The retired blob renderers applied these per-blob in software via
         * [applyTuning]; the OG field is a single shader draw, so the equivalent is one ColorMatrix on
         * [ogFieldPaint]. Returns null when all three controls sit at neutral so the common case pays
         * nothing.
         */
        private fun buildOgTuneFilter() {
            val sat = userSat.coerceIn(0f, 2.5f)
            val bright = userBright.coerceIn(0.2f, 2.0f)
            val hue = userHue
            if (abs(sat - 1f) < 0.01f && abs(bright - 1f) < 0.01f && abs(hue) < 0.5f) {
                ogTuneFilter = null
                return
            }
            val cm = android.graphics.ColorMatrix()
            cm.setSaturation(sat)
            if (abs(hue) > 0.5f) {
                val rad = hue / 180f * PI.toFloat()
                val c = cos(rad)
                val s = sin(rad)
                // Standard luminance-preserving hue rotation (Rec.709 weights).
                val lr = 0.213f
                val lg = 0.715f
                val lb = 0.072f
                cm.postConcat(
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            lr + c * (1f - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1f - lb), 0f, 0f,
                            lr + c * (-lr) + s * 0.143f, lg + c * (1f - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
                            lr + c * (-lr) + s * (-(1f - lr)), lg + c * (-lg) + s * lg, lb + c * (1f - lb) + s * lb, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            if (abs(bright - 1f) > 0.01f) {
                cm.postConcat(
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            bright, 0f, 0f, 0f, 0f,
                            0f, bright, 0f, 0f, 0f,
                            0f, 0f, bright, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            ogTuneFilter = android.graphics.ColorMatrixColorFilter(cm)
        }

        private fun drawOgFieldFallback(canvas: Canvas, w: Float, h: Float, field: Bitmap) {
            // No RuntimeShader: upscale the blurred field to fill, with a slow drift + beat zoom so it
            // still breathes like the original liquid wash.
            val drift = time * 0.06f
            val zoom = 1.18f + ogBorderEased * 0.10f
            val fw = ogFieldPx.toFloat()
            val fh = ogFieldPx.toFloat()
            ogFieldMatrix.reset()
            // Scale field to cover the screen at `zoom`, keeping it centred.
            val sx = (w / fw) * zoom
            val sy = (h / fh) * zoom
            ogFieldMatrix.postScale(sx, sy)
            val extraX = fw * sx - w
            val extraY = fh * sy - h
            ogFieldMatrix.postTranslate(
                -extraX * (0.5f + 0.5f * sin(drift)),
                -extraY * (0.5f + 0.5f * cos(drift * 0.8f))
            )
            val shader = BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(ogFieldMatrix)
            ogFieldPaint.shader = shader
            canvas.drawRect(0f, 0f, w, h, ogFieldPaint)
            ogFieldPaint.shader = null
        }

        private fun drawOgDiffuse(canvas: Canvas, w: Float, h: Float, visualProgress: Float) {
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

            val backdrop = ensureOgBackdropBitmap(MusicListenerService.currentAlbumArt)
            val primary = ogDiffuseColor(rawBg, 0)
            val light = ogDiffuseColor(rawBg2, 1)
            val deep = ogDiffuseColor(rawBg3, 2)
            val counter = ogDiffuseColor(rawBg, 3)
            val secondCounter = ogDiffuseColor(rawBg2, 4)
            val glow = ogDiffuseColor(targetColors.getOrElse(4) { rawBg3 }, 5)

            ogOverlayPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    ogDiffuseBackground(rawBg, 0.82f),
                    ogDiffuseBackground(rawBg2, 0.66f),
                    ogDiffuseBackground(rawBg3, 0.78f)
                ),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, ogOverlayPaint)
            ogOverlayPaint.shader = null

            if (backdrop != null) {
                ogBackdropPaint.alpha = 58
                destRect.set(0f, 0f, w, h)
                canvas.drawBitmap(backdrop, null, destRect, ogBackdropPaint)
            }

            ogOverlayPaint.shader = LinearGradient(
                0f, h * 0.08f, w, h,
                intArrayOf(withAlpha(light, 70), Color.TRANSPARENT, withAlpha(counter, 78)),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, ogOverlayPaint)
            ogOverlayPaint.shader = null

            val pulseBass = bassEnergy * userBeatPulse
            val alphaBoost = (pulseBass * 18f + surgeProgress * 24f).toInt()
            val baseAlpha = ((userBlobAlpha * 0.44f).toInt() + alphaBoost).coerceIn(72, 158)
            val p = time * 0.12f

            drawOgEllipse(canvas, w * (0.18f + sin(p) * 0.035f), h * (0.06f + cos(p * 0.8f) * 0.020f), w * 0.84f, h * 0.42f, light, baseAlpha + 18, 0.44f)
            drawOgEllipse(canvas, w * (0.88f + cos(p * 0.9f) * 0.030f), h * (0.18f + sin(p * 0.7f) * 0.030f), w * 0.72f, h * 0.54f, primary, baseAlpha + 8, 0.50f)
            drawOgEllipse(canvas, w * (0.20f + cos(p * 1.2f) * 0.035f), h * (0.56f + sin(p * 0.9f) * 0.028f), w * 0.86f, h * 0.62f, deep, baseAlpha + 34, 0.48f)
            drawOgEllipse(canvas, w * (0.94f + sin(p * 0.7f) * 0.020f), h * (0.76f + cos(p * 1.1f) * 0.026f), w * 0.78f, h * 0.52f, counter, baseAlpha - 6, 0.54f)
            drawOgEllipse(canvas, w * (0.46f + sin(p * 0.6f) * 0.030f), h * (1.06f + cos(p * 0.8f) * 0.018f), w * 0.96f, h * 0.48f, secondCounter, baseAlpha - 10, 0.58f)
            drawOgEllipse(canvas, w * (0.54f + cos(p * 1.4f) * 0.025f), h * (0.36f + sin(p * 1.0f) * 0.030f), w * 0.58f, h * 0.34f, glow, baseAlpha - 18, 0.42f)

            ogScrimPaint.alpha = if (isLightMode) 0 else 18
            canvas.drawRect(0f, 0f, w, h, ogScrimPaint)
        }

        private fun drawOgRibbon(
            canvas: Canvas,
            w: Float,
            h: Float,
            yNorm: Float,
            color: Int,
            alpha: Int,
            phase: Float,
            thickness: Float
        ) {
            val y = h * yNorm
            ogOverlayPaint.style = Paint.Style.STROKE
            ogOverlayPaint.strokeCap = Paint.Cap.ROUND
            ogOverlayPaint.strokeJoin = Paint.Join.ROUND
            ogOverlayPaint.strokeWidth = thickness
            ogOverlayPaint.alpha = 255
            ogOverlayPaint.shader = LinearGradient(
                0f, y - thickness,
                w, y + thickness,
                intArrayOf(Color.TRANSPARENT, withAlpha(color, alpha), withAlpha(color, (alpha * 0.62f).toInt()), Color.TRANSPARENT),
                floatArrayOf(0f, 0.28f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )

            glassPath.reset()
            glassPath.moveTo(-w * 0.24f, y + sin(phase) * h * 0.055f)
            glassPath.cubicTo(
                w * 0.08f,
                y - h * (0.09f + sin(phase * 0.7f) * 0.026f),
                w * 0.34f,
                y + h * (0.13f + cos(phase * 0.9f) * 0.030f),
                w * 0.58f,
                y + sin(phase + 1.1f) * h * 0.070f
            )
            glassPath.cubicTo(
                w * 0.78f,
                y - h * (0.15f + cos(phase * 0.8f) * 0.026f),
                w * 1.02f,
                y + h * (0.10f + sin(phase * 1.1f) * 0.024f),
                w * 1.24f,
                y + cos(phase) * h * 0.045f
            )
            canvas.drawPath(glassPath, ogOverlayPaint)

            ogOverlayPaint.shader = null
            ogOverlayPaint.style = Paint.Style.FILL
            ogOverlayPaint.strokeWidth = 1f
            ogOverlayPaint.alpha = 255
        }

        private fun drawOgFluidDiffuse(canvas: Canvas, w: Float, h: Float, visualProgress: Float) {
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

            val backdrop = ensureOgBackdropBitmap(MusicListenerService.currentAlbumArt)
            val primary = ogDiffuseColor(rawBg, 0)
            val light = ogDiffuseColor(rawBg2, 1)
            val deep = ogDiffuseColor(rawBg3, 2)
            val counter = ogDiffuseColor(rawBg, 3)
            val secondCounter = ogDiffuseColor(rawBg2, 4)
            val glow = ogDiffuseColor(targetColors.getOrElse(4) { rawBg3 }, 5)

            ogOverlayPaint.shader = LinearGradient(
                0f, 0f, w * 0.8f, h,
                intArrayOf(
                    ogDiffuseBackground(rawBg, 0.70f),
                    ogDiffuseBackground(rawBg2, 0.56f),
                    ogDiffuseBackground(rawBg3, 0.72f)
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, ogOverlayPaint)
            ogOverlayPaint.shader = null

            if (backdrop != null) {
                ogBackdropPaint.alpha = 42
                destRect.set(0f, 0f, w, h)
                canvas.drawBitmap(backdrop, null, destRect, ogBackdropPaint)
            }

            val pulseBass = bassEnergy * userBeatPulse
            val alphaBoost = (pulseBass * 16f + surgeProgress * 22f).toInt()
            val baseAlpha = ((userBlobAlpha * 0.38f).toInt() + alphaBoost).coerceIn(62, 138)
            val p = time * (0.20f + userWander * 0.035f)

            drawOgEllipse(canvas, w * (0.10f + sin(p * 0.55f) * 0.035f), h * 0.10f, w * 0.76f, h * 0.40f, light, baseAlpha + 10, 0.42f)
            drawOgEllipse(canvas, w * (1.02f + cos(p * 0.50f) * 0.025f), h * 0.28f, w * 0.66f, h * 0.48f, primary, baseAlpha + 2, 0.46f)
            drawOgEllipse(canvas, w * (0.18f + cos(p * 0.68f) * 0.028f), h * 0.78f, w * 0.84f, h * 0.48f, secondCounter, baseAlpha - 10, 0.54f)

            drawOgRibbon(canvas, w, h, 0.18f, light, baseAlpha + 36, p + 0.2f, h * 0.24f)
            drawOgRibbon(canvas, w, h, 0.36f, counter, baseAlpha + 18, p + 1.6f, h * 0.30f)
            drawOgRibbon(canvas, w, h, 0.56f, deep, baseAlpha + 44, p + 3.0f, h * 0.34f)
            drawOgRibbon(canvas, w, h, 0.75f, primary, baseAlpha + 28, p + 4.4f, h * 0.28f)
            drawOgRibbon(canvas, w, h, 0.92f, glow, baseAlpha + 6, p + 5.7f, h * 0.24f)

            ogOverlayPaint.shader = LinearGradient(
                w * 0.10f, 0f,
                w * 0.92f, h,
                intArrayOf(withAlpha(Color.WHITE, 20), Color.TRANSPARENT, withAlpha(Color.WHITE, 14)),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, ogOverlayPaint)
            ogOverlayPaint.shader = null

            ogScrimPaint.alpha = if (isLightMode) 0 else 16
            canvas.drawRect(0f, 0f, w, h, ogScrimPaint)
        }

        private fun drawAlbumGlassAccents(canvas: Canvas, w: Float, h: Float, visualProgress: Float, pulseBass: Float) {
            val accent = albumGlassColor(
                lerpColor(
                    oldColors.getOrElse(1) { Color.WHITE },
                    targetColors.getOrElse(1) { Color.WHITE },
                    visualProgress
                ),
                1
            )
            glassEdgePaint.color = accent
            glassEdgePaint.alpha = (46 + pulseBass * 18f).toInt().coerceIn(35, 92)
            glassEdgePaint.strokeWidth = (w * 0.0042f).coerceIn(2.5f, 7f)
            glassEdgePaint.strokeCap = Paint.Cap.ROUND

            for (i in 0 until 3) {
                val y = h * (0.20f + i * 0.25f) + sin(time * 0.35f + i) * h * 0.035f
                glassPath.reset()
                glassPath.moveTo(-w * 0.08f, y)
                glassPath.cubicTo(w * 0.24f, y - h * 0.07f, w * 0.62f, y + h * 0.08f, w * 1.08f, y - h * 0.025f)
                canvas.drawPath(glassPath, glassEdgePaint)
            }

            glassSheenPaint.color = Color.WHITE
            glassSheenPaint.alpha = (34 * userGlassSheen).toInt().coerceIn(14, 56)
            glassSheenPaint.strokeWidth = (w * 0.006f).coerceIn(4f, 10f)
            val y = h * (0.58f + sin(time * 0.22f) * 0.06f)
            glassPath2.reset()
            glassPath2.moveTo(w * 1.05f, y)
            glassPath2.cubicTo(w * 0.76f, y + h * 0.08f, w * 0.32f, y - h * 0.07f, -w * 0.05f, y + h * 0.03f)
            canvas.drawPath(glassPath2, glassSheenPaint)
        }

        // Mesh-based glass renderer: album-color blobs are drawn once offscreen, then refracted.
        private fun drawLiquidMesh(canvas: Canvas, w: Float, h: Float, visualProgress: Float) {
            val mCanvas = meshCanvas ?: return
            val mBitmap = meshBitmap ?: return

            updateBlobPositions(w, h, true)
            mCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val pulseBass = bassEnergy * userBeatPulse
            val baseRadius = hypot(w.toDouble(), h.toDouble()).toFloat() *
                    (0.10f + userSizeMult * 0.070f + userGlassDepth * 0.024f)
            val alphaLift = (pulseBass * 28f + surgeProgress * 40f).toInt()
            val finalAlpha = ((userBlobAlpha * 0.68f).toInt() + alphaLift).coerceIn(70, 210)

            for (i in 0 until activeBlobCount) {
                if (isTouching) {
                    val dx = (positions[i * 2] + blobOffsetX[i]) - touchX
                    val dy = (positions[i * 2 + 1] + blobOffsetY[i]) - touchY
                    val dist = hypot(dx, dy)
                    val radius = w * 0.36f
                    if (dist < radius) {
                        val force = (radius - dist) / radius
                        blobOffsetX[i] += dx * force * 0.13f
                        blobOffsetY[i] += dy * force * 0.13f
                    }
                }
                blobOffsetX[i] *= 0.94f
                blobOffsetY[i] *= 0.94f

                val blobX = positions[i * 2] + blobOffsetX[i]
                val blobY = positions[i * 2 + 1] + blobOffsetY[i]
                val rawOld = oldColors.getOrElse(i) { Color.GRAY }
                val rawNew = targetColors.getOrElse(i) { Color.GRAY }
                val blendedBlob = styledColor(lerpColor(rawOld, rawNew, visualProgress), i)
                if (drawingColors.size > i) drawingColors[i] = blendedBlob

                paints[i].colorFilter = PorterDuffColorFilter(blendedBlob, PorterDuff.Mode.SRC_IN)
                paints[i].alpha = finalAlpha
                paints[i].xfermode = when (liquidVariant) {
                    4, 6 -> null
                    else -> blendModeXfermode
                }

                val radiusDrift = 0.86f + abs(sin(time * (0.32f + i * 0.02f) + i)) * 0.34f
                val radius = baseRadius * radiusDrift + pulseBass * 34f
                destRect.set(blobX - radius, blobY - radius, blobX + radius, blobY + radius)
                mCanvas.drawBitmap(baseBlob, null, destRect, paints[i])
            }

            var index = 0
            val warp = (10f + userGlassWarp * 24f + pulseBass * 5f).coerceAtMost(58f)
            for (y in 0..MESH_ROWS) {
                for (x in 0..MESH_COLS) {
                    val ox = origVerts[index * 2]
                    val oy = origVerts[index * 2 + 1]
                    var dx = 0f
                    var dy = 0f

                    for (i in 0 until activeBlobCount) {
                        val bx = positions[i * 2]
                        val by = positions[i * 2 + 1]
                        val vx = ox - bx
                        val vy = oy - by
                        val distSq = vx * vx + vy * vy
                        val influence = (1.0f / (distSq * 0.000012f + 1f)).pow(2f)
                        dx += sin(time * 1.6f + i * 0.9f + oy * 0.004f) * warp * influence
                        dy += cos(time * 1.45f + i * 1.1f + ox * 0.003f) * warp * influence
                    }

                    dx += sin(time * 0.9f + oy * 0.009f + liquidVariant) * userGlassWarp * 4f
                    dy += cos(time * 0.8f + ox * 0.007f + liquidVariant) * userGlassWarp * 4f
                    verts[index * 2] = ox + dx
                    verts[index * 2 + 1] = oy + dy
                    index++
                }
            }

            canvas.drawBitmapMesh(mBitmap, MESH_COLS, MESH_ROWS, verts, 0, null, 0, meshPaint)

            highlightPaint.strokeWidth = (1.4f + userGlassSheen * 1.6f).coerceAtMost(4f)
            highlightPaint.alpha = ((28 + pulseBass * 18 + surgeProgress * 46) * userGlassSheen).toInt().coerceIn(0, 120)
            canvas.drawBitmapMesh(mBitmap, MESH_COLS, MESH_ROWS, verts, 0, null, 0, highlightPaint)
            drawGlassAccents(canvas, w, h, pulseBass)
        }

        private fun drawGlassAccents(canvas: Canvas, w: Float, h: Float, pulseBass: Float) {
            glassFillPaint.shader = null
            glassFillPaint.color = styleBackgroundSeed()
            glassFillPaint.alpha = when (liquidVariant) {
                3 -> 118
                6 -> 132
                else -> 92
            }
            canvas.drawRect(0f, 0f, w, h, glassFillPaint)

            val glowAlpha = ((22 + pulseBass * 10f) * userGlassSheen).toInt().coerceIn(0, 58)
            glassFillPaint.shader = RadialGradient(
                w * (0.42f + sin(time * 0.18f) * 0.08f),
                h * (0.34f + cos(time * 0.16f) * 0.08f),
                hypot(w.toDouble(), h.toDouble()).toFloat() * 0.62f,
                intArrayOf(Color.argb(glowAlpha, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, glassFillPaint)
            glassFillPaint.shader = null

            val sheenAlpha = ((38 + pulseBass * 22 + surgeProgress * 90) * userGlassSheen).toInt().coerceIn(0, 170)
            glassSheenPaint.alpha = sheenAlpha
            glassSheenPaint.strokeWidth = (w * 0.010f).coerceIn(5f, 18f)
            glassSheenPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )

            val y1 = h * (0.22f + sin(time * 0.22f + liquidVariant) * 0.08f)
            glassPath.reset()
            glassPath.moveTo(-w * 0.12f, y1)
            glassPath.cubicTo(w * 0.20f, y1 - h * 0.16f, w * 0.56f, y1 + h * 0.18f, w * 1.12f, y1 - h * 0.04f)
            canvas.drawPath(glassPath, glassSheenPaint)

            glassSheenPaint.strokeWidth *= 0.46f
            glassSheenPaint.alpha = (sheenAlpha * 0.72f).toInt()
            val y2 = h * (0.64f + cos(time * 0.18f + liquidVariant) * 0.10f)
            glassPath2.reset()
            glassPath2.moveTo(w * 1.08f, y2)
            glassPath2.cubicTo(w * 0.78f, y2 + h * 0.12f, w * 0.35f, y2 - h * 0.15f, -w * 0.08f, y2 + h * 0.03f)
            canvas.drawPath(glassPath2, glassSheenPaint)
            glassSheenPaint.shader = null

            glassEdgePaint.color = Color.WHITE
            glassEdgePaint.alpha = (18 + userGlassSheen * 42f).toInt().coerceIn(18, 72)
            glassEdgePaint.strokeWidth = (w * 0.0035f).coerceIn(2f, 7f)
            for (i in 0 until activeBlobCount step 2) {
                val bx = positions[i * 2] + blobOffsetX[i]
                val by = positions[i * 2 + 1] + blobOffsetY[i]
                val r = w * (0.10f + (i % 3) * 0.025f) * userGlassDepth
                destRect.set(bx - r, by - r * 0.55f, bx + r, by + r * 0.55f)
                canvas.drawArc(destRect, 205f + i * 18f + time * 12f, 82f, false, glassEdgePaint)
            }

            if (liquidVariant == 5 || liquidVariant == 2 || liquidVariant == 7) {
                glassEdgePaint.alpha = (26 + userGlassSheen * 64f).toInt().coerceIn(26, 98)
                glassEdgePaint.strokeWidth = (w * 0.0026f).coerceIn(1.5f, 5f)
                for (i in 0 until 5) {
                    val y = h * (0.18f + i * 0.15f) + sin(time * 0.55f + i) * h * 0.018f
                    glassPath.reset()
                    glassPath.moveTo(-w * 0.05f, y)
                    glassPath.cubicTo(w * 0.25f, y + sin(time + i) * 44f, w * 0.68f, y - cos(time + i) * 38f, w * 1.05f, y)
                    canvas.drawPath(glassPath, glassEdgePaint)
                }
            }

            val grainAlpha = (userGrainOpacity * 20f).toInt().coerceIn(0, 28)
            if (grainAlpha > 0) {
                glassFillPaint.color = Color.WHITE
                glassFillPaint.alpha = grainAlpha
                for (i in 0 until 36) {
                    val x = ((i * 97f + time * 17f) % w)
                    val y = ((i * 181f + time * 11f) % h)
                    val r = 0.45f + (i % 4) * 0.18f
                    canvas.drawCircle(x, y, r, glassFillPaint)
                }
            }
        }

        private fun drawDustParticles(canvas: Canvas, w: Float, h: Float) {
            if (!isDustEnabled || userDustOpacity <= 5) return

            val trebleBoost = min(trebleEnergy * 5f, 10f)
            var pAlpha = (partAlpha[0].toInt() + (trebleEnergy * 100).toInt()).coerceAtMost(255)
            pAlpha = (pAlpha * (userDustOpacity / 255f)).toInt()

            particlePaint.alpha = pAlpha
            val pSize = (0.07f + (trebleEnergy * 0.2f)) * userDustSize
            particlePaint.strokeWidth = pSize * (w / 100f)

            var pointCount = 0
            val dustCount = userDustCount.coerceAtMost(MAX_PARTICLES)
            for (i in 0 until dustCount) {
                partY[i] -= (partSpeed[i] + trebleBoost)
                if (partY[i] < 0) {
                    partY[i] = h
                    partX[i] = (Math.random() * w).toFloat()
                }
                particlePoints[pointCount * 2] = partX[i]
                particlePoints[pointCount * 2 + 1] = partY[i]
                pointCount++
            }
            canvas.drawPoints(particlePoints, 0, pointCount * 2, particlePaint)
        }

        /**
         * Field-wide film grain. The original app dithered its blurred field to hide banding; this
         * is the same idea done cheaply - a small set of sub-pixel white dots drifting over the
         * frame, scaled by the "grain" setting. Driven by [userGrainOpacity].
         */
        private fun drawOgGrain(canvas: Canvas, w: Float, h: Float) {
            val grainAlpha = (userGrainOpacity * 20f).toInt().coerceIn(0, 28)
            if (grainAlpha <= 0) return
            glassFillPaint.shader = null
            glassFillPaint.xfermode = null
            glassFillPaint.color = Color.WHITE
            glassFillPaint.alpha = grainAlpha
            for (i in 0 until 36) {
                val x = ((i * 97f + time * 17f) % w)
                val y = ((i * 181f + time * 11f) % h)
                val r = 0.45f + (i % 4) * 0.18f
                canvas.drawCircle(x, y, r, glassFillPaint)
            }
        }

        private fun drawVignetteLayer(canvas: Canvas) {
            if (userVignette <= 0) return
            val beatDarkness = bassEnergy * 100f
            val finalVigAlpha = (userVignette + beatDarkness).coerceIn(0f, 255f).toInt()
            vignettePaint.alpha = finalVigAlpha
            vignetteBitmap?.let {
                canvas.drawBitmap(it, 0f, 0f, vignettePaint)
            }
        }

        private fun drawFluidEffect(canvas: Canvas, w: Float, h: Float) {
            if (!isRainEnabled || userRainCount <= 0 || fluidEffectName == "None") return

            rainPaint.style = Paint.Style.FILL
            rainPaint.strokeCap = Paint.Cap.ROUND
            val count = userRainCount.coerceAtMost(MAX_RAIN)
            for (i in 0 until count) {
                val size = rainSize[i] * userRainSizeMult
                val rawAlpha = (rainAlpha[i] * (userRainOpacity / 255f)).toInt().coerceIn(0, 255)
                val albumColor = targetColors.getOrElse(i % 5) { rainColor[i] }
                rainPaint.color = if (isGlassRenderMode()) albumGlassColor(albumColor, i) else rainColor[i]

                when (fluidEffectName) {
                    "Bubbles" -> {
                        rainY[i] -= rainSpeed[i] * userRainSpeedMult * 0.42f
                        rainX[i] += sin(time * 0.9f + i) * 0.9f
                        if (rainY[i] < -size * 3f) {
                            resetRainDrop(i, false)
                            rainY[i] = h + (Math.random() * h * 0.25f).toFloat()
                            rainX[i] = (Math.random() * w).toFloat()
                        }
                        rainPaint.style = Paint.Style.STROKE
                        rainPaint.strokeWidth = (size * 0.42f).coerceAtLeast(1.4f)
                        rainPaint.alpha = (rawAlpha * 0.56f).toInt()
                        canvas.drawCircle(rainX[i] % w, rainY[i], size * 1.9f, rainPaint)
                        rainPaint.style = Paint.Style.FILL
                    }
                    "Liquid Trails" -> {
                        rainY[i] += rainSpeed[i] * userRainSpeedMult * 0.72f
                        rainX[i] += sin(time * 0.4f + i) * 1.4f
                        if (rainY[i] > h + size * 10f) {
                            resetRainDrop(i, false)
                            rainX[i] = (Math.random() * w).toFloat()
                        }
                        rainPaint.style = Paint.Style.STROKE
                        rainPaint.strokeWidth = (size * 0.65f).coerceAtLeast(1.2f)
                        rainPaint.alpha = (rawAlpha * 0.62f).toInt()
                        val x = rainX[i] % w
                        val y = rainY[i]
                        canvas.drawLine(x, y - size * 7f, x + sin(time + i) * size * 2f, y + size * 7f, rainPaint)
                        rainPaint.style = Paint.Style.FILL
                    }
                    "Caustic Sparks" -> {
                        rainY[i] += rainSpeed[i] * userRainSpeedMult * 0.36f
                        rainX[i] += cos(time * 0.7f + i) * 1.2f
                        if (rainY[i] > h) {
                            resetRainDrop(i, false)
                            rainX[i] = (Math.random() * w).toFloat()
                        }
                        rainPaint.style = Paint.Style.STROKE
                        rainPaint.strokeWidth = (size * 0.28f).coerceAtLeast(1f)
                        rainPaint.alpha = (rawAlpha * 0.72f).toInt()
                        val x = rainX[i] % w
                        val y = rainY[i]
                        destRect.set(x - size * 2f, y - size, x + size * 2f, y + size)
                        canvas.drawArc(destRect, (time * 40f + i * 19f) % 360f, 80f, false, rainPaint)
                        rainPaint.style = Paint.Style.FILL
                    }
                    "Mist" -> {
                        rainY[i] -= rainSpeed[i] * userRainSpeedMult * 0.18f
                        rainX[i] += sin(time * 0.28f + i * 0.4f) * 0.65f
                        if (rainY[i] < -size * 6f) {
                            resetRainDrop(i, false)
                            rainY[i] = h + (Math.random() * h * 0.35f).toFloat()
                            rainX[i] = (Math.random() * w).toFloat()
                        }
                        rainPaint.alpha = (rawAlpha * 0.26f).toInt()
                        canvas.drawCircle(rainX[i] % w, rainY[i], size * 3.4f, rainPaint)
                    }
                    else -> {
                        rainY[i] += rainSpeed[i] * userRainSpeedMult
                        if (rainY[i] > h) {
                            resetRainDrop(i, false)
                            rainX[i] = (Math.random() * w).toFloat()
                        }
                        val rcx = rainX[i] % w
                        val rcy = rainY[i]
                        rainPaint.alpha = rawAlpha
                        canvas.drawCircle(rcx, rcy, size, rainPaint)
                        rainPaint.alpha = (rainPaint.alpha * 0.5f).toInt()
                        canvas.drawCircle(rcx - size * 0.3f, rcy - size * 0.3f, size * 0.25f, rainPaint)
                    }
                }
            }
            rainPaint.style = Paint.Style.FILL
        }

        private fun drawAtmosphere(canvas: Canvas, w: Float, h: Float) {
            drawDustParticles(canvas, w, h)
            drawFluidEffect(canvas, w, h)
            drawVignetteLayer(canvas)
        }

        private fun drawClassicBlobs(canvas: Canvas, w: Float, h: Float, visualProgress: Float) {
            updateBlobPositions(w, h, false)
            val radius = hypot(w.toDouble(), h.toDouble()).toFloat() * 0.5f * userSizeMult

            for (i in 0 until activeBlobCount) {
                val blobX = positions[i * 2]
                val blobY = positions[i * 2 + 1]

                val rawOld = oldColors.getOrElse(i) { Color.GRAY }
                val rawNew = targetColors.getOrElse(i) { Color.GRAY }

                val blendedBlob = lerpColor(applyTuning(rawOld), applyTuning(rawNew), visualProgress)
                if (drawingColors.size > i) drawingColors[i] = blendedBlob

                paints[i].colorFilter = PorterDuffColorFilter(blendedBlob, PorterDuff.Mode.SRC_IN)
                paints[i].alpha = userBlobAlpha
                paints[i].xfermode = null // simple SRC_OVER blending

                destRect.set(blobX - radius, blobY - radius, blobX + radius, blobY + radius)
                canvas.drawBitmap(baseBlob, null, destRect, paints[i])
            }
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
                if (canvas == null) return

                if (isDebug) {
                    frames++
                    val nowNs = System.nanoTime()
                    val elapsedMs = (nowNs - debugLastTimeNs) / 1_000_000L
                    if (elapsedMs >= 1000) {
                        fps = (frames * 1000 / elapsedMs).toInt()
                        frames = 0
                        debugLastTimeNs = nowNs
                    }
                }

                // --- COLOR TRANSITION ---
                val newColorsFromListener = MusicListenerService.currentColors
                if (newColorsFromListener != targetColors) {
                    oldColors = drawingColors.toList() // Snapshot exactly what we see
                    targetColors = newColorsFromListener
                    fadeProgress = 0.0f
                }
                if (fadeProgress < 1.0f) {
                    fadeProgress += userFadeSpeed
                    if (fadeProgress > 1.0f) fadeProgress = 1.0f
                }

                // Quintic ease-out
                val invT = 1.0f - fadeProgress
                val visualProgress = 1.0f - (invT * invT * invT * invT * invT)

                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                val cx = w / 2f
                val cy = h / 2f

                // PREPARE BG COLORS
                var rawOldBg = oldColors.getOrElse(0) { Color.GRAY }
                var rawNewBg = targetColors.getOrElse(0) { Color.GRAY }

                if (isLightMode) {
                    rawOldBg = Color.WHITE; rawNewBg = Color.WHITE
                } else if (isOledMode) {
                    rawOldBg = Color.BLACK; rawNewBg = Color.BLACK
                }

                val blendedBg = if (isGlassRenderMode() && !isLightMode && !isOledMode) {
                    albumGlassBackground(lerpColor(rawOldBg, rawNewBg, visualProgress), 0.32f)
                } else {
                    lerpColor(applyTuning(rawOldBg), applyTuning(rawNewBg), visualProgress)
                }
                canvas.drawColor(blendedBg)

                // Text/particle colors depending on light/oled
                if (isLightMode) {
                    titlePaint.color = Color.BLACK
                    artistPaint.color = Color.DKGRAY
                    particlePaint.color = Color.BLACK
                } else if (isOledMode) {
                    titlePaint.color = Color.WHITE
                    artistPaint.color = Color.LTGRAY
                    particlePaint.color = Color.WHITE
                } else {
                    titlePaint.color = Color.WHITE
                    artistPaint.color = Color.LTGRAY
                    particlePaint.color = Color.WHITE
                }

                // This build ships exactly one renderer: the faithful OG Diffuse pipeline (blurred
                // album-art field domain-warped by animated fbm noise). Everything below is an
                // optional overlay on top of that field, in draw order.
                drawOgDiffuseReal(canvas, w, h)

                run {
                    // --- CENTER ART ---
                    if (isCenterArtEnabled) {
                        val currentArt = MusicListenerService.currentAlbumArt
                        if (currentArt != null && (currentArt != lastProcessedArt || centerArtBitmap == null)) {
                            lastProcessedArt = currentArt
                            try {
                                if (centerArtBlur < 0.05f) {
                                    centerArtBitmap = Bitmap.createScaledBitmap(currentArt, 1200, 1200, true)
                                } else {
                                    val blurFactor = 1f - (centerArtBlur * 0.95f)
                                    val targetW = (currentArt.width * blurFactor).toInt().coerceAtLeast(10)
                                    val targetH = (currentArt.height * blurFactor).toInt().coerceAtLeast(10)
                                    val tiny = Bitmap.createScaledBitmap(currentArt, targetW, targetH, true)
                                    centerArtBitmap = Bitmap.createScaledBitmap(tiny, 1000, 1000, true)
                                    if (tiny != centerArtBitmap && !tiny.isRecycled) tiny.recycle()
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        if (centerArtBitmap != null) {
                            val drawSize = 800f * centerArtScale
                            val halfSize = drawSize / 2f
                            centerArtPaint.alpha = centerArtOpacity
                            destRect.set(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize)
                            canvas.drawBitmap(centerArtBitmap!!, null, destRect, centerArtPaint)
                        }
                    }

                    // Film grain + vignette are the only field-wide finishes this build keeps: they
                    // are the two the original app's look actually depends on. The blob layer, dust
                    // particles and rain overlays belonged to the retired Liquid/Fluid renderers and
                    // are gone along with their settings.
                    drawOgGrain(canvas, w, h)
                    drawVignetteLayer(canvas)

                    if (isWidgetEnabled) {
                        val widgetX = (w * userPosX); val widgetY = (h * userPosY)
                        val targetArt = MusicListenerService.currentAlbumArt
                        if (targetArt != activeArt) {
                            oldArt = activeArt
                            activeArt = targetArt
                            artFadeProgress = 0f
                        }
                        if (artFadeProgress < 1.0f) {
                            artFadeProgress += 0.02f
                            if (artFadeProgress > 1.0f) artFadeProgress = 1.0f
                        } else {
                            oldArt = null
                        }

                        val artSize = 300f * userWidgetScale
                        val artLeft = widgetX - (artSize/2); val artTop = widgetY - (artSize/2)

                        canvas.save()
                        val textBoxHeight = 150f * userWidgetScale
                        val padding = 20f
                        canvas.clipRect(artLeft - padding, artTop - padding, artLeft + artSize + padding, artTop + artSize + textBoxHeight + padding)

                        destRect.set(artLeft, artTop, artLeft+artSize, artTop+artSize)

                        if (oldArt != null && artFadeProgress < 1.0f) {
                            artPaint.alpha = 255
                            canvas.drawBitmap(oldArt!!, null, destRect, artPaint)
                        }
                        if (activeArt != null) {
                            val smoothAlpha = if (oldArt != null) artFadeProgress * artFadeProgress * (3 - 2 * artFadeProgress) else 1.0f
                            artFadePaint.alpha = (smoothAlpha * 255).toInt()
                            canvas.drawBitmap(activeArt!!, null, destRect, artFadePaint)
                        }

                        if (activeArt != null || oldArt != null) {
                            titlePaint.textSize = 50f * userWidgetScale
                            artistPaint.textSize = 35f * userWidgetScale
                            val title = MusicListenerService.currentTitle
                            val artist = MusicListenerService.currentArtist
                            val textY = artTop + artSize + (70f * userWidgetScale)
                            val maxTextW = w * 0.8f
                            drawMarquee(canvas, title, widgetX, textY, titlePaint, maxTextW)
                            drawMarquee(canvas, artist, widgetX, textY + (50f * userWidgetScale), artistPaint, maxTextW)
                        }
                        canvas.restore()
                    }
                } // end liquid vs classic branch

                if (isDebug) {
                    canvas.drawText("FPS: $fps", 100f, 200f, debugPaint)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }
}
