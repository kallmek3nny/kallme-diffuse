package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderFramePolicyTest {
    @Test fun musicRunsSmoothWithoutAlwaysRenderingAt120() {
        assertEquals(60f, fps(playing = true), 0.01f)
        assertEquals(120f, fps(playing = true, highMotion = true), 0.01f)
        assertEquals(60f, fps(transitioning = true), 0.01f)
    }

    @Test fun idleAndAmbientRespectBattery() {
        assertEquals(24f, fps(), 0.01f)
        assertEquals(30f, fps(hasArtwork = true), 0.01f)
        assertEquals(30f, fps(screenOff = true, highMotion = true), 0.01f)
        assertEquals(20f, fps(cap = 20f, playing = true), 0.01f)
    }

    @Test fun userCanKeepFullRefresh() {
        assertEquals(120f, fps(adaptive = false), 0.01f)
        assertEquals(30f, fps(adaptive = false, screenOff = true), 0.01f)
        assertEquals(30f, fps(cap = 30f, highMotion = true), 0.01f)
    }

    private fun fps(
        cap: Float = 120f,
        adaptive: Boolean = true,
        screenOff: Boolean = false,
        playing: Boolean = false,
        hasArtwork: Boolean = false,
        transitioning: Boolean = false,
        highMotion: Boolean = false
    ): Float = RenderFramePolicy.targetFps(
        cap, adaptive, screenOff, playing, hasArtwork, transitioning, highMotion
    )
}
