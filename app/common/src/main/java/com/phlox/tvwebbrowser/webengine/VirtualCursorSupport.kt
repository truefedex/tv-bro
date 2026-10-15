package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.flow.StateFlow

/**
 * A [WebEngine] whose view can be controlled with the virtual cursor (TV remotes).
 * The engine owns the cursor surface; the UI reaches the cursor only through this interface.
 */
interface VirtualCursorSupport {
    /** False in the direct navigation mode, when D-pad keys go to the page */
    fun isVirtualCursorMode(): Boolean
    fun setVirtualCursorMode(enabled: Boolean)
    /** Null while the engine has no view */
    fun getCursorDrawerDelegate(): CursorDrawerDelegate?
    /** The modes of the cursor of this engine, the same flow for the engine's whole life */
    val cursorModes: StateFlow<CursorModes>
    /** Shows the cursor at [x], [y] (pixels of the engine view) */
    fun moveCursorTo(x: Float, y: Float) {
        getCursorDrawerDelegate()?.moveTo(x, y)
    }
}

/**
 * Cursor modes that change what the D-pad and Back do.
 */
data class CursorModes(
    /** The cursor is hidden and D-pad keys go to the page */
    val directNavigation: Boolean = false,
    /** The cursor drags the page (the touch is held) */
    val grab: Boolean = false
)

/** Null if the engine has no virtual cursor */
val WebEngine.virtualCursor: VirtualCursorSupport?
    get() = this as? VirtualCursorSupport
