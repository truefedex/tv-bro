package com.phlox.tvwebbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeMatcherTest {
    //the 4K modes of a Shield on a 4K TV, and a 1080p one
    private val modes = listOf(
        DisplayMode(1, 3840, 2160, 29.97003f), DisplayMode(3, 3840, 2160, 59.94006f), DisplayMode(4, 3840, 2160, 23.976025f),
        DisplayMode(6, 3840, 2160, 30.000002f), DisplayMode(11, 3840, 2160, 60.000004f), DisplayMode(12, 3840, 2160, 50f),
        DisplayMode(14, 3840, 2160, 24.000002f), DisplayMode(7, 1920, 1080, 24.000002f))
    private val current = modes[1]

    private fun choose(fps: Float) = DisplayModeMatcher.choose(current, modes, fps)?.id

    @Test
    fun filmsGetTheirOwnRefreshRate() {
        assertEquals(4, choose(23.976f))
        assertEquals(14, choose(24f))
    }

    @Test
    fun videosOf25And30FpsGetTheDoubleRate() {
        assertEquals(12, choose(25f))
        assertEquals(11, choose(30f))
        assertEquals(12, choose(50f))
        assertEquals(11, choose(60f))
    }

    @Test
    fun aFittingModeStays() {
        assertNull(choose(59.94f))
        assertNull(choose(29.97f))
        assertNull(DisplayModeMatcher.choose(modes[2], modes, 23.976f))
    }

    @Test
    fun unknownOrNoFittingRateStays() {
        assertNull(choose(0f))
        assertNull(choose(-1f))
        assertNull(choose(Float.NaN))
        assertNull(choose(15.5f))
    }

    @Test
    fun theSizeStays() {
        //1080p at 24 Hz is not taken for a 4K screen
        val only1080p24 = listOf(current, DisplayMode(7, 1920, 1080, 24.000002f))
        assertNull(DisplayModeMatcher.choose(current, only1080p24, 24f))
    }

    @Test
    fun theToleranceTellsNtscRatesApart() {
        assertTrue(DisplayModeMatcher.fits(23.976025f, 23.976f))
        assertFalse(DisplayModeMatcher.fits(24.000002f, 23.976f))
        assertTrue(DisplayModeMatcher.fits(59.94006f, 29.97f))
        assertFalse(DisplayModeMatcher.fits(60.000004f, 59.94f))
    }
}
