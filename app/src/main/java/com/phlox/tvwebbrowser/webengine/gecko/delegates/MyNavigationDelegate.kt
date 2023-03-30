package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import java.io.BufferedReader

class MyNavigationDelegate(private val webEngine: GeckoWebEngine) : GeckoSession.NavigationDelegate {
    companion object {
        val TAG: String = MyNavigationDelegate::class.java.simpleName
        const val ERROR_TEMPLATE_FILE = "pages/error.html"
    }

    var canGoBack = false
    var canGoForward = false
    var locationURL: String? = null
    private var errorTemplate: String? = null

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
        Log.d(TAG, "onLocationChange: $url")
        locationURL = url
        webEngine.tab.url = url ?: ""
    }

    override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
        Log.d(TAG,"onLoadRequest="
                    + request.uri
                    + " triggerUri="
                    + request.triggerUri
                    + " where="
                    + request.target
                    + " isRedirect="
                    + request.isRedirect
                    + " isDirectNavigation="
                    + request.isDirectNavigation
        )

        return GeckoResult.allow()
    }

    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        Log.d(TAG,
            "onSubframeLoadRequest="
                    + request.uri
                    + " triggerUri="
                    + request.triggerUri
                    + " isRedirect="
                    + request.isRedirect
                    + "isDirectNavigation="
                    + request.isDirectNavigation
        )

        return GeckoResult.allow()
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        val engine = webEngine.callback?.onOpenInNewTabRequested(uri, false)
        return if (engine == null) {
            null
        } else {
            GeckoResult.fromValue((engine as GeckoWebEngine).session)
        }
    }

    override fun onLoadError(
        session: GeckoSession, uri: String?, error: WebRequestError
    ): GeckoResult<String>? {
        Log.d(TAG,
            "onLoadError=" + uri + " error category=" + error.category + " error=" + error.code
        )

        return GeckoResult.fromValue(
            "data:text/html," + createErrorPage(
                error.category,
                error.code
            )
        )
    }

    private fun categoryToString(category: Int): String {
        return when (category) {
            WebRequestError.ERROR_CATEGORY_UNKNOWN -> "ERROR_CATEGORY_UNKNOWN"
            WebRequestError.ERROR_CATEGORY_SECURITY -> "ERROR_CATEGORY_SECURITY"
            WebRequestError.ERROR_CATEGORY_NETWORK -> "ERROR_CATEGORY_NETWORK"
            WebRequestError.ERROR_CATEGORY_CONTENT -> "ERROR_CATEGORY_CONTENT"
            WebRequestError.ERROR_CATEGORY_URI -> "ERROR_CATEGORY_URI"
            WebRequestError.ERROR_CATEGORY_PROXY -> "ERROR_CATEGORY_PROXY"
            WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "ERROR_CATEGORY_SAFEBROWSING"
            else -> "UNKNOWN"
        }
    }

    private fun errorToString(error: Int): String {
        return when (error) {
            WebRequestError.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
            WebRequestError.ERROR_SECURITY_SSL -> "ERROR_SECURITY_SSL"
            WebRequestError.ERROR_SECURITY_BAD_CERT -> "ERROR_SECURITY_BAD_CERT"
            WebRequestError.ERROR_NET_RESET -> "ERROR_NET_RESET"
            WebRequestError.ERROR_NET_INTERRUPT -> "ERROR_NET_INTERRUPT"
            WebRequestError.ERROR_NET_TIMEOUT -> "ERROR_NET_TIMEOUT"
            WebRequestError.ERROR_CONNECTION_REFUSED -> "ERROR_CONNECTION_REFUSED"
            WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "ERROR_UNKNOWN_PROTOCOL"
            WebRequestError.ERROR_UNKNOWN_HOST -> "ERROR_UNKNOWN_HOST"
            WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE -> "ERROR_UNKNOWN_SOCKET_TYPE"
            WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> "ERROR_UNKNOWN_PROXY_HOST"
            WebRequestError.ERROR_MALFORMED_URI -> "ERROR_MALFORMED_URI"
            WebRequestError.ERROR_REDIRECT_LOOP -> "ERROR_REDIRECT_LOOP"
            WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI -> "ERROR_SAFEBROWSING_PHISHING_URI"
            WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI -> "ERROR_SAFEBROWSING_MALWARE_URI"
            WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI -> "ERROR_SAFEBROWSING_UNWANTED_URI"
            WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI -> "ERROR_SAFEBROWSING_HARMFUL_URI"
            WebRequestError.ERROR_CONTENT_CRASHED -> "ERROR_CONTENT_CRASHED"
            WebRequestError.ERROR_OFFLINE -> "ERROR_OFFLINE"
            WebRequestError.ERROR_PORT_BLOCKED -> "ERROR_PORT_BLOCKED"
            WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "ERROR_PROXY_CONNECTION_REFUSED"
            WebRequestError.ERROR_FILE_NOT_FOUND -> "ERROR_FILE_NOT_FOUND"
            WebRequestError.ERROR_FILE_ACCESS_DENIED -> "ERROR_FILE_ACCESS_DENIED"
            WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> "ERROR_INVALID_CONTENT_ENCODING"
            WebRequestError.ERROR_UNSAFE_CONTENT_TYPE -> "ERROR_UNSAFE_CONTENT_TYPE"
            WebRequestError.ERROR_CORRUPTED_CONTENT -> "ERROR_CORRUPTED_CONTENT"
            WebRequestError.ERROR_HTTPS_ONLY -> "ERROR_HTTPS_ONLY"
            WebRequestError.ERROR_BAD_HSTS_CERT -> "ERROR_BAD_HSTS_CERT"
            else -> "UNKNOWN"
        }
    }

    private fun createErrorPage(category: Int, error: Int): String {
        return createErrorPage(
            categoryToString(category) + " : " + errorToString(error)
        )
    }

    private fun createErrorPage(error: String): String {
        val template = readErrorTemplate() ?: "\$ERROR"
        return template.replace("\$ERROR", error)
    }

    private fun readErrorTemplate(): String? {
        var errorTemplate = this.errorTemplate

        if (errorTemplate != null) return errorTemplate

        return try {
            errorTemplate = TVBro.instance.resources.assets
                .open(ERROR_TEMPLATE_FILE)
                .bufferedReader()
                .use(BufferedReader::readText)
            this.errorTemplate = errorTemplate
            errorTemplate
        } catch (e: Exception) {
            Log.d(TAG, "Failed to open error page template", e)
            null
        }
    }
}