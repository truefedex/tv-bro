package com.phlox.tvwebbrowser.webengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageZoomTest {
    @Test
    fun stepsGoDownToTheSmallestAndUpTo100() {
        assertEquals(90, PageZoom.smaller(PageZoom.DEFAULT))
        assertEquals(67, PageZoom.smaller(80))
        assertNull(PageZoom.smaller(PageZoom.STEPS.first()))
        assertEquals(80, PageZoom.bigger(67))
        assertEquals(PageZoom.DEFAULT, PageZoom.bigger(90))
        assertNull(PageZoom.bigger(PageZoom.DEFAULT))
    }

    @Test
    fun aZoomBetweenTheStepsGoesToTheNextStep() {
        //a value stored by another version of the steps
        assertEquals(67, PageZoom.smaller(75))
        assertEquals(80, PageZoom.bigger(75))
    }
}
