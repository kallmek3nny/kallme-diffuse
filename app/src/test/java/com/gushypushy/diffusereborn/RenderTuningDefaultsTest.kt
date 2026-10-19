package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RenderTuningDefaultsTest {
    @Test fun fluiditySliderKeepsOldMidpointAndClampsBounds() {
        assertEquals(0.45f, RenderTuningDefaults.fluidityMultiplier(-20), 0.0001f)
        assertEquals(1.0f, RenderTuningDefaults.fluidityMultiplier(50), 0.0001f)
        assertEquals(1.22f, RenderTuningDefaults.fluidityMultiplier(RenderTuningDefaults.FLUIDITY), 0.0001f)
        assertEquals(1.55f, RenderTuningDefaults.fluidityMultiplier(150), 0.0001f)
    }

    @Test fun initialLookPrioritizesFlowOverBorders() {
        assertEquals(60, RenderTuningDefaults.SPEED)
        assertFalse(RenderTuningDefaults.EDGE_GLOW)
    }
}
