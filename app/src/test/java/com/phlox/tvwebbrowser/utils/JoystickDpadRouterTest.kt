package com.phlox.tvwebbrowser.utils

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The joystick motion of game controllers goes to the views (the cursor of the page) or, while the video takes the
 * D-pad, to the platform, which makes D-pad keys of it.
 */
@RunWith(RobolectricTestRunner::class)
class JoystickDpadRouterTest {
    /** What the views got: the device and the hat and stick axes of each event (the router recycles its copies) */
    private data class Seen(val device: Int, val hatX: Float, val hatY: Float, val x: Float, val y: Float)

    private val views = mutableListOf<Seen>()
    private var takesDpad = false
    private val router = JoystickDpadRouter({ takesDpad }) { event ->
        views += Seen(event.deviceId, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        true
    }

    private fun motion(hatX: Float = 0f, hatY: Float = 0f, x: Float = 0f, y: Float = 0f,
                       source: Int = InputDevice.SOURCE_JOYSTICK, deviceId: Int = 1): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
            setAxisValue(MotionEvent.AXIS_X, x)
            setAxisValue(MotionEvent.AXIS_Y, y)
        }
        return MotionEvent.obtain(1000L, 1000L, MotionEvent.ACTION_MOVE, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, deviceId, 0, source, 0)
    }

    @Test
    fun motionGoesToTheViewsWhileTheVideoLeavesTheDpad() {
        assertTrue(router.dispatch(motion(hatX = 1f)))
        assertEquals(listOf(Seen(1, 1f, 0f, 0f, 0f)), views)
    }

    @Test
    fun motionGoesToThePlatformWhileTheVideoTakesTheDpad() {
        takesDpad = true
        assertFalse(router.dispatch(motion(hatX = 1f)))
        assertFalse(router.dispatch(motion()))
        assertFalse(router.dispatch(motion(hatY = -1f)))
        //the cursor got one centered event, to let go of what it held, and nothing else
        assertEquals(listOf(Seen(1, 0f, 0f, 0f, 0f)), views)
    }

    @Test
    fun theCursorLetsGoOfADirectionItHeld() {
        router.dispatch(motion(x = 1f))
        takesDpad = true
        router.dispatch(motion(x = 1f))
        assertEquals(listOf(Seen(1, 0f, 0f, 1f, 0f), Seen(1, 0f, 0f, 0f, 0f)), views)
    }

    @Test
    fun aDeviceStaysWithThePlatformUntilItsAxesAreAtRest() {
        takesDpad = true
        router.dispatch(motion(hatY = -1f))
        views.clear()
        //Up gave the cursor back while the hat is held: the platform still repeats its key
        takesDpad = false
        assertFalse(router.dispatch(motion(hatY = -1f)))
        //it releases the key with the event at rest
        assertFalse(router.dispatch(motion()))
        assertTrue(views.isEmpty())
        //then the views get the motion again
        assertTrue(router.dispatch(motion(hatX = 1f)))
        assertEquals(listOf(Seen(1, 1f, 0f, 0f, 0f)), views)
    }

    @Test
    fun aStickAtRestIsBelowTheThresholdOfThePlatform() {
        takesDpad = true
        router.dispatch(motion(x = 1f))
        takesDpad = false
        //a deflection the platform makes no key of is at rest
        assertFalse(router.dispatch(motion(x = 0.3f, y = -0.2f)))
        assertTrue(router.dispatch(motion(x = 0.3f)))
    }

    @Test
    fun devicesGoTheirOwnWays() {
        takesDpad = true
        router.dispatch(motion(hatX = 1f, deviceId = 1))
        takesDpad = false
        views.clear()
        assertTrue(router.dispatch(motion(hatX = -1f, deviceId = 2)))
        assertFalse(router.dispatch(motion(hatX = 1f, deviceId = 1)))
        assertEquals(listOf(Seen(2, -1f, 0f, 0f, 0f)), views)
    }

    @Test
    fun otherSourcesGoToTheViews() {
        takesDpad = true
        assertTrue(router.dispatch(motion(x = 500f, y = 300f, source = InputDevice.SOURCE_MOUSE)))
        assertEquals(listOf(Seen(1, 0f, 0f, 500f, 300f)), views)
    }

    @Test
    fun atRestFollowsTheThresholdOfThePlatform() {
        assertTrue(JoystickDpadRouter.atRest(motion(x = 0.49f, y = -0.49f)))
        assertFalse(JoystickDpadRouter.atRest(motion(x = 0.5f)))
        assertFalse(JoystickDpadRouter.atRest(motion(hatX = -1f)))
        assertFalse(JoystickDpadRouter.atRest(motion(hatY = 1f)))
    }
}
