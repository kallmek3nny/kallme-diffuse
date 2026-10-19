package com.gushypushy.diffusereborn

/**
 * Defaults shared by settings, preview, and the wallpaper engine.
 * 0..70 preserves the exact previous look; the newly expanded 71..100 range
 * adds substantial liquid deformation for users who want a more dramatic flow.
 */
internal object RenderTuningDefaults {
    const val SPEED = 60
    const val FLUIDITY = 70
    const val EDGE_GLOW = false

    private fun highFlow(sliderValue: Int): Float =
        ((sliderValue.coerceIn(0, 100) - FLUIDITY).coerceAtLeast(0) / 30f)
            .let { it * it }

    fun fluidityMultiplier(sliderValue: Int): Float {
        val clamped = sliderValue.coerceIn(0, 100)
        val legacy = 0.45f + clamped / 100f * 1.1f
        return legacy + 2.05f * highFlow(clamped)
    }

    /** The lower 70% retains identical motion speed, including the stock default. */
    fun fluidityMotionMultiplier(sliderValue: Int): Float =
        1f + 0.80f * highFlow(sliderValue)

    /** Extra curled domain warp, only at settings above the old default. */
    fun extraCurl(sliderValue: Int): Float = highFlow(sliderValue)
}
