package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RenderTuningDefaultsTest {
    @Test fun fluiditySliderKeepsOldMidpointAndClampsBounds() {
        assertEquals(0.45f, RenderTuningDefaults.fluidityMultiplier(-20), 0.0001f)
        assertEquals(1.0f, RenderTuningDefaults.fluidityMultiplier(50), 0.0001f)
        assertEquals(1.22f, RenderTuningDefaults.fluidityMultiplier(RenderTuningDefaults.FLUIDITY), 0.0001f)
        assertEquals(7.00f, RenderTuningDefaults.fluidityMultiplier(150), 0.0001f)
        assertEquals(7.00f, RenderTuningDefaults.fluidityMultiplier(100), 0.0001f)
        assertEquals(2.7475f, RenderTuningDefaults.fluidityMultiplier(85), 0.0001f)
        assertEquals(1.22f, RenderTuningDefaults.fluidityMultiplier(70), 0.0001f)
    }

    @Test fun extendedRangeAddsMotionOnlyAbovePreviousDefault() {
        assertEquals(1f, RenderTuningDefaults.fluidityMotionMultiplier(0), 0.0001f)
        assertEquals(1f, RenderTuningDefaults.fluidityMotionMultiplier(70), 0.0001f)
        assertEquals(1.4f, RenderTuningDefaults.fluidityMotionMultiplier(85), 0.0001f)
        assertEquals(2.6f, RenderTuningDefaults.fluidityMotionMultiplier(100), 0.0001f)
        assertEquals(0f, RenderTuningDefaults.extraCurl(70), 0.0001f)
        assertEquals(0.625f, RenderTuningDefaults.extraCurl(85), 0.0001f)
        assertEquals(2.5f, RenderTuningDefaults.extraCurl(100), 0.0001f)
        assertEquals(0f, RenderTuningDefaults.vortexStrength(70), 0.0001f)
        assertEquals(0.25f, RenderTuningDefaults.vortexStrength(85), 0.0001f)
        assertEquals(1f, RenderTuningDefaults.vortexStrength(100), 0.0001f)
    }

    @Test fun initialLookPrioritizesFlowOverBorders() {
        assertEquals(60, RenderTuningDefaults.SPEED)
        assertFalse(RenderTuningDefaults.EDGE_GLOW)
    }
}
