package com.phlox.tvwebbrowser.widgets.cursor

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The events the virtual cursor sends to the page as a finger and as a mouse.
 */
@RunWith(RobolectricTestRunner::class)
class CursorPointerEmulationTest {
    private data class Sent(
        /** Sent through the touch path (else the generic motion path) */
        val touch: Boolean,
        val action: Int,
        val toolType: Int,
        val buttonState: Int,
        val vScroll: Float = 0f,
        val hScroll: Float = 0f,
        val mouseSource: Boolean = false,
        val downTimeIsEventTime: Boolean = false,
    )

    private class RecordingTarget(
        override val wheelScrollFactor: Float = 64f,
        override val contextClickOnLongPress: Boolean = false,
    ) : PointerTarget {
        val sent = ArrayList<Sent>()

        override fun dispatchSyntheticTouch(event: MotionEvent) = record(true, event)

        override fun dispatchSyntheticGenericMotion(event: MotionEvent) = record(false, event)

        private fun record(touch: Boolean, event: MotionEvent): Boolean {
            sent += Sent(
                touch, event.actionMasked, event.getToolType(0), event.buttonState,
                event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                event.isFromSource(InputDevice.SOURCE_MOUSE), event.downTime == event.eventTime,
            )
            return true
        }

        fun actions() = sent.map { it.action }
    }

    private val target = RecordingTarget()
    private val mouse = MouseEmulator(target)
    private val touch = TouchEmulator(target)

