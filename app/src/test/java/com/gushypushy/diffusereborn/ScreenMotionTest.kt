package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMotionTest {
    @Test fun fadeIntoSleepIsBoundedAndMonotonic() {
        assertEquals(48, ScreenMotion.AOD_DIM_ALPHA)
        assertEquals(0, ScreenMotion.sleepDimAlpha(-30L))
        assertEquals(48, ScreenMotion.sleepDimAlpha(880L))
        assertTrue(ScreenMotion.sleepDimAlpha(250L) < ScreenMotion.sleepDimAlpha(480L))
        assertTrue(ScreenMotion.sleepDimAlpha(480L) < ScreenMotion.sleepDimAlpha(720L))
    }

    @Test fun wakingBrieflyAcceleratesThenReturnsToNormal() {
        assertEquals(1f, ScreenMotion.wakeMotionMultiplier(-1L), 0.0001f)
        assertEquals(1.7f, ScreenMotion.wakeMotionMultiplier(0L), 0.0001f)
        assertTrue(ScreenMotion.wakeMotionMultiplier(550L) in 1f..1.7f)
        assertEquals(1f, ScreenMotion.wakeMotionMultiplier(1100L), 0.0001f)
        assertEquals(1f, ScreenMotion.wakeMotionMultiplier(10000L), 0.0001f)
    }

    @Test fun wakingQuicklyRestoresWallpaperBrightness() {
        assertEquals(48, ScreenMotion.wakeDimAlpha(0L))
        assertTrue(ScreenMotion.wakeDimAlpha(140L) > ScreenMotion.wakeDimAlpha(280L))
        assertEquals(0, ScreenMotion.wakeDimAlpha(360L))
        assertEquals(0, ScreenMotion.wakeDimAlpha(-1L))
    }
}
