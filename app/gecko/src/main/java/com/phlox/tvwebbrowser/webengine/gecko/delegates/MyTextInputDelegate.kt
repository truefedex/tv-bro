package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.TextInputDelegate

/**
 * The text input of GeckoView ([base], its default delegate), plus the soft keyboard for clicks of
 * the mouse of the virtual cursor: GeckoView opens the keyboard for a field that a touch focuses,
 * but not for one a mouse clicks (it expects a mouse to come with a keyboard), and on a TV the
 * soft keyboard is the only one. [onCursorMouseClick] is called for every click of the cursor.
 */
class MyTextInputDelegate(private val base: TextInputDelegate) : TextInputDelegate {
    /** A field of the page has the focus */
    private var editableFocused = false
    private var lastCursorClickTime = 0L
    private var session: GeckoSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val showIfFieldFocused = Runnable {
        val session = session ?: return@Runnable
        if (!editableFocused) return@Runnable
        // A request made before the keyboard started the input of the field fails, and the window
        // then takes the keyboard for requested and drops the next requests: hide resets that.
        base.hideSoftInput(session)
        base.showSoftInput(session)
    }

    /** The cursor's mouse clicked [session] (the primary button went up) */
    fun onCursorMouseClick(session: GeckoSession) {
        this.session = session
        lastCursorClickTime = SystemClock.uptimeMillis()
        // a click into the field that has the focus already restarts no input: check after the
        // page handled the click (a click elsewhere takes the focus away from the field first)
        handler.removeCallbacks(showIfFieldFocused)
        handler.postDelayed(showIfFieldFocused, FOCUS_SETTLE_DELAY)
    }

    override fun restartInput(session: GeckoSession, reason: Int) {
        base.restartInput(session, reason)
        when (reason) {
            GeckoSession.TextInputDelegate.RESTART_REASON_FOCUS -> {
                editableFocused = true
                if (SystemClock.uptimeMillis() - lastCursorClickTime < CLICK_FOCUS_WINDOW) {
                    this.session = session
                    handler.removeCallbacks(showIfFieldFocused)
                    handler.postDelayed(showIfFieldFocused, FOCUS_SETTLE_DELAY)
                }
            }
            GeckoSession.TextInputDelegate.RESTART_REASON_BLUR -> editableFocused = false
        }
    }

    override fun showSoftInput(session: GeckoSession) = base.showSoftInput(session)

    override fun hideSoftInput(session: GeckoSession) = base.hideSoftInput(session)

    override fun updateSelection(session: GeckoSession, selStart: Int, selEnd: Int, compositionStart: Int, compositionEnd: Int) =
        base.updateSelection(session, selStart, selEnd, compositionStart, compositionEnd)

    override fun updateExtractedText(session: GeckoSession, request: ExtractedTextRequest, text: ExtractedText) =
        base.updateExtractedText(session, request, text)

    override fun updateCursorAnchorInfo(session: GeckoSession, info: CursorAnchorInfo) =
        base.updateCursorAnchorInfo(session, info)

    companion object {
        /** A field focused this long after a click of the cursor was focused by the click */
        private const val CLICK_FOCUS_WINDOW = 1000L
        private const val FOCUS_SETTLE_DELAY = 300L
    }
}
