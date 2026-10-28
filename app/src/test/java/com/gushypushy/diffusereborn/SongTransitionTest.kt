package com.gushypushy.diffusereborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTransitionTest {
    @Test fun easingStartsAndEndsAtRest() {
        assertEquals(0f, SongTransition.ease(-1f), 0f)
        assertEquals(0f, SongTransition.ease(0f), 0f)
        assertEquals(0.5f, SongTransition.ease(0.5f), 0.00001f)
        assertEquals(1f, SongTransition.ease(1f), 0f)
        assertEquals(1f, SongTransition.ease(5f), 0f)
        assertTrue(SongTransition.ease(0.15f) < 0.10f)
        assertTrue(SongTransition.ease(0.85f) > 0.90f)
    }

    @Test fun midFadeNewSongStartsAtDisplayedColor() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        val displayed = SongTransition.blendArgb(red, blue, SongTransition.ease(0.35f))
        assertEquals(displayed, SongTransition.blendArgb(displayed, green, 0f))
        assertEquals(green, SongTransition.blendArgb(displayed, green, 1f))
    }

    @Test fun channelBlendPreservesOpaqueAlphaAndEndpoints() {
        val start = 0xFF204080.toInt()
        val end = 0xFFE0C010.toInt()
        assertEquals(start, SongTransition.blendArgb(start, end, 0f))
        assertEquals(end, SongTransition.blendArgb(start, end, 1f))
        val midway = SongTransition.blendArgb(start, end, 0.5f)
        assertEquals(0xFF808048.toInt(), midway)
    }
}
