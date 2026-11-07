package com.phlox.tvwebbrowser.utils

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Where the joystick motion of game controllers goes: their D-pad is mostly a hat, reported as motion, which the view
 * of a page gives to its virtual cursor ([DPADNavigationEventsAdapter] takes all joystick motion). While the video
 * takes the D-pad ([takesDpad]: the video mode, the built-in player), the motion goes past the views to the platform
 * instead, which makes D-pad keys of it (`ViewRootImpl`, repeated while held, from the hats and the left sticks), and
 * those reach the video as the keys of a remote do.
 *
 * A device stays with the platform until its axes are at rest, also when the video lets the D-pad go meanwhile: the
 * platform repeats its keys until it sees them released. A device that leaves the views gives them a centered copy
 * of its motion first, so the cursor lets go of the direction it held.
 */
class JoystickDpadRouter(
    /** Whether the video takes the D-pad now */
    private val takesDpad: () -> Boolean,
    /** Gives the views a motion event (the dispatch of the window) */
    private val toViews: (MotionEvent) -> Boolean,
) {
    /** The devices whose motion goes to the platform */
    private val toPlatform = HashSet<Int>()

    /** @return the result of the dispatch: false leaves the event to the platform */
    fun dispatch(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return toViews(event)
        val device = event.deviceId
        val takes = takesDpad()
        if (device !in toPlatform) {
            if (!takes) return toViews(event)
            val centered = centered(event)
            toViews(centered)
            centered.recycle()
            toPlatform += device
        } else if (!takes && atRest(event)) {
            //the platform releases its keys with this event: the next ones go to the views again
            toPlatform -= device
        }
        return false
    }

    companion object {
        /** Past this the platform makes a key of an axis (`ViewRootImpl.SyntheticJoystickHandler`) */
        private const val KEY_THRESHOLD = 0.5f

        private val KEY_AXES = intArrayOf(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y)

        /** No axis the platform makes keys of is past its threshold */
        fun atRest(event: MotionEvent): Boolean = KEY_AXES.all { abs(event.getAxisValue(it)) < KEY_THRESHOLD }

        /** The [event] with all its axes at 0 */
        private fun centered(event: MotionEvent): MotionEvent {
            val properties = MotionEvent.PointerProperties()
            event.getPointerProperties(0, properties)
            return MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_MOVE, 1, arrayOf(properties),
                arrayOf(MotionEvent.PointerCoords()), event.metaState, event.buttonState, 1f, 1f, event.deviceId, 0,
                event.source, event.flags)
        }
    }
}
