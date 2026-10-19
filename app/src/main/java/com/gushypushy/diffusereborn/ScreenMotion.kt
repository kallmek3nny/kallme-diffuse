package com.gushypushy.diffusereborn

/** Monotonic-time easing without timers or long-running AOD callbacks. */
internal object ScreenMotion {
    const val SLEEP_DURATION_MS = 880L
    const val WAKE_DURATION_MS = 1100L
    private const val WAKE_FADE_MS = 360L

    private fun ease(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    fun sleepDimAlpha(ageMs: Long): Int =
        (225f * ease(ageMs.coerceAtLeast(0L).toFloat() / SLEEP_DURATION_MS)).toInt()
            .coerceIn(0, 225)

    fun wakeDimAlpha(ageMs: Long): Int {
        if (ageMs < 0L) return 0
        return (225f * (1f - ease(ageMs.toFloat() / WAKE_FADE_MS))).toInt()
            .coerceIn(0, 225)
    }

    fun wakeMotionMultiplier(ageMs: Long): Float {
        if (ageMs < 0L || ageMs >= WAKE_DURATION_MS) return 1f
        return 1f + 0.7f * (1f - ease(ageMs.toFloat() / WAKE_DURATION_MS))
    }
}
