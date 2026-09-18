package com.talqyn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** How far the default loader decodes a catalog photo down. */
class ImageLoaderTests {
    @Test
    fun theDecoderStepsDownInPowersOfTwoWithoutGoingUnderTheTarget() {
        assertEquals(1, sampleSize(720, 1280, 720))
        assertEquals(1, sampleSize(1439, 2000, 720))
        assertEquals(2, sampleSize(1440, 2000, 720))
        assertEquals("the shorter side decides", 4, sampleSize(4000, 3000, 720))
        assertEquals("a picture smaller than the target is left as it is", 1, sampleSize(300, 200, 720))
        assertEquals("no target, no step", 1, sampleSize(4000, 3000, 0))
    }
}
