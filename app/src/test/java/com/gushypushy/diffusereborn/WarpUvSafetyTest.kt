package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CPU reference for the branchless radial coordinate mapping in OG_DIFFUSE_AGSL.
 * Unlike the previous per-axis soft clamp, this has no knee or mirrored fold.
 */
class WarpUvSafetyTest {
    private fun safe(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - 0.5f
        val dy = y - 0.5f
        val radius = 0.48f
        val gain = radius / sqrt(radius * radius + dx * dx + dy * dy)
        return Pair(0.5f + dx * gain, 0.5f + dy * gain)
    }

    @Test fun extremeDistortionsRemainSafelyInsideArtwork() {
        for (x in listOf(-100f, -20f, -5f, -2f, -1f, 0f, 0.5f, 1f, 2f, 5f, 20f, 100f)) {
            for (y in listOf(-100f, -3f, -1f, 0f, 0.5f, 1f, 3f, 100f)) {
                val (u, v) = safe(x, y)
                assertTrue("u outside image at $x,$y: $u", u > 0f && u < 1f)
                assertTrue("v outside image at $x,$y: $v", v > 0f && v < 1f)
            }
        }
    }

    @Test fun noSeamsOrPlateausAcrossPreviousKnees() {
        // Restrict the other axis to center and densely sample both previous
        // soft-clamp join locations and the old mirrored-wrap boundaries.
        var previous = -1f
        for (step in -400..400) {
            val x = step / 100f
            val u = safe(x, 0.5f).first
            assertTrue("must remain strictly increasing across x=$x", u > previous)
            previous = u
        }
        val eps = 0.0001f
        for (knee in listOf(-1f, 0f, 0.18f, 0.5f, 0.82f, 1f, 2f)) {
            val left = (safe(knee, 0.5f).first - safe(knee - eps, 0.5f).first) / eps
            val right = (safe(knee + eps, 0.5f).first - safe(knee, 0.5f).first) / eps
            assertTrue("unexpected derivative kink at $knee: $left vs $right",
                abs(left - right) < 0.005f)
        }
    }

    @Test fun centralDetailAndVortexRotationRemainContinuous() {
        assertEquals(0.5f, safe(0.5f, 0.5f).first, 0f)
        assertEquals(0.5f, safe(0.5f, 0.5f).second, 0f)
        val a = safe(0.4f, 0.5f)
        val b = safe(0.6f, 0.5f)
        assertEquals(1f, a.first + b.first, 0.00001f)
        assertEquals(a.second, b.second, 0.00001f)
        // The derivative at the center is one, preserving local fluid detail.
        val eps = 0.0001f
        val centerDerivative = (safe(0.5f + eps, 0.5f).first -
                               safe(0.5f - eps, 0.5f).first) / (2 * eps)
        assertEquals(1f, centerDerivative, 0.002f)
    }
}
