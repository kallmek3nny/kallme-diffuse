package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * CPU reference for the AGSL safeFieldAxis mapping. Keeping the tests explicit
 * protects against reintroducing repeated mirror folds on extreme fluidity.
 */
class WarpUvSafetyTest {
    private fun safeAxis(value: Float): Float {
        val edge = 0.18f
        val edgeSq = edge * edge
        return when {
            value < edge -> edgeSq / (2f * edge - value)
            value > 1f - edge -> 1f - edgeSq / (value - (1f - 2f * edge))
            else -> value
        }
    }

    @Test fun neverWrapsAndAlwaysStaysInsideTexture() {
        val coords = listOf(-100f, -10f, -2f, -1f, -0.3f, 0f, 0.1f, 0.18f,
            0.5f, 0.82f, 0.9f, 1f, 1.3f, 2f, 10f, 100f)
        var last = -1f
        for (x in coords) {
            val value = safeAxis(x)
            assertTrue("coordinate must stay in texture: $x -> $value", value > 0f && value < 1f)
            assertTrue("no repeated mirrored folds", value > last)
            last = value
        }
    }

    @Test fun interiorRetainsExactArtworkCoordinates() {
        for (n in 18..82) {
            val x = n / 100f
            assertEquals(x, safeAxis(x), 0f)
        }
    }

    @Test fun joinsHaveNoVisibleKinks() {
        val eps = 0.0001f
        for (join in listOf(0.18f, 0.82f)) {
            val left = (safeAxis(join) - safeAxis(join - eps)) / eps
            val right = (safeAxis(join + eps) - safeAxis(join)) / eps
            assertTrue("left derivative near 1 at $join", abs(left - 1f) < 0.003f)
            assertTrue("right derivative near 1 at $join", abs(right - 1f) < 0.003f)
        }
    }
}
