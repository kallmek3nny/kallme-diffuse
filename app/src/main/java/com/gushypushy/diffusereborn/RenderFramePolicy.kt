package com.gushypushy.diffusereborn

/**
 * Selects render cadence without changing shader, artwork, color, or motion math.
 * The user can disable adaptive FPS to keep the full device refresh cap.
 */
internal object RenderFramePolicy {
    fun targetFps(
        cap: Float,
        adaptive: Boolean,
        screenOff: Boolean,
        playing: Boolean,
        hasArtwork: Boolean,
        transitioning: Boolean,
        highMotion: Boolean
    ): Float {
        if (screenOff) return minOf(cap, 30f)
        if (!adaptive) return cap
        val goal = when {
            highMotion -> 120f
            playing || transitioning -> 60f
            hasArtwork -> 30f
            else -> 24f
        }
        return minOf(cap, goal).coerceAtLeast(15f)
    }
}
