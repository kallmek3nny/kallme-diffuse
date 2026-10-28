package com.gushypushy.diffusereborn

/** Frame-rate-independent, interruption-safe smooth palette transitions. */
internal object SongTransition {
    fun ease(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        // Zero velocity at both ends: unlike quintic ease-OUT this does not
        // jump most of the way to the next song in the first few frames.
        return t * t * (3f - 2f * t)
    }

    fun blendArgb(from: Int, to: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        if (t <= 0f) return from
        if (t >= 1f) return to
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 255
            val b = (to ushr shift) and 255
            return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or
                (channel(16) shl 16) or
                (channel(8) shl 8) or channel(0)
    }
}
