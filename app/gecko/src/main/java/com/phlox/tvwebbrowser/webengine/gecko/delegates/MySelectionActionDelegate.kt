package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.phlox.tvwebbrowser.webengine.gecko.GeckoViewWithVirtualCursor
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.SelectionActionDelegate
import java.lang.ref.WeakReference

/**
 * The menu of a selection (Copy, Paste, Select all…) of GeckoView's own [BasicSelectionActionDelegate],
 * for selections made by a finger or a mouse of the device (on TV GeckoView takes a mouse for a finger, so a
 * long press of it selects a word). Not for those of the virtual cursor: the D-pad can not reach the menu.
 * The pages read the clipboard without asking, as before.
 */
class MySelectionActionDelegate(private val view: () -> GeckoViewWithVirtualCursor?) : SelectionActionDelegate {
    /**
     * Weak, since the engine outlives the activity; the menu shown keeps it (its callback is an inner class).
     * It holds the activity it was made for.
     */
    private var basicRef: WeakReference<BasicSelectionActionDelegate>? = null
    private var basicActivityRef: WeakReference<Activity>? = null
    private val basic: BasicSelectionActionDelegate? get() = basicRef?.get()

    override fun onShowActionRequest(session: GeckoSession, selection: SelectionActionDelegate.Selection) {
        val view = view() ?: return
        if (view.lastPressFromCursor) {
            basic?.onHideAction(session, SelectionActionDelegate.HIDE_REASON_NO_SELECTION)
            return
        }
        val activity = view.context.findActivity() ?: return
        val delegate = basic.takeIf { basicActivityRef?.get() === activity }
            ?: BasicSelectionActionDelegate(activity).also {
                basicRef = WeakReference(it)
                basicActivityRef = WeakReference(activity)
            }
        delegate.onShowActionRequest(session, selection)
    }

    override fun onHideAction(session: GeckoSession, reason: Int) {
        basic?.onHideAction(session, reason)
    }

    override fun onShowClipboardPermissionRequest(
        session: GeckoSession,
        permission: SelectionActionDelegate.ClipboardPermission
    ): GeckoResult<AllowOrDeny>? {
        return GeckoResult.allow()
    }

    override fun onDismissClipboardPermissionRequest(session: GeckoSession) {
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
