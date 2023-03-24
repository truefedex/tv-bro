package com.phlox.tvwebbrowser.webengine.gecko.delegates

import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError

class MyNavigationDelegate: GeckoSession.NavigationDelegate {
    var canGoBack = false
    var canGoForward = false
    var locationURL: String? = null

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        this.canGoBack = canGoBack
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        this.canGoForward = canGoForward
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
    ) {
        locationURL = url
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        return super.onLoadRequest(session, request)
    }

    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        return super.onSubframeLoadRequest(session, request)
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        return super.onNewSession(session, uri)
    }

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError
    ): GeckoResult<String>? {
        return super.onLoadError(session, uri, error)
    }
}