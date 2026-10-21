package com.phlox.tvwebbrowser.player

import android.app.Activity
import android.view.Display

/**
 * Switches the display of the [activity] to the mode that fits the frame rate of the video ([DisplayModeMatcher]):
 * the window asks for it (`preferredDisplayModeId`), so the mode comes back by itself when another app is shown,
 * and [restore] gives it back to the system. The size of the mode stays the one before the first switch.
 * A switch of the mode blanks the screen for a moment, so it happens only when the frame rate is known.
 */
class FrameRateSwitcher(private val activity: Activity) {
    private var fps: Float? = null
    /** The mode before the first switch: the size of the modes to choose from */
    private var base: Display.Mode? = null
    private var paused = false

    /** The video plays at [fps] frames per second (null: unknown) */
    fun request(fps: Float?) {
        if (fps == this.fps) return
        this.fps = fps
        apply()
    }

    /** The player closed: the display goes back to the mode of the system */
    fun restore() {
        fps = null
        base = null
        setMode(0)
    }

    /** The activity is paused: the mode goes back until [resume] */
    fun pause() {
        paused = true
        setMode(0)
    }

    fun resume() {
        paused = false
        apply()
    }

    private fun apply() {
        val fps = fps
        if (fps == null || paused) {
            setMode(0)
            return
        }
        @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay
        val base = base ?: display.mode.also { base = it }
        val target = DisplayModeMatcher.choose(base.toDisplayMode(), display.supportedModes.map { it.toDisplayMode() }, fps)
        setMode(target?.id ?: 0)
    }

    private fun setMode(id: Int) {
        val attributes = activity.window.attributes
        if (attributes.preferredDisplayModeId == id) return
        attributes.preferredDisplayModeId = id
        activity.window.attributes = attributes
    }

    private fun Display.Mode.toDisplayMode() = DisplayMode(modeId, physicalWidth, physicalHeight, refreshRate)
}
