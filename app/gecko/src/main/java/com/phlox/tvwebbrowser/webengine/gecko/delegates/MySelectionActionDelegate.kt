package com.phlox.tvwebbrowser.webengine.gecko.delegates

import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.SelectionActionDelegate

class MySelectionActionDelegate: SelectionActionDelegate {
    override fun onShowActionRequest(session: GeckoSession, selection: SelectionActionDelegate.Selection) {

    }

    override fun onHideAction(p0: GeckoSession, p1: Int) {
    }

    override fun onShowClipboardPermissionRequest(
        p0: GeckoSession,
        p1: SelectionActionDelegate.ClipboardPermission
    ): GeckoResult<AllowOrDeny>? {
        return GeckoResult.allow()
    }

    override fun onDismissClipboardPermissionRequest(p0: GeckoSession) {
    }
}