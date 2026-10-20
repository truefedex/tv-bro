package com.phlox.tvwebbrowser.widgets.cursor

import android.view.MotionEvent

/**
 * The point where synthetic pointer events enter the view tree, implemented by the view that owns
 * the cursor surface. Generic motion events (hover, wheel) must bypass that view's own D-pad
 * adapter instead of going through it again, hence the dedicated seam.
 */
interface PointerTarget {
    fun dispatchSyntheticTouch(event: MotionEvent): Boolean
    fun dispatchSyntheticGenericMotion(event: MotionEvent): Boolean
    /** Pixels the page scrolls by for one wheel "click" (the engine's own factor) */
    val wheelScrollFactor: Float
    /**
     * A long press of a mouse also right-clicks the page, for engines that learn about the element
     * under the cursor only from its context menu (GeckoView: `ContentDelegate.onContextMenu`)
     */
    val contextClickOnLongPress: Boolean
        get() = false
}

/** How the virtual cursor talks to the page */
enum class PointerMode {
    /** A single finger: touch down, move and up */
    TOUCH,
    /** A mouse: hover moves, primary button press and release, wheel scrolling */
    MOUSE
}

/**
 * How the virtual cursor talks to the page: as a finger ([TouchEmulator]) or as a mouse
 * ([MouseEmulator]). One implementation is active at a time, chosen by [PointerMode]; only one
 * gesture stream may be in flight, so [CursorDrawerDelegate] cancels the active one before
 * swapping implementations.
 */
interface PointerEmulator {
    /** The cursor moved to (x, y). [pressed] tells whether the click button is held. */
    fun move(x: Float, y: Float, pressed: Boolean)

    fun press(x: Float, y: Float)

    fun release(x: Float, y: Float)

    fun cancel(x: Float, y: Float)

    /** Opens the context menu of the page at (x, y): a right click; a finger has none */
    fun contextClick(x: Float, y: Float) {}

    /**
     * Scrolls the page by (dx, dy) pixels with the cursor at (x, y).
     * @return true if the emulator handled it (mouse wheel); false to let the caller scroll
     * the view or fall back to the touch scroll hack.
     */
    fun scroll(x: Float, y: Float, dx: Float, dy: Float): Boolean

    /**
     * The cursor was turned off or its mode changed: releases the state the page still holds
     * (hover). A gesture in flight is cancelled separately, before this call.
     */
    fun detach()
}
