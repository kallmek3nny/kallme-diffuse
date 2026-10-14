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
    '''            float random(float2 st) {
                return fract(sin(dot(st, float2(12.9898, 78.233))) * 43758.5453);
            }
''',
    '''            float random(float2 st) {
                // Arithmetic hash avoids transcendental sin() calls in every lattice sample.
                float3 p3 = fract(float3(st.x, st.y, st.x) * 0.1031);
                p3 += dot(p3, p3.yzx + float3(33.33, 33.33, 33.33));
                return fract((p3.x + p3.y) * p3.z);
            }
''',
    "AGSL random hash",
)

text = replace_once(
    text,
    "        private val ogFieldPx = 512            // resolution of the soft color field\n",
    "        private val ogFieldPx = 384            // blurred field; lower build/sampling cost, same soft look\n",
    "OG field resolution",
)

text = replace_once(
    text,
    "            var cap = if (isPowerSaver) 30f else 60f\n",
    "            var cap = if (isPowerSaver) 30f else 120f\n",
    "active FPS cap",
)
text = replace_once(
    text,
    "                MusicListenerService.currentAlbumArt != null -> min(cap, 30f)\n                else -> min(cap, 15f)\n",
    "                MusicListenerService.currentAlbumArt != null -> min(cap, 60f)\n                else -> min(cap, 30f)\n",
    "adaptive calm FPS",
)

text = replace_once(
    text,
    '''            if (lastDrawTimeNs == 0L || frameTimeNanos - lastDrawTimeNs >= frameIntervalNs) {
                draw()
                lastDrawTimeNs = frameTimeNanos
            }

            // Battery optimization: sleep until the next target frame instead of waking on every display vsync.
            if (isVisible) {
                val remainingNs = if (lastDrawTimeNs == 0L) 0L else frameIntervalNs - (System.nanoTime() - lastDrawTimeNs)
                if (remainingNs >= 1_000_000L) {
                    choreographer.postFrameCallbackDelayed(this, (remainingNs / 1_000_000L).coerceAtLeast(1L))
                } else {
                    choreographer.postFrameCallback(this)
                }
            }
''',
    '''            // Tiny tolerance avoids nanosecond jitter turning 120 Hz into every-other-vsync.
            val dueThresholdNs = (frameIntervalNs - 250_000L).coerceAtLeast(0L)
            if (lastDrawTimeNs == 0L || frameTimeNanos - lastDrawTimeNs >= dueThresholdNs) {
                draw()
                lastDrawTimeNs = frameTimeNanos
            }

            if (isVisible) {
                if (TARGET_FPS >= 90f) {
                    // At 120 Hz wake on every vsync. Delayed callbacks are kept for 30/60 FPS modes.
                    choreographer.postFrameCallback(this)
                } else {
                    val remainingNs = if (lastDrawTimeNs == 0L) 0L else frameIntervalNs - (System.nanoTime() - lastDrawTimeNs)
                    if (remainingNs >= 1_000_000L) {
                        choreographer.postFrameCallbackDelayed(this, (remainingNs / 1_000_000L).coerceAtLeast(1L))
                    } else {
                        choreographer.postFrameCallback(this)
                    }
                }
            }
''',
    "120hz frame scheduling",
)

text = replace_once(text, "                createBaseBlob()\n", "", "legacy base blob startup")

text = sub_once(
    text,
    r'''\n                // Init Particles\n                for\(i in 0 until MAX_PARTICLES\) \{.*?\n                // Init Rain\n                for\(i in 0 until MAX_RAIN\) \{\n                    resetRainDrop\(i, true\)\n                \}\n''',
    "\n",
    "retired particle/rain initialization",
)

text = sub_once(
    text,
    r'''            try \{\n                vignetteBitmap = Bitmap\.createBitmap\(width, height, Bitmap\.Config\.ARGB_8888\).*?            initMesh\(width, height\)\n''',
    '''            // OG-only renderer: avoid two extra full-screen software bitmaps.
            vignetteBitmap?.recycle()
            vignetteBitmap = null
            meshBitmap?.recycle()
            meshBitmap = null
            meshCanvas = null
''',
    "surface legacy bitmap allocations",
)

text = sub_once(
    text,
    r'''        private fun drawVignetteLayer\(canvas: Canvas\) \{.*?\n        \}\n\n        private fun drawFluidEffect''',
    '''        private fun drawVignetteLayer(canvas: Canvas) {
            if (userVignette <= 0) return
            val beatDarkness = bassEnergy * 100f
            val finalVigAlpha = (userVignette + beatDarkness).coerceIn(0f, 255f).toInt()
            vignetteDrawPaint.alpha = finalVigAlpha
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), vignetteDrawPaint)
        }

        private fun drawFluidEffect''',
    "direct vignette rendering",
)

wallpaper_path.write_text(text)