    @Test
    fun mouseClickIsAPointerStreamWithButtonChanges() {
        mouse.press(10f, 20f)
        mouse.release(10f, 20f)

        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP
            ),
            target.actions()
        )
        assertEquals(listOf(true, false, false, true), target.sent.map { it.touch })
        assertTrue(target.sent.all { it.toolType == MotionEvent.TOOL_TYPE_MOUSE && it.mouseSource })
        assertEquals(
            listOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0),
            target.sent.map { it.buttonState }
        )
    }

    @Test
    fun mouseCancelReleasesTheButtonFirst() {
        mouse.press(10f, 20f)
        target.sent.clear()
        mouse.cancel(10f, 20f)

        assertEquals(listOf(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL), target.actions())
    }

    @Test
    fun mouseHoversWhileNotPressedAndDragsWhilePressed() {
        mouse.move(1f, 1f, pressed = false)
        mouse.move(2f, 2f, pressed = true)

        assertEquals(
            listOf(
                Sent(false, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.TOOL_TYPE_MOUSE, 0,
                    mouseSource = true, downTimeIsEventTime = true),
                Sent(true, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.BUTTON_PRIMARY,
                    mouseSource = true),
            ),
            target.sent
        )
    }

    @Test
    fun detachingTheMouseEndsOnlyAHover() {
        mouse.detach()
        assertEquals(emptyList<Int>(), target.actions())

        mouse.move(1f, 1f, pressed = false)
        mouse.detach()
        mouse.detach()
        assertEquals(listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT), target.actions())
    }

    @Test
    fun mouseWheelScrollsInClicksOfTheScrollFactor() {
        assertTrue(mouse.scroll(5f, 5f, dx = -32f, dy = 128f))

        val wheel = target.sent.single()
        assertEquals(MotionEvent.ACTION_SCROLL, wheel.action)
        assertEquals(false, wheel.touch)
        // scrolling the page down is a negative VSCROLL, scrolling it left a positive HSCROLL
        assertEquals(-2f, wheel.vScroll, 0.001f)
        assertEquals(0.5f, wheel.hScroll, 0.001f)
        // GeckoView drops wheel events whose downTime goes back
        assertTrue(wheel.downTimeIsEventTime)
    }

    @Test
    fun aFingerReportsMovesOnlyWhilePressedAndHasNoWheel() {
        touch.move(1f, 1f, pressed = false)
        assertEquals(false, touch.scroll(1f, 1f, 0f, 10f))
        touch.press(1f, 1f)
        touch.move(2f, 2f, pressed = true)
        touch.release(2f, 2f)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), target.actions())
        assertTrue(target.sent.all { it.touch && it.toolType == MotionEvent.TOOL_TYPE_FINGER })
    }

    @Test
    fun mouseContextClickUsesTheSecondaryButton() {
        mouse.contextClick(10f, 20f)

        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP
            ),
            target.actions()
        )
        assertEquals(
            listOf(MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 0),
            target.sent.map { it.buttonState }
        )
    }

    @Test
    fun clicksFollowThePointerMode() {
        val delegate = attachedDelegate()
        delegate.moveTo(100f, 100f)
        click(delegate)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), target.actions())
        assertTrue(target.sent.all { it.toolType == MotionEvent.TOOL_TYPE_FINGER })
        target.sent.clear()

        delegate.pointerMode = PointerMode.MOUSE
        click(delegate)
        // the mouse hovers where it presses; the press itself waits for the key up
        assertEquals(
            listOf(
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP
            ),
            target.actions()
        )
    }

    @Test
    fun aMouseLongPressLeavesNoClickInThePage() {
        val delegate = attachedDelegate(PointerMode.MOUSE)
        val longPresses = ArrayList<Pair<Int, Int>>()
        delegate.callback = object : CursorDrawerDelegate.Callback {
            override fun onLongPress(x: Int, y: Int) {
                longPresses += x to y
            }
        }
        delegate.moveTo(100f, 100f)
        target.sent.clear()

        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))

        assertEquals(listOf(100 to 100), longPresses)
        assertEquals(listOf(MotionEvent.ACTION_HOVER_MOVE), target.actions())
    }

    @Test
    fun aMouseLongPressRightClicksForEnginesThatAskForIt() {
        val target = RecordingTarget(contextClickOnLongPress = true)
        val delegate = attachedDelegate(PointerMode.MOUSE, target)
        delegate.moveTo(100f, 100f)
        target.sent.clear()

        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(
            listOf(
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP
            ),
            target.actions()
        )
        assertEquals(MotionEvent.BUTTON_SECONDARY, target.sent[1].buttonState)
    }

    @Test
    fun aMouseDragPressesWhereTheKeyWentDown() {
        val delegate = attachedDelegate(PointerMode.MOUSE)
        delegate.moveTo(100f, 100f)
        target.sent.clear()

        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        delegate.setDirection(1f, 0f)
        runFrames(20)
        delegate.setDirection(0f, 0f)
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))

        val actions = target.actions()
        assertEquals(
            listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS),
            actions.take(3)
        )
        assertTrue(actions.drop(3).dropLast(2).isNotEmpty())
        assertTrue(actions.drop(3).dropLast(2).all { it == MotionEvent.ACTION_MOVE })
        assertEquals(listOf(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP), actions.takeLast(2))
    }

    @Test
    fun switchingThePointerModeEndsThePressAndTheHover() {
        val delegate = attachedDelegate(PointerMode.MOUSE)
        delegate.moveTo(100f, 100f)
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        target.sent.clear()

        // the press had not started yet: only the hover ends
        delegate.pointerMode = PointerMode.TOUCH
        assertEquals(listOf(MotionEvent.ACTION_HOVER_EXIT), target.actions())
        target.sent.clear()

        // the key up of the press that was cancelled sends nothing
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(emptyList<Int>(), target.actions())
    }

    @Test
    fun switchingThePointerModeCancelsAFingerPress() {
        val delegate = attachedDelegate()
        delegate.moveTo(100f, 100f)
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        target.sent.clear()

        delegate.pointerMode = PointerMode.MOUSE
        assertEquals(listOf(MotionEvent.ACTION_CANCEL), target.actions())
    }

    @Test
    fun aNewDirectionSteersWithoutStopping() {
        val delegate = attachedDelegate()
        delegate.moveTo(100f, 200f)
        delegate.setDirection(1f, 0f)
        var lastStep = 0f
        repeat(5) {
            val (dx, dy) = runUntilMoved(delegate)
            assertEquals(0f, dy, 0f)
            assertTrue(dx >= lastStep)
            lastStep = dx
        }

        delegate.setDirection(0f, -1f)
        val (dx, dy) = runUntilMoved(delegate)
        // up at the speed reached, not from rest, and no drift to the right
        assertEquals(0f, dx, 0f)
        assertTrue("step $dy after $lastStep", -dy >= lastStep)
    }

    @Test
    fun aStickDirectionMovesAlongIt() {
        val delegate = attachedDelegate()
        delegate.moveTo(100f, 100f)
        delegate.setDirection(0.3f, 0.6f)
        repeat(5) { runUntilMoved(delegate) }
        val dx = delegate.cursorPosition.x - 100f
        val dy = delegate.cursorPosition.y - 100f
        assertTrue(dx > 0f)
        assertEquals(2f, dy / dx, 0.001f)
    }

    @Test
    fun aZeroDirectionStops() {
        val delegate = attachedDelegate()
        delegate.moveTo(200f, 200f)
        delegate.setDirection(-1f, 0f)
        repeat(3) { runUntilMoved(delegate) }
        delegate.setDirection(0f, 0f)
        val stopX = delegate.cursorPosition.x
        runFrames(10)
        assertEquals(stopX, delegate.cursorPosition.x, 0f)
    }

    /** Runs the tasks of the main looper until the cursor moves; gives the step */
    private fun runUntilMoved(delegate: CursorDrawerDelegate): Pair<Float, Float> {
        val x = delegate.cursorPosition.x
        val y = delegate.cursorPosition.y
        repeat(10) {
            runFrames(1)
            if (delegate.cursorPosition.x != x || delegate.cursorPosition.y != y) {
                return delegate.cursorPosition.x - x to delegate.cursorPosition.y - y
            }
        }
        throw AssertionError("the cursor did not move")
    }

    /** Runs tasks of the main looper (the movement loop among them) 16 ms apart, so the cursor accelerates */
    private fun runFrames(count: Int) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(count) {
            // the fake clock of Robolectric, which System.currentTimeMillis follows too
            SystemClock.sleep(16)
            looper.runOneTask()
        }
    }

    private fun click(delegate: CursorDrawerDelegate) {
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
    }

    /** Key tracking needs a surface attached to a window */
    private fun attachedDelegate(
        mode: PointerMode = PointerMode.TOUCH,
        target: RecordingTarget = this.target,
    ): CursorDrawerDelegate {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val surface = View(activity)
        activity.setContentView(surface)
        surface.layout(0, 0, 1920, 1080)
        return CursorDrawerDelegate(activity, surface, target).also {
            it.init()
            it.pointerMode = mode
        }
    }
}
