package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

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
}

/** Null if the engine has no virtual cursor */
val WebEngine.virtualCursor: VirtualCursorSupport?
    get() = this as? VirtualCursorSupport