music_path = Path("app/src/main/java/com/gushypushy/diffusereborn/MusicListenerService.kt")
text = music_path.read_text()
text = replace_once(
    text,
    '    private var lastNotifyKey: String = ""\n',
    '    private var lastNotifyKey: String = ""\n    @Volatile private var lastArtworkKey: String = ""\n    @Volatile private var pendingArtworkKey: String = ""\n',
    "artwork dedupe state",
)

new_process = r'''    private fun artworkIdentity(original: Bitmap): String {
        return "${currentPackageName}|${currentTitle}|${currentArtist}|${currentAlbum}|${original.width}x${original.height}"
    }

    private fun processBitmapFast(original: Bitmap) {
        val artworkKey = artworkIdentity(original)
        synchronized(this) {
            val cached = currentAlbumArt
            if ((artworkKey == lastArtworkKey && cached != null && !cached.isRecycled) ||
                artworkKey == pendingArtworkKey) {
                return
            }
            pendingArtworkKey = artworkKey
        }

        bitmapExecutor.execute {
            var temporarySource: Bitmap? = null
            var generatedArt: Bitmap? = null
            var colorBitmap: Bitmap? = null
            var published = false
            try {
                val source = if (original.config == Bitmap.Config.HARDWARE) {
                    original.copy(Bitmap.Config.ARGB_8888, false).also { temporarySource = it }
                } else {
                    original
                }

                val artBitmap = Bitmap.createScaledBitmap(source, 1200, 1200, false)
                generatedArt = artBitmap

                // The expensive pixel comparison now happens at most once per track/art identity.
                val existing = currentAlbumArt
                if (existing != null && !existing.isRecycled && existing.sameAs(artBitmap)) {
                    lastArtworkKey = artworkKey
                    return@execute
                }

                currentAlbumArt = artBitmap
                published = true
                lastArtworkKey = artworkKey
                notifyMetadataChangedIfNeeded()

                colorBitmap = Bitmap.createScaledBitmap(artBitmap, 100, 100, false)
                // Already on a serial worker, so synchronous Palette generation avoids overlapping jobs.
                val palette = Palette.from(colorBitmap)
                    .maximumColorCount(24)
                    .generate()

                val swatches = palette.swatches
                val bestSwatches = swatches.filter { swatch ->
                    val hsl = swatch.hsl
                    hsl[1] > 0.25f && hsl[2] > 0.15f && hsl[2] < 0.85f
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
                if (currentColors != distinctColors) {
                    currentColors = distinctColors
                    notifyMetadataChangedIfNeeded()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                colorBitmap?.let { if (!it.isRecycled) it.recycle() }
                if (!published) {
                    generatedArt?.let { if (it !== original && !it.isRecycled) it.recycle() }
                }
                temporarySource?.let {
                    if (it !== generatedArt && it !== original && !it.isRecycled) it.recycle()
                }
                synchronized(this) {
                    if (pendingArtworkKey == artworkKey) pendingArtworkKey = ""
                }
            }
        }
    }'''

text = sub_once(
    text,
    r'''    private fun processBitmapFast\(original: Bitmap\) \{.*?\n    \}\n\n    private fun manipulateColor''',
    new_process + "\n\n    private fun manipulateColor",
    "artwork processing dedupe",
)
music_path.write_text(text)

preview_path = Path("app/src/main/java/DiffusePreviewView.kt")
text = preview_path.read_text()
text = replace_once(
    text,
    "    private var lastFrameTimeNs = 0L\n    private val slowMotionFactor = 0.25f\n",
    "    private var lastFrameTimeNs = 0L\n    private var lastPreviewDrawTimeNs = 0L\n    private val previewFrameIntervalNs = 1_000_000_000L / 30L\n    private val slowMotionFactor = 0.25f\n",
    "preview frame state",
)
text = replace_once(
    text,
    "        lastFrameTimeNs = 0L\n        choreographer.postFrameCallback(this)\n",
    "        lastFrameTimeNs = 0L\n        lastPreviewDrawTimeNs = 0L\n        choreographer.postFrameCallback(this)\n",
    "preview resume state",
)
text = replace_once(
    text,
    '''        invalidate()
        choreographer.postFrameCallback(this)
''',
    '''        val dueNs = (previewFrameIntervalNs - 500_000L).coerceAtLeast(0L)
        if (lastPreviewDrawTimeNs == 0L || frameTimeNanos - lastPreviewDrawTimeNs >= dueNs) {
            invalidate()
            lastPreviewDrawTimeNs = frameTimeNanos
        }
        val remainingNs = if (lastPreviewDrawTimeNs == 0L) 0L else
            previewFrameIntervalNs - (System.nanoTime() - lastPreviewDrawTimeNs)
        if (remainingNs >= 1_000_000L) {
            choreographer.postFrameCallbackDelayed(this, (remainingNs / 1_000_000L).coerceAtLeast(1L))
        } else {
            choreographer.postFrameCallback(this)
        }
''',
    "preview 30fps scheduling",
)
preview_path.write_text(text)
