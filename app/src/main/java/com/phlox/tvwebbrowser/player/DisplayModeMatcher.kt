package com.phlox.tvwebbrowser.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** A mode of the display: its size in pixels and its refresh rate */
data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * The mode of the display a video plays best in: the same size as the current one, with a refresh rate that
 * shows each frame of the video for the same number of refreshes (23.976 fps at 23.976 Hz, 25 at 50, 30 at 60),
 * so that no frame stays longer than the others (judder).
 */
object DisplayModeMatcher {
    /** 0.05%: 24 Hz does not fit 23.976 fps (a frame would stay twice as long every 42 s) */
    private const val TOLERANCE = 0.0005

    /** Each frame of a video of [fps] frames per second stays the same number of refreshes at [refreshRate] */
    fun fits(refreshRate: Float, fps: Float): Boolean {
        val repeats = (refreshRate / fps).roundToInt()
        if (repeats < 1) return false
        return abs(refreshRate - repeats * fps) / refreshRate < TOLERANCE
    }

    /** The mode to switch to for a video of [fps] frames per second; null when [current] fits or none does */
    fun choose(current: DisplayMode, modes: List<DisplayMode>, fps: Float): DisplayMode? {
        if (!(fps > 1f) || fits(current.refreshRate, fps)) return null
        return modes.filter { it.width == current.width && it.height == current.height && fits(it.refreshRate, fps) }
            .minByOrNull { score(it.refreshRate, fps) }
    }

    /**
     * The fewer refreshes a frame stays, the better; but low refresh rates flicker on many TVs, so videos of 25 and
     * 30 fps prefer 50 and 60 Hz to 25 and 30 Hz (films keep 24 Hz)
     */
    private fun score(refreshRate: Float, fps: Float): Int {
        val repeats = (refreshRate / fps).roundToInt()
        return if (refreshRate < 40f && fps > 24.5f) repeats + 10 else repeats
    }
}
