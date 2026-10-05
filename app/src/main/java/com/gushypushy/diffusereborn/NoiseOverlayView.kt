package com.gushypushy.diffusereborn

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class NoiseOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    private var tile: Bitmap? = null

    init {
        // Static noise; no need to redraw constantly
        setWillNotDraw(false)
    }

    private fun buildNoiseTile() {
        tile?.recycle()
        val size = 180 // small tile, repeated
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        val rnd = Random(1337)
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            // mostly transparent specks (grain)
            val a = rnd.nextInt(0, 24) // controls intensity
            val v = rnd.nextInt(190, 255)
            pixels[i] = Color.argb(a, v, v, v)
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)

        tile = bmp
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.shader = shader
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) buildNoiseTile()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shader == null) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tile?.recycle()
        tile = null
    }
}