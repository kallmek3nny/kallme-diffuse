package com.gushypushy.diffusereborn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

class OrbitBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rect = RectF()
    private val borderPath = Path()
    private val segmentPath = Path()
    private val pathMeasure = PathMeasure()

    private var progress = 0f
    private var cornerRadiusPx = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4200L // slower (was 1400)
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun start() {
        if (!animator.isRunning) animator.start()
    }

    fun stop() {
        if (animator.isRunning) animator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    /**
     * Critical: prevents this view from measuring itself to "full screen" when parent is wrap_content.
     * FrameLayout will re-measure MATCH_PARENT children with EXACT size after it knows the final size.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)

        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        val mw = if (wMode == MeasureSpec.EXACTLY) wSize else 0
        val mh = if (hMode == MeasureSpec.EXACTLY) hSize else 0

        setMeasuredDimension(mw, mh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    private fun rebuildPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val stroke = dp(2.2f)
        paint.strokeWidth = stroke

        // must match your island bg radius (oneui_nowplaying_bar.xml uses 22dp)
        cornerRadiusPx = dp(22f)

        val inset = stroke * 0.5f + dp(1f)
        rect.set(inset, inset, w - inset, h - inset)

        borderPath.reset()
        borderPath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        pathMeasure.setPath(borderPath, true)
    }

    private fun pickAlbumColor(): Int {
        val palette = MusicListenerService.currentColors
        // choose a “nice” accent from palette; tweak index if you prefer
        val c = palette.getOrElse(1) { Color.parseColor("#A8C7FA") }
        // force opaque; we control transparency via paint.alpha
        return (c or 0xFF000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return
        val length = pathMeasure.length
        if (length <= 0f) return

        val accent = pickAlbumColor()

        // Optional: subtle base outline (album-colored)
        paint.shader = null
        paint.color = accent
        paint.alpha = 45
        paint.strokeWidth = dp(2.0f)
        canvas.drawPath(borderPath, paint)

        // Moving segment around the perimeter
        val segLen = length * 0.18f // size of the “scrobble” segment
        val start = (progress * length) % length
        val end = start + segLen

        segmentPath.reset()
        if (end <= length) {
            pathMeasure.getSegment(start, end, segmentPath, true)
        } else {
            // wrap around end of path
            pathMeasure.getSegment(start, length, segmentPath, true)
            pathMeasure.getSegment(0f, end - length, segmentPath, true)
        }

        // glow pass
        paint.color = accent
        paint.alpha = 110
        paint.strokeWidth = dp(5.0f)
        canvas.drawPath(segmentPath, paint)

        // sharp pass
        paint.alpha = 230
        paint.strokeWidth = dp(2.4f)
        canvas.drawPath(segmentPath, paint)
    }
}