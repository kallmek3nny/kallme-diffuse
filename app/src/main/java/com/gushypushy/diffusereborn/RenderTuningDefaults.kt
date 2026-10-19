package com.gushypushy.diffusereborn

/**
 * Defaults shared by the settings UI, its preview, and the live wallpaper engine.
 * The slider midpoint retains the previous warp strength for existing presets.
 */
internal object RenderTuningDefaults {
    const val SPEED = 60
    const val FLUIDITY = 70
    const val EDGE_GLOW = false

    fun fluidityMultiplier(sliderValue: Int): Float =
        0.45f + sliderValue.coerceIn(0, 100) / 100f * 1.1f
}
