package com.phlox.tvwebbrowser.widgets.cursor

import com.phlox.tvwebbrowser.AppContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every change of the grab mode is reported to the cursor callback.
 */
@RunWith(RobolectricTestRunner::class)
class CursorGrabModeTest {
    private val reported = ArrayList<Boolean>()
    private val delegate = CursorLayout(AppContext.get()).cursorDrawerDelegate.also {
        it.callback = object : CursorDrawerDelegate.Callback {
            override fun onLongPress(x: Int, y: Int) {}
            override fun onGrabModeChanged(grabMode: Boolean) {
                reported += grabMode
            }
        }
    }

    @Test
    fun enteringAndLeavingTheGrabModeIsReported() {
        delegate.goToGrabMode()
        assertTrue(delegate.canHandleBackNavigation())
        delegate.handleBackNavigation()
        assertFalse(delegate.canHandleBackNavigation())
        assertEquals(listOf(true, false), reported)
    }

    @Test
    fun hidingTheCursorLeavesTheGrabMode() {
        delegate.goToGrabMode()
        delegate.hideCursor()
        assertEquals(listOf(true, false), reported)
    }

    @Test
    fun unchangedModeIsNotReported() {
        delegate.hideCursor()
        delegate.goToGrabMode()
        delegate.goToGrabMode()
        assertEquals(listOf(true), reported)
    }
}
